package info.skyblond.daapu.history

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Framework-neutral chat history DTOs, owned by this project.
 *
 * `chats.history_json` stores a JSON array of [HistoryMessage]; no koog or
 * langchain4j type names cross the database or API boundary. The langchain4j
 * boundary is `langchain4j/Langchain4jHistoryConverters.kt`, which converts
 * these DTOs to langchain4j's `ChatMessage` list for the turn loop's
 * requests.
 *
 * Mapping notes for the langchain4j turn loop (`agent/ChatTurnLoop.kt`):
 * - `system` ↔ [dev.langchain4j.data.message.SystemMessage]
 * - `user` ↔ [dev.langchain4j.data.message.UserMessage] (its `contents` ↔ our parts)
 * - `assistant` ↔ [dev.langchain4j.data.message.AiMessage]:
 *   `thinking` (a flat String) ↔ [HistoryPart.Reasoning] singleton,
 *   `toolExecutionRequests` ↔ [HistoryPart.ToolCall]
 * - `tool` ↔ one [dev.langchain4j.data.message.ToolExecutionResultMessage] per
 *   [HistoryPart.ToolResult] (its `contents` ↔ our nested parts, `isError` ↔ ours)
 * - langchain4j carries no timestamps; token usage and `finishReason` live on
 *   `ChatResponse.metadata` and must be captured at run time into [HistoryMeta]
 *   / [HistoryMessage.finishReason].
 */
@Serializable
data class HistoryMessage(
    val role: HistoryRole,
    val parts: List<HistoryPart>,
    /**
     * Per-message metadata (timestamp, token usage). Absent when the message
     * carries none — new user/tool messages have no meta, and langchain4j
     * carries no timestamps, so assistant meta records only what the response
     * metadata provides.
     */
    val meta: HistoryMeta? = null,
    /**
     * Assistant only: the provider's `finish_reason` (e.g. "stop", "tool_calls").
     * Required on assistant messages: the streaming path only accepts responses
     * that carried a `finish_reason` (the End frame is only emitted with one),
     * and [HistoryCodec] fails fast at decode if this invariant is violated.
     */
    val finishReason: String? = null,
)

@Serializable
enum class HistoryRole {
    @SerialName("system")
    System,
    @SerialName("user")
    User,
    @SerialName("assistant")
    Assistant,

    /** Tool results (stored as `role = tool` messages by the turn loop). */
    @SerialName("tool")
    Tool,
}

@Serializable
data class HistoryMeta(
    /** ISO-8601 instant; absent when the source framework has no timestamp. */
    val timestamp: String? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val totalTokens: Int? = null,
    val modelId: String? = null,
)

/**
 * One part of a message. Serialized polymorphically via the `"type"` key with
 * the short names below. `ignoreUnknownKeys` in the codec tolerates added
 * fields on decode; an unknown `"type"` value fails fast (never silently
 * dropped), same as the old koog-format codec.
 */
@Serializable
sealed interface HistoryPart {
    @Serializable
    @SerialName("text")
    data class Text(val text: String) : HistoryPart, ContentPart

    /**
     * Reasoning/thinking trace. A [List] for compatibility with the older
     * koog-format rows (its `Reasoning` part was a list of blocks);
     * langchain4j's single flat `thinking` string maps to a singleton list.
     */
    @Serializable
    @SerialName("reasoning")
    data class Reasoning(val content: List<String>) : HistoryPart

    @Serializable
    @SerialName("tool_call")
    data class ToolCall(
        /**
         * The tool call's id, as returned by the provider. Required and must
         * be non-blank: the re-sent history would otherwise carry mismatched
         * `tool_call_id`s (or none), and strict providers reject it with a
         * 400, bricking the chat. `withGeneratedToolCallIds` guarantees an id
         * on every accepted message; [HistoryCodec] fails fast at decode if a
         * stored row violates this.
         */
        val id: String,
        val tool: String,
        /** Raw JSON-encoded argument string, as both koog and langchain4j store it. */
        val args: String,
    ) : HistoryPart

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
    ) : HistoryPart

    @Serializable
    @SerialName("attachment")
    data class Attachment(
        val kind: AttachmentKind,
        val content: AttachmentContent,
        val format: String,
        val mimeType: String,
        val fileName: String? = null,
    ) : HistoryPart, ContentPart

    /** Text and attachments may also appear nested inside [ToolResult.parts]. */
    @Serializable
    sealed interface ContentPart : HistoryPart
}

@Serializable
enum class AttachmentKind {
    @SerialName("image")
    Image,
    @SerialName("video")
    Video,
    @SerialName("audio")
    Audio,
    @SerialName("file")
    File,
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
    // content type, so no URL ever lands in history_json.

}
