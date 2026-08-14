package info.skyblond.daapu.agent.lc4j.chat

import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.data.message.*
import dev.langchain4j.model.chat.response.ChatResponse
import dev.langchain4j.model.output.FinishReason
import info.skyblond.daapu.chat.*
import info.skyblond.daapu.chat.ChatMessage
import dev.langchain4j.data.message.ChatMessage as Lc4jMessages

/**
 * Converters between the framework-neutral history DTOs and langchain4j's model.
 *
 * The reverse direction (langchain4j → neutral) is not a general converter:
 * accepted assistant messages are built directly from `ChatResponse` by
 * [toNeutralAssistantMessage], and locally-executed tool results
 * become neutral `tool` messages in the turn loop. Meta (timestamps) is
 * deliberately not reconstructed — langchain4j carries none.
 */
fun List<ChatMessage>.toLc4jMessages(): List<Lc4jMessages> = flatMap { it.toLc4jMessage() }

private fun ChatMessage.toLc4jMessage(): List<Lc4jMessages> = when (role) {
    ChatMessageRole.System -> listOf(
        SystemMessage(parts.joinToString("") { it.asSystemText() })
    )

    ChatMessageRole.User -> listOf(
        UserMessage(parts.map { it.toLc4jContent() })
    )

    // allow one tool result message contains multiple result parts
    ChatMessageRole.ToolResult -> parts.map { it.toLc4jToolResultMessage() }

    ChatMessageRole.Assistant -> {
        val texts = mutableListOf<String>()
        val thinkings = mutableListOf<String>()
        val toolRequests = mutableListOf<ToolExecutionRequest>()
        parts.forEach { part ->
            when (part) {
                // langchain4j has a single text and a single thinking string;
                // multiple parts are joined with newlines. Stored rows have at
                // most one of each (the streaming path writes one Text part and
                // one Reasoning block), so this only normalizes exotic rows.
                is ChatMessagePart.Text -> texts += part.text
                is ChatMessagePart.Reasoning -> thinkings += part.content
                is ChatMessagePart.ToolCall -> toolRequests += ToolExecutionRequest.builder()
                    .id(part.id)
                    .name(part.tool)
                    .arguments(part.args)
                    .build()

                is ChatMessagePart.Attachment -> error(
                    "An assistant message cannot carry an attachment in the langchain4j mapping."
                )

                is ChatMessagePart.ToolResult -> error(
                    "An assistant message cannot carry a tool_result part."
                )
            }
        }
        listOf(
            AiMessage.builder()
                .text(texts.joinToString("\n").takeIf { it.isNotEmpty() })
                .thinking(thinkings.joinToString("\n").takeIf { it.isNotEmpty() })
                .toolExecutionRequests(toolRequests)
                .build()
        )
    }
}

private fun ChatMessagePart.asSystemText(): String = when (this) {
    is ChatMessagePart.Text -> text
    else -> error("System messages can only contain text parts, got: $this")
}

private fun ChatMessagePart.toLc4jContent(): Content = when (this) {
    is ChatMessagePart.Text -> TextContent(text)
    is ChatMessagePart.Attachment -> toLc4jAttachment()
    else -> error("User messages can only contain text and attachment parts, got: $this")
}

private fun ChatMessagePart.Attachment.toLc4jAttachment(): Content =
    when (val attachmentContent = content) {
        is AttachmentContent.PlainText -> TextContent(attachmentContent.text)
        is AttachmentContent.Base64 -> when (kind) {
            AttachmentKind.Image -> ImageContent(attachmentContent.base64, mimeType)
            AttachmentKind.Video -> VideoContent(attachmentContent.base64, mimeType)
            AttachmentKind.Audio -> AudioContent(attachmentContent.base64, mimeType)
            AttachmentKind.File -> PdfFileContent(attachmentContent.base64, mimeType)
        }
    }

private fun ChatMessagePart.toLc4jToolResultMessage(): ToolExecutionResultMessage = when (this) {
    is ChatMessagePart.ToolResult -> ToolExecutionResultMessage.builder()
        .id(id)
        .toolName(tool)
        .contents(parts.map { it.toLc4jContent() })
        .isError(isError)
        .build()

    else -> error("Tool messages can only contain tool_result parts, got: $this")
}

/**
 * Build the neutral assistant message to append to history from an accepted
 * [ChatResponse].
 *
 * Called after the turn loop's acceptance checks (error-chunk scan,
 * truncation check, `classifyStreamResult`), so [finishReason]
 * is never null here; the defensive check pins that invariant.
 *
 * [aiMessage] overrides the response's own `AiMessage` when the caller
 * normalized it first (e.g. `withGeneratedToolCallIds`); the token usage and
 * finish reason always come from the response metadata.
 *
 * [ChatMessageMeta] is captured from the response metadata: token usage and the
 * provider's model name. No timestamp is fabricated (langchain4j carries
 * none — see `ChatMessage.kt`'s mapping notes). Part order mirrors the old
 * koog stream assembly: reasoning, text, then tool calls.
 */
fun ChatResponse.toNeutralAssistantMessage(aiMessage: AiMessage = aiMessage()): ChatMessage {
    val ai = aiMessage
    val parts = mutableListOf<ChatMessagePart>()
    ai.thinking()?.takeIf { it.isNotBlank() }
        ?.let { parts += ChatMessagePart.Reasoning(listOf(it)) }
    ai.text()?.let { parts += ChatMessagePart.Text(it) }
    ai.toolExecutionRequests().forEach { request ->
        if (request.id().isNullOrBlank()) {
            error("Tool call has no valid id")
        }
        parts += ChatMessagePart.ToolCall(
            id = request.id(),
            tool = request.name(),
            args = request.arguments(),
        )
    }
    val usage = tokenUsage()
    return ChatMessage(
        role = ChatMessageRole.Assistant,
        parts = parts,
        meta = ChatMessageMeta(
            inputTokens = usage?.inputTokenCount(),
            outputTokens = usage?.outputTokenCount(),
            totalTokens = usage?.totalTokenCount(),
            modelId = modelName(),
        ),
        finishReason = finishReason()?.toWireName()
            ?: error("Assistant message has no finish reason"),
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
