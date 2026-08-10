package info.skyblond.daapu.agent.lc4j.llm

import info.skyblond.daapu.agent.lc4j.provider.BifrostProvider

class ModelCatalog(
    bifrostProvider: BifrostProvider
) {
    // FIXME: ensure provider id not dup

    val models: List<LLM> = listOf(
        LLM(
            provider = bifrostProvider,
            modelId = "cerebras/gpt-oss-120b",
            contextLength = 131000,
            maxOutputTokens = 40000,
            capabilities = setOf(LLMCapability.Output.Reasoning, LLMCapability.Output.ToolCalls),
        ),
        LLM(
            provider = bifrostProvider,
            modelId = "cerebras/gemma-4-31b",
            contextLength = 131072,
            maxOutputTokens = 40000,
            capabilities = setOf(
                LLMCapability.Input.Vision.Image,
                LLMCapability.Output.Reasoning,
                LLMCapability.Output.ToolCalls,
            ),
        ),
        LLM(
            provider = bifrostProvider,
            modelId = "novita/google/gemma-4-31b-it",
            contextLength = 262144,
            maxOutputTokens = 131072,
            capabilities = setOf(
                LLMCapability.Input.Vision.Image,
                LLMCapability.Output.Reasoning,
                LLMCapability.Output.ToolCalls,
            ),
        ),
    )

    fun findModel(id: String): LLM? = models.firstOrNull { it.id == id }
}
