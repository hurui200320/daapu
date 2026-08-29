package info.skyblond.daapu.hand

import info.skyblond.daapu.agent.chat.ChatMessagePart
import info.skyblond.daapu.agent.model.LLM
import info.skyblond.daapu.agent.tool.ToolCallRequest
import info.skyblond.daapu.agent.tool.ToolProvider
import info.skyblond.daapu.agent.tool.ToolSpec
import info.skyblond.daapu.agent.tool.ToolTransportException
import info.skyblond.daapu.agent.tool.errorResult
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/** The tool-execution context of one in-flight run, looked up by `runId`. */
class ActiveRun(
    val toolProvider: ToolProvider,
    val model: LLM,
)

/**
 * The brain side of the hand's tool callback: the in-flight run registry
 * (keyed by the `runId` the hand's callbacks carry) plus the token check
 * and tool execution behind `POST /api/hand/tool` (see `server/endpoint/HandRoute.kt`).
 *
 * Runs are registered before the hand run starts and evicted when it ends,
 * so stale ids don't accumulate.
 */
class HandCallbackService(
    private val handToken: String,
) {
    // in-flight runs, keyed by the runId the hand's tool callbacks carry;
    // registered before the hand run starts, evicted when it ends
    private val activeRuns = ConcurrentHashMap<String, ActiveRun>()

    /** The shared static token check for the hand's callback requests. */
    fun verifyToken(token: String?): Boolean = token != null && token == handToken

    /**
     * Register an in-flight run so the hand's tool callbacks can resolve
     * its tool provider and model. Evicted by [unregister] when the run
     * ends, so stale ids don't accumulate. A duplicate [runId] fails fast
     * instead of silently overriding the first run's provider/model (each
     * in-flight run must own its id; a sequential reuse after [unregister]
     * is fine).
     */
    fun register(runId: String, toolProvider: ToolProvider, model: LLM) {
        val previous = activeRuns.putIfAbsent(runId, ActiveRun(toolProvider, model))
        check(previous == null) { "A run with runId '$runId' is already registered" }
    }

    fun unregister(runId: String) {
        activeRuns.remove(runId)
    }

    /**
     * The in-flight run's current tool advertisements (the hand queries
     * this before EVERY LLM request, so a run always sees the provider's
     * latest tool set — MCP servers can change theirs at runtime). Returns
     * null for an unknown [runId] (the run already ended, or never
     * existed).
     *
     * A provider failure (e.g. an unreachable MCP server) propagates: the
     * route answers 500, which the hand maps onto `error{tool_transport}`
     * — the same semantics as a pre-request `specifications()` failure.
     */
    suspend fun listTools(runId: String): List<ToolSpec>? =
        activeRuns[runId]?.toolProvider?.specifications()

    /**
     * Execute one tool call for an in-flight run (the hand's callback
     * route). Tool-level failures come back as an `isError` result (the
     * model can react); transport-level failures (MCP unreachable) and
     * model capability mismatches on the result attachments answer
     * `fatal`, which ends the hand run with `tool_transport` — matching
     * today's per-round `LLM.checkPromptContentCapabilities` / MCP transport
     * failure semantics.
     */
    suspend fun executeToolCall(request: HandToolCallbackRequest): HandToolCallbackResponse {
        val run = activeRuns[request.runId]
            ?: return HandToolCallbackResponse(fatal = HandToolCallbackFatal("Unknown runId '${request.runId}'"))
        val budgetSeconds = run.toolProvider.executionTimeoutSeconds(request.name)
        val result = try {
            // The hand applies no deadline of its own — it waits for this
            // callback until the brain answers (or the client disconnects,
            // or the brain crashes and the connection drops), so the
            // execution MUST answer: `withTimeout` cancels an overrunning
            // tool instead of letting it keep running past the run's death
            // (its result would be discarded), and the timeout answers an
            // isError result the model can react to in the next round.
            // 0 = no timeout. The budget comes from the in-flight run's
            // provider (its REQUIRED config), never from the hand.
            withContext(Dispatchers.IO) {
                if (budgetSeconds > 0) {
                    withTimeout(budgetSeconds * 1_000L) {
                        run.toolProvider.execute(
                            ToolCallRequest(request.id, request.name, request.args)
                        )
                    }
                } else {
                    run.toolProvider.execute(
                        ToolCallRequest(request.id, request.name, request.args)
                    )
                }
            }
        } catch (e: TimeoutCancellationException) {
            // must precede the CancellationException rethrow: a timeout is a
            // cancellation, but one we answer with a model-visible error
            val result = errorResult(
                request.id, request.name,
                "tool '${request.name}' timed out after ${budgetSeconds}s",
            )
            return HandToolCallbackResponse(parts = result.parts, isError = result.isError)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ToolTransportException) {
            return HandToolCallbackResponse(
                fatal = HandToolCallbackFatal(
                    e.message ?: "Tool transport failure"
                )
            )
        } catch (e: Exception) {
            return HandToolCallbackResponse(
                fatal = HandToolCallbackFatal(
                    e.message ?: "tool execution failed"
                )
            )
        }
        // the model must be able to process the result's attachments
        // before the next round sends them (today's per-round capability
        // check equivalent for tool results)
        val unsupportedKind = result.parts
            .filterIsInstance<ChatMessagePart.Attachment>()
            .map { it.kind }
            .toSet()
            .firstOrNull { !run.model.supportAttachmentKind(it) }
        if (unsupportedKind != null) {
            return HandToolCallbackResponse(
                fatal = HandToolCallbackFatal(
                    "Model ${run.model.id} does not support ${unsupportedKind.name.lowercase()} " +
                            "content returned by tool '${result.tool}'"
                )
            )
        }
        return HandToolCallbackResponse(parts = result.parts, isError = result.isError)
    }
}
