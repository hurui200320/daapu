package info.skyblond.daapu.server.endpoint

import info.skyblond.daapu.agent.chat.ChatCodec
import info.skyblond.daapu.server.*
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeString
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val logger = KotlinLogging.logger("ChatsRoute")

fun Route.registerChatsEndpoints(service: ChatRunService) {
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

private fun ApplicationCall.chatIdParam(): String {
    val chatId = parameters["chatId"]?.trim().orEmpty()
    if (chatId.isEmpty()) throw BadRequestException("chatId is required")
    return chatId
}

private fun ApplicationCall.messageIndexParam(): Int =
    parameters["index"]?.toIntOrNull()
        ?: throw BadRequestException("index must be a number")

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
