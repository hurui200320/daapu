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
 * (`server/endpoint/HandRoute.kt`), which looks up the in-flight run's
 * provider by `runId` and calls [execute]. [specifications] is suspend
 * because a real provider may need to reconnect to tool servers after a
 * transport failure (the MCP provider `mcp/McpToolProvider.kt` connects
 * eagerly at construction and reconnects in-turn on demand).
 *
 * [EmptyToolProvider] is the no-tools fallback: a run through
 * [info.skyblond.daapu.hand.HandService] sends neither tool URL for it, so
 * the hand makes no brain-side HTTP call at all (scripts and one-shots need
 * no HTTP server next to them). A model that emits tool calls anyway cannot
 * be answered there (the hand has no callback to reach) and the run fails;
 * [execute] still answers an explicit error result for any call that does
 * reach the provider (test doubles standing in for the hand's callback).
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

    /**
     * The namespaces this provider serves: every advertised tool name of a
     * namespaced provider is `"{namespace}__{toolName}"` (the namespace must
     * match `SAFE_ID_REGEX` and must not contain the `__` separator), and
     * [execute] only accepts those prefixed names. An empty set means the
     * provider advertises its tools unprefixed — the one-shot services'
     * shape ([EmptyToolProvider], the merge/ELTM tool providers in their
     * default form). [CombinedToolProvider] requires every child to serve a
     * non-blank namespace (fail fast at construction), so its routing on the
     * `__` separator stays unambiguous.
     */
    fun namespaces(): Set<String> = emptySet()
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
