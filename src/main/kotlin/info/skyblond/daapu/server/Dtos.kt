package info.skyblond.daapu.server

import info.skyblond.daapu.memory.sstm.ShortTermMemory
import kotlinx.serialization.Serializable

/**
 * Request body of `POST /api/chats/{id}/messages`.
 *
 * At least one of [text] or [images] must be present. [model] is required
 * (the web UI picks one per message; there is no server-side default).
 */
@Serializable
data class SendMessageRequest(
    val text: String? = null,
    val images: List<ImagePart> = emptyList(),
    val model: String? = null,
)

/**
 * One image attached to a message, as a `data:image/<format>;base64,<data>`
 * data URL (the same format `FileReader.readAsDataURL` produces).
 */
@Serializable
data class ImagePart(val dataUrl: String)

@Serializable
data class ChatIdResponse(val id: String)

/**
 * Request body of `PUT /api/chats/{id}`. [title] must be non-blank.
 */
@Serializable
data class RenameChatRequest(val title: String)

@Serializable
data class ModelInfo(
    val id: String,
    val vision: Boolean,
    val contextLength: Long?,
    val maxOutputTokens: Long?,
)

@Serializable
data class MemoryDto(
    val id: Long,
    val lastUpdate: String,
    val content: String,
) {
    companion object {
        fun ShortTermMemory.toDto() = MemoryDto(
            id = id,
            lastUpdate = lastUpdate.toString(),
            content = content
        )
    }
}

@Serializable
data class MemoryWriteRequest(val content: String)

/**
 * The chat already has an active run. Mapped to HTTP 409.
 */
class ChatRunConflictException(message: String) : Exception(message)
