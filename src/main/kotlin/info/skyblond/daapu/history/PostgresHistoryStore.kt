package info.skyblond.daapu.history

import info.skyblond.daapu.db.Chats
import info.skyblond.daapu.db.withTransaction
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert

/**
 * Load/store the chat history as the framework-neutral format
 * ([HistoryMessage]s), owned by this project.
 *
 * The turn loop (`agent/ChatTurnLoop.kt`) is the only consumer: it loads the
 * full history before a run and stores the updated full history after a
 * *successful* run, so a failed or aborted run leaves the chat untouched.
 * No koog or langchain4j type names reach the database.
 */
interface HistoryStore {
    suspend fun load(chatId: String): List<HistoryMessage>

    suspend fun store(chatId: String, messages: List<HistoryMessage>)
}

/**
 * Postgres-backed [HistoryStore], storing the whole history as one JSON array
 * in the chat row's `history_json` column.
 *
 * Loading fails fast: an undecodable `history_json` (corrupt row, or a format
 * change incompatible with [HistoryCodec]) throws rather than silently
 * resetting the chat to empty, so the break is noticed and migrated instead of
 * quietly discarding history.
 */
class PostgresHistoryStore : HistoryStore {

    override suspend fun load(chatId: String): List<HistoryMessage> = withTransaction {
        Chats.selectAll()
            .where { Chats.id eq chatId }
            .singleOrNull()
            ?.get(Chats.historyJson)
            ?: "[]"
    }.let { HistoryCodec.decodeHistory(chatId, it) }

    override suspend fun store(chatId: String, messages: List<HistoryMessage>) {
        val historyJson = HistoryCodec.encodeHistory(messages)
        withTransaction {
            Chats.upsert {
                it[Chats.id] = chatId
                it[Chats.historyJson] = historyJson
            }
        }
    }
}
