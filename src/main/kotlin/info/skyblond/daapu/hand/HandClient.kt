package info.skyblond.daapu.hand

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.sse.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.github.oshai.kotlinlogging.KotlinLogging
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
     * exactly one of [HandEvent.Done]/[HandEvent.RunError]. A connection
     * failure before a terminal event throws [HandUpstreamException]: the
     * stateless hand cannot resume a dead run.
     */
    suspend fun run(request: HandRunRequest): Flow<HandEvent>
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
