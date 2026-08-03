package info.skyblond.daapu.chat

import info.skyblond.daapu.db.MessageRole
import kotlinx.serialization.Serializable


data class Chat(
    // TODO: use uuid?
    val id: Long,
    val title: String,
    // TODO: use timestamp?
    val createdAt: java.time.OffsetDateTime,
    val updatedAt: java.time.OffsetDateTime,
)

fun Chat.toResponse(): ChatResponse = ChatResponse(
    id = id,
    title = title,
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
)

data class ChatMessage(
    val id: Long,
    val role: MessageRole,
    val content: String,
    val createdAt: java.time.OffsetDateTime,
)

fun ChatMessage.toResponse(): MessageResponse = MessageResponse(
    id = id,
    role = role.name,
    content = content,
    createdAt = createdAt.toString(),
)

@Serializable
data class ChatResponse(
    val id: Long,
    val title: String,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class CreateChatResponse(val id: Long)

@Serializable
data class RenameRequest(val title: String)

@Serializable
data class MessageResponse(
    val id: Long,
    val role: String,
    val content: String,
    val createdAt: String,
)

@Serializable
data class SendMessageRequest(val content: String)

@Serializable
data class ChatError(val error: String)
