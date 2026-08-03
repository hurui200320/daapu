package info.skyblond.daapu.chat

import info.skyblond.daapu.auth.SessionData
import info.skyblond.daapu.db.MessageRole
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json

private val json = Json { encodeDefaults = true }

/**
 * The current chat id path parameter, or `null` when missing/invalid.
 */
private fun ApplicationCall.chatIdOrNull(): Long? =
    parameters["chatId"]?.toLongOrNull()

fun Route.chatRoutes(chatService: ChatService) {
    route("/chats") {
        get {
            val session = call.sessions.get<SessionData>()
            if (session == null) {
                call.respond(HttpStatusCode.Unauthorized, ChatError("not logged in"))
            } else {
                call.respond(chatService.listChats(session.userId).map { it.toResponse() })
            }
        }

        post {
            val session = call.sessions.get<SessionData>()
            if (session == null) {
                call.respond(HttpStatusCode.Unauthorized, ChatError("not logged in"))
            } else {
                val id = chatService.createChat(session.userId)
                call.respond(HttpStatusCode.Created, CreateChatResponse(id))
            }
        }

        route("/{chatId}") {
            get { call.chatMessagesHandler(chatService) }
            patch { call.renameHandler(chatService) }
            delete { call.deleteHandler(chatService) }
            post("/messages") { call.sendMessageHandler(chatService) }
        }
    }
}

private fun ApplicationCall.requireUserId(): Long? {
    val session = sessions.get<SessionData>() ?: return null
    return session.userId
}

private suspend fun ApplicationCall.chatMessagesHandler(chatService: ChatService) {
    val chatId = chatIdOrNull()
    val userId = requireUserId()
    when {
        chatId == null -> respond(HttpStatusCode.BadRequest, ChatError("invalid chat id"))
        userId == null -> respond(HttpStatusCode.Unauthorized, ChatError("not logged in"))
        else -> {
            val messages = chatService.listMessages(userId, chatId)
            if (messages == null) {
                respond(HttpStatusCode.NotFound, ChatError("chat not found"))
            } else {
                respond(messages.map { it.toResponse() })
            }
        }
    }
}

private suspend fun ApplicationCall.renameHandler(chatService: ChatService) {
    val chatId = chatIdOrNull()
    val userId = requireUserId()
    when {
        chatId == null -> respond(HttpStatusCode.BadRequest, ChatError("invalid chat id"))
        userId == null -> respond(HttpStatusCode.Unauthorized, ChatError("not logged in"))
        else -> {
            val body = receive<RenameRequest>()
            val updated = chatService.renameChat(userId, chatId, body.title)
            if (updated == null) {
                respond(HttpStatusCode.NotFound, ChatError("chat not found"))
            } else {
                respond(updated.toResponse())
            }
        }
    }
}

private suspend fun ApplicationCall.deleteHandler(chatService: ChatService) {
    val chatId = chatIdOrNull()
    val userId = requireUserId()
    when {
        chatId == null -> respond(HttpStatusCode.BadRequest, ChatError("invalid chat id"))
        userId == null -> respond(HttpStatusCode.Unauthorized, ChatError("not logged in"))
        else -> {
            if (chatService.deleteChat(userId, chatId)) {
                respond(HttpStatusCode.NoContent)
            } else {
                respond(HttpStatusCode.NotFound, ChatError("chat not found"))
            }
        }
    }
}

private suspend fun ApplicationCall.sendMessageHandler(chatService: ChatService) {
    val chatId = chatIdOrNull()
    val userId = requireUserId()
    when {
        chatId == null -> respond(HttpStatusCode.BadRequest, ChatError("invalid chat id"))
        userId == null -> respond(HttpStatusCode.Unauthorized, ChatError("not logged in"))
        else -> {
            val body = receive<SendMessageRequest>()
            if (body.content.isBlank()) {
                respond(HttpStatusCode.BadRequest, ChatError("content is required"))
            } else {
                val userMessage =
                    chatService.appendMessage(userId, chatId, MessageRole.USER, body.content)
                if (userMessage == null) {
                    respond(HttpStatusCode.NotFound, ChatError("chat not found"))
                } else {
                    streamEchoReply(this, chatService, userId, chatId, body.content)
                }
            }
        }
    }
}

/**
 * Stream an SSE reply and persist the assistant message when streaming finishes.
 *
 * The echo implementation streams the input back one word at a time so the
 * streaming path (SSE framing, client disconnect, persistence on completion) is
 * fully exercised before the real LLM lands in phase 5.
 */
private suspend fun streamEchoReply(
    call: ApplicationCall,
    chatService: ChatService,
    userId: Long,
    chatId: Long,
    userText: String,
) {
    val replyText = "Got it: $userText"

    call.respondTextWriter(
        contentType = ContentType.Text.EventStream,
        status = HttpStatusCode.OK,
    ) {
        var emitted = ""
        for (word in replyText.split(" ")) {
            if (emitted.isNotEmpty()) emitted += " "
            emitted += word
            val frame = MessageResponse(
                id = -1,
                role = "assistant",
                content = emitted,
                createdAt = "",
            )
            write("data: ${json.encodeToString(frame)}\n\n")
            flush()
            // Small delay so the incremental frames are visible; removed in phase 5.
            delay(30)
        }
        write("data: [DONE]\n\n")
        flush()
    }

    chatService.appendMessage(userId, chatId, MessageRole.ASSISTANT, replyText)
}
