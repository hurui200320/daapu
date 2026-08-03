package info.skyblond.daapu.chat

import info.skyblond.daapu.auth.SessionData
import info.skyblond.daapu.llm.ChatAgentService
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val json = Json { encodeDefaults = true }

/**
 * The current chat id path parameter, or `null` when missing/invalid.
 */
private fun ApplicationCall.chatIdOrNull(): Long? =
    parameters["chatId"]?.toLongOrNull()

fun Route.chatRoutes(chatService: ChatService, chatAgentService: ChatAgentService) {
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
            post("/messages") { call.sendMessageHandler(chatService, chatAgentService) }
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

private suspend fun ApplicationCall.sendMessageHandler(
    chatService: ChatService,
    chatAgentService: ChatAgentService,
) {
    val chatId = chatIdOrNull()
    val userId = requireUserId()
    when {
        chatId == null -> respond(HttpStatusCode.BadRequest, ChatError("invalid chat id"))
        userId == null -> respond(HttpStatusCode.Unauthorized, ChatError("not logged in"))
        else -> {
            val body = receive<SendMessageRequest>()
            if (body.content.isBlank()) {
                respond(HttpStatusCode.BadRequest, ChatError("content is required"))
            } else if (!chatService.ownsChat(userId, chatId)) {
                respond(HttpStatusCode.NotFound, ChatError("chat not found"))
            } else {
                streamAgentReply(this, chatAgentService, chatId, body.content)
            }
        }
    }
}

/**
 * Stream the koog agent's reply as SSE. koog's ChatMemory loads the chat's
 * history before the run and persists the updated conversation (including the
 * new user message and this reply) after the run completes.
 */
private suspend fun streamAgentReply(
    call: ApplicationCall,
    chatAgentService: ChatAgentService,
    chatId: Long,
    content: String,
) {
    call.respondTextWriter(
        contentType = ContentType.Text.EventStream,
        status = HttpStatusCode.OK,
    ) {
        var emitted = ""
        chatAgentService.streamReply(
            chatId = chatId,
            content = content,
            onDelta = { delta ->
                emitted += delta
                val frame = MessageResponse(
                    id = -1,
                    role = "assistant",
                    content = emitted,
                    createdAt = "",
                )
                write("data: ${json.encodeToString(frame)}\n\n")
                flush()
            },
        )
        write("data: [DONE]\n\n")
        flush()
    }
}
