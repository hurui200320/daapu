package info.skyblond.daapu.agent.lc4j.provider.client

import dev.langchain4j.http.client.HttpClient
import dev.langchain4j.http.client.HttpClientBuilder
import dev.langchain4j.http.client.HttpRequest
import dev.langchain4j.http.client.SuccessfulHttpResponse
import dev.langchain4j.http.client.sse.ServerSentEvent
import dev.langchain4j.http.client.sse.ServerSentEventContext
import dev.langchain4j.http.client.sse.ServerSentEventListener
import dev.langchain4j.http.client.sse.ServerSentEventParser
import java.time.Duration

/**
 * Rewrites the gateway's reasoning dialect in each SSE data line before
 * langchain4j parses it (the #1 spike's deferred Cerebras/bifrost reasoning
 * decorator).
 *
 * The bifrost gateway (and Cerebras directly) stream reasoning as
 * `delta.reasoning` — a plain text string — never `reasoning_content`.
 * langchain4j's `Delta` parser is hardcoded to `reasoning_content`, so the
 * whole thinking trace is silently dropped out of the box: no `thinking`
 * deltas, no `AiMessage.thinking()` in the final response. Rewriting the raw
 * `"reasoning":` field to `"reasoning_content":` in the SSE data lets the
 * stock parser accumulate `AiMessage.thinking()`, so reasoning streams live,
 * stays in stored history, and round-trips via `sendThinking` on later
 * requests.
 *
 * Only the exact string form `"reasoning":"` is rewritten, so object-valued
 * reasoning dialects (`"reasoning":{...}`) or already-correct
 * `reasoning_content` streams pass through untouched; the companion
 * `reasoning_details` array is left alone (ignored by the lenient parser).
 */
class ReasoningRewriteHttpClient(private val delegate: HttpClient) : HttpClient {

    override fun execute(request: HttpRequest): SuccessfulHttpResponse = delegate.execute(request)

    override fun execute(request: HttpRequest, parser: ServerSentEventParser, listener: ServerSentEventListener) {
        delegate.execute(request, parser, rewritingEvents(listener))
    }

    override fun execute(request: HttpRequest, listener: ServerSentEventListener) {
        delegate.execute(request, rewritingEvents(listener))
    }

    private fun rewritingEvents(delegate: ServerSentEventListener) = object : ServerSentEventListener {
        override fun onOpen(response: SuccessfulHttpResponse) {
            delegate.onOpen(response)
        }

        override fun onEvent(event: ServerSentEvent, context: ServerSentEventContext) {
            delegate.onEvent(event.rewriteReasoningDialect(), context)
        }

        override fun onEvent(event: ServerSentEvent) {
            delegate.onEvent(event.rewriteReasoningDialect())
        }

        override fun onError(error: Throwable) {
            delegate.onError(error)
        }

        override fun onClose() {
            delegate.onClose()
        }
    }

    private fun ServerSentEvent.rewriteReasoningDialect(): ServerSentEvent {
        val data = data()
        // keep the value-opening quote in the replacement: the match covers
        // `"reasoning":"` up to and including the value's opening quote
        val rewritten = REASONING_FIELD_REGEX.replaceFirst(data, "\"reasoning_content\":\"")
        return if (rewritten === data) this else ServerSentEvent(event(), rewritten)
    }

    private companion object {
        // matches `"reasoning":"` exactly — the string-form field name and
        // value-opening quote, so `"reasoning_details"` and object-valued
        // `"reasoning":{` are never touched
        val REASONING_FIELD_REGEX = Regex("\"reasoning\":\"")
    }

    /**
     * [HttpClientBuilder] that wraps the default JDK client in a
     * [ReasoningRewriteHttpClient]. Delegates the timeout knobs so the model
     * builder's `timeout(...)` keeps working (DefaultOpenAiClient calls
     * `connectTimeout`/`readTimeout` on this builder).
     */
    class Builder(private val delegate: HttpClientBuilder) : HttpClientBuilder {
        // nullable returns: the JDK builder's getters return null when unset, and
        // DefaultOpenAiClient calls them eagerly (Java evaluates both args of
        // getOrDefault), so a Kotlin non-null check here would crash
        override fun connectTimeout(): Duration? = delegate.connectTimeout()

        override fun connectTimeout(connectTimeout: Duration): HttpClientBuilder {
            delegate.connectTimeout(connectTimeout)
            return this
        }

        override fun readTimeout(): Duration? = delegate.readTimeout()

        override fun readTimeout(readTimeout: Duration): HttpClientBuilder {
            delegate.readTimeout(readTimeout)
            return this
        }

        override fun build(): HttpClient = ReasoningRewriteHttpClient(delegate.build())
    }
}
