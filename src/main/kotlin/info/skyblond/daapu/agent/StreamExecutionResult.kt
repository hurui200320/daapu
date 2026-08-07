package info.skyblond.daapu.agent

import ai.koog.agents.core.dsl.extension.ToolCalls
import ai.koog.http.client.KoogHttpClientException
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import kotlinx.coroutines.CancellationException

/**
 * Outcome of one streaming execution round, deciding how the strategy graph
 * routes next.
 */
sealed interface StreamExecutionResult {
    /**
     * The stream produced a usable assistant message; it has been appended to
     * the prompt. Route to tool execution or post-processing.
     */
    data class Completed(val assistant: Message.Assistant) : StreamExecutionResult {
        fun hasToolCall(): Boolean =
            assistant.parts.any { p -> p is MessagePart.Tool.Call }

        fun toToolCalls(): ToolCalls =
            ToolCalls(assistant.parts.filterIsInstance<MessagePart.Tool.Call>())
    }

    /**
     * The stream ended with `finish_reason == "length"` and no usable output,
     * and the prompt is large enough (input > context - output) that it is
     * crowding the context window. Shrinking the input frees output room, so
     * route to compaction, then retry.
     */
    data object ContextExhausted : StreamExecutionResult

    /**
     * The stream ended with `finish_reason == "length"` and no usable output,
     * but the prompt is at or below the threshold: the output cap bound on
     * its own (e.g. reasoning burned the whole output budget), so compaction
     * cannot help. Also used when the provider sent no usage data and we
     * cannot tell which limit bound. The run fails with
     * [OutputExhaustionException].
     */
    data object OutputBudgetExhausted : StreamExecutionResult

    /**
     * The stream completed cleanly but produced no usable output and gave no
     * reason for it (`finish_reason` missing). Treated as a transient gateway
     * hiccup: retry with backoff instead of routing anywhere.
     */
    data object EmptyTransient : StreamExecutionResult

    /**
     * The stream completed cleanly but produced no usable output, and the
     * provider gave a named reason other than `length` (e.g. `content_filter`,
     * or a deterministic empty `stop`). The provider ended the response
     * deliberately, so retrying the identical prompt would spin forever:
     * the run fails with [EmptyPermanentResponseException].
     */
    data class EmptyPermanent(val finishReason: String) : StreamExecutionResult
}

/**
 * The stream completed cleanly but yielded no usable assistant output
 * (see [StreamExecutionResult.EmptyTransient]).
 */
class EmptyStreamResponseException(message: String) : Exception(message)

/**
 * The model hit its output limit (`finish_reason == "length"`) without
 * producing anything usable, and the prompt is small enough that compacting
 * the history would not free up output room. The request is too hard for the
 * current output limit; it is up to the user how to solve it (different
 * model, bigger output limit, lower thinking budget, ...).
 */
class OutputExhaustionException(message: String) : Exception(message)

/**
 * The stream completed with a named `finish_reason` (see
 * [StreamExecutionResult.EmptyPermanent]) but no usable content. The provider
 * ended the response deliberately — e.g. a safety filter (`content_filter`)
 * or a deterministic empty `stop` — so retrying the identical prompt would
 * fail identically forever. It is up to the user how to solve it (rephrase
 * the message, change the model/provider, ...).
 */
class EmptyPermanentResponseException(message: String) : Exception(message)

