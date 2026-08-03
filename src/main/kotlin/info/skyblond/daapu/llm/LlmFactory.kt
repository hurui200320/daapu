package info.skyblond.daapu.llm

import ai.koog.http.client.HttpClientFactoryResolver
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel

/**
 * Build the koog [PromptExecutor] and [LLModel] from the configured LLM
 * provider settings (OpenAI-compatible API).
 */
object LlmFactory {

    /**
     * Build an OpenAI-compatible [PromptExecutor].
     */
    fun createExecutor(apiKey: String, baseUrl: String): PromptExecutor {
        val httpClientFactory = HttpClientFactoryResolver.resolve()
        val client = OpenAILLMClient(
            apiKey = apiKey,
            settings = OpenAIClientSettings(baseUrl = baseUrl),
            httpClientFactory = httpClientFactory,
        )
        return MultiLLMPromptExecutor(LLMProvider.OpenAI to client)
    }

    /**
     * Build an [LLModel] for the configured model id. The capability set mirrors
     * what a generic OpenAI-compatible chat model supports.
     */
    fun createModel(modelId: String): LLModel = LLModel(
        provider = LLMProvider.OpenAI,
        id = modelId,
        capabilities = listOf(
            LLMCapability.Completion,
            LLMCapability.Temperature,
            LLMCapability.Speculation,
            LLMCapability.OpenAIEndpoint.Completions,
        ),
    )
}
