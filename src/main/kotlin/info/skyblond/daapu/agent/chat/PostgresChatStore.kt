package info.skyblond.daapu.agent.chat

import info.skyblond.daapu.db.Chats
import info.skyblond.daapu.db.newChatId
import info.skyblond.daapu.db.withTransaction
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.*

/**
 * Postgres-backed [ChatStore], storing the whole chat as one JSON array in
 * the chat row's `chat_json` column.
 */
class PostgresChatStore : ChatStore {

    override suspend fun listChats(): List<ChatInfo> = withTransaction {
        Chats.selectAll()
            // TODO: should add time to Chats, lastUpdatedAt
            .orderBy(Chats.id to SortOrder.DESC)
            // TODO: pagination?
            .limit(200)
            .map { row ->
                ChatInfo(row[Chats.id], row[Chats.title], row[Chats.personaId])
            }
    }

    override suspend fun newChat(personaId: Long): ChatInfo = withTransaction {
        val id = newChatId()
        Chats.insert {
            it[Chats.id] = id
            it[Chats.title] = DEFAULT_CHAT_TITLE
            it[Chats.personaId] = personaId
        }
        ChatInfo(id, DEFAULT_CHAT_TITLE, personaId)
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