/**
 * Classify a streamed assistant message for graph routing.
 *
 * A response only counts as usable if it carries non-blank user-visible
 * content or tool calls. A reasoning-only or blank-text message is NOT
 * usable: serialized into the next request it becomes an assistant message
 * with `content = null` or empty content, which strict providers reject with
 * a 400 — and once such a message is stored in the chat history, every
 * subsequent run of that chat fails the same way.
 *
 * An unusable response with NO finish reason is treated as a transient
 * gateway hiccup ([StreamExecutionResult.EmptyTransient]) and retried. A
 * named reason other than `length` is definitive — the provider ended the
 * response on purpose (e.g. `content_filter`, or a deterministic empty
 * `stop`) — so the run fails fast with [StreamExecutionResult.EmptyPermanent]
 * instead of retrying the identical prompt forever.
 *
 * `finish_reason == "length"` means the *output* budget ran out. Input and
 * output share the context window, so the effective output room is
 * `min(maxOutputTokens, contextLength - promptTokens)`. Compaction only helps
 * when the prompt is what crowds the output room, i.e.
 * `promptTokens > contextLength - maxOutputTokens`; at or below that
 * threshold the output cap bound on its own and retrying after compaction
 * would repeat the same result. When the provider sent no usage data we
 * cannot tell which limit bound, so we fail fast with
 * [StreamExecutionResult.OutputBudgetExhausted]: it breaks the retry loop
 * with a clear error instead of compacting blindly.
 */
fun classifyStreamResult(
    assistant: Message.Assistant,
    contextLength: Long,
    maxOutputTokens: Long,
): StreamExecutionResult {
    val hasUsableOutput = assistant.parts.any {
        (it is MessagePart.Text && it.text.isNotBlank()) || it is MessagePart.Tool.Call
    }
    if (hasUsableOutput) return StreamExecutionResult.Completed(assistant)
    // no usable output: a missing finish reason says nothing about why, so
    // treat it as a transient hiccup; a named reason other than "length" is
    // definitive and must not be retried forever
    val finishReason = assistant.finishReason ?: return StreamExecutionResult.EmptyTransient
    if (finishReason != "length") return StreamExecutionResult.EmptyPermanent(finishReason)
    val promptTokens = assistant.metaInfo.inputTokensCount
        ?: return StreamExecutionResult.OutputBudgetExhausted
    return if (promptTokens > contextLength - maxOutputTokens) {
        StreamExecutionResult.ContextExhausted
    } else {
        StreamExecutionResult.OutputBudgetExhausted
    }
}

/**
 * Decide whether a failure during a streaming round is worth retrying.
 */
internal fun isRetryableStreamError(t: Throwable): Boolean = when (t) {
    // the caller called cancel, never swallow it
    is CancellationException,
    // the output cap bound on its own
    // retrying the identical request would fail identically.
    is OutputExhaustionException,
    // the provider ended the response deliberately (e.g. content_filter):
    // the identical request would fail identically forever.
    is EmptyPermanentResponseException,
    // the model cannot handle content present in the prompt (e.g. images
    // with a text-only model, possibly from earlier history): the identical
    // request would fail identically forever.
    is ModelCapabilityException,
    // deterministic guard failures like `check()`/`error()`
    is IllegalStateException -> false

    // JVM errors (OOM, stack overflow): retrying would likely fail the same
    // way and impede GC recovery; crashing is more recoverable than an
    // infinite retry loop. koog's StreamFrameFlowBuilderError extends
    // Throwable directly, NOT Error, so it stays retryable via `else`.
    is Error -> false

    // HTTP errors ([KoogHttpClientException]) are retryable unless they carry a
    // permanent 4xx status — except 408 (timeout) and 429 (rate limited), which
    // are transient. A null status means the failure happened before a response
    // arrived or mid-stream (e.g. a gateway's error chunk), which is transient
    // by nature.
    //
    // koog's SSE wrapper re-wraps exceptions thrown while streaming, and ktor's
    // SSE plugin wraps them once more with the (successful) response: the chain
    // can look like KoogHttpClientException(200) → SSEClientException →
    // KoogHttpClientException(403) for a mid-stream error chunk carrying a
    // numeric `code` (thrown by CustomOpenAILLMClient). Walk the cause chain for
    // the first non-2xx status code: the 2xx is the HTTP response status of the
    // otherwise-successful stream, never a meaningful error code.
    is KoogHttpClientException -> {
        val statusCode = generateSequence(t as Throwable) { it.cause }
            .filterIsInstance<KoogHttpClientException>()
            .mapNotNull { it.statusCode }
            .firstOrNull { it !in 200..299 }
        statusCode == null || statusCode !in 400..499 || statusCode == 408 || statusCode == 429
    }

    // everything else
    else -> true
}
