package info.skyblond.daapu.agent.oneshot.compaction

import info.skyblond.daapu.agent.chat.ChatMessage
import info.skyblond.daapu.agent.chat.ChatMessagePart
import info.skyblond.daapu.agent.chat.ChatMessageRole
import info.skyblond.daapu.agent.chat.roundCount
import info.skyblond.daapu.agent.chat.takeLastNRound
import info.skyblond.daapu.agent.model.LLM
import info.skyblond.daapu.agent.oneshot.runOneShotText
import info.skyblond.daapu.agent.persist.ContextInjection
import info.skyblond.daapu.hand.HandRunPolicy
import info.skyblond.daapu.hand.HandService
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Instant

class ChatCompactionService(
    private val model: LLM,
    private val hand: HandService,
    // the hand's /v1/run policy knobs for this one-shot (config `hand.*`):
    // transient failures retry with the same budget/backoff as the chat loop
    private val policy: HandRunPolicy,
    // the harness context: sanitize the input (it may be the chat loop's
    // injected in-loop chat) and re-anchor the history user messages so the
    // summarizer sees each message's send time
    private val contextInjection: ContextInjection = ContextInjection(),
) {
    /**
     * Compact the given chat history, returning the compacted chat plus the
     * dropped raw messages (which feed the memory extraction before they are
     * discarded).
     *
     * The [fullChat] is the raw conversation history (the system prompt is
     * not part of it; the summarizer gets its own prompt out of band).
     *
     * The [excludeLastNRound] is an indicator, the whole chat history will be
     * feed to the compactor LLM, but will tell it the last N round is for context/reference-only,
     * after the compaction, the last N round of messages should be preserved as-is.
     *
     * One round means one user message to an assistant message with stop reason: stop.
     * So one round of messages will contain 1 user message, 1 or more assistant messages,
     * and multiple rounds of tool call and tool result messages.
     *
     * A chat with fewer rounds than [excludeLastNRound] is still compacted:
     * the keep count shrinks down to zero (see [splitMessage]), so a single
     * overflowing round is summarized in full instead of giving up.
     *
     * The history is left untouched whenever this throws:
     * - [IllegalArgumentException] when the chat has no user messages at all
     *   (nothing to summarize);
     * - [info.skyblond.daapu.agent.model.ModelCapabilityException] when the
     *   compactor model cannot process the chat's content (e.g. images with
     *   a text-only model) — a capability mismatch is a configuration error
     *   (`memory.compactModel`), so it fails fast instead of silently
     *   skipping the compaction;
     * - [IllegalStateException] when the summarization call failed, was
     *   truncated, or produced no text.
     *
     * The returned chat does NOT contain any injection or meta.
     */
    suspend fun compactChat(
        fullChat: List<ChatMessage>,
        excludeLastNRound: Int
    ): ChatCompactionResult {
        // Treat the input as potentially injected (a reactive compaction runs
        // on the chat loop's injected in-loop chat): sanitize first so no
        // harness part is ever fed twice, then split the clean chat — the
        // dropped region the memory extraction sees is clean too.
        val clean = contextInjection.removeInjection(fullChat)
        val (chatToCompact, chatToPreserve) = splitMessage(clean, excludeLastNRound)
        // fail fast on a capability mismatch before the LLM call: the same
        // prompt would fail identically forever (see the loop's per-round
        // check, which this reuses)
        model.checkPromptContentCapabilities(fullChat)
        // chat to feed into summary llm:
        // First contains the part to compact,
        // Then add a user message to tell model the line between summary and context.
        // Finally, add a user message to request the summary.
        // Also replace the system prompt with our own.
        // The historical user messages get their <meta> send-time anchors here
        // (no full injection — this is a one-shot, the anchors-only spec).
        val chat = contextInjection.injectContext(
            chatToCompact + ChatMessage(
                role = ChatMessageRole.User,
                parts = listOf(
                    ChatMessagePart.Text(
                        "<system>Above are the messages to summarize, below are messages for context. " +
                                "**DO NOT** summarize messages for context.</system>"
                    )
                ),
            ) + chatToPreserve + ChatMessage(
                role = ChatMessageRole.User,
                parts = listOf(
                    ChatMessagePart.Text(
                        "Summarize this chat according to system prompt."
                    )
                ),
            ),
            spec = null,
        )

        val summary = hand.runOneShotText(
            model = model,
            messages = chat,
            systemPrompt = renderSystemPrompt(500),
            policy = policy,
            label = "Compaction summarization",
        )
        logger.info { "Compaction summary:\n${summary}" }
        val summaryMessage = ChatMessage(
            role = ChatMessageRole.User,
            parts = listOf(ChatMessagePart.Text(COMPACTION_HEADER + summary)),
            // the summary is authored now and becomes the first message of
            // the stored chat, so it carries a createdAt like any other
            createdAt = Instant.now(),
        )
        // TODO: check summary shorter than input?

        // chatToCompact and chatToPreserve derived from chat without injection
        // safe to use here
        return ChatCompactionResult(
            droppedMessages = chatToCompact,
            newChat = listOf(summaryMessage) + chatToPreserve,
        )
    }

    /**
     * Split the chat at a user-turn boundary: everything before the kept
     * tail is compacted, the tail is preserved verbatim. Cutting only at
     * user-turn boundaries keeps every round of the preserved part
     * (assistant tool calls and their results) intact. Trailing messages
     * after the last user message (an in-flight tool chain of the current
     * run) always land in the preserved part.
     *
     * The keep count shrinks when the chat has fewer rounds than
     * [lastNRound] — down to zero, which drops the entire body — so a chat
     * that overflows its context is always compactable, even a single
     * overflowing round. (Compacting "everything" is the best that can be
     * done when the keep count cannot be honored.)
     *
     * Throws [IllegalArgumentException] when the chat has no user messages
     * at all: there is literally nothing to summarize.
     */
    internal fun splitMessage(
        chat: List<ChatMessage>,
        lastNRound: Int,
    ): Pair<List<ChatMessage>, List<ChatMessage>> {
        require(lastNRound >= 1)
        require(chat.roundCount() >= 1) {
            "Nothing to compact: the chat has no user messages"
        }
        // always leave at least one round to compact: keep the last N
        // rounds, but never ALL of them
        val keep = minOf(lastNRound, chat.roundCount() - 1)
        val preserved = chat.takeLastNRound(keep)
        val dropped = chat.subList(0, chat.size - preserved.size)
        // defensive: the arithmetic above always cuts after the first chat
        // message, so an empty drop region can never reach the LLM
        require(dropped.isNotEmpty()) {
            "Nothing to compact: the drop region would be empty"
        }
        return dropped to preserved
    }

    companion object {
        private val logger = KotlinLogging.logger {}

        /**
         * The marker every compaction summary message starts with. Internal
         * so tooling (the import script) can recognize a summary message
         * without duplicating the literal.
         */
        internal const val COMPACTION_HEADER = "CONTEXT COMPACTION: "

        private fun renderSystemPrompt(outputSize: Int): String = """
You're summarizing the conversation between user and assistant to compact the context window.
Summarize the conversation between the first user message all the way to the marker.
Your output will become the first message of the new conversation.

Include:
+ User's goal (high level) and what they are trying to accomplish.
+ User may change topic in the middle, focus on the latest goal, but also include previous goals for callback.
+ Key decisions, constrains, and preferences.
+ Important details: preserve names, numbers, identifiers, file names, and values verbatim.
+ Errors and fixes
+ Current state: where things are stand right now
+ Pending items, TODOs and unresolved questions
+ Tool results that matters going forward (paths, values, errors, search results), summarize the tool result, DO NOT repeat tool output.

Exclude:
+ Tool result content
+ redundant exchanges
+ intermediate reasoning

Guideline:
+ For important details, include them as-is. For example, implementation plan, final conclusion, etc.
+ For informational details, include the source, so when it's needed, future assistant and re-fetch the details.
+ Use the same language as the conversation.
+ Plain text, no markup.
+ **NEVER invent details that are not present in the conversation.**
+ Keep your output around $outputSize words.
+ DO NOT output `$COMPACTION_HEADER` in your output.

The first message might be a summarized message starts with marker `$COMPACTION_HEADER`,
in that case, you should include the related parts from previous compaction.
""".trimIndent().trim()
    }
}
