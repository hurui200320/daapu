package info.skyblond.daapu.koog

import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import java.util.UUID

fun createModel(
    id: String,
    capabilities: List<LLMCapability>,
    contextLength: Long,
    maxOutputTokens: Long,
) = LLModel(
    // TODO: maybe should create our own provider?
    provider = LLMProvider.OpenAI,
    id = id,
    capabilities = capabilities,
    contextLength = contextLength,
    maxOutputTokens = maxOutputTokens,
)

/**
 * Give every tool call a stable id.
 *
 * Gateways that stream `tool_calls` without `id` fields produce
 * [MessagePart.Tool.Call] parts with `id == null`; some send `"id": ""` on
 * non-streaming responses. koog's request serializer then assigns
 * *independent* random UUIDs to the assistant's tool call and to the tool
 * result, so the `tool_call_id` never matches and strict providers reject
 * the next request with a 400. A blank id is as useless as a missing one for
 * matching, so both are replaced. Generating the id once up front makes
 * both sides agree.
 */
fun Message.Assistant.withGeneratedToolCallIds(): Message.Assistant {
    if (parts.none { it is MessagePart.Tool.Call && it.id.isNullOrBlank() }) return this
    return copy(parts = parts.map { part ->
        if (part is MessagePart.Tool.Call && part.id.isNullOrBlank()) {
            part.copy(id = "call_${UUID.randomUUID()}")
        } else {
            part
        }
    })
}