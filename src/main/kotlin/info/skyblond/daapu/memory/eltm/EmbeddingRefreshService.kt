package info.skyblond.daapu.memory.eltm

import info.skyblond.daapu.agent.model.EmbeddingModel
import info.skyblond.daapu.db.ELTM_VERSION_KEY
import info.skyblond.daapu.db.EltmEntities
import info.skyblond.daapu.db.EltmEntityAttributes
import info.skyblond.daapu.db.EltmNotes
import info.skyblond.daapu.db.bumpMetaNumberTx
import info.skyblond.daapu.db.withTransaction
import info.skyblond.daapu.hand.HandRunPolicy
import info.skyblond.daapu.hand.HandService
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

private val logger = KotlinLogging.logger {}

/**
 * The in-server re-embed job's phase, snapshotted by [EmbeddingRefreshService.status].
 * In-memory only: a restart starts at [Idle] whatever a previous boot's job
 * was doing (already-written batches stay written, a partial run is safely
 * re-runnable — see [EmbeddingRefreshService]).
 */
sealed interface ReembedStatus {
    /** No refresh has been started this boot. */
    data object Idle : ReembedStatus

    /** A refresh is running in the background. */
    data object Running : ReembedStatus

    /** The last refresh finished: [entities] + [notes] rows re-embedded. */
    data class Finished(val entities: Long, val notes: Long) : ReembedStatus

    /** The last refresh failed: [error] is the failure reason (written batches stay written). */
    data class Failed(val error: String) : ReembedStatus
}

/**
 * Re-embed EVERY stored ELTM vector (`eltm_entities.embedding`,
 * `eltm_notes.embedding`) with the embedding model the config currently
 * points to (`memory.eltm.embeddingModel`), resolved ONCE at boot — the
 * same instance the ELTM service embeds with, so the refresh and the write
 * path can never disagree about the model. Run it after switching
 * embedding models: the old vectors are useless to the new model, and
 * cosine similarities across models are not comparable.
 *
 * Entry point: `POST /api/maintenance/reembed` (the web UI's `#/maintenance`
 * tab) — the route requires ELTM maintenance mode ON, so no ELTM writes
 * happen while the refresh runs (the ELTM read endpoints stay open — the
 * blocking scope is `requireEltmNotInMaintenance`'s KDoc,
 * `server/endpoint/MaintenanceRoute.kt`). The mode gate lives in the route,
 * not here: turning the mode OFF mid-run is allowed (the refresh keeps
 * going — mid-refresh writes already embed with the new model, so
 * re-embedding them is a harmless no-op; searches see a mixed state until
 * the run finishes).
 *
 * The text shapes are the SAME functions the write path uses —
 * [entityEmbeddingText] (name + category + `key: value` attribute lines)
 * and [noteEmbeddingText] (the trimmed note) — so a future format change
 * stays consistent between new writes and this refresh.
 *
 * Rows are processed in id order, one page at a time (bounded memory),
 * embedded in [EMBED_BATCH_SIZE] chunks through the hand's `/v1/embed`
 * (the shared ELTM batching helper, [HandService.embedAll] — the SAME
 * one the write path embeds with, so the cap and the zero-padding stay
 * single-sourced), and each chunk is written back in its own
 * transaction. Fails the run on any embed error
 * (the hand already retries `hand.maxRetries` times per request):
 * already-written batches stay written, a failed run is safely
 * re-runnable. Progress goes to the server log (per-page "so far" lines),
 * not the API.
 *
 * On full success the global ELTM write counter (`gsg_meta_number.
 * eltm_version`, `db/MetaNumber.kt`'s [ELTM_VERSION_KEY]) is bumped ONCE,
 * so every chat's next run flags `eltm-updated` — the retrieval results
 * are different under the new model. An empty ELTM skips the bump.
 *
 * Relationships and attributes carry no stored vectors, so nothing else
 * needs refreshing. The vector width never depends on the model
 * (zero-padded to the fixed column width by [embedAll]), so no schema
 * change is ever needed.
 *
 * Lifecycle: single-flight — [start] refuses while a run is active (the
 * route answers 409). The job runs on this service's own scope
 * (SupervisorJob + Dispatchers.IO, the ExtractionQueueWorker pattern);
 * [close] (the Koin onClose callback) cancels it — a job in flight at
 * shutdown is abandoned on purpose, no join that could stall the shutdown
 * for minutes. Only `Exception`s mark the run [ReembedStatus.Failed]: an
 * `Error` escaping the job is logged loudly by the scope's handler and
 * leaves the status at [ReembedStatus.Running] until the restart.
 */
