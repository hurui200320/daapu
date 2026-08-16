package info.skyblond.daapu.hand

import info.skyblond.daapu.agent.chat.ChatMessage
import info.skyblond.daapu.agent.chat.ChatMessageMeta
import info.skyblond.daapu.agent.chat.ChatMessagePart
import info.skyblond.daapu.agent.chat.ChatMessageRole
import info.skyblond.daapu.agent.tool.ToolCallRequest
import info.skyblond.daapu.agent.tool.ToolProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * A scripted in-process [HandClient] for unit tests: records every request
 * and answers `run` from a test-provided script. The script mirrors the
 * hand's contract — it can inspect the request and, in tool tests, execute
 * the provider directly (see [toolRoundEvents]), standing in for the hand's
 * HTTP callback POST (the HTTP contract itself is pinned by HandCallbackTest
 * and hand-pi's vitest suite).
 */
class FakeHand(
    private val runScript: suspend (HandRunRequest) -> List<HandEvent> = { listOf(HandEvent.Done("stop")) },
) : HandClient {
    // thread-safe: the shared-service concurrency test runs several chats
    // against one FakeHand at the same time
    val requests = java.util.concurrent.CopyOnWriteArrayList<HandRunRequest>()

    override suspend fun run(request: HandRunRequest): Flow<HandEvent> = flow {
        requests += request
        runScript(request).forEach { emit(it) }
    }

    override fun close() {}
}

/** A one-shot run flow: the final assistant answer followed by `done`. */
fun textRunFlow(text: String, finishReason: String = "stop"): List<HandEvent> =
    listOf(
        HandEvent.AssistantMessage(assistantMessage(text, finishReason = finishReason)),
        HandEvent.Done("stop"),
    )

/** A run flow ending in a hand error (the `error` SSE event). */
fun errorRunFlow(type: String, message: String): List<HandEvent> =
    listOf(HandEvent.RunError(type, message))

/**
 * Executes every tool call of one assistant round via [provider] — standing
 * in for the hand's HTTP tool callback — and returns the `tool_call` /
 * `tool_result` echo events the hand would emit for them.
 */
suspend fun toolRoundEvents(
    assistant: ChatMessage,
    provider: ToolProvider,
): List<HandEvent> {
    val echoes = mutableListOf<HandEvent>()
    for (call in assistant.parts.filterIsInstance<ChatMessagePart.ToolCall>()) {
        echoes += HandEvent.ToolCall(call.id, call.tool, call.args)
        val result = provider.execute(ToolCallRequest(call.id, call.tool, call.args))
        echoes += HandEvent.ToolResult(call.id, call.tool, result.parts, result.isError)
    }
    return echoes
}

/** A minimal daapu assistant message for scripts. */
fun assistantMessage(
    text: String? = null,
    parts: List<ChatMessagePart>? = null,
    finishReason: String = "stop",
    meta: ChatMessageMeta = ChatMessageMeta(inputTokens = 10, outputTokens = 10, totalTokens = 20),
): ChatMessage = ChatMessage(
    role = ChatMessageRole.Assistant,
    parts = parts ?: listOfNotNull(text?.let { ChatMessagePart.Text(it) }),
    meta = meta,
    finishReason = finishReason,
)
