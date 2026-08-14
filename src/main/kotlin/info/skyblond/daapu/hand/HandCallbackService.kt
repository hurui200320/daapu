package info.skyblond.daapu.hand

import info.skyblond.daapu.agent.model.LLM
import info.skyblond.daapu.agent.tool.ToolCallRequest
import info.skyblond.daapu.agent.tool.ToolProvider
import info.skyblond.daapu.agent.tool.ToolTransportException
import info.skyblond.daapu.agent.chat.ChatMessagePart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** The tool-execution context of one in-flight run, looked up by `runId`. */
class ActiveRun(
    val toolProvider: ToolProvider,
    val model: LLM,
)

/**
 * The brain side of the hand's tool callback: the in-flight run registry
 * (keyed by the `runId` the hand's callbacks carry) plus the token check
 * and tool execution behind `POST /api/hand/tool` (see [HandCallbackRoute]).
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
     * ends, so stale ids don't accumulate.
     */
    fun register(runId: String, toolProvider: ToolProvider, model: LLM) {
        activeRuns[runId] = ActiveRun(toolProvider, model)
    }

    fun unregister(runId: String) {
        activeRuns.remove(runId)
    }

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
        val result = try {
            // TODO: the hand aborts its callback POST after its callback
            // timeout (default 120s), but this execution is not cancelled
            // with it: the tool keeps running (and its result is discarded
            // when the hand ends the run with tool_transport). Consider
            // tying the execution to a per-callback timeout/cancellation.
            withContext(Dispatchers.IO) {
                run.toolProvider.execute(ToolCallRequest(request.id, request.name, request.args))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ToolTransportException) {
            return HandToolCallbackResponse(fatal = HandToolCallbackFatal(e.message ?: "Tool transport failure"))
        } catch (e: Exception) {
            return HandToolCallbackResponse(fatal = HandToolCallbackFatal(e.message ?: "tool execution failed"))
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
