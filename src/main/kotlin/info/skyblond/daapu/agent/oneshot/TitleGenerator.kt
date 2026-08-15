package info.skyblond.daapu.agent.oneshot

import info.skyblond.daapu.agent.chat.ChatMessage
import info.skyblond.daapu.agent.chat.ChatMessagePart
import info.skyblond.daapu.agent.chat.ChatMessageRole
import info.skyblond.daapu.agent.model.LLM
import info.skyblond.daapu.db.DEFAULT_CHAT_TITLE
import info.skyblond.daapu.hand.HandClient
import info.skyblond.daapu.hand.HandCompleteRequest
import info.skyblond.daapu.hand.toHandModelSpec

class TitleGenerator(
    private val model: LLM,
    private val hand: HandClient,
) {
    suspend fun generateTitle(
        history: List<ChatMessage>,
    ): String {
        if (history.isEmpty()) return DEFAULT_CHAT_TITLE

        val response = hand.complete(
            HandCompleteRequest(
                model = model.toHandModelSpec(),
                messages = history + ChatMessage(
                    role = ChatMessageRole.User,
                    parts = listOf(
                        ChatMessagePart.Text(
                            "Generate a title according to the system prompt."
                        )
                    )
                ),
                // TODO: start with 30 words
                systemPrompt = renderSystemPrompt(30),
            )
        )
        return response.checkAndGetTextResp()
    }

    companion object {

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