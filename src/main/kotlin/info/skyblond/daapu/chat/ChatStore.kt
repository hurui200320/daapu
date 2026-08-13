package info.skyblond.daapu.chat

/**
 * Load/store a chat's messages in the framework-neutral format
 * ([ChatMessage]s), owned by this project.
 */
interface ChatStore {
    suspend fun load(chatId: String): ChatEntry

    suspend fun store(chatId: String, chat: ChatEntry)
}
