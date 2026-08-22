package info.skyblond.daapu.hand

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.sse.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json

/**
 * The pure HTTP transport to the hand: wire format only, no run/callback
 * state. The agent layer talks to [HandService], which wraps this client
 * and owns the runId + in-flight tool-callback registration. Kotlin
 * owns all content decisions; the hand owns LLM execution.
 */
interface HandClient : AutoCloseable {
    /**
     * The chat round loop as a stream of [HandEvent]s, terminated by
     * exactly one of [HandEvent.Done]/[HandEvent.RunError]. A dropped or
     * failed connection before a terminal event is terminal — the stateless
     * hand cannot resume a dead run: an HTTP-level failure surfaces as
     * [HandRunException] (when the hand's error envelope is present) or
     * [HandUpstreamException]; a transport failure may propagate the raw
     * exception.
     */
    suspend fun run(request: HandRunRequest): Flow<HandEvent>

    /**
     * One `/v1/embed` call: a single OpenAI-compatible embedding request
     * (retries and the timeout are the hand's job, budgeted per request).
     * A non-2xx response carrying the hand's error envelope throws
     * [EmbeddingException] with the hand's type; a missing envelope throws
     * [HandUpstreamException]; a connection failure propagates the raw
     * transport exception ([HandService.embed] normalizes both to
     * `EmbeddingException("upstream")`).
     */
    suspend fun embed(request: HandEmbedRequest): HandEmbedResult
}

/**
 * HTTP implementation over ktor-client CIO with the SSE plugin. The SSE
 * auto-reconnect is disabled (`reconnectionTime = null`): a dropped hand
 * connection is terminal, never silently re-dialed.
 */
class HttpHandClient(
    private val baseUrl: String,
    private val token: String,
) : HandClient {

    private val logger = KotlinLogging.logger("HandClient")

    // mirrors the ChatCodec configuration: daapu messages must encode with
    // explicit defaults (e.g. `isError: false`), and tolerate extra fields
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    private val client = HttpClient(CIO) {
        expectSuccess = false
        engine {
            // the CIO engine caps every non-SSE request at `requestTimeout`
            // (default 15_000 ms) — that would kill slow `/v1/embed` calls
            // (e.g. a slow embedding gateway) long before the hand's own
            // per-attempt budget; 0 disables the cap. SSE requests are
            // exempt anyway, so the chat loop's stream is unaffected.
            requestTimeout = 0
        }
        // the SSE plugin is required for sseSession; reconnect stays off
        // (passed per request as `reconnectionTime = null`)
        install(SSE)
    }

    override suspend fun run(request: HandRunRequest): Flow<HandEvent> = flow {
        // capture the flow's emit so nested lambdas (with their own
        // receivers) can still send events
        val emitEvent: suspend (HandEvent) -> Unit = { emit(it) }
        var terminal = false
        // a failure to rethrow after the session ends — the finally block
        // must not mask it with the generic stream-closed error
        var failure: Exception? = null
        try {
            val session = client.sseSession(reconnectionTime = null) {
                url("$baseUrl/v1/run")
                method = HttpMethod.Post
                contentType(ContentType.Application.Json)
                header("x-daapu-token", token)
                setBody(json.encodeToString(HandRunRequest.serializer(), request))
            }
            session.incoming.collect { event ->
                val data = event.data ?: return@collect
                when (event.event) {
                    "text_delta" -> emitEvent(
                        HandEvent.TextDelta(
                            json.decodeFromString(HandTextDeltaPayload.serializer(), data).text
                        )
                    )

                    "reasoning_delta" -> emitEvent(
                        HandEvent.ReasoningDelta(
                            json.decodeFromString(HandTextDeltaPayload.serializer(), data).text
                        )
                    )

                    "assistant_message" -> emitEvent(
                        HandEvent.AssistantMessage(
                            json.decodeFromString(
                                HandAssistantMessagePayload.serializer(), data
                            ).message
                        )
                    )

                    "tool_call" -> {
                        val payload = json.decodeFromString(HandToolCallPayload.serializer(), data)
                        emitEvent(HandEvent.ToolCall(payload.id, payload.name, payload.args))
                    }

                    "tool_result" -> {
                        val payload =
                            json.decodeFromString(HandToolResultPayload.serializer(), data)
                        emitEvent(
                            HandEvent.ToolResult(
                                payload.id, payload.name, payload.parts, payload.isError
                            )
                        )
                    }

                    "retry" -> {
                        val payload = json.decodeFromString(HandRetryPayload.serializer(), data)
                        emitEvent(
                            HandEvent.Retry(
                                payload.attempt, payload.delayMs, payload.message
                            )
                        )
                    }

                    "done" -> {
                        // the hand closes the stream right after the
                        // terminal event; the loop's collector also stops
                        // on it, cancelling this flow and the session
                        val payload = json.decodeFromString(HandDonePayload.serializer(), data)
                        emitEvent(HandEvent.Done(payload.finishReason))
                        terminal = true
                    }

                    "error" -> {
                        val error = json.decodeFromString(HandError.serializer(), data)
                        emitEvent(HandEvent.RunError(error.type, error.message))
                        terminal = true
                    }

                    else -> logger.warn {
                        "Ignoring unknown SSE event '${event.event}' from the hand"
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure = if (e is SSEClientException) {
                val response = e.response
                val status = response?.status?.value ?: 0
                val text = response?.let { runCatching { it.bodyAsText() }.getOrDefault("") } ?: ""
                parseHandFailure(status, text)
            } else {
                e
            }
        } finally {
            val caught = failure
            if (caught != null && currentCoroutineContext().isActive) {
                throw caught
            }
            // the flow is cancelled on brain disconnect — don't mask that
            if (!terminal && currentCoroutineContext().isActive) {
                throw HandUpstreamException("hand run stream closed without a terminal event")
            }
        }
    }

    override suspend fun embed(request: HandEmbedRequest): HandEmbedResult {
        val response = client.post("$baseUrl/v1/embed") {
            header("x-daapu-token", token)
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(HandEmbedRequest.serializer(), request))
        }
        if (response.status.isSuccess()) {
            return json.decodeFromString(HandEmbedResult.serializer(), response.bodyAsText())
        }
        val error = runCatching {
            json.decodeFromString(HandErrorResponse.serializer(), response.bodyAsText()).error
        }.getOrNull()
        if (error != null) {
            throw EmbeddingException(error.type, error.message)
        }
        throw HandUpstreamException("hand embed request failed with HTTP ${response.status.value}")
    }

    override fun close() {
        client.close()
    }

    /** Maps a non-200 hand response onto a typed failure when it carries the hand's error shape. */
    private fun parseHandFailure(status: Int, text: String): Exception {
        val error = runCatching {
            json.decodeFromString(HandErrorResponse.serializer(), text).error
        }.getOrNull()
        if (error != null) {
            return HandRunException(error.type, error.message)
        }
        return HandUpstreamException("hand request failed with HTTP $status: ${text.take(200)}")
    }
}

/** The hand's `{ok:false,error:{...}}` failure envelope. */
@kotlinx.serialization.Serializable
private data class HandErrorResponse(
    val ok: Boolean,
    val error: HandError? = null,
)
