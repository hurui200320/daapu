package info.skyblond.daapu.agent.chat

import info.skyblond.daapu.agent.model.LLMCapability
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonObject

/**
 * Framework-neutral chat DTOs, owned by this project. The shape mirrors the
 * hand-pi service's needs (pi-ai's message model) closely, so the hand's
 * conversion is a near-passthrough:
 *
 * - There is no `system` role: the system prompt travels as a separate
 *   `systemPrompt` field on the hand requests and is never stored in
 *   `chat_json` (the loop refreshes it from configuration every run).
 * - `tool_call.args` is a parsed JSON object (pi-ai's `arguments`).
 * - A `tool_result` message carries exactly one [ChatMessagePart.ToolResult]
 *   part, matching pi-ai's one message per tool call.
 */
@Serializable
data class ChatMessage(
    val role: ChatMessageRole,
    val parts: List<ChatMessagePart>,
    /**
     * Per-message metadata (token usage). Required on assistant messages
     * (the hand refuses to serve responses without provider-reported usage,
     * and [ChatCodec] fails fast at decode if this invariant is violated);
     * user/tool messages carry none.
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
        if (role == ChatMessageRole.Assistant && meta == null) {
            throw IllegalArgumentException(
                "An assistant message must carry meta with provider-reported usage."
            )
        }
        if (role != ChatMessageRole.Assistant && meta != null) {
            throw IllegalArgumentException(
                "A $role message must not carry any meta."
            )
        }

        parts.forEach { part ->
            val check = when (role) {
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
        if (role == ChatMessageRole.ToolResult) {
            require(parts.size == 1) {
                "A tool_result message must carry exactly one tool_result part, got ${parts.size}."
            }
        }
    }
}

@Serializable
enum class ChatMessageRole {
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
    /**
     * The FULL prompt size (`prompt_tokens`): the hand reports
     * `input + cacheRead + cacheWrite`, never the cache-subtracted input
     * count. Required: the hand fails a round when the provider reports no
     * usage (it must honor `stream_options.include_usage`), so every
     * assistant message carries a measured snapshot the proactive compaction
     * trigger and the exhaustion classifier depend on.
     */
    val inputTokens: Int,
    val outputTokens: Int,
    val totalTokens: Int,
    val modelId: String? = null,
) {
    init {
        require(inputTokens >= 0) { "InputTokens must be non-negative: $inputTokens" }
        require(outputTokens >= 0) { "OutputTokens must be non-negative: $outputTokens" }
        require(totalTokens >= 0) { "TotalTokens must be non-negative: $totalTokens" }
    }
}

/**
 * One part of a message. Serialized polymorphically via the `"type"` key with
 * the short names below. `ignoreUnknownKeys` in the codec tolerates added
 * fields on decode; an unknown `"type"` value fails fast (never silently
 * dropped).
 */
@Serializable
sealed interface ChatMessagePart {
    @Serializable
    @SerialName("text")
    data class Text(val text: String) : ChatMessagePart, ContentPart

    /**
     * Reasoning/thinking trace: one flat thinking block per part, mirroring
     * pi-ai's `ThinkingContent`.
     */
    @Serializable
    @SerialName("reasoning")
    data class Reasoning(val content: String) : ChatMessagePart

    @Serializable
    @SerialName("tool_call")
    data class ToolCall(
        /**
         * The tool call's id, as returned by the provider. Required and must
         * be non-blank: the re-sent history would otherwise carry mismatched
         * `tool_call_id`s (or none), and strict providers reject it with a
         * 400, bricking the chat. The hand guarantees an id on every accepted
         * message (uuidv7 synthesis for id-less calls); [ChatCodec] fails fast
         * at decode if a stored row violates this.
         */
        val id: String,
        val tool: String,
        /** The parsed argument object (pi-ai's `arguments`). */
        val args: JsonObject,
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

    // URL content is deliberately NOT supported: nothing in this app produces
    // it, and allowing stored external URLs into the system is a risk we
    // don't need until a real use case exists (e.g. provider-returned URLs
    // after a future model feature). The neutral format simply has no URL
    // content type, so no URL ever lands in chat_json.

}
