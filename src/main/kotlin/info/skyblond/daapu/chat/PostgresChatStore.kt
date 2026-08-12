package info.skyblond.daapu.chat

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

    override suspend fun load(chatId: String): List<ChatMessage> = withTransaction {
        Chats.selectAll()
            .where { Chats.id eq chatId }
            .singleOrNull()
            ?.get(Chats.chatJson)
            ?: "[]"
    }.let { ChatCodec.decodeChat(chatId, it) }

    override suspend fun store(chatId: String, messages: List<ChatMessage>) {
        val chatJson = ChatCodec.encodeChat(messages)
        withTransaction {
            Chats.upsert {
                it[Chats.id] = chatId
                it[Chats.chatJson] = chatJson
            }
        }
    }
}
