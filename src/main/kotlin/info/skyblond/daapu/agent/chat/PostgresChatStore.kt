package info.skyblond.daapu.agent.chat

import info.skyblond.daapu.db.Chats
import info.skyblond.daapu.db.DEFAULT_CHAT_TITLE
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
            .map { row -> ChatInfo(row[Chats.id], row[Chats.title]) }
    }

    override suspend fun newChat(): ChatInfo = withTransaction {
        val id = newChatId()
        Chats.insert {
            it[Chats.id] = id
            it[Chats.title] = DEFAULT_CHAT_TITLE
        }
        ChatInfo(id, DEFAULT_CHAT_TITLE)
    }

    override suspend fun load(chatId: String): ChatEntry? = withTransaction {
        val entry = Chats.selectAll()
            .where { Chats.id eq chatId }
            .singleOrNull()
            ?: return@withTransaction null
        ChatEntry(
            info = ChatInfo(entry[Chats.id], entry[Chats.title]),
            content = ChatContent(
                messages = ChatCodec.decodeChat(chatId, entry[Chats.chatJson]),
                sstmVersion = entry[Chats.sstmVersion]
            )
        )
    }

    override suspend fun store(chatId: String, chat: ChatContent) {
        val chatJson = ChatCodec.encodeChat(chat.messages)
        withTransaction {
            Chats.upsert {
                it[Chats.id] = chatId
                it[Chats.chatJson] = chatJson
                it[Chats.sstmVersion] = chat.sstmVersion
            }
        }
    }

    override suspend fun rename(chatId: String, title: String): ChatInfo? = withTransaction {
        val updated = Chats.update({ Chats.id eq chatId }) {
            it[Chats.title] = title
        }
        if (updated == 0) null else ChatInfo(chatId, title)
    }

    override suspend fun delete(chatId: String): Boolean = withTransaction {
        Chats.deleteWhere { Chats.id eq chatId } > 0
    }
}
