package info.skyblond.daapu.agent.chat

/**
 * Load/store a chat's messages in the framework-neutral format
 * ([ChatMessage]s), owned by this project.
 */
interface ChatStore {
    suspend fun load(chatId: String): ChatStoreEntry

    suspend fun store(chatId: String, chat: ChatStoreEntry)
}
