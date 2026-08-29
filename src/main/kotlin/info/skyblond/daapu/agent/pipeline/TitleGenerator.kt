package info.skyblond.daapu.agent.pipeline

import info.skyblond.daapu.agent.chat.ChatMessage
import info.skyblond.daapu.agent.chat.ChatMessagePart
import info.skyblond.daapu.agent.chat.ChatMessageRole
import info.skyblond.daapu.agent.chat.roundCount
import info.skyblond.daapu.agent.chat.takeLastNRound
import info.skyblond.daapu.agent.model.LLM
import info.skyblond.daapu.agent.chat.DEFAULT_CHAT_TITLE
import info.skyblond.daapu.agent.model.ModelCapabilityException
import info.skyblond.daapu.hand.HandRunPolicy
import info.skyblond.daapu.hand.HandService

class TitleGenerator(
    private val model: LLM,
    private val hand: HandService,
    /**
     * How many trailing user rounds of the history feed the title generator;
     * `0` means the whole history. One round is one user message plus the
     * assistant/tool messages until the next user message, so cutting at a
     * user-message boundary never splits tool_call/tool_result pairs.
     */
    private val lastNRound: Int = 0,
    // the hand's /v1/run policy knobs for this one-shot (config `hand.*`):
    // transient failures retry with the same budget/backoff as the chat loop
    private val policy: HandRunPolicy,
) {
    /**
     * Generate a session title from [history]. An empty history returns the
     * default title without calling the LLM; a capability mismatch (e.g. a
     * text-only title model with image history) fails fast with
     * [ModelCapabilityException] before the call; any hand/validation
     * failure throws [IllegalStateException].
     */
    suspend fun generateTitle(
        history: List<ChatMessage>,
    ): String {
        if (history.isEmpty()) return DEFAULT_CHAT_TITLE
        val truncated = truncateToLastNRounds(history, lastNRound)
        // fail fast on a capability mismatch before the LLM call (same as the
        // compaction/extraction services): a text-only title model cannot see
        // image history, which is a `title.model` configuration error
        model.checkPromptContentCapabilities(truncated)

        return hand.runOneShotText(
            model = model,
            messages = truncated + ChatMessage(
                role = ChatMessageRole.User,
                parts = listOf(
                    ChatMessagePart.Text(
                        "Generate a title according to the system prompt."
                    )
                )
            ),
            systemPrompt = renderSystemPrompt(15),
            policy = policy,
            label = "Title generation",
        )
    }

    companion object {

        /**
         * Cut the history to its last [lastNRound] user rounds (a round is
         * one user message through to the next user message). Cutting at the
         * Nth-from-last user message keeps whole rounds only, so tool
         * call/result pairs survive intact. `0` or a cap beyond the round
         * count keeps the whole history.
         */
        private fun truncateToLastNRounds(
            chat: List<ChatMessage>,
            lastNRound: Int,
        ): List<ChatMessage> {
            if (lastNRound <= 0 || chat.roundCount() <= lastNRound) return chat
            return chat.takeLastNRound(lastNRound)
        }

        private fun renderSystemPrompt(words: Int): String = """
You're generating session title based on the conversation.

Rules:
- Be concise, the title should contains the core topic for user to distinguish it from other sessions.
- Output **ONE LINE** no more than $words words.
- Generate title in the same language as the conversation
- User may change topic in the middle, focus on the latest topic
""".trimIndent().trim()

    }
}