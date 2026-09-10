package info.skyblond.daapu.server.endpoint

import info.skyblond.daapu.db.isEltmMaintenanceModeEnabledTx
import info.skyblond.daapu.db.setEltmMaintenanceModeTx
import info.skyblond.daapu.memory.eltm.EmbeddingRefreshService
import info.skyblond.daapu.memory.eltm.ReembedStatus
import info.skyblond.daapu.server.MaintenanceStatusResponse
import info.skyblond.daapu.server.ReembedStatusResponse
import info.skyblond.daapu.server.SetMaintenanceRequest
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * An operation blocked by ELTM maintenance mode: mapped to 503 with this
 * message (WebServer's StatusPages). An expected admin state, not an
 * error — never logged.
 */
class EltmMaintenanceException(message: String) : RuntimeException(message)

/**
 * A re-embed request that cannot start: mapped to 409 with this message
 * (WebServer's StatusPages). An expected admin state, not an error — never
 * logged.
 */
class ReembedConflictException(message: String) : RuntimeException(message)

/**
 * The maintenance-mode route guard: throws [EltmMaintenanceException]
 * (→ 503) while the `gsg_meta_number.eltm_maintenance` flag is on
 * (`db/MetaNumber.kt`). Called as the FIRST statement of a guarded
 * handler, before any body receive or validation — maintenance trumps
 * per-request errors.
 *
 * Blocked while enabled — every entry point that reads or writes the
 * ELTM, directly or through the deferred extraction pipeline:
 * - `POST /api/chats/{id}/messages` — the run's `<memories>` injection
 *   and `gsg__investigate` read the ELTM, compaction enqueues extraction
 *   writes;
 * - `DELETE /api/chats/{id}` — deletion enqueues the history for
 *   extraction;
 * - `POST /api/eltm/digest` and `POST /api/eltm/import` — direct ELTM
 *   writes.
 *
 * Everything else stays open (chat create/rename/title/truncate/fork/
 * import/export, all ELTM reads, personas, models). The background
 * extraction worker pauses too — no claim while the flag is on, so
 * already-enqueued jobs wait for the mode to turn off
 * (`memory/eltm/ExtractionQueueWorker.kt`). Accepted limits, deliberate:
 * an extraction already in flight when the flag flips finishes normally,
 * and `POST /api/hand/tool` is never blocked — that would kill in-flight
 * chat runs mid-stream with a fatal tool error. The check is
 * check-then-act, not a lock: a run that passed the guard just before
 * the flag flips still proceeds.
 */
suspend fun requireEltmNotInMaintenance() {
    if (isEltmMaintenanceModeEnabledTx()) {
        throw EltmMaintenanceException("ELTM maintenance mode is enabled; operation blocked")
    }
}

/**
 * The `/api/maintenance` routes:
 * - `GET`/`PUT` — read and toggle the ELTM maintenance-mode flag (the
 *   `gsg_meta_number.eltm_maintenance` row; the blocking scope is
 *   [requireEltmNotInMaintenance]'s KDoc). Never blocked themselves —
 *   turning the mode OFF must stay possible while it is on (a running
 *   re-embed job keeps going after the turn-off, see the service's KDoc).
 * - `GET /reembed` — the re-embed job's status, never blocked.
 * - `POST /reembed` — start the job ([EmbeddingRefreshService]): 409
 *   unless maintenance mode is ON (the freeze is the run's safety) or
 *   while a job is already running; 202 + the running status otherwise.
 *   The job itself is fire-and-forget — progress goes to the server log.
 */
fun Route.registerMaintenanceEndpoints(refresh: EmbeddingRefreshService) {
    route("/maintenance") {
        get {
            call.respond(MaintenanceStatusResponse(isEltmMaintenanceModeEnabledTx()))
        }
        put {
            val request = call.receive<SetMaintenanceRequest>()
            // fail-fast when the row is missing (setMetaNumber's check) —
            // the migration seeds it, so a missing row is a broken database
            setEltmMaintenanceModeTx(request.enabled)
            call.respond(MaintenanceStatusResponse(request.enabled))
        }
        route("/reembed") {
            get {
                call.respond(refresh.status().toDto())
            }
            post {
                if (!isEltmMaintenanceModeEnabledTx()) {
                    throw ReembedConflictException("enable ELTM maintenance mode before re-embedding")
                }
                if (!refresh.start()) {
                    throw ReembedConflictException("an ELTM embedding refresh is already running")
                }
                call.respond(HttpStatusCode.Accepted, refresh.status().toDto())
            }
        }
    }
}

/** Map the service's status snapshot to its wire shape (see `ReembedStatusResponse`). */
private fun ReembedStatus.toDto() = ReembedStatusResponse(
    state = when (this) {
        is ReembedStatus.Idle -> "idle"
        is ReembedStatus.Running -> "running"
        is ReembedStatus.Finished -> "finished"
        is ReembedStatus.Failed -> "failed"
    },
    entities = (this as? ReembedStatus.Finished)?.entities ?: 0L,
    notes = (this as? ReembedStatus.Finished)?.notes ?: 0L,
    error = (this as? ReembedStatus.Failed)?.error,
)
