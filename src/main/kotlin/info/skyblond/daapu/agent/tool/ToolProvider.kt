package info.skyblond.daapu.agent.tool

import info.skyblond.daapu.agent.chat.ChatMessagePart
import kotlinx.serialization.json.JsonObject

/**
 * One tool advertisement in the neutral (framework-free) format: the name
 * the model sees on the wire, a human-readable description, and the raw
 * JSON schema (JSON Schema object) sent to the gateway.
 *
 * [timeoutSeconds] is the tool's execution budget (0 = no timeout),
 * REQUIRED on every advertised tool: the callback route enforces it with
 * `withTimeout` ([HandCallbackService]) and the hand waits a little longer
 * than it for the callback answer, so a timed-out tool always gets a result.
 */
data class ToolSpec(
    val name: String,
    val description: String,
    val schema: JsonObject,
    val timeoutSeconds: Long,
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
 * The brain advertises [specifications] in the hand run request and the
 * hand executes accepted tool calls back through the tool callback route
 * (`hand/HandCallbackService.kt`), which looks up the in-flight run's provider
 * by `runId` and calls [execute] — so this interface is what the callback
 * route executes against. [specifications] is suspend because a real
 * provider may need to reconnect to tool servers after a transport failure
 * (the MCP provider `mcp/McpToolProvider.kt` connects eagerly at
 * construction and reconnects in-turn on demand).
 *
 * [EmptyToolProvider] is the no-tools fallback: a model that emits tool
 * calls anyway gets an explicit error result back and can recover in the
 * next round instead of failing the run.
 */
interface ToolProvider {
    suspend fun specifications(): List<ToolSpec>

    suspend fun execute(request: ToolCallRequest): ChatMessagePart.ToolResult
}

/**
 * The tool's transport itself failed (e.g. the MCP server is unreachable):
 * the model cannot react to a dead transport, so the run fails instead of
 * producing an error tool-result. Thrown by [execute]; the hand callback
 * route maps it onto a `fatal` response, ending the hand run.
 */
open class ToolTransportException(message: String, cause: Throwable? = null) : Exception(message, cause)

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
