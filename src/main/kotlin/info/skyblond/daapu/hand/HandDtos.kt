package info.skyblond.daapu.hand

import info.skyblond.daapu.agent.chat.ChatMessage
import info.skyblond.daapu.agent.chat.ChatMessagePart
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Wire DTOs of the hand-pi service (`hand-pi/`). The
 * message shape IS daapu's stored chat JSON ([ChatMessage], one schema
 * across DB, brain, and hand); the DTOs here add the hand's request/event
 * envelope.
 */

/** Per-request model description (the hand has no catalog). */
@Serializable
data class HandModelSpec(
    val baseUrl: String,
    val apiKey: String,
    val modelId: String,
    val contextWindow: Long,
    val maxOutputTokens: Long,
    val reasoning: Boolean,
    /** e.g. "high"; reasoning models only (omitted otherwise). */
    val reasoningEffort: String? = null,
    val input: List<String>,
)

@Serializable
data class HandToolSpec(
    val name: String,
    val description: String,
    val schema: JsonObject,
)

@Serializable
data class HandRunRequest(
    val model: HandModelSpec,
    val messages: List<ChatMessage>,
    val systemPrompt: String? = null,
    val maxTokens: Long,
    /**
     * The in-flight run's id, echoed back by the hand's tool callbacks.
     * INTERNAL to the run/callback plumbing: [HandService] generates it
     * when absent, so the chat loop never provides it. Always non-null on
     * the wire (the hand requires it).
     */
    val runId: String? = null,
    /**
     * The brain's tool-listing endpoint the hand queries BEFORE every LLM
     * request of the run (`GET {toolListUrl}?runId=...`, see
     * `server/endpoint/HandRoute.kt`): the tool set is no longer passed
     * statically, so a run always works with the provider's latest
     * advertisements (MCP servers can change theirs at runtime). The
     * response feeds the LLM request's `tools` only — execution budgets
     * are a brain-side concern and never leave it. Omitted = no tools at
     * all.
     */
    val toolListUrl: String? = null,
    val toolCallbackUrl: String? = null,
    /** Round cap; 0 = unlimited. */
    val maxRounds: Int,
    /** Transient retries per round; 0 = unlimited. */
    val maxRetries: Int,
    /** Idle timeout per streamed round in ms; 0 = disabled. */
    val streamIdleTimeoutMs: Long,
)

@Serializable
data class HandError(
    val type: String,
    val message: String,
)

/** The hand's per-request embedding model description (the hand has no catalog). */
@Serializable
data class HandEmbedModelSpec(
    val baseUrl: String,
    val apiKey: String,
    val modelId: String,
)

/**
 * The `/v1/embed` request (see `hand-pi/`): one OpenAI-compatible embedding
 * call, fully described per request — the hand holds no defaults, mirroring
 * [HandRunRequest]. [maxRetries] (0 = unlimited) and [timeoutMs] (0 =
 * disabled) are the caller's per-call budget, passed through as-is.
 * [dimensions] is the output size the catalog entry pins; the hand sends it
 * to the gateway and the caller verifies the response against it.
 */
@Serializable
data class HandEmbedRequest(
    val model: HandEmbedModelSpec,
    val dimensions: Int,
    val input: List<String>,
    /** Transient retries (5xx/429/network/timeout); 0 = unlimited. */
    val maxRetries: Int,
    /** Per-attempt timeout in ms; 0 = disabled. */
    val timeoutMs: Long,
)

@Serializable
data class HandEmbedUsage(
    val promptTokens: Int,
    val totalTokens: Int,
)

@Serializable
data class HandEmbedResult(
    /** One vector per input item, in order. */
    val vectors: List<List<Float>>,
    /** `vectors[0].size`. */
    val dimensions: Int,
    /** Passed through when the provider reports it; omitted otherwise. */
    val usage: HandEmbedUsage? = null,
)

/** SSE events the hand emits during a `/v1/run` stream. */
sealed interface HandEvent {
    data class TextDelta(val text: String) : HandEvent

    data class ReasoningDelta(val text: String) : HandEvent

    /** Per round: the authoritative assembled assistant message. */
    data class AssistantMessage(val message: ChatMessage) : HandEvent

