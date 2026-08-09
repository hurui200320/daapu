package info.skyblond.daapu.langchain4j

import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.AudioContent
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.Content
import dev.langchain4j.data.message.ImageContent
import dev.langchain4j.data.message.PdfFileContent
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.TextContent
import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.data.message.VideoContent
import dev.langchain4j.model.chat.response.ChatResponse
import dev.langchain4j.model.output.FinishReason
import info.skyblond.daapu.history.AttachmentContent
import info.skyblond.daapu.history.AttachmentKind
import info.skyblond.daapu.history.HistoryMeta
import info.skyblond.daapu.history.HistoryMessage
import info.skyblond.daapu.history.HistoryPart
import info.skyblond.daapu.history.HistoryRole

/**
 * Converters between the framework-neutral history DTOs
 * (`info.skyblond.daapu.history`, the format stored in `chats.history_json`)
 * and langchain4j's `ChatMessage` model.
 *
 * The neutral format is the canonical in-loop structure (see
 * `agent/ChatTurnLoop.kt`): langchain4j messages carry no meta, so the loop
 * rebuilds the request from a fresh [toLangchain4jMessages] conversion each
 * round, and captures `ChatResponse` token usage / `finishReason` into
 * [HistoryMeta] via [ChatResponse.toNeutralAssistantMessage] at accept time.
 *
 * Mapping (per the notes in `history/HistoryMessage.kt`):
 * - `system` ↔ [SystemMessage] (multiple text parts are joined — langchain4j
 *   has a single text).
 * - `user` ↔ [UserMessage]; parts map to [Content]: text ↔ [TextContent],
 *   attachments ↔ [ImageContent]/[VideoContent]/[AudioContent]/[PdfFileContent]
 *   by kind (base64 content), plain-text attachment content ↔ [TextContent].
 * - `assistant` ↔ [AiMessage]: [HistoryPart.Reasoning] ↔ `thinking` (the
 *   list of blocks is joined into the single flat string langchain4j uses;
 *   re-storing normalizes back to a singleton list), [HistoryPart.ToolCall] ↔
 *   [ToolExecutionRequest]. Assistant `attachment` parts are refused on load:
 *   nothing in this app produces them, and langchain4j's `AiMessage` can only
 *   carry images (not video/audio/file), so failing fast beats silently
 *   dropping stored content.
 * - `tool` ↔ one [ToolExecutionResultMessage] per [HistoryPart.ToolResult]
 *   (contents ↔ nested parts, `isError` ↔ ours).
 *
 * The reverse direction (langchain4j → neutral) is not a general converter:
 * accepted assistant messages are built directly from `ChatResponse` by
 * [ChatResponse.toNeutralAssistantMessage], and locally-executed tool results
 * become neutral `tool` messages in the turn loop. Meta (timestamps) is
 * deliberately not reconstructed — langchain4j carries none.
 */
fun List<HistoryMessage>.toLangchain4jMessages(): List<ChatMessage> = map { it.toLangchain4jMessage() }

private fun HistoryMessage.toLangchain4jMessage(): ChatMessage = when (role) {
    HistoryRole.System -> SystemMessage(parts.joinToString("") { it.asSystemText() })

    HistoryRole.User -> UserMessage(parts.map { it.toLc4jContent() })

    HistoryRole.Tool -> parts.map { it.toLc4jToolResultMessage() }.let { toolMessages ->
        // a stored `tool` message should contain exactly one ToolResult; a
        // defensive check instead of silently splitting
        if (toolMessages.size != 1) {
            throw IllegalStateException(
                "A neutral tool message must contain exactly one tool_result part, got ${parts.size}"
            )
        }
        toolMessages.single()
    }

    HistoryRole.Assistant -> {
        val texts = mutableListOf<String>()
        val thinkings = mutableListOf<String>()
        val toolRequests = mutableListOf<ToolExecutionRequest>()
        parts.forEach { part ->
            when (part) {
                // langchain4j has a single text and a single thinking string;
                // multiple parts are joined with newlines. Stored rows have at
                // most one of each (the streaming path writes one Text part and
                // one Reasoning block), so this only normalizes exotic rows.
                is HistoryPart.Text -> texts += part.text
                is HistoryPart.Reasoning -> thinkings += part.content
                is HistoryPart.ToolCall -> toolRequests += ToolExecutionRequest.builder()
                    .id(part.id)
                    .name(part.tool)
                    .arguments(part.args)
                    .build()

                is HistoryPart.Attachment -> throw IllegalStateException(
                    "An assistant message cannot carry an attachment in the langchain4j mapping: " +
                            "nothing in this app produces one, and AiMessage can only hold images. " +
                            "Migrate or fix the chats row manually."
                )

                is HistoryPart.ToolResult -> throw IllegalStateException(
                    "An assistant message cannot carry a tool_result part: invalid history_json."
                )
            }
        }
        AiMessage.builder()
            .text(texts.joinToString("\n").takeIf { it.isNotEmpty() })
            .thinking(thinkings.joinToString("\n").takeIf { it.isNotEmpty() })
            .toolExecutionRequests(toolRequests)
            .build()
    }
}

