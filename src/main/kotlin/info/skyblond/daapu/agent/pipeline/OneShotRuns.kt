package info.skyblond.daapu.agent.pipeline

import info.skyblond.daapu.agent.chat.ChatMessage
import info.skyblond.daapu.agent.chat.ChatMessageRole
import info.skyblond.daapu.agent.chat.textContent
import info.skyblond.daapu.agent.model.LLM
import info.skyblond.daapu.agent.tool.EmptyToolProvider
import info.skyblond.daapu.agent.tool.ToolProvider
import info.skyblond.daapu.hand.HandRunPolicy
import info.skyblond.daapu.hand.HandService
import info.skyblond.daapu.hand.handRunRequest
import kotlinx.coroutines.CancellationException

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
    return assistant.parts.textContent()
        .takeIf { it.isNotBlank() }
        ?: error("One-shot call produced no text")
}

/**
 * One hand `/v1/run` collect for the one-shot services: builds the request
 * (the model's own output budget, no tools unless [toolProvider] says
 * otherwise, round-capped by [maxRounds]), collects the run's messages, and
 * wraps every non-cancellation failure into an [IllegalStateException]
 * labelled with [label] — the uniform one-shot failure contract (a failed
 * one-shot fails the run; the label names the pipeline stage). The text
 * one-shots use [runOneShotText] on top of this.
 */
suspend fun HandService.runOneShotCollect(
    model: LLM,
    messages: List<ChatMessage>,
    systemPrompt: String,
    policy: HandRunPolicy,
    label: String,
    maxRounds: Int = 0,
    toolProvider: ToolProvider = EmptyToolProvider,
): List<ChatMessage> = try {
    runCollect(
        handRunRequest(
            model = model,
            messages = messages,
            systemPrompt = systemPrompt,
            policy = policy,
            maxRounds = maxRounds,
        ),
        toolProvider = toolProvider,
        model = model,
    )
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    throw IllegalStateException("$label failed", e)
}

/**
 * The text one-shot: [runOneShotCollect] plus the final-message text
 * extraction ([lastMessageText]) — the "one LLM call, one text answer"
 * shape shared by the title/compaction/rewrite/extraction one-shots. The
 * extraction runs INSIDE the same labelled wrap as the collect: its
 * defensive backstop failures ("produced no text" etc.) carry the stage
 * label too, one [IllegalStateException] family per pipeline stage.
 */
suspend fun HandService.runOneShotText(
    model: LLM,
    messages: List<ChatMessage>,
    systemPrompt: String,
    policy: HandRunPolicy,
    label: String,
    maxRounds: Int = 0,
    toolProvider: ToolProvider = EmptyToolProvider,
): String {
    val collected = runOneShotCollect(model, messages, systemPrompt, policy, label, maxRounds, toolProvider)
    return try {
        collected.lastMessageText()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw IllegalStateException("$label failed", e)
    }
}
