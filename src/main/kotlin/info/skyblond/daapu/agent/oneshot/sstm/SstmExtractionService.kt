package info.skyblond.daapu.agent.oneshot.sstm

import info.skyblond.daapu.agent.chat.ChatMessage
import info.skyblond.daapu.agent.chat.ChatMessagePart
import info.skyblond.daapu.agent.chat.ChatMessageRole
import info.skyblond.daapu.agent.model.LLM
import info.skyblond.daapu.agent.model.LLMCapability
import info.skyblond.daapu.agent.oneshot.lastMessageText
import info.skyblond.daapu.agent.oneshot.sstm.SstmExtractionService.Companion.NOTHING_TO_REMEMBER_TEXT
import info.skyblond.daapu.agent.tool.EmptyToolProvider
import info.skyblond.daapu.hand.HandRunRequest
import info.skyblond.daapu.hand.HandService
import info.skyblond.daapu.hand.toHandModelSpec
import info.skyblond.daapu.memory.sstm.SstmService
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import java.time.LocalDate

/**
 * The two-LLM memory extraction pipeline (see `agent/persist/SystemPrompt.kt`:
 * when messages are removed from context, extract info from the raw messages
 * and merge it into the SSTM before discarding them):
 *
 * 1. **Extractor** — one hand `/v1/run` call (no tools) with the raw dropped
 *    history (attachments included, so the model needs the matching input
 *    capabilities) plus the extraction system prompt, returning a free-text
 *    list of candidate memories (or the [NOTHING_TO_REMEMBER_TEXT]
 *    sentinel).
 * 2. **Merger** — one hand `/v1/run` call (tool loop, the hand executes the
 *    add/update/list tools back through the callback route) against
 *    the existing SSTM ([SstmService]). No lock is held: a concurrent run's
 *    injection read may observe a half-merged SSTM, which is healed by the
 *    `sstm-updated` flag comparison on the next round.
 *
 * A failure throws and fails the run:
 * - [info.skyblond.daapu.agent.model.ModelCapabilityException] when the
 *   extraction model cannot process the dropped content (e.g. images with a
 *   text-only model), which is a configuration error (`memory.extractModel`)
 *   and fails fast;
 * - a failed extraction (a classified hand error such as a truncated
 *   `length` finish) or one producing tool calls or no text;
 * - any terminal merge failure: a classified hand error, an exhausted
 *   transient-retry budget, the `round_limit` cap (the model is stuck in a
 *   loop or failed to merge everything in time) or an `empty_response` (no
 *   text and no tool calls). A failed merge fails the run — and with it the
 *   deletion when triggered from there, so a retry re-extracts from the
 *   still-existing history instead of losing memories. The SSTM keeps
 *   whatever was already applied when a later round fails.
 */
