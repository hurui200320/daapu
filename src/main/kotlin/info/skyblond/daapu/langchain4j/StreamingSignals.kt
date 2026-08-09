package info.skyblond.daapu.langchain4j

import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.chat.response.ChatResponse
import dev.langchain4j.model.chat.response.CompleteToolCall
import dev.langchain4j.model.chat.response.PartialResponse
import dev.langchain4j.model.chat.response.PartialResponseContext
import dev.langchain4j.model.chat.response.PartialThinking
import dev.langchain4j.model.chat.response.PartialThinkingContext
import dev.langchain4j.model.chat.response.PartialToolCall
import dev.langchain4j.model.chat.response.PartialToolCallContext
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler
import dev.langchain4j.model.chat.response.StreamingHandle
import dev.langchain4j.model.openai.OpenAiStreamingChatModel
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow

/**
 * One event of a streaming round, bridging langchain4j's callback-based
 * streaming to a coroutine [Flow] ([OpenAiStreamingChatModel.streamSignals]).
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
 * Bridge notes (from the #1/#2 spikes, all verified on langchain4j 1.18.1):
 * - `onError` and `onCompleteResponse` can BOTH fire for the same failure
 *   (parser `onError` + `onClose` race) — only the first terminal signal is
 *   emitted, the rest are dropped.
 * - A stream that ends with NO `finish_reason` completes normally (the
 *   [StreamSignal.Completed] signal carries `finishReason() == null`); the
 *   turn loop implements truncation detection itself.
 * - The `StreamingHandle` (which can cancel the in-flight HTTP request) is
 *   only reachable through the context-carrying callback variants, so those
 *   are overridden here and the handle is captured; [kotlinx.coroutines.flow.Flow]
 *   cancellation (e.g. the SSE client disconnecting) cancels it via
 *   `awaitClose`.
 * - Parallel tool calls in one round are fragile upstream: openai4j flushes
 *   the shared `ToolCallBuilder` whenever the chunk `index` changes, so
 *   chunks must arrive index-sequential or calls get corrupted/dropped. See
 *   AGENTS.md (and `ChatTurnLoopTest`'s parallel-tool-call test) for the
 *   known upstream bug class and the investigation checklist.
 *
 * [toolSpecifications] are only attached when non-empty: some providers
 * reject a request carrying an empty `tools` array.
 */
fun OpenAiStreamingChatModel.streamSignals(
    messages: List<ChatMessage>,
    toolSpecifications: List<ToolSpecification> = emptyList(),
): Flow<StreamSignal> = channelFlow {
    val terminal = AtomicBoolean(false)
    val handleRef = AtomicReference<StreamingHandle?>(null)

    val handler = object : StreamingChatResponseHandler {
        override fun onPartialResponse(response: PartialResponse, context: PartialResponseContext) {
            handleRef.set(context.streamingHandle())
            trySend(StreamSignal.TextDelta(response.text()))
        }

        override fun onPartialThinking(thinking: PartialThinking, context: PartialThinkingContext) {
            handleRef.set(context.streamingHandle())
            trySend(StreamSignal.ThinkingDelta(thinking.text()))
        }

        override fun onPartialToolCall(call: PartialToolCall, context: PartialToolCallContext) {
            // the tool call id/name/args are emitted by onCompleteToolCall;
            // here we only get the handle for cancellation
            handleRef.set(context.streamingHandle())
        }

        override fun onCompleteToolCall(call: CompleteToolCall) {
            val request = call.toolExecutionRequest()
            trySend(StreamSignal.ToolCallDone(request.name(), request.arguments()))
        }

        override fun onCompleteResponse(response: ChatResponse) {
            // first terminal signal wins; a racing onError is dropped
            if (terminal.compareAndSet(false, true)) {
                trySend(StreamSignal.Completed(response))
                close()
            }
        }

        override fun onError(error: Throwable) {
            // first terminal signal wins; a racing onCompleteResponse is dropped
            if (terminal.compareAndSet(false, true)) {
                trySend(StreamSignal.Failed(error))
                close()
            }
        }
    }

    try {
        chat(
            ChatRequest.builder()
                .messages(messages)
                .apply { if (toolSpecifications.isNotEmpty()) toolSpecifications(toolSpecifications) }
                .build(),
            handler,
        )
    } catch (t: Throwable) {
        // synchronous failures (e.g. before any callback fires) surface as a
        // Failed signal too, so the caller handles one path only
        if (terminal.compareAndSet(false, true)) {
            trySend(StreamSignal.Failed(t))
            close()
        }
    }

    awaitClose { handleRef.get()?.cancel() }
}
