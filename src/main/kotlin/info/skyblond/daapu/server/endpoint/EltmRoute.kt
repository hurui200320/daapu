package info.skyblond.daapu.server.endpoint

import info.skyblond.daapu.agent.pipeline.eltm.EltmWriterService
import info.skyblond.daapu.agent.pipeline.eltm.MemoryExtractionService
import info.skyblond.daapu.memory.eltm.EltmService
import info.skyblond.daapu.server.EltmImportRequest
import info.skyblond.daapu.server.EltmNoteDto.Companion.toDto
import info.skyblond.daapu.server.EntityViewDto.Companion.toDto
import info.skyblond.daapu.server.RelationshipViewDto.Companion.toDto
import io.ktor.http.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** Default page size of the browse-only `/api/eltm` list routes. */
private const val DEFAULT_ELTM_PAGE_LIMIT = 100

/**
 * Upper bound of a single page. The whole-page count/latest-note batch
 * queries materialize the page's subjects' notes in memory, so an
 * unbounded `limit` would be a memory attack surface; the frontend fetches
 * at most 500 rows per request (its resync walks the window in chunks).
 */
private const val MAX_ELTM_PAGE_LIMIT = 500

/**
 * A terminal failure of the ELTM writer run behind `POST /api/eltm/import`
 * ([EltmWriterService.writeToEltm] threw its [IllegalStateException]).
 * Mapped to 502 with the real failure reason (WebServer's StatusPages) —
 * the importer is interactively waiting and must know why the write failed
 * (an upstream error, the writer round cap, ...) to decide on a retry;
 * whatever the writer already recorded sticks (it deduplicates on retry).
 * [cause] carries the writer's exception for the server-side log only:
 * the response body renders the message chain, not the stack.
 */
class EltmWriteException(message: String, cause: Throwable) : RuntimeException(message, cause)

/**
 * The `/api/eltm` routes: the browse-only reads over [EltmService] plus the
 * ONE write endpoint, `POST /import`, feeding a caller-supplied fact batch
 * through [eltmWriterService] (the same writer the extraction pipeline
 * uses; the ELTM is otherwise written only by that pipeline).
 */
fun Route.registerEltmEndpoints(
    eltmService: EltmService,
    eltmWriterService: EltmWriterService,
) {
    route("/eltm") {
        get("/entities") {
            call.respond(
                eltmService.listEntities(
                    call.pageLimitParam(DEFAULT_ELTM_PAGE_LIMIT, MAX_ELTM_PAGE_LIMIT),
                    call.pageOffsetParam(),
                ).map { it.toDto() }
            )
        }
        get("/entities/{entityId}") {
            val id = call.longParam("entityId")
            call.respond(
                eltmService.getEntity(id)?.toDto()
                    ?: throw NotFoundException("Entity $id not found")
            )
        }
        // TODO: the existence checks below run `getEntity`/
        //     `getRelationship`, which build the FULL view (counts +
        //     latest note, 3-4 queries) just to 404-check; a cheap
        //     id-exists query would do
        get("/entities/{entityId}/relationships") {
            val id = call.longParam("entityId")
            // parse the filter before the existence check: a bad
            // flag is a 400 even when the subject is gone
            val raw = call.request.queryParameters["includeInvalid"]
            val includeInvalid = when (raw) {
                null -> false
                else -> raw.toBooleanStrictOrNull()
                    ?: throw BadRequestException("includeInvalid must be true or false")
            }
            if (eltmService.getEntity(id) == null) {
                throw NotFoundException("Entity $id not found")
            }
            call.respond(
                eltmService.getRelationships(id, includeInvalid).map { it.toDto() }
            )
        }
        get("/entities/{entityId}/notes") {
            val id = call.longParam("entityId")
            // parse the filters before the existence check: a bad
            // range is a 400 even when the subject is gone
            val from = call.dateParam("from")
            val to = call.dateParam("to")
            val limit = call.pageLimitParam(DEFAULT_ELTM_PAGE_LIMIT, MAX_ELTM_PAGE_LIMIT)
            val offset = call.pageOffsetParam()
            checkDateRange(from, to)
            if (eltmService.getEntity(id) == null) {
                throw NotFoundException("Entity $id not found")
            }
            call.respond(
                eltmService.getEntityNotes(id, from, to, limit, offset).map { it.toDto() }
            )
        }
        get("/relationships") {
            call.respond(
                eltmService.listRelationships(
                    call.pageLimitParam(DEFAULT_ELTM_PAGE_LIMIT, MAX_ELTM_PAGE_LIMIT),
                    call.pageOffsetParam(),
                ).map { it.toDto() }
            )
        }
        get("/relationships/{relationshipId}") {
            val id = call.longParam("relationshipId")
            call.respond(
                eltmService.getRelationship(id)?.toDto()
                    ?: throw NotFoundException("Relationship $id not found")
            )
        }
        get("/relationships/{relationshipId}/notes") {
            val id = call.longParam("relationshipId")
            // as in the entity-notes route: parse the filters before the
            // existence check, so a bad range is a 400 even when gone
            val from = call.dateParam("from")
            val to = call.dateParam("to")
            val limit = call.pageLimitParam(DEFAULT_ELTM_PAGE_LIMIT, MAX_ELTM_PAGE_LIMIT)
            val offset = call.pageOffsetParam()
            checkDateRange(from, to)
            if (eltmService.getRelationship(id) == null) {
                throw NotFoundException("Relationship $id not found")
            }
            call.respond(
                eltmService.getRelationshipNotes(id, from, to, limit, offset)
                    .map { it.toDto() }
            )
        }
        // the manual write path: a caller-supplied fact batch (the memory
        // extractor's output tone — the writer records verbatim) through the
        // ELTM writer tool loop. The request blocks for the whole loop
        // (minutes are normal; the same blocking-LLM precedent as
        // deleteChat's extraction). No chat lock: nothing here touches the
        // chats table, and the ELTM store already tolerates the extraction
        // pipeline's concurrent writers (the writer deduplicates recorded
        // content, so a retry after a failure never duplicates entries).
        // Accepted PoC limits, the same stance as the rest of this API's
        // request bodies: no size cap on `facts` and no import concurrency
        // limit (each import is one minutes-long writer loop).
        post("/import") {
            val request = call.receive<EltmImportRequest>()
            val facts = request.facts.trim()
            if (facts.isEmpty()) throw BadRequestException("facts must not be blank")
            val date = request.date?.let { raw ->
                try {
                    LocalDate.parse(raw)
                } catch (_: DateTimeParseException) {
                    throw BadRequestException("date must be YYYY-MM-DD")
                }
            }
            // the notes' event dates never run ahead of the write day (the
            // extraction pipeline writes its own `LocalDate.now()`)
            if (date != null && date.isAfter(LocalDate.now())) {
                throw BadRequestException("date must not be in the future")
            }
            // the extractor's skip sentinel is not a fact batch: record it
            // verbatim and the diary would grow a "Nothing worth remember."
            // note — answer the empty batch with the same 204, no LLM call
            if (facts == MemoryExtractionService.NOTHING_TO_REMEMBER_TEXT) {
                call.respond(HttpStatusCode.NoContent)
                return@post
            }
            try {
                eltmWriterService.writeToEltm(facts, date ?: LocalDate.now())
            } catch (e: IllegalStateException) {
                throw EltmWriteException(failureChainMessages(e).joinToString("\nCaused by: "), e)
            }
            call.respond(HttpStatusCode.NoContent)
        }
    }
}