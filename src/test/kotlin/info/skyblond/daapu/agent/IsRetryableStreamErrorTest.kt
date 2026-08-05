package info.skyblond.daapu.agent

import ai.koog.http.client.KoogHttpClientException
import ai.koog.prompt.streaming.IncompleteStreamException
import ai.koog.prompt.streaming.StreamFrameFlowBuilderError
import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the retry policy behind the streaming execution loop in Main: which
 * failures are retried with backoff (transient) and which fail the run
 * (permanent). A regression here either bricks a chat on a transient hiccup
 * or spins forever on a permanent error.
 */
class IsRetryableStreamErrorTest {

    private fun httpError(statusCode: Int?) =
        KoogHttpClientException(clientName = "test", statusCode = statusCode)

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
    fun `transient empty response is retryable`() {
        // an empty response with NO finish reason is treated as a gateway
        // hiccup and retried
        assertTrue(isRetryableStreamError(EmptyStreamResponseException("no content, no reason")))
    }

    @Test
    fun `jvm errors are not retryable`() {
        // an OOME/StackOverflowError would likely recur on retry and impede
        // GC recovery; crashing is more recoverable than an infinite retry
        // loop. Note koog's StreamFrameFlowBuilderError extends Throwable
        // directly, NOT Error, so it stays retryable (pinned above).
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
    fun `http error without status is retryable`() {
        // a null status means the failure happened before a response arrived
        // or mid-stream (e.g. a gateway's error chunk): transient by nature
        assertTrue(isRetryableStreamError(httpError(null)))
    }

    @Test
    fun `malformed tool call stream error is retryable`() {
        // koog throws these as Throwable (not Exception) when a gateway
        // streams malformed tool_call chunks
        assertTrue(isRetryableStreamError(StreamFrameFlowBuilderError.NoPartialToolCallToComplete()))
        assertTrue(isRetryableStreamError(StreamFrameFlowBuilderError.UnexpectedPartialToolCallIndex(0, 1)))
    }

    @Test
    fun `truncated stream is retryable`() {
        // a dropped connection can surface as a normal flow completion;
        // requireEndFrame flags it with this exception
        assertTrue(isRetryableStreamError(IncompleteStreamException()))
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
        assertTrue(isRetryableStreamError(RuntimeException("connection reset")))
    }
}
