package info.skyblond.daapu.memory.eltm

import info.skyblond.daapu.agent.chat.ChatCodec
import info.skyblond.daapu.agent.chat.ChatMessage
import info.skyblond.daapu.db.PendingExtractions
import info.skyblond.daapu.db.withTransaction
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

private val logger = KotlinLogging.logger {}

/**
 * One claimed queue job: the row's [id] and the decoded history snapshot
 * ([messages]) the extractor will consume. The row stays in the table
 * (invisible, see the queue's KDoc) until the worker deletes it on success.
 */
data class ClaimedJob(
    val id: Long,
    val messages: List<ChatMessage>,
)

/**
 * The background memory-extraction queue (`pending_extractions` table,
 * `V3__pending_extractions.sql`): the Postgres-as-queue seam between the
 * chat-deletion path (`agent/chat/ChatService.kt`'s `deleteChat`, which
 * enqueues a history snapshot and deletes the chats row on the request
 * path) and the extraction worker (`ExtractionQueueWorker.kt`, which drains
 * the queue into the ELTM off the request path — slow endpoints no longer
 * stall the delete).
 *
 * VISIBILITY-TIMEOUT PATTERN (the migration's header comment holds the
 * authoritative mechanism description): there is no separate lease or
 * attempt bookkeeping — the single `visible_after` column carries
 * everything. A job is claimable when `visible_after <= now()`; the claim
 * runs SELECT..FOR UPDATE SKIP LOCKED + the `visible_after` update inside
 * ONE transaction, moving the claimed row [jobTimeoutMinutes] into the
 * future, so it is invisible to every worker (including the claimant's next
 * polls) until the window lapses:
 *
 * - success: the worker deletes the row ([complete]);
 * - a KNOWN failure: the worker reschedules the row to the shorter
 *   [retryDelayMinutes] ([reschedule]) — it will re-emerge and be retried;
 * - a crash / shutdown / a job overrunning the timeout: the row is left
 *   alone and re-emerges at the lease boundary — same mechanism, no extra
 *   state. A duplicate run of an over-claimed job is benign: the ELTM
 *   writer deduplicates against the store.
 *
 * Retries are unlimited; every failure is logged by the worker. All time
 * arithmetic happens in the database (`now()`), so multiple app instances
 * never disagree on the clock.
 *
 * Retention: a job's snapshot carries the deleted chat's full content (text
 * and image attachments) until the job completes — see the retention note
 * in `V3__pending_extractions.sql`, the authoritative one.
 */
interface ExtractionQueue {
    /**
     * Insert a job carrying the history snapshot; returns its id. Callers
     * pass [ChatMessage]s — how the snapshot is stored is the
     * implementation's detail (see [PostgresExtractionQueue]).
     */
    suspend fun enqueue(messages: List<ChatMessage>): Long

    /**
     * Atomically claim the oldest claimable job (FIFO by id), or return
     * null when none is visible. On success the job is invisible for
     * [jobTimeoutMinutes]. A job whose stored snapshot fails to decode is
     * never handed out: it is treated as a known failure (rescheduled to
     * the retry delay) and null is returned — see the implementation.
     */
    suspend fun claim(): ClaimedJob?

    /** Delete a successfully processed job. */
    suspend fun complete(id: Long)

    /**
     * Re-arm a failed job to re-emerge after [retryDelayMinutes] (best
     * effort by the caller — if this fails, the claim's lease is the
     * backstop).
     */
    suspend fun reschedule(id: Long)
}

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
        // every poll, blocking the queue head on the same job.
        val messages = try {
            ChatCodec.decodeChat("extraction job $id", row[PendingExtractions.chatJson])
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
}
