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
    /**
     * The tool's execution budget in seconds (0 = no timeout). REQUIRED on
     * every advertised tool: the hand waits `timeoutSeconds + 30s` for the
     * callback POST, and the callback route enforces the budget itself
     * ([HandCallbackService]), so a timed-out tool always answers the hand.
     */
    val timeoutSeconds: Long,
)

@Serializable
data class HandCompleteRequest(
    val model: HandModelSpec,
    val messages: List<ChatMessage>,
    /**
     * The system prompt for this call. Kept out of [messages] (there is no
     * system role) and never stored in the chat: the caller renders it per
     * call, so identical text hits the provider cache.
     */
    val systemPrompt: String? = null,
    val tools: List<HandToolSpec>? = null,
    /** The output budget for this call; always explicit (no hand-side default). */
    val maxTokens: Long,
)

@Serializable
data class HandRunRequest(
    val model: HandModelSpec,
    val messages: List<ChatMessage>,
    val systemPrompt: String? = null,
    val tools: List<HandToolSpec>? = null,
    val maxTokens: Long,
    /**
     * The in-flight run's id, echoed back by the hand's tool callbacks.
     * INTERNAL to the run/callback plumbing: [HandService] generates it
     * when absent, so the chat loop never provides it. Always non-null on
     * the wire (the hand requires it).
     */
    val runId: String? = null,
    val toolCallbackUrl: String? = null,
    /** Round cap; 0 = unlimited. */
    val maxRounds: Int,
    /** Transient retries per round; 0 = unlimited. */
    val maxRetries: Int,
    /** Idle timeout per streamed round in ms; 0 = disabled. */
    val streamIdleTimeoutMs: Long,
    /** Tool callback POST timeout in ms; 0 = no timeout. */
    val callbackTimeoutMs: Long,
)

@Serializable
data class HandError(
    val type: String,
    val message: String,
)

@Serializable
data class HandCompleteResponse(
    val ok: Boolean,
    val message: ChatMessage? = null,
    val finishReason: String? = null,
    val error: HandError? = null,
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

/** The tool callback POST body (hand → brain, `hand/HandCallbackRoute.kt`). */
@Serializable
data class HandToolCallbackRequest(
    val runId: String,
    val id: String,
    val name: String,
    val args: JsonObject,
    /**
     * The advertised tool's execution budget, echoed back by the hand: the
     * callback route enforces it with `withTimeout` (0 = no timeout), so the
     * hand — which waits `timeoutSeconds + 30s` — always receives an answer.
     */
    val timeoutSeconds: Long,
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
 * A hand run failed terminally (the hand's `error` event). The `type` is
 * the hand's [HandError.type] taxonomy (`upstream`, `context_exhausted`,
 * `output_budget_exhausted`, `content_filter`, `tool_transport`,
 * `round_limit`, `internal`, ...).
 */
class HandRunException(val type: String, message: String) : Exception(message)

/**
 * The hand could not serve the request at all (connection failure, HTTP
 * error response, dropped stream without a terminal event). A hand
 * connection drop is terminal: the stateless hand cannot resume a dead run.
 */
class HandUpstreamException(message: String, cause: Throwable? = null) : Exception(message, cause)
