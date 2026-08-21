package info.skyblond.daapu.server

import info.skyblond.daapu.agent.chat.ChatCodec
import info.skyblond.daapu.agent.model.ModelCapabilityException
import info.skyblond.daapu.config.AppConfig
import info.skyblond.daapu.di.daapuModule
import info.skyblond.daapu.hand.HandCallbackService
import info.skyblond.daapu.hand.handToolCallback
import info.skyblond.daapu.hand.handToolList
import info.skyblond.daapu.memory.eltm.EltmService
import info.skyblond.daapu.memory.sstm.SstmService
import info.skyblond.daapu.server.EltmNoteDto.Companion.toDto
import info.skyblond.daapu.server.EntityViewDto.Companion.toDto
import info.skyblond.daapu.server.MemoryDto.Companion.toDto
import info.skyblond.daapu.server.RelationshipViewDto.Companion.toDto
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.koin.core.Koin
import org.koin.dsl.koinApplication
import java.time.LocalDate
import java.time.format.DateTimeParseException

private val logger = KotlinLogging.logger("WebServer")

/** Default page size of the browse-only `/api/eltm` list routes. */
private const val DEFAULT_ELTM_PAGE_LIMIT = 100

/**
 * Upper bound of a single page. The whole-page count/latest-note batch
 * queries materialize the page's subjects' notes in memory, so an
 * unbounded `limit` would be a memory attack surface; the frontend fetches
 * at most 500 rows per request (its resync walks the window in chunks).
 */
private const val MAX_ELTM_PAGE_LIMIT = 500

@Serializable
data class ErrorResponse(val error: String)

/**
 * Start the HTTP API server (the frontend is a separate dev server that
 * proxies `/api` here; this process only serves the API).
 *
 * The whole object graph lives in the Koin container (`di/DaapuModule.kt`);
 * resolving its root ([ChatRunService]) eagerly before the server starts
 * runs every definition, so the fail-fast config validation and the MCP
 * tool servers' eager connect (a server that cannot be reached aborts
 * startup instead of silently degrading every chat run) fire at boot.
 * Resource cleanup is Koin's too: the shutdown hook closes the container,
 * which fires the `onClose` callbacks (hand client, MCP clients).
 */
fun startWebServer(config: AppConfig) {
    val koinApp = koinApplication { modules(daapuModule(config)) }
    // eager resolution: every fail-fast validation above fires here, never
    // mid-run (the resolved service is what the module below serves)
    koinApp.koin.get<ChatRunService>()
    // graceful close of the hand client and the MCP clients (stdio
    // subprocesses, HTTP sessions) via the container's onClose callbacks
    Runtime.getRuntime().addShutdownHook(Thread { koinApp.close() })
    embeddedServer(Netty, port = config.server.port, host = "0.0.0.0") {
        module(koinApp.koin)
    }.start(wait = true)
}

/**
 * The HTTP API: routes take their services from the Koin container, not
 * from [ChatRunService] — the service only holds what its own methods use
 * (the stores and the hand callback service live here as independent
 * definitions, shared with the run pipeline). Tests assemble the container
 * with fake seams (see `testutil/TestDi.kt`) and pass its `Koin` instance.
 */
