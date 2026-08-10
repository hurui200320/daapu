package info.skyblond.daapu.agent.lc4j.executor

import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.exception.HttpException
import dev.langchain4j.model.chat.response.ChatResponse
import dev.langchain4j.model.openai.OpenAiChatResponseMetadata
import dev.langchain4j.model.openai.OpenAiStreamingChatModel
import dev.langchain4j.model.output.FinishReason
import info.skyblond.daapu.agent.executor.StreamingExecutionCallback
import info.skyblond.daapu.agent.executor.StreamingExecutionResult
import info.skyblond.daapu.agent.executor.StreamingExecutor
import info.skyblond.daapu.agent.lc4j.chat.toLc4jMessages
import info.skyblond.daapu.agent.lc4j.chat.toNeutralAssistantMessage
import info.skyblond.daapu.agent.lc4j.chat.toWireName
import info.skyblond.daapu.agent.lc4j.tool.ToolProvider
import info.skyblond.daapu.chat.ChatMessage
import kotlinx.serialization.json.*
import java.util.*

class Lc4jStreamingExecutor : StreamingExecutor {

    override suspend fun executeOnce(
        model: OpenAiStreamingChatModel,
        modelContextLength: Long,
        modelMaxOutputTokens: Long,
        chat: List<ChatMessage>,
        toolProvider: ToolProvider,
        callback: StreamingExecutionCallback
    ): StreamingExecutionResult {
        val response = model
            .streamSignals(chat.toLc4jMessages(), toolProvider.specifications())
            .collectSignals(callback)
        return classifyStreamResult(response, modelContextLength, modelMaxOutputTokens)
    }
}

/**
 * Classify one completed streaming round into a [StreamingExecutionResult].
 *
 * Order matters:
 * 1. The mid-stream SSE `{"error": ...}` chunk scan runs first: without it a
 *    rejected response (e.g. a moderation 403 delivered as a chunk after a
 *    2xx) would look usable (non-blank text, no finish reason) and be
 *    accepted. A numeric `code` becomes `dev.langchain4j.exception.HttpException(code, data)`,
 *    a code-less chunk `MidStreamErrorChunkException`.
 * 2. A stream that ended with NO `finish_reason` is truncated (langchain4j
 *    has no `requireEndFrame` equivalent): `EmptyTransient`, the only
 *    retryable outcome.
 * 3. Named reasons classify the response; only `stop` with usable content or
 *    tool calls is accepted.
 */
internal fun classifyStreamResult(
    response: ChatResponse,
    contextLength: Long,
    maxOutputTokens: Long,
): StreamingExecutionResult {
    // after we finished the streaming call, first check error code in sse chunks
    response.findErrorChunk()?.let { (code, data) ->
        throw if (code != null) {
            HttpException(code, data)
        } else {
            MidStreamErrorChunkException("Gateway sent a mid-stream error chunk: $data")
        }
    }

    // then check finish reason
    if (response.finishReason() == null) {
        // if no reason, considered as empty transient
        return StreamingExecutionResult.EmptyTransient
    }

    val assistant = response.aiMessage().withGeneratedToolCallIds()
    when (response.finishReason()) {
        FinishReason.STOP -> {
            return if ( // some provider may send stop for tool calls
                !assistant.text().isNullOrBlank() || assistant.hasToolExecutionRequests()
            ) {
                StreamingExecutionResult.Completed(
                    response.toNeutralAssistantMessage(assistant),
                    if (!assistant.hasToolExecutionRequests()) emptyList()
                    else assistant.toolExecutionRequests()
                )
            } else {
                StreamingExecutionResult.EmptyPermanent(
                    response.finishReason().toWireName()
                )
            }
        }

        FinishReason.LENGTH -> {
            // a truncated answer is never accepted, even with partial text
            // (a chat must end with a clean stop, see ChatCodec.validateChat)
            // if no input token count, considered as output exhaustion. which cannot be retried
            val inputTokens = response.tokenUsage()?.inputTokenCount()
                ?: return StreamingExecutionResult.OutputBudgetExhausted

            return if (inputTokens > contextLength - maxOutputTokens) {
                StreamingExecutionResult.ContextExhausted
            } else {
                StreamingExecutionResult.OutputBudgetExhausted
            }
        }

        FinishReason.TOOL_EXECUTION -> {
            return if (assistant.hasToolExecutionRequests()) {
                StreamingExecutionResult.Completed(
                    response.toNeutralAssistantMessage(assistant),
                    assistant.toolExecutionRequests()
                )
            } else {
                StreamingExecutionResult.EmptyPermanent(
                    response.finishReason().toWireName()
                )
            }
        }

        else -> {
            // finish reason is not normal stop or length, and is not tool call,
            // then might due to content filter or something that cannot be retried.
            return StreamingExecutionResult.EmptyPermanent(
                response.finishReason().toWireName()
            )
        }
    }
}

/**
 * Mid-stream SSE error chunk detection.
 *
 * Some gateways (OpenRouter-style, e.g. moderation rejections mapped to 403)
 * deliver errors as a mid-stream SSE `{"error": {"code": 403, ...}}` chunk
 * after a 2xx response instead of an HTTP error status. langchain4j's SSE
 * layer neither swallows the chunk nor throws: the stream **completes
 * normally** with `finishReason() == null` and no error, while the raw chunk
 * is retained verbatim in `OpenAiChatResponseMetadata.rawServerSentEvents()`.
 *
 * Returns the numeric `code` (when the chunk carries one) plus the raw chunk
 * data; `code == null` means the chunk carries no numeric code (e.g., a string
 * message or none at all).
 */
internal fun ChatResponse.findErrorChunk(): Pair<Int?, String>? {
    val metadata = metadata() as? OpenAiChatResponseMetadata ?: return null
    for (event in metadata.rawServerSentEvents()) {
        val root = runCatching { Json.parseToJsonElement(event.data()).jsonObject }.getOrNull()
            ?: continue
        val error = root["error"] as? JsonObject ?: continue
        val code = (error["code"] as? JsonPrimitive)?.intOrNull
        return code to event.data()
    }
    return null
}

/**
 * Give every tool call a stable id.
 *
 * Gateways that stream `tool_calls` without `id` fields produce
 * [ToolExecutionRequest]s with `id == null`; some send `"id": ""` on
 * non-streaming responses. The next request then carries a `tool_call_id`
 * that never matches (or a blank one), and strict providers reject it with a
 * 400, bricking the chat once the message is stored in history. Generating
 * the id once up front makes the call and its result agree.
 */
internal fun AiMessage.withGeneratedToolCallIds(): AiMessage {
    val requests = toolExecutionRequests()
    if (requests.none { it.id().isNullOrBlank() }) return this
    val normalized = requests.map { request ->
        if (request.id().isNullOrBlank()) {
            request.toBuilder()
                .id("call_${System.currentTimeMillis()}_${UUID.randomUUID()}")
                .build()
        } else {
            request
        }
    }
    return this.toBuilder()
        .toolExecutionRequests(normalized)
        .build()
}
