package info.skyblond.daapu.hand

import info.skyblond.daapu.agent.model.LLM
import info.skyblond.daapu.agent.model.LLMCapability

/**
 * The per-request [HandModelSpec] for a catalog [LLM]: the hand has no
 * catalog — everything it needs arrives per request.
 */
fun LLM.toHandModelSpec(): HandModelSpec = HandModelSpec(
    baseUrl = provider.baseUrl,
    apiKey = provider.apiKey,
    modelId = modelId,
    contextWindow = contextLength,
    maxOutputTokens = maxOutputTokens,
    reasoning = hasReasoning(),
    reasoningEffort = reasoningEffort(),
    input = listOf("text") +
            if (supports(LLMCapability.Input.Vision.Image)) listOf("image") else emptyList(),
)
