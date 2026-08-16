package info.skyblond.daapu.agent.oneshot

import info.skyblond.daapu.agent.chat.ChatMessage
import info.skyblond.daapu.agent.chat.ChatMessagePart
import info.skyblond.daapu.agent.chat.ChatMessageRole

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


/**
 * The one-shot text answer: the final message of a collected run
 * ([HandService.runCollect]'s list — by construction the last message of a
 * successful run is the assistant's clean `stop` message, because tool-call
 * rounds continue the loop and a stop without text fails as
 * `empty_response` before `done`). The checks below stay as a defensive
 * backstop on top of the hand's own guarantees.
 */
fun List<ChatMessage>.lastMessageText(): String {
    val assistant = lastOrNull()
        ?: error("One-shot call produced no messages")
    if (assistant.role != ChatMessageRole.Assistant) {
        error("One-shot call produced no assistant message")
    }
    if (assistant.finishReason != "stop") {
        error("One-shot call ended with finish_reason=${assistant.finishReason}, not a clean stop")
    }
    return assistant.parts.filterIsInstance<ChatMessagePart.Text>()
        .joinToString("\n") { it.text }
        .trim()
        .takeIf { it.isNotBlank() }
        ?: error("One-shot call produced no text")
}
