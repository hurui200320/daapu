package info.skyblond.daapu.server.endpoint

import info.skyblond.daapu.agent.chat.AttachmentContent
import info.skyblond.daapu.agent.chat.AttachmentKind
import info.skyblond.daapu.agent.chat.ChatCodec
import info.skyblond.daapu.agent.chat.ChatMessagePart
import info.skyblond.daapu.agent.chat.ChatValidationException
import info.skyblond.daapu.agent.chat.imageMimeTypeRegex
import info.skyblond.daapu.agent.pipeline.eltm.MemoryExtractionService
import info.skyblond.daapu.hand.EmbeddingException
import info.skyblond.daapu.memory.eltm.EltmExportPayload
import info.skyblond.daapu.memory.eltm.EltmReplayService
import info.skyblond.daapu.memory.eltm.EltmService
import info.skyblond.daapu.memory.eltm.EltmTransferService
import info.skyblond.daapu.memory.eltm.ReplayStatus
import info.skyblond.daapu.server.EltmDigestRequest
import info.skyblond.daapu.server.EltmNoteDto.Companion.toDto
import info.skyblond.daapu.server.EltmReplayStatusResponse
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
 * queries run per subject of the page (bounded aggregates, see
 * `EltmNoteQueries.noteCountsAndLatest`), and the frontend fetches at
 * most 500 rows per request (its resync walks the window in chunks) — an
 * unbounded `limit` would still be an unbounded work-per-request surface.
 */
private const val MAX_ELTM_PAGE_LIMIT = 500

/** Default `compactionRounds` of `POST /api/eltm/replay` (the window knobs). */
private const val DEFAULT_REPLAY_COMPACTION_ROUNDS = 8

/** Default `contextRounds` of `POST /api/eltm/replay` (the window knobs). */
private const val DEFAULT_REPLAY_CONTEXT_ROUNDS = 3

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
 * A terminal failure of the ELTM import behind `POST /api/eltm/import`:
 * an embedding call inside the merge failed (the hand unreachable, the
 * content too large for the embedding model — a size validation cannot
 * know). Mapped to 502 with the real failure reason (WebServer's
 * StatusPages) — the submitter is interactively waiting and must know why;
 * whatever the merge already wrote sticks, and re-running the same file
 * skips the existing content and resumes (see
 * `EltmTransferService.importEltm`). [cause] carries the stage's exception
 * for the server-side log only: the response body renders the message
 * chain, not the stack.
 */
class EltmImportException(message: String, cause: Throwable) : RuntimeException(message, cause)

/**
 * A replay start that cannot proceed because a walk is already active:
 * mapped to 409 with this message (WebServer's StatusPages). An expected
 * admin state, not an error — never logged.
 */
class EltmReplayConflictException(message: String) : RuntimeException(message)

/**
 * The `/api/eltm` routes: the browse-only reads over [EltmService] plus the
 * write endpoints — `POST /digest`, feeding caller-supplied text/image
 * parts through [memoryExtractionService] (both the extraction one-shot
 * and the writer run inside `MemoryExtractionService.digestUserInput`;
 * the same extractor/writer pair the discard pipeline uses — the ELTM is
 * otherwise written only by that pipeline), the replay pair
 * (`GET/POST /replay`, see [replayService] — the replay's walk semantics
 * and the queue-drain write path live in `memory/eltm/EltmReplayService.kt`),
 * and the transfer pair (`GET /export` / `POST /import`, see
 * [eltmTransferService]).
 */
