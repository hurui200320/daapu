package info.skyblond.daapu.agent

import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.exception.HttpException
import dev.langchain4j.model.chat.response.ChatResponse
import dev.langchain4j.model.output.FinishReason
import info.skyblond.daapu.langchain4j.toWireName
import kotlinx.coroutines.CancellationException

/**
 * Outcome of one streaming execution round, deciding how the turn loop routes
 * next.
 */
sealed interface StreamExecutionResult {
    /**
     * The stream produced a usable assistant message; the caller appends it
     * to the history and routes to tool execution or post-processing.
     */
    data class Completed(val response: ChatResponse) : StreamExecutionResult {
        val assistant: AiMessage get() = response.aiMessage()

        fun hasToolCall(): Boolean = assistant.hasToolExecutionRequests()
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
 * (see [StreamExecutionResult.EmptyTransient]). Also thrown by the turn loop
 * for a stream that ended with NO `finish_reason` (truncated, or an unknown
 * finish reason mapped to null) — the langchain4j equivalent of koog's
 * `requireEndFrame` failure, which the retry policy treats as transient.
 */
class EmptyStreamResponseException(message: String) : Exception(message)

/**
 * A mid-stream SSE `{"error": ...}` chunk was detected after the stream
 * completed, but the chunk carries no numeric `code` (e.g. a string code or
 * none at all). Matches the old koog client's treatment of code-less error
 * chunks: transient by nature, so the retry policy retries it.
 */
class MidStreamErrorChunkException(message: String) : Exception(message)

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
 * The model cannot handle content present in the prompt. This is a
 * deterministic failure: the same prompt with the same model fails
 * identically forever, so it is pinned as non-retryable in
 * [isRetryableStreamError].
 *
 * Thrown by `checkPromptContentCapabilities` (`langchain4j/ModelCapabilityCheck.kt`)
 * from the turn loop's pre-send step, before any LLM request is made.
 */
class ModelCapabilityException(message: String) : Exception(message)

/**
 * Classify a streamed chat response for turn-loop routing.
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
 * instead of retrying the identical prompt forever. (The turn loop's
 * truncation check runs before this and throws on a missing finish reason,
 * so the null branch here is defensive.)
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
    response: ChatResponse,
    contextLength: Long,
    maxOutputTokens: Long,
): StreamExecutionResult {
    val assistant = response.aiMessage()
    // first check the presence of the finish reason
    val finishReason = response.finishReason() ?: return StreamExecutionResult.EmptyTransient
    // then content
    val hasUsableOutput = !assistant.text().isNullOrBlank() || assistant.hasToolExecutionRequests()
    if (hasUsableOutput) return StreamExecutionResult.Completed(response)
    // check finish reason
    // a named reason other than "length" is definitive and must not be retried forever
    if (finishReason != FinishReason.LENGTH) return StreamExecutionResult.EmptyPermanent(finishReason.toWireName())
    val promptTokens = response.tokenUsage()?.inputTokenCount()
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
internal fun isRetryableStreamError(t: Throwable): Boolean {
    // the caller called cancel, never swallow it
    if (t is CancellationException) return false
    // deterministic guard failures like `check()`/`error()` and the pinned
    // run failures: the identical request would fail identically forever
    if (t is IllegalStateException ||
        t is OutputExhaustionException ||
        t is EmptyPermanentResponseException ||
        t is ModelCapabilityException
    ) return false

    // JVM errors (OOM, stack overflow): retrying would likely fail the same
    // way and impede GC recovery; crashing is more recoverable than an
    // infinite retry loop
    if (t is Error) return false

    // HTTP errors (dev.langchain4j.exception.HttpException) are retryable
    // unless they carry a permanent 4xx status — except 408 (timeout) and 429
    // (rate limited), which are transient. A null status (no HttpException in
    // the chain) means the failure happened before a response arrived or
    // mid-stream (e.g. a gateway's error chunk without a numeric code), which
    // is transient by nature.
    //
    // langchain4j's ExceptionMapper wraps HttpException inside typed
    // exceptions (AuthenticationException, RateLimitException, ...), and the
    // turn loop's mid-stream error-chunk scan throws HttpException directly:
    // the chain can look like RuntimeException → HttpException(403). Walk the
    // cause chain for the first non-2xx status: a 2xx is the stream's own
    // HTTP response status, never a meaningful error code.
    val statusCode = generateSequence(t) { it.cause }
        .filterIsInstance<HttpException>()
        .map { it.statusCode() }
        .firstOrNull { it !in 200..299 }
    return statusCode == null || statusCode !in 400..499 || statusCode == 408 || statusCode == 429
}
