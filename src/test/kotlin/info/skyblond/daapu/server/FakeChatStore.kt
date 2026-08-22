package info.skyblond.daapu.server

import info.skyblond.daapu.agent.chat.*
import info.skyblond.daapu.db.DEFAULT_CHAT_TITLE

/**
 * An in-memory [ChatStore] for service/route tests: seeded via [seed] and
 * inspected via [title]/[deleteRow] without a database. The row stores the
 * title, the messages AND the `eltm_version` (truncations and forks reset
 * it — tests of those paths need the value to round-trip).
 */
class FakeChatStore : ChatStore {
    private data class Row(
        val title: String,
        val chat: List<ChatMessage>,
        val eltmVersion: String,
    )

    private val rows = mutableMapOf<String, Row>()

    // ids are `chat-{n}`; a counter that skips ids already taken by [seed]
    // so newChat never overwrites a seeded row
    private var nextId = 0

    fun seed(
        chatId: String,
        title: String = DEFAULT_CHAT_TITLE,
        chat: List<ChatMessage> = emptyList(),
        eltmVersion: String = "",
    ) {
        rows[chatId] = Row(title, chat, eltmVersion)
    }

    fun title(chatId: String): String? = rows[chatId]?.title

    fun deleteRow(chatId: String) {
        rows.remove(chatId)
    }

    override suspend fun listChats(): List<ChatInfo> =
        rows.map { (id, row) -> ChatInfo(id, row.title) }

    override suspend fun newChat(): ChatInfo {
        var id: String
        do {
            id = "chat-${nextId++}"
        } while (rows.containsKey(id))
        rows[id] = Row(DEFAULT_CHAT_TITLE, emptyList(), "")
        return ChatInfo(id, DEFAULT_CHAT_TITLE)
    }

    override suspend fun load(chatId: String): ChatEntry? =
        rows[chatId]?.let {
            ChatEntry(
                ChatInfo(chatId, it.title),
                ChatContent(it.chat, it.eltmVersion)
            )
        }

    override suspend fun store(chatId: String, chat: ChatContent) {
        val title = rows[chatId]?.title ?: DEFAULT_CHAT_TITLE
        rows[chatId] = Row(title, chat.messages, chat.eltmVersion)
    }

    override suspend fun rename(chatId: String, title: String): ChatInfo? = rows[chatId]?.let {
        rows[chatId] = it.copy(title = title)
        ChatInfo(chatId, title)
    }

    override suspend fun delete(chatId: String): Boolean = rows.remove(chatId) != null
}
