package info.skyblond.daapu.agent.chat

import info.skyblond.daapu.db.Chats
import info.skyblond.daapu.db.newChatId
import info.skyblond.daapu.db.withTransaction
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.*

/** The `GET /api/chats` page size (fixed — no client-visible limit knob). */
private const val CHAT_PAGE_SIZE = 200

/**
 * Postgres-backed [ChatStore], storing the whole chat as one JSON array in
 * the chat row's `chat_json` column.
 */
class PostgresChatStore : ChatStore {

    override suspend fun listChats(cursor: String?): ChatListPage = withTransaction {
        // keyset pagination: `id < cursor` anchors the page start at a
        // POSITION in the newest-first order (see ChatListPage), so a
        // concurrent delete can never shift a row across the page boundary
        // (the id is immutable and creation-time-ordered — see newChatId in
        // `db/ChatIds.kt`). One extra row beyond the page size tells whether
        // a next page exists without a separate count query.
        val rows = Chats.selectAll()
            .apply { if (cursor != null) andWhere { Chats.id less cursor } }
            .orderBy(Chats.id to SortOrder.DESC)
            .limit(CHAT_PAGE_SIZE + 1)
            .map { row ->
                ChatInfo(row[Chats.id], row[Chats.title], row[Chats.personaId])
            }
        val hasMore = rows.size > CHAT_PAGE_SIZE
        val chats = if (hasMore) rows.dropLast(1) else rows
        ChatListPage(chats, if (hasMore) chats.last().id else null)
    }

    override suspend fun newChat(personaId: Long, title: String): ChatInfo = withTransaction {
        val id = newChatId()
        Chats.insert {
            it[Chats.id] = id
            it[Chats.title] = title
            it[Chats.personaId] = personaId
        }
        ChatInfo(id, title, personaId)
    }

    override suspend fun load(chatId: String): ChatEntry? = withTransaction {
        val entry = Chats.selectAll()
            .where { Chats.id eq chatId }
            .singleOrNull()
            ?: return@withTransaction null
        ChatEntry(
            info = ChatInfo(entry[Chats.id], entry[Chats.title], entry[Chats.personaId]),
            content = ChatContent(
                messages = ChatCodec.decodeChat(chatId, entry[Chats.chatJson]),
                eltmVersion = entry[Chats.eltmVersion],
                personaId = entry[Chats.personaId],
            )
        )
    }

    override suspend fun store(chatId: String, chat: ChatContent) {
        // fail fast before anything is written: the same validation the
        // decode path applies (user messages must carry createdAt, the chat
        // must be re-sendable), so a bad row can never be stored
        ChatCodec.validateChat(chat.messages)
        val chatJson = ChatCodec.encodeChat(chat.messages)
        withTransaction {
            Chats.upsert {
                it[Chats.id] = chatId
                it[Chats.chatJson] = chatJson
                it[Chats.eltmVersion] = chat.eltmVersion
                it[Chats.personaId] = chat.personaId
            }
        }
    }

    override suspend fun rename(chatId: String, title: String): ChatInfo? = withTransaction {
        // read the row first: the returned ChatInfo must carry the row's
        // ACTUAL persona record (a defaulted personaId would silently report
        // the reserved default 0 for a chat whose record is a custom persona)
        val row = Chats.selectAll()
            .where { Chats.id eq chatId }
            .singleOrNull()
            ?: return@withTransaction null
        Chats.update({ Chats.id eq chatId }) {
            it[Chats.title] = title
        }
        ChatInfo(chatId, title, row[Chats.personaId])
    }

    override suspend fun delete(chatId: String): Boolean = withTransaction {
        Chats.deleteWhere { Chats.id eq chatId } > 0
    }
}
