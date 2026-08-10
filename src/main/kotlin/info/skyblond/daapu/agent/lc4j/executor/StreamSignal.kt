package info.skyblond.daapu.agent.lc4j.executor

import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.kotlin.model.chat.request.chatRequest
import dev.langchain4j.model.chat.response.*
import dev.langchain4j.model.openai.OpenAiStreamingChatModel
import info.skyblond.daapu.agent.executor.StreamingExecutionCallback
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Convert lc4j's callback to a flow of signals,
 * so we can deal the streaming within coroutine's world.
 */
sealed interface StreamSignal {
    data class TextDelta(val text: String) : StreamSignal
    data class ThinkingDelta(val text: String) : StreamSignal
    data class ToolCallDone(val name: String, val args: String) : StreamSignal
    data class Completed(val response: ChatResponse) : StreamSignal
    data class Failed(val error: Throwable) : StreamSignal
}

/**
 * Stream one round of [messages] as a [Flow] of [StreamSignal]s.
 *
 * Notes (verified on langchain4j 1.18.1):
 * - `onError` and `onCompleteResponse` can BOTH fire for the same failure
 *   (parser `onError` + `onClose` race) — the `terminal` flag keeps only the
 *   first terminal signal; the rest are dropped.
 * - A stream that ends with NO `finish_reason` completes normally (the
 *   [StreamSignal.Completed] signal carries `finishReason() == null`); the
 *   turn loop implements truncation detection itself.
 * - The `StreamingHandle` (which can cancel the in-flight HTTP request) is
 *   only reachable through the context-carrying callback variants, so those
 *   are overridden here and the handle is captured; [Flow]
 *   cancellation (e.g. the SSE client disconnecting) cancels it via
 *   `awaitClose`.
 * - Callbacks never throw: a failed send means the collector is gone (flow
 *   cancelled or already closed), so the failure path just closes the flow —
 *   throwing into langchain4j's parser thread would be an unhandled escape.
 * - Parallel tool calls in one round are fragile upstream: openai4j flushes
 *   the shared `ToolCallBuilder` whenever the chunk `index` changes, so
 *   chunks must arrive index-sequential or calls get corrupted/dropped. See
 *   AGENTS.md (and `ChatTurnLoopTest`'s parallel-tool-call test) for the
 *   known upstream bug class and the investigation checklist.
 *
 * [toolSpecs] are only attached when non-empty: some providers
 * reject a request carrying an empty `tools` array.
 */
fun OpenAiStreamingChatModel.streamSignals(
    messages: List<ChatMessage>,
    toolSpecs: List<ToolSpecification> = emptyList(),
): Flow<StreamSignal> = channelFlow {
    // Note on channel flow: lc4j's callback has no access to the coroutine's scope/context,
    // so regular flow {} doesn't work since we can't emit without suspension.
    // Thus, we need the channel to build the flow, where we can trySend.
    // Note on runBlocking: The trySendBlocking will use runBlocking, but channel has a trySend,
    // the fast path will avoid that. If we use normal flow {}, then we will use runBlocking
    // on all paths, unavoidable.

    // first terminal signal wins; the racing second one is dropped
    val terminal = AtomicBoolean(false)
    // the handle can cancel the in-flight HTTP request; captured from the
    // context-carrying callbacks (the only place it is reachable) so
    // awaitClose can abort the request when the flow is cancelled
    val handleRef = AtomicReference<StreamingHandle?>(null)

    val handler = object : StreamingChatResponseHandler {
        override fun onPartialResponse(response: PartialResponse, context: PartialResponseContext) {
            handleRef.set(context.streamingHandle())
            val send = trySendBlocking(StreamSignal.TextDelta(response.text()))
            if (send.isFailure) {
                // cannot stream result, close flow
                close(send.exceptionOrNull())
                // cancel the streaming handle
                runCatching { context.streamingHandle().cancel() }
            }
        }

        override fun onPartialThinking(thinking: PartialThinking, context: PartialThinkingContext) {
            handleRef.set(context.streamingHandle())
            val send = trySendBlocking(StreamSignal.ThinkingDelta(thinking.text()))
            if (send.isFailure) {
                // cannot stream result, close flow
                close(send.exceptionOrNull())
                // cancel the streaming handle
                runCatching { context.streamingHandle().cancel() }
            }
        }

        override fun onPartialToolCall(call: PartialToolCall, context: PartialToolCallContext) {
            // the tool call id/name/args are emitted by onCompleteToolCall;
            // here we only get the handle for cancellation
            handleRef.set(context.streamingHandle())
        }

        override fun onCompleteToolCall(call: CompleteToolCall) {
            val request = call.toolExecutionRequest()
            val send = trySendBlocking(
                StreamSignal.ToolCallDone(request.name(), request.arguments())
            )
            if (send.isFailure) {
                // cannot stream result, close flow; the collector is gone,
                // so there is nothing to throw into the callback for
                close(send.exceptionOrNull())
            }
        }

        override fun onCompleteResponse(response: ChatResponse) {
            // first terminal signal wins; a racing onError is dropped
            if (terminal.compareAndSet(false, true)) {
                val send = trySendBlocking(StreamSignal.Completed(response))
                close(send.exceptionOrNull())
            }
        }

        override fun onError(error: Throwable) {
            // first terminal signal wins; a racing onCompleteResponse is dropped
            if (terminal.compareAndSet(false, true)) {
                val send = trySendBlocking(StreamSignal.Failed(error))
                close(send.exceptionOrNull())
            }
        }
    }

    try {
        chat(
            chatRequest {
                messages(messages)
                // some providers reject a request carrying an empty `tools`
                // array, so they are only attached when non-empty
                if (toolSpecs.isNotEmpty()) {
                    parameters {
                        this.toolSpecifications = toolSpecs
                    }
                }
            },
            handler
        )
    } catch (t: Throwable) {
        // synchronous failures (e.g. before any callback fires) surface as a
        // Failed signal too, so the caller handles one path only
        if (terminal.compareAndSet(false, true)) {
            val send = trySendBlocking(StreamSignal.Failed(t))
            // if failed to send signal, fail the flow builder
            send.getOrThrow()
            // otherwise just close the flow
            close()
        }
    }
    // cancelling the flow (e.g. the SSE client disconnected) aborts the
    // in-flight HTTP request via the captured handle
    awaitClose { handleRef.get()?.cancel() }
}

/**
 * Collect the streaming signals, forwarding deltas to [callback], returning
 * the final response, or rethrowing the failure.
 */
suspend fun Flow<StreamSignal>.collectSignals(callback: StreamingExecutionCallback): ChatResponse {
    var result: ChatResponse? = null
    collect { signal ->
        when (signal) {
            is StreamSignal.TextDelta -> callback.onTextDelta(signal.text)
            is StreamSignal.ThinkingDelta -> callback.onReasoningDelta(signal.text)
            is StreamSignal.ToolCallDone -> callback.onToolCall(signal.name, args = signal.args)
            is StreamSignal.Completed -> result = signal.response
            is StreamSignal.Failed -> throw signal.error
        }
    }
    return result ?: error("Streaming round ended without a terminal signal")
}
