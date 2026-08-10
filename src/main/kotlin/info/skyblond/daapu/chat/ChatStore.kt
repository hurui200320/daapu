package info.skyblond.daapu.chat

/**
 * Load/store the chat history as the framework-neutral format
 * ([ChatMessage]s), owned by this project.
 */
interface ChatStore {
    suspend fun load(chatId: String): List<ChatMessage>

    suspend fun store(chatId: String, messages: List<ChatMessage>)
}
