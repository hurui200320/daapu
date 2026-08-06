package info.skyblond.daapu.koog.client

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.http.client.HttpClientFactoryResolver
import ai.koog.http.client.KoogHttpClient
import ai.koog.http.client.KoogHttpClientException
import ai.koog.http.client.post
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.LLMClientException
import ai.koog.prompt.executor.clients.openai.OpenAIChatParams
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.base.OpenAICompatibleToolDescriptorSchemaGenerator
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.streaming.buildStreamFrameFlow
import ai.koog.prompt.streaming.requireEndFrame
import ai.koog.utils.time.KoogClock
import info.skyblond.daapu.koog.withGeneratedToolCallIds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.*

/**
 * Custom OpenAI chat-completions client that applies customized fixes.
 *
 * koog's [OpenAILLMClient] reads only `delta.content` and `delta.tool_calls`
 * from each SSE chunk, so reasoning fields used by gateways/providers
 * (`reasoning`, `reasoning_details`, `reasoning_content`) are silently dropped.
 * This subclass re-parses every chunk and forwards the thinking text as
 * [StreamFrame.ReasoningDelta] frames, keeping everything else identical to the
 * stock client.
 *
 * The same reasoning fields are also dropped by koog's typed decoder on the
 * non-streaming path (`OpenAIMessage.Assistant` only knows `reasoning_content`),
 * so [execute] and [executeMultipleChoices] are overridden to parse the raw
 * response body and reconstruct the assistant message (including the reasoning
 * as a `MessagePart.Reasoning`) by hand.
 */