internal fun Application.module(koin: Koin) {
    val service = koin.get<ChatRunService>()
    val sstmService = koin.get<SstmService>()
    val eltmService = koin.get<EltmService>()
    val handCallback = koin.get<HandCallbackService>()
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
    install(StatusPages) {
        exception<CancellationException> { _, cause ->
            // the client disconnected mid-stream (the sink wrapper in
            // handleChatMessage converts the failed write into this): the run
            // is already aborted, so don't log an "Unhandled error" stack
            // trace or attempt a 500 on the committed SSE response — let the
            // exception propagate so the connection just closes
            throw cause
        }
        exception<ChatRunConflictException> { call, cause ->
            call.respond(HttpStatusCode.Conflict, ErrorResponse(cause.message ?: "Conflict"))
        }
        exception<BadRequestException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(cause.message ?: "Bad request"))
        }
        exception<NotFoundException> { call, cause ->
            call.respond(HttpStatusCode.NotFound, ErrorResponse(cause.message ?: "Not found"))
        }
        // a capability mismatch (e.g. a text-only `title.model` with image
        // history) is a configuration error the client can act on: surface
        // the reason as a 400 instead of an opaque 500. In-run capability
        // failures never reach here (they fail the SSE stream, which handles
        // them itself).
        exception<ModelCapabilityException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(cause.message ?: "Model capability mismatch")
            )
        }
        // must be registered before ContentTransformationException: StatusPages
        // picks the nearest registered class, so a directly thrown
        // UnsupportedMediaTypeException (e.g. from multipart handling) gets its
        // semantically correct 415 with the real reason. Note that a wrong
        // request Content-Type never reaches this handler: ktor's own default
        // transformation checker answers 415 (body-less) for it first.
        exception<UnsupportedMediaTypeException> { call, cause ->
            call.respond(
                HttpStatusCode.UnsupportedMediaType,
                ErrorResponse(cause.message ?: "Unsupported media type")
            )
        }
        exception<ContentTransformationException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Malformed request body"))
        }
        exception<Throwable> { call, cause ->
            call.application.log.error("Unhandled error on ${call.request.uri}", cause)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Internal server error"))
        }
    }

    routing {
        route("/api") {
            get("/models") {
                call.respond(service.models())
            }
            handToolCallback(handCallback)
            handToolList(handCallback)
            route("/chats") {
                get {
                    call.respond(service.listChats())
                }
                post {
                    call.respond(HttpStatusCode.Created, service.newChat())
                }
                put("/{chatId}") {
                    val id = call.chatIdParam()
                    val request = call.receive<RenameChatRequest>()
                    val title = request.title.trim()
                    if (title.isEmpty()) throw BadRequestException("Chat title is empty")
                    call.respond(
                        service.renameChat(id, title)
                            ?: throw NotFoundException("Chat $id not found")
                    )
                }
                post("/{chatId}/title") {
                    val id = call.chatIdParam()
                    call.respond(
                        service.generateTitle(id)
                            ?: throw NotFoundException("Chat $id not found")
                    )
                }
                delete("/{chatId}") {
                    val id = call.chatIdParam()
                    if (!service.deleteChat(id)) throw NotFoundException("Chat $id not found")
                    call.respond(HttpStatusCode.NoContent)
                }
                // drop the message at `index` (a user message) and everything
                // after it; the tail is discarded WITHOUT SSTM extraction
                delete("/{chatId}/messages/{index}") {
                    val id = call.chatIdParam()
                    val index = call.messageIndexParam()
                    if (!service.truncateChat(id, index)) {
                        throw NotFoundException("Chat $id not found")
                    }
                    call.respond(HttpStatusCode.NoContent)
                }
                // new chat whose history is the source's `messages[0..index]`
                // (index must point at a naturally finished assistant message)
                post("/{chatId}/fork/{index}") {
                    val id = call.chatIdParam()
                    val index = call.messageIndexParam()
                    call.respond(
                        HttpStatusCode.Created,
                        service.forkChat(id, index)
                            ?: throw NotFoundException("Chat $id not found")
                    )
                }
                get("/{chatId}/chat") {
                    val chat = service.chat(call.chatIdParam())
                    call.respondText(ChatCodec.encodeChat(chat), ContentType.Application.Json)
                }
                post("/{chatId}/messages") {
                    handleChatMessage(call, service)
                }
            }
            route("/memories") {
                get {
                    call.respond(
                        sstmService.listMemories().memories.map { it.toDto() }
                    )
                }
                post {
                    val request = call.receive<MemoryWriteRequest>()
                    val content = request.content.trim()
                    if (content.isEmpty()) throw BadRequestException("Memory content is empty")
                    call.respond(
                        HttpStatusCode.Created,
                        sstmService.createMemory(content).toDto()
                    )
                }
                put("/{memoryId}") {
                    val id = call.memoryIdParam()
                    val request = call.receive<MemoryWriteRequest>()
                    val content = request.content.trim()
                    if (content.isEmpty()) throw BadRequestException("Memory content is empty")
                    call.respond(
                        sstmService.updateMemory(id, content)?.toDto()
                            ?: throw NotFoundException("Memory $id not found")
                    )
                }
                delete("/{memoryId}") {
                    val id = call.memoryIdParam()
                    if (!sstmService.deleteMemory(id)) throw NotFoundException("Memory $id not found")
                    call.respond(HttpStatusCode.NoContent)
                }
            }
            // browse-only ELTM views for the `#/eltm` frontend tab; writes
            // stay LLM-driven via the SSTM purge pipeline, so no POST/PUT/
            // DELETE routes exist
            route("/eltm") {
                get("/entities") {
                    call.respond(
                        eltmService.listEntities(
                            call.pageLimitParam(),
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
                // `getRelationship`, which build the FULL view (counts +
                // latest note, 3-4 queries) just to 404-check; a cheap
                // id-exists query would do
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
                    val limit = call.pageLimitParam()
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
                            call.pageLimitParam(),
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
                    val limit = call.pageLimitParam()
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
    }
}

/**
 * Stream a chat run as Server-Sent Events.
 *
 * Validation and the per-chat lock happen BEFORE the response starts, so
 * malformed requests get a plain 400/409. Once the stream starts, the run's
 * outcome is delivered as events (`text`, `reasoning`, `tool_call`,
 * `tool_result`, `retry`, `done`, `error`); a 200 response is
 * already committed then.
 */
private suspend fun handleChatMessage(call: ApplicationCall, service: ChatRunService) {
    val chatId = call.chatIdParam()
    val setup = service.prepareRun(chatId, call.receive<SendMessageRequest>())
    val lock = service.acquireChatLock(chatId)
    try {
        call.respondBytesWriter(ContentType.Text.EventStream) {
            // Flush the SSE stream immediately: ktor Netty's
            // responseWriteTimeoutSeconds (10s default) starts a timer on the
            // first (headers) write and kills the connection when it isn't
            // flushed within 10s. A run can stay silent for minutes during
            // history compaction/SSTM extraction, which would trip that
            // timeout (502 at the proxy) — committing the stream up front
            // completes the write and cancels the timer. The frontend
            // ignores unknown events, so this is invisible to the client.
            sendEvent("comment", "connected")
            // the sink is what the agent callback writes through; a failed
            // write means the client went away, so abort the run with a
            // CancellationException (pinned as non-retryable) instead of
            // letting the retry loop treat it as a transient stream error
            val sink: suspend (String, String) -> Unit = { event, data ->
                try {
                    sendEvent(event, data)
                } catch (e: Exception) {
                    throw CancellationException("SSE client disconnected, aborting chat run", e)
                }
            }
            try {
                service.runChat(setup, streamEventCallback(sink))
                sink("done", "{}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger.error(e) { "Chat run '$chatId' failed: ${e.message}" }
                runCatching { sink("error", errorEventData(e)) }
            }
        }
    } finally {
        service.releaseChatLock(chatId, lock)
    }
}

private fun errorEventData(error: Throwable): String = buildJsonObject {
    val rootMessage = error.message ?: error.toString()
    val causeMessage = error.cause?.let { it.message ?: it.toString() }
        ?.let { "\nCaused by: $it" } ?: ""

    put("message", rootMessage + causeMessage)
    put("type", error::class.simpleName ?: "Unknown")
}.toString()

private suspend fun ByteWriteChannel.sendEvent(event: String, data: String) {
    writeString("event: $event\ndata: $data\n\n")
    flush()
}

private fun ApplicationCall.chatIdParam(): String {
    val chatId = parameters["chatId"]?.trim().orEmpty()
    if (chatId.isEmpty()) throw BadRequestException("chatId is required")
    return chatId
}

private fun ApplicationCall.messageIndexParam(): Int =
    parameters["index"]?.toIntOrNull()
        ?: throw BadRequestException("index must be a number")

private fun ApplicationCall.memoryIdParam(): Long =
    parameters["memoryId"]?.toLongOrNull()
        ?: throw BadRequestException("memoryId must be a number")

// ---- `/api/eltm` query/param helpers: every parse failure is a 400 (and a
// DB-free test target), so the service-level `require`s stay unreachable
// from the HTTP layer

private fun ApplicationCall.longParam(name: String): Long =
    parameters[name]?.toLongOrNull()
        ?: throw BadRequestException("$name must be a number")

private fun ApplicationCall.pageLimitParam(): Int {
    val raw = request.queryParameters["limit"] ?: return DEFAULT_ELTM_PAGE_LIMIT
    val value = raw.toIntOrNull() ?: throw BadRequestException("limit must be a number")
    if (value < 1) throw BadRequestException("limit must be >= 1")
    if (value > MAX_ELTM_PAGE_LIMIT) {
        throw BadRequestException("limit must be <= $MAX_ELTM_PAGE_LIMIT")
    }
    return value
}

private fun ApplicationCall.pageOffsetParam(): Int {
    val raw = request.queryParameters["offset"] ?: return 0
    val value = raw.toIntOrNull() ?: throw BadRequestException("offset must be a number")
    if (value < 0) throw BadRequestException("offset must be >= 0")
    return value
}

/** A `YYYY-MM-DD` query param, or null when absent; anything else is a 400. */
private fun ApplicationCall.dateParam(name: String): LocalDate? {
    val raw = request.queryParameters[name] ?: return null
    return try {
        LocalDate.parse(raw)
    } catch (e: DateTimeParseException) {
        throw BadRequestException("$name must be YYYY-MM-DD")
    }
}

private fun checkDateRange(from: LocalDate?, to: LocalDate?) {
    if (from != null && to != null && from.isAfter(to)) {
        throw BadRequestException("from must not be after to")
    }
}
