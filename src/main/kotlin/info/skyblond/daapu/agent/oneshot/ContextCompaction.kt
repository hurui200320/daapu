package info.skyblond.daapu.agent.oneshot

import dev.langchain4j.model.openai.OpenAiChatModel
import info.skyblond.daapu.agent.lc4j.llm.LLM
import info.skyblond.daapu.agent.refreshSystemPrompt
import info.skyblond.daapu.chat.ChatMessage
import info.skyblond.daapu.chat.ChatMessagePart
import info.skyblond.daapu.chat.ChatMessageRole

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
     * */
    fun compactChat(
        fullChat: List<ChatMessage>,
        excludeLastNRound: Int
    ): ChatCompactionResult {
        val (chatToCompact, chatToPreserve) = splitMessage(excludeLastNRound)
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

        // TODO: check model capacities, send request, etc.
        TODO()
    }

    private fun splitMessage(lastNRound: Int): Pair<List<ChatMessage>, List<ChatMessage>> {
        TODO()
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

The first message might be a summarized message starts with marker `$COMPACTION_HEADER`,
in that case, you should include the related parts from previous compaction.
""".trimIndent().trim()
    }
}