package info.skyblond.daapu.lc4j

import dev.langchain4j.http.client.HttpClient
import dev.langchain4j.http.client.HttpMethod
import dev.langchain4j.http.client.HttpRequest
import dev.langchain4j.http.client.SuccessfulHttpResponse
import dev.langchain4j.http.client.sse.ServerSentEvent
import dev.langchain4j.http.client.sse.ServerSentEventListener
import dev.langchain4j.http.client.sse.ServerSentEventParser
import info.skyblond.daapu.agent.lc4j.provider.client.ReasoningRewriteHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the reasoning-dialect rewrite (spike #1's deferred decorator): the
 * bifrost gateway streams reasoning as `delta.reasoning` (plain text), which
 * langchain4j's `Delta` parser silently drops — the rewrite to
 * `reasoning_content` makes the stock parser accumulate `AiMessage.thinking()`.
 */
class ReasoningRewriteHttpClientTest {

    private class CapturingHttpClient : HttpClient {
        var listener: ServerSentEventListener? = null
        var error: Throwable? = null

        override fun execute(request: HttpRequest): SuccessfulHttpResponse =
            error("sync execute not used in this test")

        override fun execute(request: HttpRequest, parser: ServerSentEventParser, listener: ServerSentEventListener) {
            this.listener = listener
        }

        override fun execute(request: HttpRequest, listener: ServerSentEventListener) {
            this.listener = listener
        }
    }

    private fun rewriteClient(delegate: CapturingHttpClient) = ReasoningRewriteHttpClient(delegate)

    private fun rewrittenData(data: String): String {
        val delegate = CapturingHttpClient()
        val received = mutableListOf<ServerSentEvent>()
        rewriteClient(delegate).execute(
            HttpRequest.builder().url("http://test").method(HttpMethod.POST).build(),
            ServerSentEventParser { _, _ -> error("not used") },
            object : ServerSentEventListener {
                override fun onEvent(event: ServerSentEvent) {
                    received += event
                }

                override fun onError(e: Throwable) {
                }
            },
        )
        delegate.listener!!.onEvent(ServerSentEvent("message", data))
        return received.single().data()
    }

    @Test
    fun `string reasoning field is rewritten to reasoning_content`() {
        val data = """{"choices":[{"delta":{"reasoning":"The","reasoning_details":[{"text":"The"}]}}]}"""
        val rewritten = rewrittenData(data)
        assertTrue(rewritten.contains("\"reasoning_content\":\"The\""), "missing rewrite: $rewritten")
        assertFalse(rewritten.contains("\"reasoning\":\""), "original field must be gone: $rewritten")
        // the companion details array passes through untouched
        assertTrue(rewritten.contains("\"reasoning_details\""), "details must survive: $rewritten")
    }

    @Test
    fun `object-valued reasoning passes through untouched`() {
        // a dialect where reasoning is an object, not a string, must not be
        // rewritten into an invalid reasoning_content value
        val data = """{"choices":[{"delta":{"reasoning":{"type":"structured"}}}]}"""
        val rewritten = rewrittenData(data)
        assertTrue(rewritten.contains("\"reasoning\":{"), "must be untouched: $rewritten")
        assertFalse(rewritten.contains("reasoning_content"), "must be untouched: $rewritten")
    }

    @Test
    fun `native reasoning_content passes through untouched`() {
        val data = """{"choices":[{"delta":{"reasoning_content":"think","content":"answer"}}]}"""
        assertEquals(data, rewrittenData(data))
    }

    @Test
    fun `non-reasoning chunks pass through unchanged`() {
        val data = """{"choices":[{"delta":{"content":"hi"}}]}"""
        assertEquals(data, rewrittenData(data))
    }

    @Test
    fun `event name survives the rewrite`() {
        val delegate = CapturingHttpClient()
        val received = mutableListOf<ServerSentEvent>()
        rewriteClient(delegate).execute(
            HttpRequest.builder().url("http://test").method(HttpMethod.POST).build(),
            ServerSentEventParser { _, _ -> error("not used") },
            object : ServerSentEventListener {
                override fun onEvent(event: ServerSentEvent) {
                    received += event
                }

                override fun onError(e: Throwable) {
                }
            },
        )
        delegate.listener!!.onEvent(ServerSentEvent("custom-event", """{"delta":{"reasoning":"x"}}"""))
        assertEquals("custom-event", received.single().event())
    }

    @Test
    fun `onError and onClose are forwarded`() {
        val delegate = CapturingHttpClient()
        var error: Throwable? = null
        var closed = false
        rewriteClient(delegate).execute(
            HttpRequest.builder().url("http://test").method(HttpMethod.POST).build(),
            ServerSentEventParser { _, _ -> error("not used") },
            object : ServerSentEventListener {
                override fun onError(e: Throwable) {
                    error = e
                }

                override fun onClose() {
                    closed = true
                }
            },
        )
        val failure = RuntimeException("boom")
        delegate.listener!!.onError(failure)
        delegate.listener!!.onClose()
        assertEquals(failure, error)
        assertTrue(closed)
    }
}