private fun HistoryPart.asSystemText(): String = when (this) {
    is HistoryPart.Text -> text
    else -> throw IllegalStateException("System messages can only contain text parts, got: $this")
}

private fun HistoryPart.toLc4jContent(): Content = when (this) {
    is HistoryPart.Text -> TextContent(text)
    is HistoryPart.Attachment -> toLc4jAttachment()
    else -> throw IllegalStateException("User messages can only contain text and attachment parts, got: $this")
}

private fun HistoryPart.Attachment.toLc4jAttachment(): Content = when (val attachmentContent = content) {
    is AttachmentContent.PlainText -> TextContent(attachmentContent.text)
    is AttachmentContent.Base64 -> when (kind) {
        AttachmentKind.Image -> ImageContent(attachmentContent.base64, mimeType)
        AttachmentKind.Video -> VideoContent(attachmentContent.base64, mimeType)
        AttachmentKind.Audio -> AudioContent(attachmentContent.base64, mimeType)
        AttachmentKind.File -> PdfFileContent(attachmentContent.base64, mimeType)
    }
}

private fun HistoryPart.toLc4jToolResultMessage(): ToolExecutionResultMessage = when (this) {
    is HistoryPart.ToolResult -> ToolExecutionResultMessage.builder()
        .id(id)
        .toolName(tool)
        .contents(parts.map { it.toLc4jContent() })
        .isError(isError)
        .build()

    else -> throw IllegalStateException("Tool messages can only contain tool_result parts, got: $this")
}

/**
 * Build the neutral assistant message to append to history from an accepted
 * [ChatResponse].
 *
 * Called after the turn loop's acceptance checks (error-chunk scan,
 * truncation check, `classifyStreamResult`), so [ChatResponse.finishReason]
 * is never null here; the defensive check pins that invariant.
 *
 * [aiMessage] overrides the response's own `AiMessage` when the caller
 * normalized it first (e.g. `withGeneratedToolCallIds`); the token usage and
 * finish reason always come from the response metadata.
 *
 * [HistoryMeta] is captured from the response metadata: token usage and the
 * provider's model name. No timestamp is fabricated (langchain4j carries
 * none — see `HistoryMessage.kt`'s mapping notes). Part order mirrors the old
 * koog stream assembly: reasoning, text, then tool calls.
 */
fun ChatResponse.toNeutralAssistantMessage(aiMessage: AiMessage = aiMessage()): HistoryMessage {
    val ai = aiMessage
    val parts = mutableListOf<HistoryPart>()
    ai.thinking()?.takeIf { it.isNotBlank() }?.let { parts += HistoryPart.Reasoning(listOf(it)) }
    ai.text()?.let { parts += HistoryPart.Text(it) }
    ai.toolExecutionRequests().forEach { request ->
        parts += HistoryPart.ToolCall(
            // `withGeneratedToolCallIds` (see langchain4j/Utils.kt) guarantees
            // an id on every accepted message; refuse a blank one since strict
            // providers reject re-sent history with mismatched ids forever
            id = request.id().requireToolCallId("tool_call"),
            tool = request.name(),
            args = request.arguments(),
        )
    }
    val usage = tokenUsage()
    return HistoryMessage(
        role = HistoryRole.Assistant,
        parts = parts,
        meta = HistoryMeta(
            inputTokens = usage?.inputTokenCount(),
            outputTokens = usage?.outputTokenCount(),
            totalTokens = usage?.totalTokenCount(),
            modelId = modelName(),
        ),
        finishReason = finishReason()?.toWireName() ?: throw IllegalStateException(
            "Refusing to store an assistant message without a finish_reason: the streaming path " +
                    "only accepts responses that carried one (see ChatTurnLoop's truncation check)."
        ),
    )
}

/**
 * The provider-facing `finish_reason` wire name, as stored in the neutral
 * history and served to the frontend (pinned by the golden-format tests).
 */
fun FinishReason.toWireName(): String = when (this) {
    FinishReason.STOP -> "stop"
    FinishReason.LENGTH -> "length"
    FinishReason.TOOL_EXECUTION -> "tool_calls"
    FinishReason.CONTENT_FILTER -> "content_filter"
    FinishReason.OTHER -> "other"
}

internal fun String?.requireToolCallId(kind: String): String =
    if (this.isNullOrBlank()) {
        throw IllegalStateException(
            "Refusing to store a $kind without a stable non-blank id: strict providers reject " +
                    "re-sent history with mismatched ids forever. Fix the tool-call id upstream " +
                    "(withGeneratedToolCallIds)."
        )
    } else {
        this
    }
