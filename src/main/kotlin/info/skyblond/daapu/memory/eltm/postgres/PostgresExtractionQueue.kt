package info.skyblond.daapu.memory.eltm.postgres

import info.skyblond.daapu.agent.chat.ChatCodec
import info.skyblond.daapu.agent.chat.ChatMessage
import info.skyblond.daapu.db.PendingExtractions
import info.skyblond.daapu.db.withTransaction
import info.skyblond.daapu.memory.eltm.ClaimedJob
import info.skyblond.daapu.memory.eltm.ExtractionQueue
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * Postgres-backed [ExtractionQueue] over Exposed. The ChatCodec
 * encode/decode of the snapshot (the `chat_json` column, see
 * `V3__pending_extractions.sql`) is THIS class's job — the interface works
 * in [ChatMessage]s only, so no caller touches the storage format. Only the
 * two `visible_after` updates run as raw SQL (Exposed's DSL cannot express
 * `now() + interval` date arithmetic); everything else is plain DSL.
 * Everything runs in one short transaction per call — the minutes-long
 * extraction afterwards is protected purely by the moved `visible_after`,
 * so no connection is pinned.
 *
 * Note on failures: per `db/Database.kt`, an escaping SQLException makes
 * Exposed re-run the block — for the claim this can hand out a DIFFERENT
 * job on the retry, which is harmless (both are valid claims).
 */
class PostgresExtractionQueue(
    private val jobTimeoutMinutes: Int,
    private val retryDelayMinutes: Int,
) : ExtractionQueue {

    override suspend fun enqueue(messages: List<ChatMessage>): Long = withTransaction {
        PendingExtractions.insert {
            it[PendingExtractions.chatJson] = ChatCodec.encodeChat(messages)
        } get PendingExtractions.id
    }

    override suspend fun claim(): ClaimedJob? = withTransaction {
        // the row lock taken by FOR UPDATE SKIP LOCKED persists until this
        // transaction commits, and the visible_after update below runs in
        // the SAME transaction: the claim is select + decode + update,
        // atomic against other claimers (a concurrent claimer either skips
        // the locked row or, after commit, sees the moved visible_after)
        val row = PendingExtractions.selectAll()
            .where { PendingExtractions.visibleAfter lessEq CurrentTimestampWithTimeZone }
            // FIFO by id: a retried job keeps its place against newer work,
            // and ties are impossible. The PK's btree serves this ordering
            // (the scan stops at the first visible row) — no extra index.
            .orderBy(PendingExtractions.id, order = SortOrder.ASC)
            .forUpdate(ForUpdateOption.PostgreSQL.ForUpdate(ForUpdateOption.PostgreSQL.MODE.SKIP_LOCKED))
            .limit(1)
            .firstOrNull()
            ?: return@withTransaction null
        val id = row[PendingExtractions.id]
        // decode INSIDE the claim transaction, BEFORE the lease: a corrupt
        // snapshot (only external tampering can produce one — enqueue always
        // encodes valid histories) is a known failure, pushed to the retry
        // delay like any other. ROLLING BACK instead would leave the row
        // visible and oldest forever: it would be re-claimed and re-fail
        // every poll, blocking the queue head on the same job. The SNAPSHOT
        // validation (ChatCodec.validateSnapshot) applies, not the stored-
        // chat one: a compaction drop region is a fragment, not a complete
        // chat (see ChatCodec.decodeSnapshot).
        val messages = try {
            ChatCodec.decodeSnapshot("extraction job $id", row[PendingExtractions.chatJson])
        } catch (e: IllegalStateException) {
            logger.error(e) {
                "Extraction job $id holds a corrupt history snapshot, " +
                        "rescheduled to retry in $retryDelayMinutes minute(s)"
            }
            updateVisibleAfter(id, retryDelayMinutes)
            return@withTransaction null
        }
        updateVisibleAfter(id, jobTimeoutMinutes)
        ClaimedJob(id, messages)
    }

    override suspend fun complete(id: Long) {
        withTransaction {
            PendingExtractions.deleteWhere { PendingExtractions.id eq id }
        }
        logger.debug { "Extraction job $id completed" }
    }

    override suspend fun reschedule(id: Long) {
        withTransaction {
            updateVisibleAfter(id, retryDelayMinutes)
        }
        logger.debug { "Extraction job $id rescheduled to retry in $retryDelayMinutes minute(s)" }
    }

    /** Move one job's visibility marker [minutes] into the DB's future. */
    private fun JdbcTransaction.updateVisibleAfter(
        id: Long,
        minutes: Int,
    ) {
        exec(
            "UPDATE pending_extractions SET visible_after = now() + make_interval(mins => ?) WHERE id = ?",
            args = listOf(
                IntegerColumnType() to minutes,
                LongColumnType() to id,
            ),
        )
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
