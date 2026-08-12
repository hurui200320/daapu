package info.skyblond.daapu.chat

import info.skyblond.daapu.agent.lc4j.llm.LLMCapability
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.time.Instant

/**
 * Framework-neutral chat DTOs, owned by this project.
 */
@Serializable
data class ChatMessage(
    val role: ChatMessageRole,
    val parts: List<ChatMessagePart>,
    /**
     * Per-message metadata (timestamp, token usage). Absent when the message
     * carries none — new user/tool messages have no meta, and langchain4j
     * carries no timestamps, so assistant meta records only what the response
     * metadata provides.
     */
    val meta: ChatMessageMeta? = null,
    /**
     * Assistant only: the provider's `finish_reason` (e.g. "stop", "tool_calls").
     * Required on assistant messages: the streaming path only accepts responses
     * that carried a `finish_reason` (the End frame is only emitted with one),
     * and [ChatCodec] fails fast at decode if this invariant is violated.
     */
    val finishReason: String? = null,
) {
    init {
        if (role == ChatMessageRole.Assistant && finishReason.isNullOrBlank()) {
            throw IllegalArgumentException(
                "An assistant message must carry a non-empty finishReason."
            )
        }
        if (role != ChatMessageRole.Assistant && finishReason != null) {
            throw IllegalArgumentException(
                "A $role message must not carry any finishReason."
            )
        }

        parts.forEach { part ->
            val check = when (role) {
                ChatMessageRole.System -> part is ChatMessagePart.Text
                ChatMessageRole.User ->
                    part is ChatMessagePart.Text
                            || part is ChatMessagePart.Attachment

                ChatMessageRole.Assistant ->
                    part is ChatMessagePart.Text
                            || part is ChatMessagePart.Reasoning
                            || part is ChatMessagePart.ToolCall

                ChatMessageRole.ToolResult -> part is ChatMessagePart.ToolResult
            }
            require(check) {
                "Part type ${part.javaClass.simpleName} is not allowed for $role message."
            }
        }
    }
}

@Serializable
enum class ChatMessageRole {
    @SerialName("system")
    System,

    @SerialName("user")
    User,

    @SerialName("assistant")
    Assistant,

    /** Tool results (stored as `role = tool_result` messages by the turn loop). */
    @SerialName("tool_result")
    ToolResult,
}

@Serializable
data class ChatMessageMeta(
    /** ISO-8601 instant; absent when the source framework has no timestamp. */
    val timestamp: String? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val totalTokens: Int? = null,
    val modelId: String? = null,
) {
    init {
        timestamp?.let {
            if (Instant.parseOrNull(it) == null) {
                throw IllegalArgumentException(
                    "Timestamp is not a valid ISO-8601 instant: $timestamp"
                )
            }
        }
        inputTokens?.let { require(it >= 0) { "InputTokens must be non-negative: $it" } }
        outputTokens?.let { require(it >= 0) { "OutputTokens must be non-negative: $it" } }
        totalTokens?.let { require(it >= 0) { "TotalTokens must be non-negative: $it" } }
    }
}

/**
 * One part of a message. Serialized polymorphically via the `"type"` key with
 * the short names below. `ignoreUnknownKeys` in the codec tolerates added
 * fields on decode; an unknown `"type"` value fails fast (never silently
 * dropped), same as the old koog-format codec.
 */
@Serializable
sealed interface ChatMessagePart {
    @Serializable
    @SerialName("text")
    data class Text(val text: String) : ChatMessagePart, ContentPart

    /**
     * Reasoning/thinking trace. A [List] for compatibility with the older
     * koog-format rows (its `Reasoning` part was a list of blocks);
     * langchain4j's single flat `thinking` string maps to a singleton list.
     */
    @Serializable
    @SerialName("reasoning")
    data class Reasoning(val content: List<String>) : ChatMessagePart

    @Serializable
    @SerialName("tool_call")
    data class ToolCall(
        /**
         * The tool call's id, as returned by the provider. Required and must
         * be non-blank: the re-sent history would otherwise carry mismatched
         * `tool_call_id`s (or none), and strict providers reject it with a
         * 400, bricking the chat. `withGeneratedToolCallIds` guarantees an id
         * on every accepted message; [ChatCodec] fails fast at decode if a
         * stored row violates this.
         */
        val id: String,
        val tool: String,
        /** Raw JSON-encoded argument string. */
        val args: String,
    ) : ChatMessagePart {
        init {
            if (id.isBlank()) {
                throw IllegalArgumentException("A tool call part must carry a non-blank id.")
            }
        }
    }

    @Serializable
    @SerialName("tool_result")
    data class ToolResult(
        /**
         * The id of the tool call this result answers. Required and non-blank,
         * and must match the corresponding `tool_call` id — the same bricked-
         * chat argument as [ToolCall.id].
         */
        val id: String,
        val tool: String,
        val parts: List<ContentPart>,
        val isError: Boolean = false,
    ) : ChatMessagePart {
        init {
            if (id.isBlank()) {
                throw IllegalArgumentException("A tool result part must carry a non-blank id.")
            }
        }
    }

    @Serializable
    @SerialName("attachment")
    data class Attachment(
        val kind: AttachmentKind,
        val content: AttachmentContent,
        val mimeType: String,
    ) : ChatMessagePart, ContentPart

    /** Text and attachments may also appear nested inside [ToolResult.parts]. */
    @Serializable
    sealed interface ContentPart : ChatMessagePart
}

@Serializable
enum class AttachmentKind(
    @Transient
    val requiredCapabilities: Set<LLMCapability>
) {
    @SerialName("image")
    Image(
        setOf(
            LLMCapability.Input.Vision.Image
        )
    ),

    @SerialName("video")
    Video(
        setOf(
            LLMCapability.Input.Vision.Video
        )
    ),

    @SerialName("audio")
    Audio(
        setOf(
            LLMCapability.Input.Audio
        )
    ),

    @SerialName("file")
    File(
        setOf(
            LLMCapability.Input.Document
        )
    ),
}

@Serializable
sealed interface AttachmentContent {
    @Serializable
    @SerialName("base64")
    data class Base64(val base64: String) : AttachmentContent

    @Serializable
    @SerialName("text")
    data class PlainText(val text: String) : AttachmentContent

    // URL content is deliberately NOT supported: nothing in this app produces
    // it, and allowing stored external URLs into the system is a risk we
    // don't need until a real use case exists (e.g. provider-returned URLs
    // after a future model feature). The neutral format simply has no URL
    // content type, so no URL ever lands in chat_json.

}