    /** Display echo, pre-execution. */
    data class ToolCall(val id: String, val name: String, val args: JsonObject) : HandEvent

    /** Echo of the tool callback response (executed by the hand via HTTP). */
    data class ToolResult(
        val id: String,
        val name: String,
        val parts: List<ChatMessagePart.ContentPart>,
        val isError: Boolean,
    ) : HandEvent

    data class Retry(val attempt: Int, val delayMs: Long, val message: String) : HandEvent

    data class Done(val finishReason: String) : HandEvent

    /** Terminal; exactly one of [Done]/[RunError] closes a run. */
    data class RunError(val type: String, val message: String) : HandEvent
}

// the hand's named SSE event payloads (decode targets for [HandEvent]s)

@Serializable
data class HandTextDeltaPayload(val text: String)

@Serializable
data class HandAssistantMessagePayload(val message: ChatMessage)

@Serializable
data class HandToolCallPayload(val id: String, val name: String, val args: JsonObject)

@Serializable
data class HandToolResultPayload(
    val id: String,
    val name: String,
    val parts: List<ChatMessagePart.ContentPart>,
    val isError: Boolean,
)

@Serializable
data class HandRetryPayload(val attempt: Int, val delayMs: Long, val message: String)

@Serializable
data class HandDonePayload(val finishReason: String)

/** The tool callback POST body (hand → brain, `server/endpoint/HandRoute.kt`). */
@Serializable
data class HandToolCallbackRequest(
    val runId: String,
    val id: String,
    val name: String,
    val args: JsonObject,
)

@Serializable
data class HandToolCallbackFatal(val message: String)

/**
 * The tool callback response body: either executed parts (`isError` marks a
 * tool-level failure) or a transport-level `fatal`, which ends the run.
 * `isError` is always encoded: the wire contract carries the explicit
 * value, and the ktor ContentNegotiation codec omits defaults otherwise.
 */
@Serializable
data class HandToolCallbackResponse(
    val parts: List<ChatMessagePart>? = null,
    @kotlinx.serialization.EncodeDefault
    val isError: Boolean = false,
    @SerialName("fatal") val fatal: HandToolCallbackFatal? = null,
)

/**
 * The tool-listing response body (hand → `GET /api/hand/tools`, see
 * `server/endpoint/HandRoute.kt`): the in-flight run's provider
 * advertisements, resolved per LLM request by the hand.
 */
@Serializable
data class HandToolListResponse(
    val tools: List<HandToolSpec>,
)

/**
 * A hand run failed terminally (the hand's `error` event). The `type` is
 * the hand's [HandError.type] taxonomy (`upstream`, `context_exhausted`,
 * `output_budget_exhausted`, `content_filter`, `tool_transport`,
 * `round_limit`, `internal`, ...).
 */
class HandRunException(val type: String, message: String) : Exception(message)

/**
 * The outcome of a one-shot run that keeps its partial history on failure
 * ([HandService.runCollectPartial]): the collected messages plus the
 * terminal [HandRunException] when the run ended on a hand `error` event
 * (null on success). A dropped connection before a terminal event is NOT
 * captured here — it throws [HandUpstreamException] like the plain run.
 */
class HandRunResult(
    val result: List<ChatMessage>,
    val exception: HandRunException? = null,
)

/**
 * The hand could not serve the request at all (connection failure, HTTP
 * error response, dropped stream without a terminal event). A hand
 * connection drop is terminal: the stateless hand cannot resume a dead run.
 */
class HandUpstreamException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * A `/v1/embed` call failed (the hand's `{ok:false,error:{...}}` envelope
 * parsed by the transport, or a transport-level failure wrapped by
 * [HandService.embed]). The [type] is the hand's taxonomy restricted to the
 * embed endpoint: `auth` (bad api key), `invalid_request` (the gateway
 * rejected the input — the too-large channel the ELTM tool layer maps to
 * "split it into smaller entries"), `upstream` (transient provider
 * failures, already retried by the hand against its budget).
 */
class EmbeddingException(val type: String, message: String, cause: Throwable? = null) : Exception(message, cause)
