package info.skyblond.daapu.agent

import dev.langchain4j.exception.HttpException
import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the retry policy behind the turn loop in ChatTurnLoop: which failures
 * are retried with backoff (transient) and which fail the run (permanent). A
 * regression here either bricks a chat on a transient hiccup or spins forever
 * on a permanent error.
 */
class IsRetryableStreamErrorTest {

    private fun httpError(statusCode: Int) = HttpException(statusCode, "test error")

    private fun wrapped(statusCode: Int) = RuntimeException("Exception during streaming", httpError(statusCode))

    @Test
    fun `cancellation is not retryable`() {
        assertFalse(isRetryableStreamError(CancellationException("cancelled")))
    }

    @Test
    fun `output exhaustion is not retryable`() {
        // the output cap bound on its own: the identical request would
        // fail identically forever
        assertFalse(isRetryableStreamError(OutputExhaustionException("budget burned")))
    }

    @Test
    fun `deterministic guard failure is not retryable`() {
        // check()/error() guards would fail identically on a retry
        assertFalse(isRetryableStreamError(IllegalStateException("bad state")))
    }

    @Test
    fun `permanently empty response is not retryable`() {
        // a named finish reason (e.g. content_filter) means the provider
        // ended the response deliberately; the identical request would fail
        // identically forever
        assertFalse(isRetryableStreamError(EmptyPermanentResponseException("content_filter")))
    }

    @Test
    fun `model capability violation is not retryable`() {
        // the model cannot handle content present in the prompt (e.g. images
        // with a text-only model): the identical request would fail
        // identically forever
        assertFalse(isRetryableStreamError(ModelCapabilityException("model lacks vision")))
    }

    @Test
    fun `transient empty response is retryable`() {
        // an empty response with NO finish reason is treated as a gateway
        // hiccup and retried (also covers truncated streams, which surface
        // the same way in langchain4j: completion with finishReason == null)
        assertTrue(isRetryableStreamError(EmptyStreamResponseException("no content, no reason")))
    }

    @Test
    fun `jvm errors are not retryable`() {
        // an OOME/StackOverflowError would likely recur on retry and impede
        // GC recovery; crashing is more recoverable than an infinite retry
        // loop
        assertFalse(isRetryableStreamError(OutOfMemoryError("oom")))
        assertFalse(isRetryableStreamError(StackOverflowError("soe")))
    }

    @Test
    fun `permanent 4xx is not retryable`() {
        // config errors (except 408/429) fail the run
        listOf(400, 401, 403, 404, 422).forEach { status ->
            assertFalse(isRetryableStreamError(httpError(status)), "status $status should not be retryable")
        }
    }

    @Test
    fun `timeout and rate limit are retryable`() {
        assertTrue(isRetryableStreamError(httpError(408)))
        assertTrue(isRetryableStreamError(httpError(429)))
    }

    @Test
    fun `server errors are retryable`() {
        listOf(500, 502, 503).forEach { status ->
            assertTrue(isRetryableStreamError(httpError(status)), "status $status should be retryable")
        }
    }

    @Test
    fun `failure without an http status in the chain is retryable`() {
        // no HttpException at all: the failure happened before a response
        // arrived or mid-stream (e.g. a connection drop): transient by nature
        assertTrue(isRetryableStreamError(RuntimeException("connection reset")))
    }

    @Test
    fun `wrapped mid-stream error keeps its permanent 4xx classification`() {
        // langchain4j's ExceptionMapper wraps HttpException inside typed
        // exceptions (AuthenticationException, RateLimitException, ...), and
        // the turn loop's mid-stream error-chunk scan throws HttpException
        // directly: the chain can look like RuntimeException → HttpException.
        // The policy must walk the cause chain past the wrapper.
        listOf(400, 401, 403, 404, 422).forEach { status ->
            assertFalse(isRetryableStreamError(wrapped(status)), "status $status should not be retryable")
        }
    }

    @Test
    fun `wrapped mid-stream timeout and rate limit stay retryable`() {
        listOf(408, 429).forEach { status ->
            assertTrue(isRetryableStreamError(wrapped(status)), "status $status should be retryable")
        }
    }

    @Test
    fun `plain 2xx wrapped error is retryable`() {
        // only the stream's own 200 response status in the chain: a 2xx is
        // never a meaningful error code
        assertTrue(isRetryableStreamError(httpError(200)))
        assertTrue(isRetryableStreamError(wrapped(200)))
    }

    @Test
    fun `mid-stream error chunk without a numeric code is retryable`() {
        // an error chunk with no numeric code carries only the stream's own
        // 2xx response status, which is not an error code: transient by
        // nature, same as a failure without an HttpException
        assertTrue(isRetryableStreamError(MidStreamErrorChunkException("no code")))
    }

    @Test
    fun `illegal argument is retryable`() {
        // deliberate asymmetry with IllegalStateException: LLM output is
        // stochastic, so e.g. malformed tool-call argument JSON can parse
        // fine on a fresh attempt
        assertTrue(isRetryableStreamError(IllegalArgumentException("bad args")))
    }

    @Test
    fun `generic failure is retryable`() {
        assertTrue(isRetryableStreamError(RuntimeException("upstream hiccup")))
    }
}
