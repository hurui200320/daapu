package info.skyblond.daapu.agent.oneshot

import dev.langchain4j.model.openai.OpenAiChatModel
import dev.langchain4j.model.output.FinishReason
import info.skyblond.daapu.agent.checkPromptContentCapabilities
import info.skyblond.daapu.agent.lc4j.chat.toLc4jMessages
import info.skyblond.daapu.agent.lc4j.llm.LLM
import info.skyblond.daapu.agent.refreshSystemPrompt
import info.skyblond.daapu.chat.ChatMessage
import info.skyblond.daapu.chat.ChatMessagePart
import info.skyblond.daapu.chat.ChatMessageRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ChatCompactionResult(
    /**
     * Messages that summary has replaced.
     * */
    val droppedMessages: List<ChatMessage>,
    /**
     * The chat to continue.
     * */
    val newChat: List<ChatMessage>,
)

/**
 * Estimate the prompt size of [chat] in tokens.
 *
 * Provider-reported usage wins when available: every round's
 * `meta.inputTokens` already counts the whole prompt up to that round, so
 * summing rounds would massively over-count — instead the LAST assistant
 * message's `inputTokens` is the best measured snapshot of the prompt, and
 * only the messages appended after it (the new user input, tool rounds, the
 * compaction summary) are estimated. Messages without meta fall back to a
 * chars/4 heuristic. Attachments are not counted (base64 would dominate and
 * is not token-like); the trigger headroom absorbs the inaccuracy.
 */
// FIXME: get rid of this. It's hard to estimate tokens since OpenAI API is used widely
//        by opensource models, each model has different tokenizer. Impossible to cover all.
//        Should require usage on response meta.
fun estimateTokens(chat: List<ChatMessage>): Long {
    val lastAssistantIndex = chat.indexOfLast { it.role == ChatMessageRole.Assistant }
    if (lastAssistantIndex >= 0) {
        val measured = chat[lastAssistantIndex].meta?.inputTokens
        if (measured != null) {
            return measured + chat.drop(lastAssistantIndex + 1).sumOf { charEstimate(it) }
        }
    }
    return chat.sumOf { charEstimate(it) }
}

internal fun charEstimate(message: ChatMessage): Long =
    (message.parts.sumOf { part -> partCharCount(part) } / 4).coerceAtLeast(1).toLong()

private fun partCharCount(part: ChatMessagePart): Int = when (part) {
    is ChatMessagePart.Text -> part.text.length
    is ChatMessagePart.Reasoning -> part.content.sumOf { it.length }
    is ChatMessagePart.ToolCall -> part.tool.length + part.args.length
    is ChatMessagePart.ToolResult -> part.tool.length + part.parts.sumOf { partCharCount(it) }
    is ChatMessagePart.Attachment -> 0
}

class ChatCompactor(
    private val model: LLM,
    private val chatModel: OpenAiChatModel,
) {
    /**
     * Compact the given chat history, return a summarized text that replaced the history.
     *
     * The [fullChat] can be the raw chat history from other agents, including the system prompt.
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
     * - [info.skyblond.daapu.agent.ModelCapabilityException] when the
     *   compactor model cannot process the chat's content (e.g. images with
     *   a text-only model) — a capability mismatch is a configuration error
     *   (`memory.compactModel`), so it fails fast instead of silently
     *   skipping the compaction;
     * - [IllegalStateException] when the summarization call failed, was
     *   truncated, produced no text, or the summary is not smaller than the
     *   messages it replaces (a degenerate summarizer must not grow the
     *   history).
     */
    suspend fun compactChat(
        fullChat: List<ChatMessage>,
        excludeLastNRound: Int
    ): ChatCompactionResult {
        val (chatToCompact, chatToPreserve) = splitMessage(fullChat, excludeLastNRound)
        // fail fast on a capability mismatch before the LLM call: the same
        // prompt would fail identically forever (see the loop's per-round
        // check, which this reuses)
        checkPromptContentCapabilities(fullChat, model)
        // chat to feed into summary llm:
        // First contains the part to compact,
        // Then add a user message to tell model the line between summary and context.
        // Finally, add a user message to request the summary.
        // Also replace the system prompt with our own.
        val chat = (chatToCompact + ChatMessage(
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
        )).refreshSystemPrompt(
            // TODO: estimate output size?
            renderSystemPrompt(500)
        )

        val response = try {
            // non-streaming: the blocking call stays off the event loop
            withContext(Dispatchers.IO) { chatModel.chat(chat.toLc4jMessages()) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException(
                "Compaction summarization failed; the history was left untouched",
                e,
            )
        }

        if (response.finishReason() != FinishReason.STOP) {
            error("Compaction summarization ended with finish_reason=${response.finishReason()}, not a clean stop")
        }
        if (response.aiMessage().hasToolExecutionRequests()) {
            error("Compaction summarization produced tool calls instead of text")
        }
        val summary = response.aiMessage().text()?.trim()?.takeIf { it.isNotBlank() }
            ?: error("Compaction summarization produced no text")

        val head = fullChat.takeWhile { it.role == ChatMessageRole.System }
        val summaryMessage = ChatMessage(
            role = ChatMessageRole.User,
            parts = listOf(ChatMessagePart.Text(COMPACTION_HEADER + summary)),
        )
        // TODO: check summary shorter than input?
        return ChatCompactionResult(
            droppedMessages = chatToCompact,
            newChat = head + summaryMessage + chatToPreserve,
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
        val head = chat.takeWhile { it.role == ChatMessageRole.System }
        val body = chat.drop(head.size)
        val userIndexes = body.mapIndexedNotNull { index, message ->
            if (message.role == ChatMessageRole.User) index else null
        }
        require(userIndexes.isNotEmpty()) {
            "Nothing to compact: the chat has no user messages"
        }
        // always leave at least one round to compact: keep the last N
        // rounds, but never ALL of them
        val keep = minOf(lastNRound, userIndexes.size - 1)
        val cutIdx = if (keep == 0) body.size else userIndexes[userIndexes.size - keep]
        // defensive: the arithmetic above always cuts after the first body
        // message, so an empty drop region can never reach the LLM
        require(cutIdx > 0) {
            "Nothing to compact: the drop region would be empty"
        }
        val dropped = body.subList(0, cutIdx)
        val preserved = body.subList(cutIdx, body.size)
        return dropped to preserved
    }

    companion object {
        private const val COMPACTION_HEADER = "CONTEXT COMPACTION: "

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
