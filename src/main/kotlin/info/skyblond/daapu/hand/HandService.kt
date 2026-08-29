package info.skyblond.daapu.hand

import info.skyblond.daapu.agent.chat.ChatMessage
import info.skyblond.daapu.agent.chat.ChatMessagePart
import info.skyblond.daapu.agent.chat.ChatMessageRole
import info.skyblond.daapu.agent.model.EmbeddingModel
import info.skyblond.daapu.agent.model.LLM
import info.skyblond.daapu.agent.tool.EmptyToolProvider
import info.skyblond.daapu.agent.tool.ToolProvider
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.*

/**
 * The brain's hand-facing service: the [HandClient] transport plus the
 * tool-callback wiring of [HandCallbackService]. The agent layer talks to
 * this single seam; the client itself stays a pure HTTP transport.
 *
 * `run` owns everything the tool callback needs:
 * - the `runId` is INTERNAL to the run/callback plumbing: it is generated
 *   here (unless the request already carries one) and the in-flight run is
 *   registered under it before the request goes out. The entry is evicted
 *   when the flow ends — success, failure, or brain-disconnect
 *   cancellation (the flow body's `finally` runs on cancellation).
 * - the tool URLs are attached on every TOOL-FUL run, keeping the callback
 *   wiring out of the agent layer: the tool-listing URL
 *   (`GET {toolListUrl}?runId=...`) is re-queried by the hand before EVERY
 *   LLM request, so the model always sees the provider's latest
 *   advertisements instead of a static list captured at request time, and
 *   the callback URL is where the hand POSTs each tool call for execution
 *   (only when a tool call actually needs executing).
 * - a tool-less run ([EmptyToolProvider] — the one-shot services) attaches
 *   NEITHER URL: the fields are sent as null and the JSON encoder omits
 *   nulls (`explicitNulls = false`), so they vanish from the wire and the
 *   hand performs no brain-side HTTP at all — no per-round tool-list GET,
 *   and no callback can ever fire. A tool-less run therefore needs no HTTP
 *   server next to it (scripts, one-shots). The flip side: a model that
 *   emits tool calls anyway cannot be answered (the hand has no callback to
 *   reach), so the hand fails such a run — with no tools advertised that is
 *   a model pathology, not a recoverable state.
 *
 * The runId carries no meaning to the chat loop: a fresh id is generated
 * per `/v1/run` call (reactive-compaction retries each get their own), and
 * the registry is only consulted by the hand's callback POSTs while a run
 * stream is open — the hand waits for each callback before continuing, so
 * no callback can legitimately arrive between runs.
 */
