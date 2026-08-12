package info.skyblond.daapu.agent.lc4j.llm

import info.skyblond.daapu.agent.lc4j.provider.OpenAICompatibleProvider

/**
 * The model catalog: every model the API can serve, across all configured
 * providers.
 *
 * Provider ids must be unique — they prefix every model id
 * (`{provider.id}/{modelId}`), so a duplicate would produce colliding ids
 * that break `findModel` and the history's `modelId` round-trip. Model ids
 * must be unique for the same reason.
 */
class ModelCatalog(
    vararg providers: OpenAICompatibleProvider,
) {
    private val providersById = providers.associateBy { it.id }

    // PoC: the model entries below are pinned to the bifrost gateway (see
    // Main.kt); a catalog without it is a wiring bug, so fail fast instead
    // of throwing a NoSuchElementException at model resolution time.
    private val bifrostProvider = providersById["bifrost"]
        ?: throw IllegalArgumentException("ModelCatalog requires a provider with id 'bifrost'")

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

    init {
        require(providersById.size == providers.size) {
            "Duplicate provider id in catalog: " +
                    providers.groupBy { it.id }.filterValues { it.size > 1 }.keys
        }
        require(models.map { it.id }.distinct().size == models.size) {
            "Duplicate model id in catalog: " +
                    models.groupBy { it.id }.filterValues { it.size > 1 }.keys
        }
    }

    fun findModel(id: String): LLM? = models.firstOrNull { it.id == id }
}
