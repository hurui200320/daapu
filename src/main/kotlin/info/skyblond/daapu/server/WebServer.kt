package info.skyblond.daapu.server

import info.skyblond.daapu.AppConfig
import info.skyblond.daapu.history.HistoryCodec
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.plugins.UnsupportedMediaTypeException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeString
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val logger = KotlinLogging.logger("WebServer")

@Serializable
data class ErrorResponse(val error: String)

/**
 * Start the HTTP API server (the frontend is a separate dev server that
 * proxies `/api` here; this process only serves the API).
 */
fun startWebServer(config: AppConfig) {
    val service = ChatRunService(config)
    val sstmService = SstmService()
    embeddedServer(Netty, port = config.httpPort, host = "0.0.0.0") {
        module(service, sstmService)
    }.start(wait = true)
}

internal fun Application.module(service: ChatRunService, sstmService: SstmService) {
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
        // must be registered before ContentTransformationException: StatusPages
        // picks the nearest registered class, so a directly thrown
        // UnsupportedMediaTypeException (e.g. from multipart handling) gets its
        // semantically correct 415 with the real reason. Note that a wrong
        // request Content-Type never reaches this handler: ktor's own default
        // transformation checker answers 415 (body-less) for it first.
        exception<UnsupportedMediaTypeException> { call, cause ->
            call.respond(HttpStatusCode.UnsupportedMediaType, ErrorResponse(cause.message ?: "Unsupported media type"))
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
            route("/chats") {
                get {
                    call.respond(service.listChats())
                }
                post {
                    call.respond(HttpStatusCode.Created, service.newChat())
                }
                delete("/{chatId}") {
                    val id = call.chatIdParam()
                    if (!service.deleteChat(id)) throw NotFoundException("Chat $id not found")
                    call.respond(HttpStatusCode.NoContent)
                }
                get("/{chatId}/history") {
                    val history = service.history(call.chatIdParam())
                    call.respondText(HistoryCodec.encodeHistory(history), ContentType.Application.Json)
                }
                post("/{chatId}/messages") {
                    handleChatMessage(call, service)
                }
            }
            route("/memories") {
                get {
                    call.respond(sstmService.listMemories())
                }
                post {
                    val request = call.receive<MemoryWriteRequest>()
                    val content = request.content.trim()
                    if (content.isEmpty()) throw BadRequestException("Memory content is empty")
                    call.respond(HttpStatusCode.Created, sstmService.createMemory(content))
                }
                put("/{memoryId}") {
                    val id = call.memoryIdParam()
                    val request = call.receive<MemoryWriteRequest>()
                    val content = request.content.trim()
                    if (content.isEmpty()) throw BadRequestException("Memory content is empty")
                    call.respond(sstmService.updateMemory(id, content) ?: throw NotFoundException("Memory $id not found"))
                }
                delete("/{memoryId}") {
                    val id = call.memoryIdParam()
                    if (!sstmService.deleteMemory(id)) throw NotFoundException("Memory $id not found")
                    call.respond(HttpStatusCode.NoContent)
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
                service.runChat(setup, sink)
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
    put("message", error.message ?: error.toString())
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

private fun ApplicationCall.memoryIdParam(): Long =
    parameters["memoryId"]?.toLongOrNull()
        ?: throw BadRequestException("memoryId must be a number")
