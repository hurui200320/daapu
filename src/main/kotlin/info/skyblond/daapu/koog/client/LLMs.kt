package info.skyblond.daapu.koog.client

import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import kotlinx.serialization.Serializable

@Serializable
object CustomLLMProvider : LLMProvider("custom", "Custom")

fun createModel(
    id: String,
    capabilities: List<LLMCapability>,
    contextLength: Long,
    maxOutputTokens: Long,
) = LLModel(
    provider = CustomLLMProvider,
    id = id,
    capabilities = capabilities,
    contextLength = contextLength,
    maxOutputTokens = maxOutputTokens,
)

@Suppress("unused")
object Cerebras {
    val Gemma4_31B = createModel(
        id = "cerebras/gemma-4-31b",
        capabilities = listOf(
            LLMCapability.ToolChoice,
            LLMCapability.Schema.JSON.Basic,
            LLMCapability.Schema.JSON.Standard,
            LLMCapability.Tools,
            LLMCapability.Vision.Image,
            LLMCapability.Completion,
            LLMCapability.MultipleChoices,
            LLMCapability.OpenAIEndpoint.Completions,
            LLMCapability.Thinking,
        ),
        contextLength = 131072,
        maxOutputTokens = 40000,
    )


    val GPT_OSS_120B = createModel(
        id = "cerebras/gpt-oss-120b",
        capabilities = listOf(
            LLMCapability.ToolChoice,
            LLMCapability.Schema.JSON.Basic,
            LLMCapability.Schema.JSON.Standard,
            LLMCapability.Tools,
            LLMCapability.Completion,
            LLMCapability.MultipleChoices,
            LLMCapability.OpenAIEndpoint.Completions,
            LLMCapability.Thinking,
        ),
        contextLength = 131000,
        maxOutputTokens = 40000,
    )
}

@Suppress("unused")
object Novita {
    val Gemma4_31B = createModel(
        id = "novita/google/gemma-4-31b-it",
        capabilities = listOf(
            LLMCapability.ToolChoice,
            LLMCapability.Schema.JSON.Basic,
            LLMCapability.Schema.JSON.Standard,
            LLMCapability.Tools,
            LLMCapability.Vision.Image,
            LLMCapability.Completion,
            LLMCapability.MultipleChoices,
            LLMCapability.OpenAIEndpoint.Completions,
            LLMCapability.Thinking,
        ),
        contextLength = 262144,
        maxOutputTokens = 131072,
    )
}