package info.skyblond.daapu.llm

import ai.koog.agents.chatMemory.feature.ChatHistoryProvider
import ai.koog.prompt.message.Message
import info.skyblond.daapu.db.Messages
import info.skyblond.daapu.db.withTransaction
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * Postgres-backed [ChatHistoryProvider] for koog's `ChatMemory` feature.
 *
 * koog owns the conversation history: it loads the full history before each run
 * and stores the full updated history after the run completes. This provider
 * persists those koog [Message] objects in our `messages` table, one row per
 * message, still keyed by `chat_id` so we know which message belongs to which
 * chat.
 */
class PostgresChatHistoryProvider : ChatHistoryProvider {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    override suspend fun load(conversationId: String): List<Message> = withTransaction {
        val chatId = conversationId.toLong()
        Messages.selectAll()
            .where { Messages.chatId eq chatId }
            .orderBy(Messages.id, SortOrder.ASC)
            .map { row -> json.decodeFromString(Message.serializer(), row[Messages.messageJson]) }
    }

    override suspend fun store(conversationId: String, messages: List<Message>) = withTransaction {
        val chatId = conversationId.toLong()
        Messages.deleteWhere { Messages.chatId eq chatId }
        messages.forEach { message ->
            Messages.insert {
                it[Messages.chatId] = chatId
                it[Messages.messageJson] = json.encodeToString(Message.serializer(), message)
            }
        }
    }
}