fun Route.registerEltmEndpoints(
    eltmService: EltmService,
    memoryExtractionService: MemoryExtractionService,
    eltmTransferService: EltmTransferService,
    replayService: EltmReplayService,
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
            // the cheap existence probe, not the full view (counts +
            // latest note): a 404 check must not pay for a page
            if (!eltmService.entityExists(id)) {
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
            if (!eltmService.entityExists(id)) {
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
            if (!eltmService.relationshipExists(id)) {
                throw NotFoundException("Relationship $id not found")
            }
            call.respond(
                eltmService.getRelationshipNotes(id, from, to, limit, offset)
                    .map { it.toDto() }
            )
        }
        // the replay pair: walk an uploaded foreign chat through the
        // production compaction stage window by window, enqueueing every
        // dropped region into the background extraction queue (the walk
        // semantics and the async-drain write path: EltmReplayService).
        // The chat uploads as the neutral format's JSON array VERBATIM —
        // the body is what `GET /api/chats/{id}/chat` serves, decoded with
        // the stored-chat invariants (ChatCodec.decodeChat; a violation is
        // a 400), and the window knobs ride the query params like the
        // import's overwriteAttr. POST validates synchronously (a 400 for
        // bad knobs/empty chat/a capability mismatch, before any LLM
        // spend) then answers 202 with the running status — the walk and
        // the memory work run in the background, the status endpoint
        // tracks the walk (NOT the drain: "finished" means every region
        // is queued). 409 while a walk is already active. No chat lock:
        // nothing here touches the chats table — the foreign chat never
        // becomes one. Accepted PoC limits, the same stance as the digest
        // and import routes: no size cap on the body and no replay
        // concurrency beyond the single-flight 409.
        route("/replay") {
            get {
                call.respond(replayService.status().toDto())
            }
            post {
                requireEltmNotInMaintenance()
                val compactionRounds = call.intQueryParam("compactionRounds", DEFAULT_REPLAY_COMPACTION_ROUNDS)
                val contextRounds = call.intQueryParam("contextRounds", DEFAULT_REPLAY_CONTEXT_ROUNDS)
                // a body that fails the stored-chat invariants (not JSON,
                // not a chat — decodeChat wraps every failure in an
                // IllegalStateException) is a client error, not a 500
                val chat = try {
                    ChatCodec.decodeChat("eltm-replay", call.receiveText())
                } catch (e: IllegalStateException) {
                    throw BadRequestException(e.message ?: "Invalid replay chat payload")
                }
                // start's synchronous validations (the knob bounds, the
                // non-empty chat, both pipeline models' capability check
                // over the whole chat) are client errors before any LLM
                // spend — a mid-walk failure is the status endpoint's
                // Failed phase, never a response here
                val started = try {
                    replayService.start(chat, compactionRounds, contextRounds)
                } catch (e: IllegalArgumentException) {
                    throw BadRequestException(e.message ?: "Invalid replay request")
                }
                if (!started) {
                    throw EltmReplayConflictException("an ELTM replay is already running")
                }
                call.respond(HttpStatusCode.Accepted, replayService.status().toDto())
            }
        }
        // export the whole ELTM as the transfer payload (see
        // EltmTransferService.exportEltm): an attachment named `eltm.json`,
        // like the persona export. No route conflict: the id-carrying GETs
        // all live under /entities/... or /relationships/...
        get("/export") {
            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment
                    .withParameter(ContentDisposition.Parameters.FileName, "eltm.json")
                    .toString(),
            )
            call.respond(eltmTransferService.exportEltm())
        }
        // import (merge) the transfer payload: the exported file posts
        // verbatim as the body — `overwriteAttr` rides the query param
        // (missing = false) instead, so the two endpoints share one shape
        // (see EltmTransfer.kt). The merge semantics, the fail-fast partial
        // stance and the validation errors (IllegalArgumentException → 400,
        // earlier writes sticking) are EltmTransferService.importEltm's.
        // No chat lock: nothing here touches the chats table. Accepted PoC
        // limits, the same stance as the digest route below: no size cap on
        // the body and no import concurrency limit (each new note and each
        // changed attribute costs an embed call through the hand).
        post("/import") {
            requireEltmNotInMaintenance()
            val overwriteAttr = when (val raw = call.request.queryParameters["overwriteAttr"]) {
                null -> false
                else -> raw.toBooleanStrictOrNull()
                    ?: throw BadRequestException("overwriteAttr must be true or false")
            }
            val payload = call.receive<EltmExportPayload>()
            // an embedding failure mid-merge (hand unreachable, content too
            // large for the model) is an upstream failure: 502 with the
            // reason — see EltmImportException (the digest's precedent)
            val summary = try {
                eltmTransferService.importEltm(payload, overwriteAttr)
            } catch (e: IllegalArgumentException) {
                throw BadRequestException(e.message ?: "Invalid ELTM payload")
            } catch (e: EmbeddingException) {
                throw EltmImportException(failureChainMessages(e).joinToString("\nCaused by: "), e)
            }
            call.respond(summary)
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
            requireEltmNotInMaintenance()
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

/**
 * Map the replay status snapshot to its wire shape (see
 * `EltmReplayStatusResponse`): the progress counters carry the running
 * and finished phases' values and a failed walk's at-failure values (0
 * otherwise), [ReplayStatus.Failed]'s reason rides
 * [EltmReplayStatusResponse.error]. The DTO's [EncodeDefault] annotations
 * keep the zero counters on the wire.
 */
private fun ReplayStatus.toDto(): EltmReplayStatusResponse = when (this) {
    is ReplayStatus.Idle -> EltmReplayStatusResponse(state = "idle")
    is ReplayStatus.Running -> EltmReplayStatusResponse(
        state = "running",
        windowsCompacted = windowsCompacted,
        jobsQueued = jobsQueued,
    )
    is ReplayStatus.Finished -> EltmReplayStatusResponse(
        state = "finished",
        messagesTotal = messagesTotal,
        windowsCompacted = windowsCompacted,
        jobsQueued = jobsQueued,
    )
    is ReplayStatus.Failed -> EltmReplayStatusResponse(
        state = "failed",
        windowsCompacted = windowsCompacted,
        jobsQueued = jobsQueued,
        error = error,
    )
}
