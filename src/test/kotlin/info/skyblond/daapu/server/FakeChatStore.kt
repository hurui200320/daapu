package info.skyblond.daapu.server

import info.skyblond.daapu.agent.chat.*
import info.skyblond.daapu.db.DEFAULT_CHAT_TITLE

/**
 * An in-memory [ChatStore] for service/route tests: seeded via [seed] and
 * inspected via [title]/[deleteRow] without a database. The row stores the
 * title, the messages AND the `sstm_version` (truncations and forks reset
 * it — tests of those paths need the value to round-trip).
 */
class FakeChatStore : ChatStore {
    private val rows = mutableMapOf<String, Triple<String, List<ChatMessage>, String>>()
    // ids are `chat-{n}`; a counter that skips ids already taken by [seed]
    // so newChat never overwrites a seeded row
    private var nextId = 0

    fun seed(
        chatId: String,
        title: String = DEFAULT_CHAT_TITLE,
        chat: List<ChatMessage> = emptyList(),
        sstmVersion: String = "",
    ) {
        rows[chatId] = Triple(title, chat, sstmVersion)
    }

    fun title(chatId: String): String? = rows[chatId]?.first

    fun sstmVersion(chatId: String): String? = rows[chatId]?.third

    fun deleteRow(chatId: String) {
        rows.remove(chatId)
    }

    override suspend fun listChats(): List<ChatInfo> =
        rows.map { (id, row) -> ChatInfo(id, row.first) }

    override suspend fun newChat(): ChatInfo {
        var id: String
        do {
            id = "chat-${nextId++}"
        } while (rows.containsKey(id))
        rows[id] = Triple(DEFAULT_CHAT_TITLE, emptyList(), "")
        return ChatInfo(id, DEFAULT_CHAT_TITLE)
    }

    override suspend fun load(chatId: String): ChatEntry? =
        rows[chatId]?.let {
            ChatEntry(
                ChatInfo(chatId, it.first),
                ChatContent(it.second, it.third)
            )
        }

    override suspend fun store(chatId: String, chat: ChatContent) {
        val title = rows[chatId]?.first ?: DEFAULT_CHAT_TITLE
        rows[chatId] = Triple(title, chat.messages, chat.sstmVersion)
    }

    override suspend fun rename(chatId: String, title: String): ChatInfo? = rows[chatId]?.let {
        rows[chatId] = Triple(title, it.second, it.third)
        ChatInfo(chatId, title)
    }

    override suspend fun delete(chatId: String): Boolean = rows.remove(chatId) != null
}
