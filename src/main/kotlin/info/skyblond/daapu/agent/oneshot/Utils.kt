package info.skyblond.daapu.agent.oneshot

import info.skyblond.daapu.agent.chat.ChatMessage
import info.skyblond.daapu.agent.chat.ChatMessagePart
import info.skyblond.daapu.agent.chat.ChatMessageRole
import info.skyblond.daapu.hand.HandCompleteResponse

/**
 * The current prompt size in tokens: the last assistant message's measured
 * `meta.inputTokens` (the FULL prompt of that round, as reported by the
 * provider). There is no estimation: usage meta is required on every hand
 * response, and a stored chat always ends with the last round's assistant
 * message, so the snapshot is the freshest exact measurement available. A
 * chat with no assistant message (a brand-new chat) returns 0 — nothing
 * meaningful to measure yet; the reactive `context_exhausted` path still
 * guards a first run that overflows the window.
 */
fun currentPromptTokens(chat: List<ChatMessage>): Long =
    chat.lastOrNull { it.role == ChatMessageRole.Assistant }?.meta?.inputTokens?.toLong() ?: 0L


fun HandCompleteResponse.checkAndGetTextResp(): String {
    if (!this.ok) {
        error("One-shot call failed (${this.error?.type}): ${this.error?.message}")
    }
    val assistant = this.message ?: error("One-shot call returned no message")
    if (assistant.finishReason != "stop") {
        error("One-shot call ended with finish_reason=${assistant.finishReason}, not a clean stop")
    }
    if (assistant.parts.any { it is ChatMessagePart.ToolCall }) {
        error("One-shot call produced tool calls instead of text")
    }
    return assistant.parts.filterIsInstance<ChatMessagePart.Text>()
        .joinToString("\n") { it.text }
        .trim()
        .takeIf { it.isNotBlank() }
        ?: error("One-shot call produced no text")
}
