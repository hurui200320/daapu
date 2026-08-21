package info.skyblond.daapu.server.endpoint

import info.skyblond.daapu.memory.eltm.EltmService
import info.skyblond.daapu.server.EltmNoteDto.Companion.toDto
import info.skyblond.daapu.server.EntityViewDto.Companion.toDto
import info.skyblond.daapu.server.RelationshipViewDto.Companion.toDto
import io.ktor.server.plugins.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/** Default page size of the browse-only `/api/eltm` list routes. */
private const val DEFAULT_ELTM_PAGE_LIMIT = 100

/**
 * Upper bound of a single page. The whole-page count/latest-note batch
 * queries materialize the page's subjects' notes in memory, so an
 * unbounded `limit` would be a memory attack surface; the frontend fetches
 * at most 500 rows per request (its resync walks the window in chunks).
 */
private const val MAX_ELTM_PAGE_LIMIT = 500

fun Route.registerEltmEndpoints(eltmService: EltmService) {
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
            // parse the filters before the existence check: a bad
            // range is a 400 even when the subject is gone
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
    }
}