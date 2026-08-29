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
    /**
     * The brain's tool-callback endpoint the hand POSTs each tool call to.
     * Required iff [toolListUrl] is present (an advertised tool may need
     * executing); omitted together with it on a tool-less run
     * ([HandService] sends neither URL for [info.skyblond.daapu.agent.tool.EmptyToolProvider]).
     */
    val toolCallbackUrl: String? = null,
    /** Round cap; 0 = unlimited. */
    val maxRounds: Int,
    /**
     * Total transient attempts per round (a `maxRetries` of 1 allows a
     * single attempt); 0 = unlimited. Mirrors [HandEmbedRequest]'s
     * `maxRetries` and `hand-pi/src/types.ts` — the hand retries a round
     * while `attempt < maxRetries`, so this caps the attempt count, not the
     * retry count.
     */
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
 * [additionalProperties] are extra root-level fields the hand merges into
 * the gateway request body ([EmbeddingModel.additionalProperties],
 * gateway-specific knobs like deepinfra's `service_tier`); omitted = no
 * extra fields. Keys must not collide with the hand-managed fields
 * (`model`, `input`, `dimensions`) — the hand rejects them as
 * `invalid_request`.
 */
@Serializable
data class HandEmbedRequest(
    val model: HandEmbedModelSpec,
    val dimensions: Int,
    val input: List<String>,
    /** Total attempts for transient failures (5xx/429/network/timeout); 0 = unlimited. */
    val maxRetries: Int,
    /** Per-attempt timeout in ms; 0 = disabled. */
    val timeoutMs: Long,
    /** Extra root-level fields merged into the `{baseUrl}/embeddings` request body. */
    val additionalProperties: JsonObject? = null,
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
