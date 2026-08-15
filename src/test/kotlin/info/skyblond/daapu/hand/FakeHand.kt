package info.skyblond.daapu.hand

import info.skyblond.daapu.agent.chat.ChatMessage
import info.skyblond.daapu.agent.chat.ChatMessageMeta
import info.skyblond.daapu.agent.chat.ChatMessagePart
import info.skyblond.daapu.agent.chat.ChatMessageRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * A scripted in-process [HandClient] for unit tests: records every request
 * and answers `run`/`complete` from test-provided scripts. The `run` script
 * mirrors the hand's contract — it can inspect the request and, in tool
 * tests, execute the provider directly, standing in for the hand's HTTP
 * callback POST (the HTTP contract itself is pinned by HandCallbackTest and
 * hand-pi's vitest suite).
 */
class FakeHand(
    private val runScript: suspend (HandRunRequest) -> List<HandEvent> = { listOf(HandEvent.Done("stop")) },
    private val completeScript: suspend (HandCompleteRequest) -> HandCompleteResponse = {
        error("no complete response scripted")
    },
) : HandClient {
    // thread-safe: the shared-service concurrency test runs several chats
    // against one FakeHand at the same time
    val requests = java.util.concurrent.CopyOnWriteArrayList<HandRunRequest>()
    val completeRequests = java.util.concurrent.CopyOnWriteArrayList<HandCompleteRequest>()

    override suspend fun run(request: HandRunRequest): Flow<HandEvent> = flow {
        requests += request
        runScript(request).forEach { emit(it) }
    }

    override suspend fun complete(request: HandCompleteRequest): HandCompleteResponse {
        completeRequests += request
        return completeScript(request)
    }

    override fun close() {}
}

/** An ok /complete response carrying [message]. */
fun okCompleteResponse(message: ChatMessage) = HandCompleteResponse(
    ok = true,
    message = message,
    finishReason = message.finishReason,
)

/** A failed /complete response with the given hand error type. */
fun failedCompleteResponse(type: String, message: String) = HandCompleteResponse(
    ok = false,
    error = HandError(type = type, message = message),
)

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
