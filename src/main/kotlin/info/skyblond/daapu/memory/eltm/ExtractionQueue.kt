package info.skyblond.daapu.memory.eltm

import info.skyblond.daapu.agent.chat.ChatMessage

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
 * paths that drop history worth remembering — the chat-deletion path
 * (`agent/chat/ChatService.kt`'s `deleteChat`, which enqueues a history
 * snapshot and deletes the chats row on the request path) and the
 * compaction path (`agent/persist/PersistChatService.kt`'s
 * `compactAndEnqueue`, which enqueues the dropped messages before the
 * compacted history is stored) — and the extraction worker
 * (`ExtractionQueueWorker.kt`, which drains the queue into the ELTM off the
 * request path — slow endpoints stall neither a delete nor a chat run).
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
 * Retention: a job's snapshot carries the dropped history's full content
 * (text and image attachments — a deleted chat's history or a compaction's
 * drop region) until the job completes — see the retention note in
 * `V3__pending_extractions.sql`, the authoritative one.
 */
interface ExtractionQueue {
    /**
     * Insert a job carrying the history snapshot (a deleted chat's full
     * history or a compaction's dropped messages); returns its id. Callers
     * pass [ChatMessage]s — how the snapshot is stored is the
     * implementation's detail (see
     * [info.skyblond.daapu.memory.eltm.postgres.PostgresExtractionQueue]).
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

