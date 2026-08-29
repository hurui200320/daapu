package info.skyblond.daapu.agent.pipeline.eltm

import info.skyblond.daapu.agent.chat.ChatMessage
import info.skyblond.daapu.agent.chat.ChatMessagePart
import info.skyblond.daapu.agent.chat.ChatMessageRole
import info.skyblond.daapu.agent.model.LLM
import info.skyblond.daapu.agent.pipeline.runOneShotText
import info.skyblond.daapu.agent.context.ContextInjection
import info.skyblond.daapu.hand.HandRunPolicy
import info.skyblond.daapu.hand.HandService
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.LocalDate

/**
 * The memory extraction pipeline (the harness's memory architecture, see the
 * README: when messages are removed from context, extract info from the raw messages and
 * write it into the ELTM before discarding them):
 *
 * 1. **Extractor** — one hand `/v1/run` call (no tools) with the raw dropped
 *    history (attachments included, so the model needs the matching input
 *    capabilities) plus the extraction system prompt, returning a free-text
 *    list of candidate facts (or the [NOTHING_TO_REMEMBER_TEXT] sentinel).
 * 2. **ELTM writer** — one hand `/v1/run` tool loop
 *    ([EltmWriterService.writeToEltm]) that records the extracted facts into
 *    the ELTM diary directly (entities, relationships, attributes, notes).
 *    The writer deduplicates against the store, so a retried run never
 *    duplicates diary entries.
 *
 * The stages are also usable separately: [extractFacts] runs the extractor
 * only and returns the fact text without writing anything (the import
 * script materializes it into a reviewable file), while
 * [EltmWriterService.writeToEltm] records a fact batch.
 *
 * A failure throws and fails the run:
 * - [info.skyblond.daapu.agent.model.ModelCapabilityException] when the
 *   extraction model cannot process the dropped content (e.g. images with a
 *   text-only model), which is a configuration error
 *   (`memory.eltm.extractionModel`) and fails fast;
 * - a failed extraction (a classified hand error such as a truncated
 *   `length` finish) or one producing tool calls or no text;
 * - any terminal writer failure: a classified hand error, an exhausted
 *   transient-retry budget, the `round_limit` cap or an `empty_response`.
 *   A failed write fails the run — and with it the deletion when triggered
 *   from there, so a retry re-extracts from the still-existing history
 *   instead of losing memories. Whatever was already recorded sticks (the
 *   writer skips already-recorded content on retry).
 */
class MemoryExtractionService(
    private val extractModel: LLM,
    private val hand: HandService,
    // the hand's /v1/run policy knobs for the one-shot calls (config
    // `hand.*`): transient failures retry with the same budget/backoff as
    // the chat loop, the writer rounds are capped inside EltmWriterService
    private val policy: HandRunPolicy,
    // the ELTM write path: the extracted facts are written into the ELTM
    // diary directly (config `memory.eltm.writerModel`, `maxWriterRounds`);
    // REQUIRED — `memory.eltm` is mandatory config
    private val eltmWriterService: EltmWriterService,
    // the harness context: sanitize the dropped history (it may be the chat
    // loop's injected in-loop chat) and anchor every user message with its
    // send time, so the extractor resolves relative dates per message and
    // never against the extraction time
    private val contextInjection: ContextInjection = ContextInjection(),
) {
    /**
     * Extract memories from [droppedMessages] (the raw messages compaction is
     * about to discard) and write them into the ELTM. Throws per the class
     * KDoc (the extraction pipeline fails the run on failure). The
     * [NOTHING_TO_REMEMBER_TEXT] sentinel is the only skip path for the
     * extraction: a blank answer is a hand `empty_response` error and fails
     * the run, it cannot silently skip the write.
     */
    suspend fun processDiscardedMessages(
        droppedMessages: List<ChatMessage>,
    ) {
        if (droppedMessages.isNotEmpty()) {
            val extraction = extractFacts(droppedMessages)
            if (extraction != NOTHING_TO_REMEMBER_TEXT) {
                logger.info { "Extracted memories from ${droppedMessages.size} dropped message(s), writing into the ELTM" }
                eltmWriterService.writeToEltm(extraction, LocalDate.now())
            }
        }
    }

    /**
     * The extraction stage alone: run the extractor over [droppedMessages]
     * and return the free-text fact list (or the [NOTHING_TO_REMEMBER_TEXT]
     * sentinel when nothing is worth remembering) WITHOUT writing anything
     * — the caller decides what to do with the facts. The import script
     * uses this to materialize the facts into a reviewable file instead of
     * recording them directly.
     *
     * Throws per the class KDoc (a capability mismatch is a configuration
     * error and fails fast; a failed extraction throws
     * [IllegalStateException]). [droppedMessages] must not be empty.
     */
    suspend fun extractFacts(droppedMessages: List<ChatMessage>): String {
        require(droppedMessages.isNotEmpty()) {
            "cannot extract memories from an empty message list"
        }
        // fail fast on a capability mismatch before the LLM call: the same
        // prompt would fail identically forever (the loop's per-round check
        // semantics, applied to the configured extraction model)
        extractModel.checkPromptContentCapabilities(droppedMessages)
        val extraction = extract(droppedMessages)
        logger.info { "Extracted memories:\n${extraction}" }
        return extraction
    }

    /**
     * The extraction call: the raw dropped history plus the extraction
     * instruction. The history is treated as potentially injected (it may
     * come from the chat loop's injected in-loop chat): sanitize first, then
     * anchor every user message with its send time. The extractor is
     * stateless — the input carries no "now" anywhere (no current date in
     * the prompt), every relative date resolves against the message's own
     * anchor, so extraction time never matters. Fails on anything but a
     * clean `stop` with text (the fail-fast semantics depend on
     * distinguishing `length` from `stop`).
     */
    private suspend fun extract(droppedMessages: List<ChatMessage>): String {
        val chat = contextInjection.injectContext(
            contextInjection.removeInjection(droppedMessages) + ChatMessage(
                role = ChatMessageRole.User,
                parts = listOf(
                    ChatMessagePart.Text(
                        "Extract memories item according to the system prompt."
                    )
                ),
            ),
            spec = null,
        )

        return hand.runOneShotText(
            model = extractModel,
            messages = chat,
            systemPrompt = renderExtractorSystemPrompt(),
            policy = policy,
            label = "Memory extraction",
        )
    }

    companion object {
        private val logger = KotlinLogging.logger {}

        internal const val NOTHING_TO_REMEMBER_TEXT = "Nothing worth remember."

        private fun renderExtractorSystemPrompt(): String = """
You're extracting memories from a discarded conversation.
Extract **all** important information from the conversation history into a list of self-contained facts suitable for long-term memory.

Every user message opens with a <meta><sent-at>...</sent-at></meta> marker carrying that message's send time.
Resolve every relative date or time ("today", "last week", "in two months") against the send time of the message that contains it.
The messages can be much older than the moment of extraction, so never resolve against "now".
Assistant messages carry no marker: they reply immediately after the preceding user message.
Write absolute dates in the facts: "User went to Paris last week" is useless 6 months later; "User went to Paris the week of May 15, 2026" is meaningful forever.

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
    }
}
