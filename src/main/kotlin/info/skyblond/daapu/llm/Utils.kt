package info.skyblond.daapu.llm

import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel

fun createModel(
    id: String,
    capabilities: List<LLMCapability>,
    contextLength: Long,
    maxOutputTokens: Long,
) = LLModel(
    provider = LLMProvider.OpenAI,
    id = id,
    capabilities = capabilities,
    contextLength = contextLength,
    maxOutputTokens = maxOutputTokens,
)