class EmbeddingRefreshService(
    private val hand: HandService,
    private val model: EmbeddingModel,
    private val policy: HandRunPolicy,
): AutoCloseable {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineName("eltm-embedding-refresh") +
                CoroutineExceptionHandler { _, error ->
                    logger.error(error) {
                        "ELTM embedding refresh died — the status stays 'running' until the restart; " +
                                "already-written batches stay written and the run is re-runnable"
                    }
                }
    )

    // TODO: status only living in current JVM instance. Fine for PoC,
    //       but will need to share the task status via db eventually.
    @Volatile
    private var current: ReembedStatus = ReembedStatus.Idle

    /** The job's current phase (a snapshot; terminal states carry the summary). */
    fun status(): ReembedStatus = current

    /**
     * Launch a refresh in the background. Single-flight: returns false
     * (and launches nothing) while a run is active. The mode gate is the
     * route's job — see the class KDoc.
     */
    @Synchronized
    fun start(): Boolean {
        if (current is ReembedStatus.Running) return false
        current = ReembedStatus.Running
        logger.info { "ELTM embedding refresh started (model '${model.id}')" }
        scope.launch {
            try {
                val entities = reembedEntities()
                val notes = reembedNotes()
                if (entities + notes == 0L) {
                    current = ReembedStatus.Finished(0L, 0L)
                    logger.info { "nothing to re-embed, the ELTM is empty; version counter left untouched" }
                    return@launch
                }
                // ONE bump for the whole successful refresh (so the next
                // chat run flags eltm-updated), via the same helper the
                // service's write path uses
                bumpMetaNumberTx(ELTM_VERSION_KEY)
                current = ReembedStatus.Finished(entities, notes)
                logger.info {
                    "ELTM embedding refresh done: re-embedded $entities entities and $notes notes " +
                            "(eltm_version bumped so the next chat run flags eltm-updated)"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                current = ReembedStatus.Failed(e.message ?: e.javaClass.simpleName)
                logger.error(e) { "ELTM embedding refresh failed: ${e.message}" }
            }
        }
        return true
    }

    /** Cancel the scope; a running job is abandoned (written batches stay written). */
    override fun close() {
        scope.cancel()
    }

    /**
     * Re-embed all entities in id order, one page at a time; the page's
     * attributes ride the entity's embedding text, read with ONE query in
     * the same transaction as the page read (a consistent snapshot).
     * Returns the number of rows processed.
     */
    private suspend fun reembedEntities(): Long {
        var lastId = 0L
        var done = 0L
        while (true) {
            val page: List<Pair<Long, String>> = withTransaction {
                // only the text-building columns — never the stored vector
                // the job is about to overwrite
                val rows = EltmEntities.select(EltmEntities.id, EltmEntities.canonicalName, EltmEntities.category)
                    .where { EltmEntities.id greater lastId }
                    .orderBy(EltmEntities.id)
                    .limit(PAGE_SIZE)
                    .toList()
                val ids = rows.map { it[EltmEntities.id] }
                val attributesByEntity = if (ids.isEmpty()) {
                    emptyMap()
                } else {
                    EltmEntityAttributes.selectAll()
                        .where { EltmEntityAttributes.entityId inList ids }
                        .groupBy { it[EltmEntityAttributes.entityId] }
                        .mapValues { (_, attributeRows) ->
                            attributeRows.associate { it[EltmEntityAttributes.key] to it[EltmEntityAttributes.value] }
                        }
                }
                rows.map { row ->
                    row[EltmEntities.id] to entityEmbeddingText(
                        canonicalName = row[EltmEntities.canonicalName],
                        category = row[EltmEntities.category],
                        attributes = attributesByEntity[row[EltmEntities.id]] ?: emptyMap(),
                    )
                }
            }
            if (page.isEmpty()) break
            page.writeEachBatch { id, vector ->
                EltmEntities.update({ EltmEntities.id eq id }) {
                    it[EltmEntities.embedding] = vector
                }
            }
            lastId = page.last().first
            done += page.size
            logger.info { "re-embedded $done entities so far" }
        }
        return done
    }

    /** Re-embed all notes in id order, one page at a time. Returns the rows processed. */
    private suspend fun reembedNotes(): Long {
        var lastId = 0L
        var done = 0L
        while (true) {
            val page: List<Pair<Long, String>> = withTransaction {
                // only the text-building columns — never the stored vector
                // the job is about to overwrite
                EltmNotes.select(EltmNotes.id, EltmNotes.note)
                    .where { EltmNotes.id greater lastId }
                    .orderBy(EltmNotes.id)
                    .limit(PAGE_SIZE)
                    .map { row ->
                        row[EltmNotes.id] to noteEmbeddingText(row[EltmNotes.note])
                    }
            }
            if (page.isEmpty()) break
            page.writeEachBatch { id, vector ->
                EltmNotes.update({ EltmNotes.id eq id }) {
                    it[EltmNotes.embedding] = vector
                }
            }
            lastId = page.last().first
            done += page.size
            logger.info { "re-embedded $done notes so far" }
        }
        return done
    }

    /**
     * Embed the pair's texts in [EMBED_BATCH_SIZE] chunks through the hand
     * and write each chunk's vectors back in its own transaction via
     * [update] (the embed call happens OUTSIDE the transaction — it must
     * not hold a connection; one chunk is exactly ONE `/v1/embed` call,
     * so a failure loses no prior chunk's progress). [update] receives
     * the row id and its padded vector.
     */
    private suspend fun List<Pair<Long, String>>.writeEachBatch(
        update: suspend (id: Long, vector: List<Float>) -> Unit,
    ) {
        chunked(EMBED_BATCH_SIZE).forEach { batch ->
            val vectors = hand.embedAll(model, batch.map { it.second }, policy)
            withTransaction {
                batch.zip(vectors).forEach { (idText, vector) ->
                    update(idText.first, vector)
                }
            }
        }
    }

    companion object {
        /** Rows per keyset page read from the tables (bounded memory). */
        const val PAGE_SIZE = 128
    }
}