class SstmExtractionService(
    private val extractModel: LLM,
    private val mergeModel: LLM = extractModel,
    private val hand: HandService,
    private val sstmService: SstmService,
    private val maxMergeRounds: Int = 150,
    // the hand's /v1/run policy knobs for the one-shot calls (config
    // `hand.*`): transient failures retry with the same budget/backoff as
    // the chat loop, the merge rounds are capped by [maxMergeRounds]
    private val maxRetries: Int,
    private val streamIdleTimeoutMs: Long,
) {
    /**
     * Extract memories from [droppedMessages] (the raw messages compaction is
     * about to discard) and merge them into the SSTM. Throws per the class
     * KDoc (the extraction pipeline fails the run on failure). The
     * [NOTHING_TO_REMEMBER_TEXT] sentinel is the only skip path: a blank
     * answer is a hand `empty_response` error and fails the run, it cannot
     * silently skip the merge.
     */
    suspend fun processDiscardedMessages(
        droppedMessages: List<ChatMessage>,
    ) {
        if (droppedMessages.isEmpty()) {
            return
        }
        // fail fast on a capability mismatch before the LLM call: the same
        // prompt would fail identically forever (the loop's per-round check
        // semantics, applied to the configured extraction model)
        extractModel.checkPromptContentCapabilities(droppedMessages)
        val extraction = extract(droppedMessages)
        if (extraction == NOTHING_TO_REMEMBER_TEXT) {
            return
        }

        mergeToSstm(extraction)
    }

    /**
     * The extraction call: the raw dropped history plus the extraction
     * instruction. Fails on anything but a clean `stop` with text (the
     * fail-fast semantics depend on distinguishing `length` from `stop`).
     */
    private suspend fun extract(droppedMessages: List<ChatMessage>): String {
        val chat = droppedMessages + ChatMessage(
            role = ChatMessageRole.User,
            parts = listOf(
                ChatMessagePart.Text(
                    "Extract memories item according to the system prompt."
                )
            ),
        )

        // TODO: when ELTM is ready, detect SSTM length and trigger ELTM
        return try {
            hand.runCollect(
                HandRunRequest(
                    model = extractModel.toHandModelSpec(),
                    messages = chat,
                    systemPrompt = renderExtractorSystemPrompt(LocalDate.now()),
                    maxTokens = extractModel.maxOutputTokens,
                    // 0 = no round cap, safe here: no tools are declared
                    // (and [EmptyToolProvider] answers stray calls with an
                    // error result), so the loop ends on the first stop
                    maxRounds = 0,
                    maxRetries = maxRetries,
                    streamIdleTimeoutMs = streamIdleTimeoutMs,
                ),
                toolProvider = EmptyToolProvider,
                model = extractModel,
            ).lastMessageText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("SSTM extraction failed", e)
        }
    }

    /**
     * Run the merge agent: one hand `/v1/run` tool loop over the SSTM until
     * the model answers without tool calls (the hand owns the loop, the tool
     * execution, the transient retries and the `maxMergeRounds` cap). The
     * collected messages are not needed here — the merge already happened
     * through the tool callbacks — but keeping them gives future callers
     * the full exchange to inspect.
     *
     * Any terminal failure throws and fails the run (see the class KDoc);
     * the SSTM keeps whatever was already applied.
     */
    private suspend fun mergeToSstm(extraction: String) {
        require(mergeModel.supports(LLMCapability.Output.ToolCalls)) {
            "Merge model ${mergeModel.id} does not support tool calls"
        }
        val toolProvider = MergeMemoryToolProvider(sstmService)
        val chat = listOf(
            ChatMessage(
                ChatMessageRole.User,
                listOf(
                    ChatMessagePart.Text(
                        buildMergeInput(
                            sstmService.listMemories().memories,
                            extraction,
                            LocalDate.now(),
                        )
                    )
                )
            ),
        )
        val result = hand.runCollect(
            HandRunRequest(
                model = mergeModel.toHandModelSpec(),
                messages = chat,
                systemPrompt = renderMergerSystemPrompt(),
                maxTokens = mergeModel.maxOutputTokens,
                maxRounds = maxMergeRounds,
                maxRetries = maxRetries,
                streamIdleTimeoutMs = streamIdleTimeoutMs,
            ),
            toolProvider = toolProvider,
            model = mergeModel,
        )
        logger.info { "SSTM merge done, ${result.size} message(s) collected" }
    }

    companion object {
        private val logger = KotlinLogging.logger {}

        private const val NOTHING_TO_REMEMBER_TEXT = "Nothing worth remember."

        private fun renderExtractorSystemPrompt(date: LocalDate): String = """
You're extracting memories from a discarded conversation.
Extract **all** important information from the conversation history into a list of self-contained facts suitable for long-term memory.

Today's date is ${formatDate(date)}. Resolve every relative date or time ("today", "last week", "in two months") against it
and write absolute dates in the facts: "User went to Paris last week" is useless 6 months later; "User went to Paris the week of May 15, 2026" is meaningful forever.

Focus on:
- The user's preferences, likes, dislikes, and personal details
- Plans, goals, pending tasks, and unresolved questions
- Decisions and constraints
- Facts about people, projects, and entities: keep names, numbers, ids, and values verbatim
- Transitions: "switched from X to Y because Z" is more valuable than "uses Y"
- Anything a future conversation would need to know

Rules:
- Each fact must be self-contained: replace pronouns with the entity name or "the user"
- Rich, not atomic: one fact may span 1-3 sentences when the context matters, but keep it under ~80 words
- Write facts in the same language as the conversation
- Do not invent details that are not present in the history
- Merge overlapping information into one fact
- Cover the whole conversation, not just the first topic
- Do not extract the assistant restating what the user said as a new fact (echo extraction)
- Extract the content of documents or code the user shared, not "the user shared a document" (meta extraction)
- When nothing is worth remembering, output sentence "$NOTHING_TO_REMEMBER_TEXT"
""".trimIndent().trim()


        private fun renderMergerSystemPrompt(): String = """
You're merging summarized memories into the memory system (SSTM).
You have access to tools that manipulating the SSTM.
The SSTM is a numbered list of memories.
The current state, the candidate facts extracted from a recent conversation, and the current date are given in the user message.

Update the SSTM with the memory tools:
- list_memories: view the current SSTM (also listed in the user message)
- add_memory(content): add a new memory
- update_memory(id, content): replace an existing memory's content in place

For each candidate decide exactly one action:
- ADD: the candidate is new information -> add_memory
- UPDATE: the candidate refines an existing memory (the same fact with more detail or a correction) -> update_memory with the existing id.
  Also use UPDATE when the candidate supersedes an existing memory: merge what stays true with the new fact and update in place.
- NONE: the candidate is already covered by an existing memory -> do nothing

Rules:
- Never modify memories unrelated to the candidates
- Keep memories concise and self-contained
- Similar facts about different things coexist: "likes pizza" and "likes burger" are not contradictions.
  Supersede only when the new fact genuinely replaces the old one (a correction or a change over time); when judging, prefer the more recent fact (see the current date in the user message).
- When all candidates are handled, reply with a short confirmation and make no further tool calls
""".trimIndent().trim()
    }
}
