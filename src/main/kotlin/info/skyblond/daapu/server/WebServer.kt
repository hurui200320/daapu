package info.skyblond.daapu.server

import info.skyblond.daapu.agent.chat.ChatCodec
import info.skyblond.daapu.agent.model.ModelCapabilityException
import info.skyblond.daapu.config.AppConfig
import info.skyblond.daapu.hand.handToolCallback
import info.skyblond.daapu.mcp.McpToolProvider
import info.skyblond.daapu.memory.sstm.PostgresSstmService
import info.skyblond.daapu.memory.sstm.SstmService
import info.skyblond.daapu.server.MemoryDto.Companion.toDto
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

private val logger = KotlinLogging.logger("WebServer")

@Serializable
data class ErrorResponse(val error: String)

/**
 * Start the HTTP API server (the frontend is a separate dev server that
 * proxies `/api` here; this process only serves the API).
 *
 * The MCP tool servers come from [AppConfig.mcp] (`config.jsonc`): the
 * provider connects eagerly at construction, so a server that cannot be
 * reached aborts startup instead of silently degrading every chat run.
 */
fun startWebServer(config: AppConfig) {
    val sstmService = PostgresSstmService()
    val service = ChatRunService(config, McpToolProvider(config.mcp.servers), sstmService)
    // graceful close of the MCP clients (stdio subprocesses, HTTP sessions)
    Runtime.getRuntime().addShutdownHook(Thread { service.close() })
    embeddedServer(Netty, port = config.server.port, host = "0.0.0.0") {
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
            handToolCallback(service.handCallback)
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

private fun ApplicationCall.memoryIdParam(): Long =
    parameters["memoryId"]?.toLongOrNull()
        ?: throw BadRequestException("memoryId must be a number")
