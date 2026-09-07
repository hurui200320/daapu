package info.skyblond.daapu.agent.pipeline

import info.skyblond.daapu.agent.chat.AttachmentContent
import info.skyblond.daapu.agent.chat.ChatMessage
import info.skyblond.daapu.agent.chat.ChatMessagePart
import info.skyblond.daapu.agent.chat.ChatMessageRole
import info.skyblond.daapu.hand.CollectRunObserver
import info.skyblond.daapu.hand.HandRunException
import info.skyblond.daapu.hand.HandRunRequest
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.Locale

/**
 * The one-shot trace: the console observability for the collect runs (see
 * [CollectRunObserver]) — the system prompt, the input messages and the
 * full per-round transcript (reasoning, text, tool calls with args, tool
 * results, usage, finish reasons), including the partial history of failed
 * runs.
 *
 * Tool advertisements are deliberately NOT traced: the hand re-queries the
 * tool list before every LLM request, so a snapshot taken here would not
 * be what the model actually saw, and fetching the specs would add extra
 * calls to the MCP servers after the run already ended.
 *
 * [OneShotTracer] is registered as the brain's [CollectRunObserver] only
 * when `observability.oneShotTrace` is enabled (`config/ObservabilityConfig.kt`,
 * wired in `di/AppModule.kt`); it logs under the dedicated logger name
 * `OneShotTrace`, so logback can route or silence it independently of the
 * rest of the application. Content is logged verbatim and untruncated on
 * purpose — a truncation cap would hide exactly the confusion this trace
 * exists to diagnose — but attachments are never logged as bytes: they
 * render as placeholders.
 */
class OneShotTracer : CollectRunObserver {

    override suspend fun onCollect(
        label: String?,
        request: HandRunRequest,
        messages: List<ChatMessage>,
        error: Exception?,
    ) {
        logger.info { renderOneShotTrace(label, request, messages, error) }
    }

    companion object {
        private val logger = KotlinLogging.logger("OneShotTrace")
    }
}

/**
 * Renders one collect run as a human-readable transcript. Pure: the header
 * (label, model, rounds, summed token usage), the system prompt, the input
 * messages, the per-message transcript — assistant messages numbered per
 * round — and the terminal outcome. Internal so the tests can pin the exact rendering.
 */
internal fun renderOneShotTrace(
    label: String?,
    request: HandRunRequest,
    messages: List<ChatMessage>,
    error: Exception?,
): String {
    val inputTokens = messages.sumOf { it.meta?.inputTokens ?: 0 }
    val outputTokens = messages.sumOf { it.meta?.outputTokens ?: 0 }
    val rounds = messages.count { it.role == ChatMessageRole.Assistant }
    return buildString {
        appendLine(
            "=== One-shot trace: ${label ?: "(unlabelled)"} | model=${request.model.modelId} | " +
                    "rounds=$rounds | tokens in=$inputTokens out=$outputTokens ==="
        )
        appendLine("--- system prompt ---")
        appendLine(request.systemPrompt?.takeIf { it.isNotBlank() } ?: "(none)")
        appendLine("--- input (${request.messages.size} messages) ---")
        request.messages.forEach { append(renderMessage(it)) }
        appendLine("--- transcript (${messages.size} messages) ---")
        var round = 0
        messages.forEach { message ->
            if (message.role == ChatMessageRole.Assistant) round += 1
            append(renderMessage(message, round))
        }
        appendLine("--- end: ${renderTerminal(messages, error)} ---")
    }
}

/** The final line: a clean done, the hand's error taxonomy, or the raw transport failure. */
private fun renderTerminal(messages: List<ChatMessage>, error: Exception?): String = when (error) {
    null -> "done (${messages.lastOrNull { it.role == ChatMessageRole.Assistant }?.finishReason ?: "?"})"
    is HandRunException -> "hand error ${error.type}: ${error.message}"
    else -> "transport failure: ${error.javaClass.simpleName}: ${error.message}"
}

private fun renderMessage(message: ChatMessage, round: Int = 0): String {
    val header = when (message.role) {
        ChatMessageRole.Assistant -> {
            // the usage meta is required on every assistant message
            // (ChatMessage invariant), so the fallbacks are defensive only
            "[assistant #$round | finish=${message.finishReason} | " +
                    "in=${message.meta?.inputTokens ?: "?"} out=${message.meta?.outputTokens ?: "?"}]"
        }

        ChatMessageRole.User -> "[user]"
        ChatMessageRole.ToolResult -> {
            val part = message.parts.single() as ChatMessagePart.ToolResult
            "[tool_result#${part.id} ${part.tool} | ${if (part.isError) "error" else "ok"}]"
        }
    }
    return header + "\n" + renderRoleParts(message)
}

private fun renderRoleParts(message: ChatMessage): String = when (message.role) {
    ChatMessageRole.Assistant -> message.parts.joinToString("") { part ->
        when (part) {
            is ChatMessagePart.Reasoning -> block("reasoning", part.content)
            is ChatMessagePart.Text -> block("text", part.text)
            is ChatMessagePart.ToolCall -> "  <tool_call#${part.id}> ${part.tool}(${part.args})\n"
            // the ChatMessage invariant allows nothing else on an assistant message
            else -> ""
        }
    }

    ChatMessageRole.User -> renderContentParts(message.parts)
    ChatMessageRole.ToolResult -> renderContentParts(
        (message.parts.single() as ChatMessagePart.ToolResult).parts
    )
}

private fun renderContentParts(parts: List<ChatMessagePart>): String = parts.joinToString("") { part ->
    when (part) {
        is ChatMessagePart.Text -> block("text", part.text)
        is ChatMessagePart.Attachment -> "  ${renderAttachment(part)}\n"
        // content parts are text and attachments only
        else -> ""
    }
}

/** A labeled block: the content indented between open and close tags. */
private fun block(tag: String, content: String): String =
    if (content.isBlank()) "  <$tag>(empty)\n"
    else "  <$tag>\n${indent(content)}\n  </$tag>\n"

/** Indents every non-blank line by four spaces (blank lines stay bare). */
private fun indent(text: String): String =
    text.split("\n").joinToString("\n") { line -> if (line.isBlank()) line else "    $line" }

/**
 * Attachments render as placeholders — never their bytes (the trace is
 * content-verbatim, but base64 blobs would drown the console).
 */
private fun renderAttachment(part: ChatMessagePart.Attachment): String {
    val base64Length = (part.content as? AttachmentContent.Base64)?.base64?.length ?: 0
    return "[attachment ${part.kind.name.lowercase()} ${part.mimeType}, " +
            "~${approxSize(base64Length * 3L / 4)} decoded, omitted]"
}

private fun approxSize(bytes: Long): String = when {
    bytes >= 1L shl 20 -> String.format(Locale.ROOT, "%.1fMB", bytes / 1048576.0)
    bytes >= 1L shl 10 -> String.format(Locale.ROOT, "%.1fKB", bytes / 1024.0)
    else -> "${bytes}B"
}