class CustomOpenAILLMClient @JvmOverloads constructor(
    apiKey: String,
    settings: OpenAIClientSettings = OpenAIClientSettings(),
    httpClientFactory: KoogHttpClient.Factory = HttpClientFactoryResolver.resolve(),
    clock: KoogClock = KoogClock.System,
    toolsConverter: OpenAICompatibleToolDescriptorSchemaGenerator = OpenAICompatibleToolDescriptorSchemaGenerator(),
) : OpenAILLMClient(apiKey, settings, httpClientFactory, clock, toolsConverter) {

    private val chatCompletionsPath: String = settings.chatCompletionsPath

    override fun llmProvider(): LLMProvider = CustomLLMProvider

    override fun executeStreaming(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>
    ): Flow<StreamFrame> {
        require(prompt.params is OpenAIChatParams) {
            "Only OpenAIChatParams is supported in prompt (for Chat Completions API)"
        }

        model.requireCapability(LLMCapability.Completion)
        if (tools.isNotEmpty()) model.requireCapability(LLMCapability.Tools)

        val messages = convertPromptToMessages(prompt, model)
        val request = serializeProviderChatRequest(
            messages = messages,
            model = model,
            tools = tools.map { it.toOpenAIChatTool() },
            toolChoice = prompt.params.toolChoice?.toOpenAIToolChoice(),
            params = prompt.params,
            stream = true
        )

        // The flow is cold: the SSE request runs at collection time, so errors
        // (including KoogHttpClientException with its status code) propagate to
        // the collector unwrapped.
        return buildStreamFrameFlow {
            var finishReason: String? = null
            var metaInfo: ResponseMetaInfo? = null

            httpClient.sse(
                path = chatCompletionsPath,
                requestBody = request,
                requestBodyType = String::class,
                dataFilter = { it != "[DONE]" },
                decodeStreamingResponse = { line ->
                    json.parseToJsonElement(line).jsonObject
                },
                processStreamingChunk = { it }
            ).collect { chunk ->
                // Some gateways deliver errors as a mid-stream SSE data chunk
                // instead of an HTTP error status. Without this check the error
                // chunk is silently ignored and the failure looks like a clean,
                // empty completion.
                (chunk["error"] as? JsonObject)?.let { error ->
                    throw LLMClientException(
                        clientName,
                        "Mid-stream error from provider: ${error["message"].asStringOrNull() ?: error}"
                    )
                }
                val choice = (chunk["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
                if (choice != null) {
                    val index = choice["index"].asIntOrNull()
                    (choice["delta"] as? JsonObject)?.let { delta ->
                        delta.reasoningTextOrNull()?.let { text ->
                            emitReasoningDelta(text = text, index = index)
                        }
                        // skip empty deltas: OpenAI-style streams open with
                        // {"delta":{"role":"assistant","content":""}}, which
                        // would otherwise fabricate an empty text part
                        delta["content"].asStringOrNull()?.takeIf { it.isNotEmpty() }?.let { text ->
                            emitTextDelta(text, index)
                        }
                        (delta["tool_calls"] as? JsonArray)?.forEach { element ->
                            val toolCall = element as? JsonObject ?: return@forEach
                            val function = toolCall["function"] as? JsonObject
                            emitToolCallDelta(
                                id = toolCall["id"].asStringOrNull(),
                                name = function?.get("name").asStringOrNull(),
                                args = function?.get("arguments").asStringOrNull(),
                                index = toolCall["index"].asIntOrNull()
                            )
                        }
                    }
                    choice["finish_reason"].asStringOrNull()?.let { finishReason = it }
                }
                (chunk["usage"] as? JsonObject)?.let { usage ->
                    metaInfo = ResponseMetaInfo.create(
                        clock = clock,
                        totalTokensCount = usage["total_tokens"].asIntOrNull(),
                        inputTokensCount = usage["prompt_tokens"].asIntOrNull(),
                        outputTokensCount = usage["completion_tokens"].asIntOrNull(),
                    )
                }
            }

            // Only a stream that delivered finish_reason is complete. A
            // dropped connection can surface as a normal flow completion;
            // without an End frame. Downstream user can use requireEndFrame()
            // to check, or they accept the partial response.
            finishReason?.let { emitEnd(it, metaInfo) }
        }
    }

    /**
     * Non-streaming counterpart of [executeStreaming].
     *
     * koog's stock `execute` decodes the response into the typed
     * `OpenAIChatCompletionResponse`, which only models `reasoning_content` and
     * silently drops `reasoning` / `reasoning_details` (unknown keys). This
     * override parses the raw response body instead, so the reasoning is
     * captured with the same field precedence as the streaming path.
     */
    override suspend fun execute(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>
    ): Message.Assistant {
        require(prompt.params is OpenAIChatParams) {
            "Only OpenAIChatParams is supported in prompt (for Chat Completions API)"
        }
        model.requireCapability(LLMCapability.Completion)
        if (tools.isNotEmpty()) model.requireCapability(LLMCapability.Tools)

        return try {
            val response = postChatCompletions(prompt, model, tools)
            val choice = (response["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
                ?: throw LLMClientException(clientName, "Empty choices in response")
            choice.toAssistantMessage(response.toResponseMetaInfo())
        } catch (e: CancellationException) {
            throw e
        } catch (e: KoogHttpClientException) {
            // keep the status code so callers can tell permanent 4xx from
            // transient errors (e.g. the retry guard in Main.kt)
            throw e
        } catch (e: Exception) {
            throw LLMClientException(clientName, e.message, e)
        }
    }

    override suspend fun executeMultipleChoices(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>
    ): List<Message.Assistant> {
        require(prompt.params is OpenAIChatParams) {
            "Only OpenAIChatParams is supported in prompt (for Chat Completions API)"
        }
        model.requireCapability(LLMCapability.MultipleChoices)
        if (tools.isNotEmpty()) model.requireCapability(LLMCapability.Tools)

        return try {
            val response = postChatCompletions(prompt, model, tools)
            val choices = (response["choices"] as? JsonArray)
                ?: throw LLMClientException(clientName, "Empty choices in response")
            val metaInfo = response.toResponseMetaInfo()
            choices.map { choice ->
                val choiceObj = choice as? JsonObject
                    ?: throw LLMClientException(clientName, "Malformed choice in response")
                choiceObj.toAssistantMessage(metaInfo)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: KoogHttpClientException) {
            // keep the status code so callers can tell permanent 4xx from
            // transient errors (e.g. the retry guard in Main.kt)
            throw e
        } catch (e: Exception) {
            throw LLMClientException(clientName, e.message, e)
        }
    }

    private suspend fun postChatCompletions(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>
    ): JsonObject {
        val messages = convertPromptToMessages(prompt, model)
        val request = serializeProviderChatRequest(
            messages = messages,
            model = model,
            tools = tools.map { it.toOpenAIChatTool() },
            toolChoice = prompt.params.toolChoice?.toOpenAIToolChoice(),
            params = prompt.params,
            stream = false
        )
        return httpClient.post<String, String>(
            path = chatCompletionsPath,
            requestBody = request
        ).let { json.parseToJsonElement(it).jsonObject }
    }

    private fun JsonObject.toResponseMetaInfo(): ResponseMetaInfo = ResponseMetaInfo.create(
        clock = clock,
        totalTokensCount = (get("usage") as? JsonObject)?.get("total_tokens").asIntOrNull(),
        inputTokensCount = (get("usage") as? JsonObject)?.get("prompt_tokens").asIntOrNull(),
        outputTokensCount = (get("usage") as? JsonObject)?.get("completion_tokens").asIntOrNull(),
    )

    /**
     * Builds an assistant message from a raw `choices[]` entry, mirroring the
     * streaming path: content (string or text parts), reasoning (via
     * [reasoningTextOrNull]) and tool calls are extracted from the raw JSON.
     */
    private fun JsonObject.toAssistantMessage(metaInfo: ResponseMetaInfo): Message.Assistant {
        val message = get("message") as? JsonObject
            ?: throw LLMClientException(clientName, "No message in choice")

        val parts: List<MessagePart.ResponsePart> = buildList {
            when (val content = message["content"]) {
                is JsonPrimitive -> {
                    if (content.isString && content.content.isNotBlank()) {
                        add(MessagePart.Text(content.content))
                    }
                }

                is JsonArray -> {
                    content.forEach { element ->
                        val obj = element as? JsonObject ?: return@forEach
                        if (obj["type"].asStringOrNull() == "text") {
                            obj["text"].asStringOrNull()?.takeIf { it.isNotBlank() }
                                ?.let { add(MessagePart.Text(it)) }
                        }
                    }
                }

                else -> Unit
            }
            message.reasoningTextOrNull()?.let { add(MessagePart.Reasoning(content = listOf(it))) }
            (message["tool_calls"] as? JsonArray)?.forEach { element ->
                val toolCall = element as? JsonObject ?: return@forEach
                val function = toolCall["function"] as? JsonObject
                val name = function?.get("name").asStringOrNull()
                    ?: throw LLMClientException(clientName, "Malformed tool call: missing function name")
                val arguments = function?.get("arguments").asStringOrNull().orEmpty()
                    .takeIf { it.isNotEmpty() }
                    ?.let { json.parseToJsonElement(it).jsonObject }
                    ?: JsonObject(emptyMap())
                add(
                    MessagePart.Tool.Call(
                        id = toolCall["id"].asStringOrNull(),
                        tool = name,
                        args = arguments,
                    )
                )
            }
        }

        return Message.Assistant(
            parts = parts,
            metaInfo = metaInfo,
            finishReason = get("finish_reason").asStringOrNull(),
        ).withGeneratedToolCallIds()
    }

    /**
     * Extracts the thinking text from a delta chunk, in the order the fields
     * are produced by OpenAI-compatible gateways:
     * 1. `reasoning_details` — structured list of `reasoning.text` entries (bifrost)
     * 2. `reasoning` — plain text (bifrost)
     * 3. `reasoning_content` — plain text (Novita/DeepSeek style)
     *
     * Each chunk is treated as an *incremental delta* and appended verbatim to
     * the accumulated reasoning. This matches the streaming contract of the
     * OpenAI-compatible gateways we use, but a gateway that echoes the full
     * accumulated reasoning in every chunk would duplicate the output N times.
     *
     * Returns null when the chunk carries no (non-empty) reasoning.
     */
    private fun JsonObject.reasoningTextOrNull(): String? {
        (get("reasoning_details") as? JsonArray)?.let { details ->
            val text = details.mapNotNull { entry ->
                when (entry) {
                    is JsonPrimitive -> entry.takeIf { it.isString }?.content
                    else -> {
                        val obj = entry as? JsonObject ?: return@mapNotNull null
                        if (obj["type"].asStringOrNull() == "reasoning.text") {
                            obj["text"].asStringOrNull()
                        } else {
                            null
                        }
                    }
                }
            }.joinToString("")
            if (text.isNotEmpty()) return text
        }
        return get("reasoning").asStringOrNull()
            ?: get("reasoning_content").asStringOrNull()
    }

    private fun JsonElement?.asStringOrNull(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonElement?.asIntOrNull(): Int? =
        (this as? JsonPrimitive)?.intOrNull
}
