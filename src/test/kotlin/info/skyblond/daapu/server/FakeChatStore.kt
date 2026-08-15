package info.skyblond.daapu.server

import info.skyblond.daapu.agent.chat.*
import info.skyblond.daapu.db.DEFAULT_CHAT_TITLE

/**
 * An in-memory [ChatStore] for service/route tests: seeded via [seed] and
 * inspected via [title]/[deleteRow] without a database.
 */
class FakeChatStore : ChatStore {
    private val rows = mutableMapOf<String, Pair<String, List<ChatMessage>>>()

    fun seed(
        chatId: String,
        title: String = DEFAULT_CHAT_TITLE,
        chat: List<ChatMessage> = emptyList()
    ) {
        rows[chatId] = title to chat
    }

    fun title(chatId: String): String? = rows[chatId]?.first

    fun deleteRow(chatId: String) {
        rows.remove(chatId)
    }

    override suspend fun listChats(): List<ChatInfo> =
        rows.map { (id, row) -> ChatInfo(id, row.first) }

    override suspend fun newChat(): ChatInfo {
        val id = "chat-${rows.size}"
        rows[id] = DEFAULT_CHAT_TITLE to emptyList()
        return ChatInfo(id, DEFAULT_CHAT_TITLE)
    }

    override suspend fun load(chatId: String): ChatEntry? =
        rows[chatId]?.let {
            ChatEntry(
                ChatInfo(chatId, it.first),
                ChatContent(it.second, "")
            )
        }

    override suspend fun store(chatId: String, chat: ChatContent) {
        rows[chatId] = (rows[chatId]?.first ?: DEFAULT_CHAT_TITLE) to chat.messages
    }

    override suspend fun rename(chatId: String, title: String): ChatInfo? = rows[chatId]?.let {
        rows[chatId] = title to it.second
        ChatInfo(chatId, title)
    }

    override suspend fun delete(chatId: String): Boolean = rows.remove(chatId) != null
}
