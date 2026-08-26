package info.skyblond.daapu.agent.persist

import info.skyblond.daapu.agent.chat.ChatMessagePart
import info.skyblond.daapu.agent.oneshot.investigate.InvestigatorService
import info.skyblond.daapu.agent.tool.ToolCallRequest
import info.skyblond.daapu.agent.tool.ToolProvider
import info.skyblond.daapu.agent.tool.ToolSpec
import info.skyblond.daapu.mcp.errorResult
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The main agent's ONLY access to the investigate sub-agent: the single
 * `gsg__investigate` tool (namespace `gsg`, one of
 * `TOOL_RESERVED_NAMESPACES`). The main model's tool set is the MCP servers
 * plus this provider — the granular ELTM read tools live in the
 * sub-agent's OWN tool set, not the loop's.
 *
 * `execute` suspends and delegates the whole tool round to
 * [InvestigatorService.runInvestigate] (the sub-agent's elastic tool loop
 * over the read-only ELTM plus the MCP tools; it recovers its own stops —
 * round limit, exhausted context, upstream failures — into an
 * [info.skyblond.daapu.agent.oneshot.investigate.InvestigateOutcome]).
 * The outcome's `report` mirrors a `ToolResult`'s `parts`, so it is
 * packaged verbatim without a lossy string round-trip, with the outcome's
 * `isError` flag passing through as the tool-result error. The execution
 * budget stays 0 (no timeout): the sub-agent has its own round cap and can
 * take minutes, and the hand waits until the brain answers either way.
 *
 * Errors: a missing/blank query answers an `isError` result (the model can
 * retry); an unexpected failure inside the investigate run answers an
 * `isError` result too — a thrown exception would bubble up to
 * `HandCallbackService` as a `fatal`, ending the MAIN hand run.
 */
class GsgToolProvider(
    private val investigator: InvestigatorService,
) : ToolProvider {

    override fun namespaces(): Set<String> = setOf("gsg")

    override suspend fun specifications(): List<ToolSpec> = listOf(
        ToolSpec(
            name = "gsg__investigate",
            description = "Investigate a query by launching a temporary sub-agent that searches the long-term memory (ELTM), the web (MCP tools) and other configured sources (for example, fs) in a multi-step tool loop, returning ONE self-contained report. " +
                    "Use this for anything needing memory recall or current web information; write a self-contained and specific query, which will be used as the first user message for the sub-agent. " +
                    "The current context is NOT shared with the sub-agent. You MUST provide everything (path of related files, or description of related contents, etc.) in your query.",
            schema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("query", buildJsonObject {
                        put("type", "string")
                        put("description", "The question or topic to investigate, self-contained and specific enough for a sub-agent without this conversation's context")
                    })
                })
                put("required", buildJsonArray { add("query") })
            },
        ),
    )

    override suspend fun execute(request: ToolCallRequest): ChatMessagePart.ToolResult {
        val query = request.args["query"]?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf { it.isNotBlank() }
        if (query == null) {
            return errorResult(request.id, request.name, "query is required and must not be blank")
        }
        return try {
            val outcome = investigator.runInvestigate(query)
            ChatMessagePart.ToolResult(
                id = request.id,
                tool = request.name,
                parts = outcome.report,
                isError = outcome.isError,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // never bubble a sub-agent failure into the main run's callback
            // (that would end the main hand run as `fatal`): the model sees
            // an error result it can react to instead
            errorResult(request.id, request.name, "investigate failed: ${e.message}")
        }
    }
}
