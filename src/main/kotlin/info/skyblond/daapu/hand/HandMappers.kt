package info.skyblond.daapu.hand

import info.skyblond.daapu.agent.chat.ChatMessage
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

/**
 * The per-request [HandRunRequest] built from a catalog [LLM] and the
 * caller's [HandRunPolicy]: the model spec ([toHandModelSpec]) plus the
 * model's own output budget and the policy knobs (retries, idle timeout).
 * The ONE construction site of the run envelope — the hand holds no
 * defaults, so every caller (the chat loop, the one-shot helpers, the
 * investigator) must map these fields identically; build run requests
 * ONLY through this factory so the mapping can never drift between the
 * call sites.
 */
fun handRunRequest(
    model: LLM,
    messages: List<ChatMessage>,
    systemPrompt: String?,
    policy: HandRunPolicy,
    maxRounds: Int,
): HandRunRequest = HandRunRequest(
    model = model.toHandModelSpec(),
    messages = messages,
    systemPrompt = systemPrompt,
    maxTokens = model.maxOutputTokens,
    maxRounds = maxRounds,
    maxRetries = policy.maxRetries,
    streamIdleTimeoutMs = policy.streamIdleTimeoutMs,
)
