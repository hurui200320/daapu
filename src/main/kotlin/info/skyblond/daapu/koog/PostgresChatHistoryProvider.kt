package info.skyblond.daapu.koog

import ai.koog.agents.chatMemory.feature.ChatHistoryProvider
import ai.koog.prompt.message.Message
import info.skyblond.daapu.db.Chats
import info.skyblond.daapu.db.withTransaction
import info.skyblond.daapu.history.HistoryCodec
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert

/**
 * Postgres-backed [ChatHistoryProvider] for koog's `ChatMemory` feature.
 *
 * koog owns the conversation history lifecycle: it loads the full history
 * before each run and stores the full updated history after the run completes.
 * The whole [Message] list is converted to the framework-neutral history DTOs
 * (`info.skyblond.daapu.history`, see [toNeutralHistory]) and serialized into
 * the chat row's `history_json` column as one JSON array, so a chat's history
 * is loaded and stored as a single row. No koog type names reach the database.
 *
 * [ChatHistoryProvider.load] and [ChatHistoryProvider.store] receive koog's
 * opaque conversation id (its `runId`, i.e. the `sessionId` passed to
 * `AIAgent.run`), used verbatim as the `chats.id` primary key. Real chat ids
 * come from [info.skyblond.daapu.db.newChatId] via `POST /api/chats`.
 *
 * Loading fails fast: an undecodable `history_json` (corrupt row, or a format
 * change incompatible with [HistoryCodec]) throws rather than silently
 * resetting the chat to empty, so the break is noticed and migrated instead of
 * quietly discarding history.
 */
class PostgresChatHistoryProvider : ChatHistoryProvider {

    override suspend fun load(conversationId: String): List<Message> = withTransaction {
        val historyJson = Chats.selectAll()
            .where { Chats.id eq conversationId }
            .singleOrNull()
            ?.get(Chats.historyJson)
            ?: return@withTransaction emptyList()
        HistoryCodec.decodeHistory(conversationId, historyJson).toKoogHistory()
    }

    override suspend fun store(conversationId: String, messages: List<Message>) {
        val historyJson = HistoryCodec.encodeHistory(messages.toNeutralHistory())
        withTransaction {
            Chats.upsert {
                it[Chats.id] = conversationId
                it[Chats.historyJson] = historyJson
            }
        }
    }
}
