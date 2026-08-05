package info.skyblond.daapu.koog

import ai.koog.agents.chatMemory.feature.ChatHistoryProvider
import ai.koog.prompt.message.Message
import info.skyblond.daapu.db.Chats
import info.skyblond.daapu.db.withTransaction
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert

/**
 * Postgres-backed [ChatHistoryProvider] for koog's `ChatMemory` feature.
 *
 * koog owns the conversation history: it loads the full history before each run
 * and stores the full updated history after the run completes. The whole
 * [Message] list is serialized into the chat row's `history_json` column as one
 * JSON array, so a chat's history is loaded and stored as a single row.
 *
 * [ChatHistoryProvider.load] and [ChatHistoryProvider.store] receive koog's
 * opaque conversation id (its `runId`, i.e. the `sessionId` passed to
 * `AIAgent.run`), used verbatim as the `chats.id` primary key.
 * [info.skyblond.daapu.db.newChatId] is the intended generator for real chat
 * ids; `Main` currently passes a hardcoded session id for debugging.
 *
 * Loading fails fast: an undecodable `history_json` (corrupt row, or a koog
 * upgrade that changed the `Message` format) throws rather than silently
 * resetting the chat to empty, so the format break is noticed and migrated
 * instead of quietly discarding history.
 */
class PostgresChatHistoryProvider : ChatHistoryProvider {

    companion object {
        /** Visible for testing: the golden-format tests exercise this codec. */
        internal val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

        /**
         * Decode a stored `history_json` payload, failing fast on corruption
         * or an incompatible koog format instead of silently resetting the
         * chat to empty. Visible for testing.
         */
        internal fun decodeHistory(conversationId: String, historyJson: String): List<Message> =
            try {
                json.decodeFromString(historyJson)
            } catch (e: Exception) {
                throw IllegalStateException(
                    "Failed to decode history_json for chat '$conversationId': the stored format " +
                            "is corrupt or incompatible with the current koog version. " +
                            "Migrate or fix the chats row manually.",
                    e
                )
            }
    }

    override suspend fun load(conversationId: String): List<Message> = withTransaction {
        val historyJson = Chats.selectAll()
            .where { Chats.id eq conversationId }
            .singleOrNull()
            ?.get(Chats.historyJson)
            ?: return@withTransaction emptyList()
        decodeHistory(conversationId, historyJson)
    }

    override suspend fun store(conversationId: String, messages: List<Message>) {
        val historyJson = json.encodeToString(messages)
        withTransaction {
            Chats.upsert {
                it[Chats.id] = conversationId
                it[Chats.historyJson] = historyJson
            }
        }
    }
}
