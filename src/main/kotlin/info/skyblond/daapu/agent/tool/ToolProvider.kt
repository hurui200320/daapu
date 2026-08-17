package info.skyblond.daapu.agent.tool

import info.skyblond.daapu.agent.chat.ChatMessagePart
import kotlinx.serialization.json.JsonObject

/**
 * One tool advertisement in the neutral (framework-free) format: the name
 * the model sees on the wire, a human-readable description, and the raw
 * JSON schema (JSON Schema object) sent to the gateway. A pure
 * advertisement — the execution budget is not part of it (the provider
 * declares it via [ToolProvider.executionTimeoutSeconds], and the hand
 * never sees it at all).
 */
data class ToolSpec(
    val name: String,
    val description: String,
    val schema: JsonObject,
)

/**
 * One tool call the model emitted, in the neutral format: the wire id
 * (non-blank), the advertised name, and the parsed argument object.
 */
data class ToolCallRequest(
    val id: String,
    val name: String,
    val args: JsonObject,
)

/**
 * The tool loop seam: which tools the model may call in one chat run.
 *
 * [specifications] is served to the hand through the brain's
 * `GET /api/hand/tools` endpoint (the in-flight run registry resolves the
 * provider by runId, `hand/HandCallbackService.kt`): the hand queries it
 * before EVERY LLM request and uses the returned list for that round, so
 * the model always sees the provider's latest advertisements. The hand
 * executes accepted tool calls back through the tool callback route
 * (`hand/HandCallbackRoute.kt`), which looks up the in-flight run's
 * provider by `runId` and calls [execute]. [specifications] is suspend
 * because a real provider may need to reconnect to tool servers after a
 * transport failure (the MCP provider `mcp/McpToolProvider.kt` connects
 * eagerly at construction and reconnects in-turn on demand).
 *
 * [EmptyToolProvider] is the no-tools fallback: a model that emits tool
 * calls anyway gets an explicit error result back and can recover in the
 * next round instead of failing the run.
 */
interface ToolProvider {
    suspend fun specifications(): List<ToolSpec>

    suspend fun execute(request: ToolCallRequest): ChatMessagePart.ToolResult

    /**
     * The execution budget in seconds for an advertised [toolName]
     * (0 = no timeout). The hand callback route enforces it with
     * `withTimeout` ([HandCallbackService]) and answers an `isError`
     * timeout result; unknown tool names answer 0 (execution itself will
     * produce an error result for them). The default is "no budget": a
     * provider that advertises a budget must override this.
     */
    fun executionTimeoutSeconds(toolName: String): Long = 0
}

/**
 * The tool's transport itself failed (e.g. the MCP server is unreachable):
 * the model cannot react to a dead transport, so the run fails instead of
 * producing an error tool-result. Thrown by [execute]; the hand callback
 * route maps it onto a `fatal` response, ending the hand run.
 */
open class ToolTransportException(
    message: String, cause: Throwable? = null
) : Exception(message, cause)

/**
 * The default empty tool provider.
 */
object EmptyToolProvider : ToolProvider {
    override suspend fun specifications(): List<ToolSpec> = emptyList()

    override suspend fun execute(request: ToolCallRequest): ChatMessagePart.ToolResult =
        ChatMessagePart.ToolResult(
            id = request.id,
            tool = request.name,
            parts = listOf(
                ChatMessagePart.Text("Error: tool '${request.name}' is not available in this harness.")
            ),
            isError = true,
        )
}

// TODO: multi tool provider? merge multiple tool provider together, adding namespace like <namespace>_<tool_name>
//       require a type that enforce the namespace adding behaviour.
