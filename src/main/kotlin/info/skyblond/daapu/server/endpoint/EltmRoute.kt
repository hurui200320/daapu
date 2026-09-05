package info.skyblond.daapu.server.endpoint

import info.skyblond.daapu.agent.chat.AttachmentContent
import info.skyblond.daapu.agent.chat.AttachmentKind
import info.skyblond.daapu.agent.chat.ChatMessagePart
import info.skyblond.daapu.agent.chat.ChatValidationException
import info.skyblond.daapu.agent.chat.imageMimeTypeRegex
import info.skyblond.daapu.agent.pipeline.eltm.MemoryExtractionService
import info.skyblond.daapu.memory.eltm.EltmService
import info.skyblond.daapu.server.EltmDigestRequest
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
import kotlin.io.encoding.Base64

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
 * A terminal failure of the ELTM digest behind `POST /api/eltm/digest`:
 * the extraction one-shot or the writer run inside
 * `MemoryExtractionService.digestUserInput` threw its
 * [IllegalStateException]. Mapped to 502 with the real failure reason
 * (WebServer's StatusPages) — the submitter is interactively waiting and
 * must know why the digest failed (an upstream error, the writer round
 * cap, ...) to decide on a retry; whatever the writer already recorded
 * sticks (it deduplicates on retry). [cause] carries the stage's exception
 * for the server-side log only: the response body renders the message
 * chain, not the stack.
 */
class EltmDigestException(message: String, cause: Throwable) : RuntimeException(message, cause)

/**
 * The `/api/eltm` routes: the browse-only reads over [EltmService] plus the
 * ONE write endpoint, `POST /digest`, feeding caller-supplied text/image
 * parts through [memoryExtractionService] (both the extraction one-shot
 * and the writer run inside `MemoryExtractionService.digestUserInput`;
 * the same extractor/writer pair the discard pipeline uses — the ELTM is
 * otherwise written only by that pipeline).
 */
fun Route.registerEltmEndpoints(
    eltmService: EltmService,
    memoryExtractionService: MemoryExtractionService,
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
        // the manual write path: caller-supplied text/image parts in the
        // user-message wire shape (raw notes, prose or facts already in
        // fact form, plus image attachments — an email or a document is
        // digested with its interleaving intact) run through
        // MemoryExtractionService.digestUserInput — the memory extraction
        // one-shot first (first-person pronouns → "the user", relative
        // dates resolve against `date`/today; the extraction model must
        // support vision when images are attached), then the extractor's
        // fact batch goes through the ELTM writer tool loop exactly like
        // the discard pipeline's does. The request blocks for both stages
        // (minutes are normal; the same blocking-LLM precedent as
        // deleteChat's extraction). No chat lock: nothing here touches the
        // chats table, and the ELTM store already tolerates the extraction
        // pipeline's concurrent writers (the writer deduplicates recorded
        // content, so a retry after a failure never duplicates entries).
        // Response: 201 Created with an EMPTY body — a pasted skip sentinel
        // or an empty extraction is an indistinguishable no-op success
        // (there is no recorded flag; a terminal stage failure is the 502
        // EltmDigestException instead). Accepted PoC limits, the same
        // stance as the rest of this API's request bodies: no size cap on
        // the parts and no digest concurrency limit (each digest is one
        // extraction one-shot plus one minutes-long writer loop).
        post("/digest") {
            val request = call.receive<EltmDigestRequest>()
            // the polymorphic decode accepts any ChatMessagePart the wire
            // can carry; only text and IMAGE attachments are digestible —
            // everything else is a client error before any LLM call
            val parts = request.parts.map { part ->
                when (part) {
                    is ChatMessagePart.Text -> part
                    is ChatMessagePart.Attachment -> {
                        if (part.kind != AttachmentKind.Image) {
                            throw BadRequestException(
                                "only image attachments can be digested, got ${part.kind}"
                            )
                        }
                        // the same image-MIME shape the chat-send data-URL
                        // regex enforces (see imageMimeTypeRegex)
                        if (imageMimeTypeRegex.matchEntire(part.mimeType) == null) {
                            throw BadRequestException(
                                "attachment mimeType must be image/*, got '${part.mimeType}'"
                            )
                        }
                        // parity with parseImageDataUrl (the chat-send path,
                        // see agent/chat/ImageAttachments.kt): strip the
                        // payload's whitespace like that path does and
                        // validate up front — a malformed base64 payload is
                        // a clear 400 here, not an opaque gateway error
                        // mid-run (the hand forwards the payload verbatim),
                        // and the STRIPPED value travels on so the wire
                        // carries no folded payloads on either path
                        val content = part.content as? AttachmentContent.Base64
                            ?: throw BadRequestException("only base64 attachment content can be digested")
                        val base64 = content.base64.filterNot { it.isWhitespace() }
                        runCatching { Base64.decode(base64) }
                            .getOrElse { throw ChatValidationException("Invalid base64 in image attachment") }
                        part.copy(content = AttachmentContent.Base64(base64))
                    }

                    else -> throw BadRequestException(
                        "only text and image attachment parts can be digested, got ${part.javaClass.simpleName}"
                    )
                }
            }
            // blank text parts carry no content: dropped before the
            // emptiness check, exactly like the old single-text shape
            // trimmed the text first (MemoryExtractionService drops them
            // again when building the synthetic message)
            val meaningful = parts.filterNot { it is ChatMessagePart.Text && it.text.isBlank() }
            if (meaningful.isEmpty()) {
                throw BadRequestException("at least one non-blank text part or image must be present")
            }
            // "today" bound once: the future-date guard below and the default
            // reference date must agree even if the request straddles midnight
            val today = LocalDate.now()
            val date = request.date?.let { raw ->
                try {
                    LocalDate.parse(raw)
                } catch (_: DateTimeParseException) {
                    throw BadRequestException("date must be YYYY-MM-DD")
                }
            }
            // the reference date only anchors the extraction: a future one
            // would resolve the text's relative dates against a future
            // time and bake future absolute dates into the facts (the
            // notes' event dates are always the write day, regardless of
            // the request's `date`)
            if (date != null && date.isAfter(today)) {
                throw BadRequestException("date must not be in the future")
            }
            try {
                val referenceDate = date ?: today
                memoryExtractionService.digestUserInput(meaningful, referenceDate)
            } catch (e: IllegalStateException) {
                throw EltmDigestException(failureChainMessages(e).joinToString("\nCaused by: "), e)
            }
            // 201 Created with an empty body — see the route comment above
            // for the no-op/failure semantics
            call.respond(HttpStatusCode.Created)
        }
    }
}
