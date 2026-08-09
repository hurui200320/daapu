package info.skyblond.daapu.agent

import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.agent.tool.ToolSpecification

/**
 * The tool loop seam: which tools the model may call in one chat run.
 *
 * The turn loop advertises [specifications] in the request and executes
 * accepted tool calls via [execute]. [specifications] is suspend because a
 * real provider may need to connect to tool servers before the first use
 * (the MCP provider `mcp/McpToolProvider.kt` connects lazily); the turn loop
 * calls it from a coroutine context. [EmptyToolProvider] is the no-tools
 * fallback (used in tests): a model that emits tool calls anyway gets an
 * explicit error result back and can recover in the next round instead of
 * failing the run.
 */
interface ToolProvider {
    suspend fun specifications(): List<ToolSpecification>

    suspend fun execute(request: ToolExecutionRequest): ToolResultInfo
}

/**
 * No tools: nothing is advertised, and any tool call the model still emits
 * (fine-tuned models do) is answered with an error result so the loop can
 * continue.
 */
object EmptyToolProvider : ToolProvider {
    override suspend fun specifications(): List<ToolSpecification> = emptyList()

    override suspend fun execute(request: ToolExecutionRequest): ToolResultInfo =
        ToolResultInfo(
            id = request.id(),
            name = request.name(),
            content = "Error: tool '${request.name()}' is not available in this harness.",
            isError = true,
        )
}
