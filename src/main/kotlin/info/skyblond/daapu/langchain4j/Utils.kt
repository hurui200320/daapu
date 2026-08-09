package info.skyblond.daapu.langchain4j

import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.agent.tool.ToolExecutionRequest
import java.util.UUID

/**
 * Give every tool call a stable id.
 *
 * Gateways that stream `tool_calls` without `id` fields produce
 * [ToolExecutionRequest]s with `id == null`; some send `"id": ""` on
 * non-streaming responses. The next request then carries a `tool_call_id`
 * that never matches (or a blank one), and strict providers reject it with a
 * 400 — bricking the chat once the message is stored in history. Generating
 * the id once up front makes the call and its result agree.
 */
fun AiMessage.withGeneratedToolCallIds(): AiMessage {
    val requests = toolExecutionRequests()
    if (requests.none { it.id().isNullOrBlank() }) return this
    val normalized = requests.map { request ->
        if (request.id().isNullOrBlank()) {
            ToolExecutionRequest.builder()
                .id("call_${UUID.randomUUID()}")
                .name(request.name())
                .arguments(request.arguments())
                .build()
        } else {
            request
        }
    }
    return this.toBuilder()
        .toolExecutionRequests(normalized)
        .build()
}