class HandService(
    private val hand: HandClient,
    private val handCallback: HandCallbackService,
    /** This brain's tool callback endpoint the hand POSTs to. */
    private val toolCallbackUrl: String,
    /** This brain's tool-listing endpoint the hand queries per LLM request. */
    private val toolListUrl: String,
) : AutoCloseable {
    /**
     * The chat round loop as a stream of [HandEvent]s (see
     * [HandClient.run]). The in-flight run is registered under the request's
     * runId (generated here when absent) before the request is sent and
     * evicted when the stream ends, so the hand's tool callbacks can always
     * resolve their provider and model.
     */
    suspend fun run(
        request: HandRunRequest,
        toolProvider: ToolProvider,
        model: LLM,
    ): Flow<HandEvent> = flow {
        val runId = request.runId ?: UUID.randomUUID().toString()
        // A tool-less run sends neither tool URL (see the class KDoc): the
        // hand skips its per-round tool-list GET and no callback can fire,
        // so the run needs no brain-side HTTP at all. Explicit URLs on a
        // tool-less request are contradictions and are dropped.
        val toolLess = toolProvider === EmptyToolProvider
        val prepared = request.copy(
            runId = runId,
            toolListUrl = if (toolLess) null else (request.toolListUrl ?: toolListUrl),
            toolCallbackUrl = if (toolLess) null else (request.toolCallbackUrl ?: toolCallbackUrl),
        )
        handCallback.register(runId, toolProvider, model)
        try {
            hand.run(prepared).collect { emit(it) }
        } finally {
            handCallback.unregister(runId)
        }
    }

    /**
     * Non-streaming variant of [run]: consumes the full run flow and returns
     * every [ChatMessage] it produced, in order — the per-round assistant
     * message followed by its tool results (the same reconstruction the chat
     * loop uses). The caller decides what to keep; the one-shot services
     * only look at the last message.
     *
     * The run ends on exactly one of:
     * - a `done` event: the collected messages are returned (by construction
     *   the last message is the final assistant `stop` message — tool-call
     *   rounds continue the loop, so a successful run never ends on a tool
     *   result);
     * - a `run_error` event: throws [HandRunException] with the hand's error
     *   type (including `round_limit`, `empty_response`, and `upstream` when
     *   the retries are exhausted);
     * - a dropped connection before a terminal event: throws
     *   [HandUpstreamException].
     */
    suspend fun runCollect(
        request: HandRunRequest,
        toolProvider: ToolProvider,
        model: LLM,
    ): List<ChatMessage> {
        val result = runCollectPartial(request, toolProvider, model)
        result.exception?.let { throw it }
        return result.result
    }

    /**
     * Like [runCollect], but a hand `run_error` does not throw: the messages
     * collected so far plus the terminal [HandRunException] come back in one
     * [HandRunResult], so a caller can recover a partial history (e.g. a
     * diagnostic action trace) from a failed run instead of losing it.
     * [runCollect] delegates here and rethrows the captured exception.
     *
     * A dropped connection before a terminal event still throws
     * [HandUpstreamException] — a dead transport carries no recoverable
     * partial state worth distinguishing, the caller treats it as terminal.
     */
    suspend fun runCollectPartial(
        request: HandRunRequest,
        toolProvider: ToolProvider,
        model: LLM,
    ): HandRunResult {
        val messages = mutableListOf<ChatMessage>()
        var terminal: HandEvent.Done? = null
        var error: HandRunException? = null
        run(request, toolProvider, model).collect { event ->
            when (event) {
                // per-round authoritative message; the deltas are dropped
                is HandEvent.AssistantMessage -> messages += event.message
                // paired with the assistant's tool_call parts by id: the args
                // already live in the call, so no extra lookup is needed
                is HandEvent.ToolResult -> messages += ChatMessage(
                    ChatMessageRole.ToolResult,
                    listOf(
                        ChatMessagePart.ToolResult(
                            id = event.id,
                            tool = event.name,
                            parts = event.parts,
                            isError = event.isError,
                        )
                    ),
                )

                is HandEvent.Done -> terminal = event
                is HandEvent.Retry -> logger.info { "one-shot retry: ${event.message}" }
                // RunError is terminal (the hand closes the stream on it), so
                // capturing it instead of throwing keeps the partial history;
                // the rest of the flow carries nothing more to collect
                is HandEvent.RunError -> error = HandRunException(event.type, event.message)
                // stream noise or display echoes: nothing to collect
                is HandEvent.TextDelta,
                is HandEvent.ReasoningDelta,
                is HandEvent.ToolCall -> Unit
            }
        }
        // defensive: [HandClient.run] already fails a stream that closes
        // without a terminal event, so this should not be reachable
        check(terminal != null || error != null) { "one-shot run ended without a terminal event" }
        return HandRunResult(messages.toList(), error)
    }

    /**
     * One `/v1/embed` call through the hand, with the caller's
     * [HandRunPolicy] supplied per call (the hand holds no defaults;
     * `policy.streamIdleTimeoutMs` doubles as the per-attempt embed timeout —
     * the same `hand.streamIdleTimeoutMs` knob that paces the runs).
     * Cancellation is rethrown untouched;
     * every transport-level failure (connection, missing error envelope,
     * unexpected protocol errors) is wrapped into
     * [EmbeddingException]("upstream") so callers see ONE exception family.
     * Fail-fast on a hand-reported [HandEmbedResult.dimensions] that
     * disagrees with the catalog entry's.
     */
    suspend fun embed(
        model: EmbeddingModel,
        input: List<String>,
        policy: HandRunPolicy,
    ): HandEmbedResult {
        // the checks live OUTSIDE the wrap: a drift is a catalog/gateway
        // bug (fail fast), not an upstream failure
        val result = try {
            hand.embed(
                HandEmbedRequest(
                    model = HandEmbedModelSpec(model.provider.baseUrl, model.provider.apiKey, model.modelId),
                    dimensions = model.dimensions,
                    input = input,
                    maxRetries = policy.maxRetries,
                    timeoutMs = policy.streamIdleTimeoutMs,
                    additionalProperties = model.additionalProperties,
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: EmbeddingException) {
            throw e
        } catch (e: Exception) {
            throw EmbeddingException("upstream", e.message ?: "embedding call failed", e)
        }
        check(result.vectors.size == input.size) {
            "Hand reported ${result.vectors.size} vectors for ${input.size} inputs " +
                    "(one vector per input is required)"
        }
        check(result.dimensions == model.dimensions) {
            "Hand-reported embedding dimensions ${result.dimensions} do not match " +
                    "catalog entry '${model.id}' (${model.dimensions})"
        }
        return result
    }

    /** Close the underlying hand HTTP client (see [HandClient.close]). */
    override fun close() {
        hand.close()
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
