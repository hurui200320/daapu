package info.skyblond.daapu.server

import info.skyblond.daapu.agent.chat.DEFAULT_CHAT_TITLE
import info.skyblond.daapu.agent.chat.*
import info.skyblond.daapu.agent.persona.DEFAULT_PERSONA_ID

/**
 * An in-memory [ChatStore] for service/route tests: seeded via [seed] and
 * inspected via [title]/[deleteRow] without a database. The row stores the
 * title, the messages, the `eltm_version` AND the persona record
 * (truncations and forks reset the version — tests of those paths need the
 * values to round-trip).
 */
class FakeChatStore : ChatStore {
    private data class Row(
        val title: String,
        val chat: List<ChatMessage>,
        val eltmVersion: String,
        val personaId: Long = DEFAULT_PERSONA_ID,
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
        personaId: Long = DEFAULT_PERSONA_ID,
    ) {
        rows[chatId] = Row(title, chat, eltmVersion, personaId)
    }

    fun title(chatId: String): String? = rows[chatId]?.title

    fun personaId(chatId: String): Long? = rows[chatId]?.personaId

    fun deleteRow(chatId: String) {
        rows.remove(chatId)
    }

    override suspend fun listChats(): List<ChatInfo> =
        rows.map { (id, row) -> ChatInfo(id, row.title, row.personaId) }

    override suspend fun newChat(personaId: Long): ChatInfo {
        var id: String
        do {
            id = "chat-${nextId++}"
        } while (rows.containsKey(id))
        rows[id] = Row(DEFAULT_CHAT_TITLE, emptyList(), "", personaId)
        return ChatInfo(id, DEFAULT_CHAT_TITLE, personaId)
    }

    override suspend fun load(chatId: String): ChatEntry? =
        rows[chatId]?.let {
            ChatEntry(
                ChatInfo(chatId, it.title, it.personaId),
                ChatContent(it.chat, it.eltmVersion, it.personaId)
            )
        }

    override suspend fun store(chatId: String, chat: ChatContent) {
        val title = rows[chatId]?.title ?: DEFAULT_CHAT_TITLE
        rows[chatId] = Row(title, chat.messages, chat.eltmVersion, chat.personaId)
    }

    override suspend fun rename(chatId: String, title: String): ChatInfo? = rows[chatId]?.let {
        rows[chatId] = it.copy(title = title)
        ChatInfo(chatId, title, it.personaId)
    }

    override suspend fun delete(chatId: String): Boolean = rows.remove(chatId) != null
}
