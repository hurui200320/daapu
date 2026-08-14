package info.skyblond.daapu.agent.chat

import info.skyblond.daapu.db.Chats
import info.skyblond.daapu.db.withTransaction
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert

/**
 * Postgres-backed [ChatStore], storing the whole chat as one JSON array in
 * the chat row's `chat_json` column.
 */
class PostgresChatStore : ChatStore {

    override suspend fun load(chatId: String): ChatStoreEntry = withTransaction {
        val entry = Chats.selectAll()
            .where { Chats.id eq chatId }
            .singleOrNull()
        val json = entry?.get(Chats.chatJson) ?: "[]"
        val sstmVersion = entry?.get(Chats.sstmVersion) ?: ""
        ChatStoreEntry(
            chat = ChatCodec.decodeChat(chatId, json),
            sstmVersion = sstmVersion
        )
    }

    override suspend fun store(chatId: String, chat: ChatStoreEntry) {
        val chatJson = ChatCodec.encodeChat(chat.chat)
        withTransaction {
            Chats.upsert {
                it[Chats.id] = chatId
                it[Chats.chatJson] = chatJson
                it[Chats.sstmVersion] = chat.sstmVersion
            }
        }
    }
}
