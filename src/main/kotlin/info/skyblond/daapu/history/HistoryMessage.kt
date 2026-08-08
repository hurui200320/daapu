package info.skyblond.daapu.history

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Framework-neutral chat history DTOs, owned by this project.
 *
 * `chats.history_json` stores a JSON array of [HistoryMessage]; no koog or
 * langchain4j type names cross the database or API boundary. The koog-facing
 * boundary is `PostgresChatHistoryProvider` (see `koog/KoogHistoryConverters.kt`),
 * which converts koog's `Message` list to and from these DTOs.
 *
 * Mapping notes for the upcoming langchain4j migration (issue #6):
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
     * carries none — e.g. koog's `RequestMetaInfo.Empty`/`ResponseMetaInfo.Empty`
     * sentinels normalize to `null`, and langchain4j messages have no timestamps.
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

    /** Tool results (koog stores them as a `Message.User` with `Tool.Result` parts). */
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
     * Reasoning/thinking trace. A [List] because koog's `Reasoning` part is a
     * list of blocks; langchain4j's single `thinking` string maps to a
     * singleton list.
     */
    @Serializable
    @SerialName("reasoning")
    data class Reasoning(val content: List<String>) : HistoryPart

    @Serializable
    @SerialName("tool_call")
    data class ToolCall(
        /**
         * The tool call's id, as returned by the provider. Required and must
         * be non-blank: koog's request serializer would otherwise assign
         * independent random ids to the call and its result, so strict
         * providers reject the re-sent history with a 400 and the chat is
         * bricked. `withGeneratedToolCallIds` guarantees an id on every
         * accepted message; [HistoryCodec] fails fast at decode if a stored
         * row violates this.
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

    // URL content is deliberately NOT supported (koog's AttachmentContent.URL
    // is refused at the converter boundary): nothing in this app produces it,
    // and allowing stored external URLs into the system is a risk we don't
    // need until a real use case exists (e.g. provider-returned URLs after
    // the langchain4j migration). Blocked at the koog boundary in
    // KoogHistoryConverters, so no URL ever lands in history_json.

}
