package info.skyblond.daapu.agent

import info.skyblond.daapu.agent.model.EmbeddingModel
import info.skyblond.daapu.agent.model.LLM
import info.skyblond.daapu.agent.model.LLMCapability
import info.skyblond.daapu.agent.model.ModelProvider
import info.skyblond.daapu.config.SAFE_ID_REGEX

/**
 * The model catalog: every model the API can serve, across all configured
 * providers.
 *
 * Provider ids must be unique — they prefix every model id
 * (`{provider.id}/{modelId}`), so a duplicate would produce colliding ids
 * that break `findModel`. Model ids must be unique within the catalog for
 * the same reason.
 */
class ModelCatalog(
    providers: Map<String, ModelProvider>,
) {
    init {
        providers.keys.forEach { id ->
            require(id.matches(SAFE_ID_REGEX)) {
                "Provider id '$id' is invalid: only [0-9a-z_-] is allowed"
            }
        }
    }

    // PoC: the model entries below are pinned to the bifrost gateway (see
    // Main.kt); a catalog without it is a wiring bug, so fail fast instead
    // of throwing a NoSuchElementException at model resolution time.
    private val bifrostProvider = providers["bifrost"]
        ?: throw IllegalArgumentException("ModelCatalog requires a provider with id 'bifrost'")

    private val deepinfraProvider = providers["deepinfra"]
        ?: throw IllegalArgumentException("ModelCatalog requires a provider with id 'deepinfra'")

    val models: List<LLM> = listOf(
        LLM(
            provider = bifrostProvider,
            modelId = "cerebras/gpt-oss-120b",
            contextLength = 131000,
            maxOutputTokens = 40000,
            capabilities = setOf(
                LLMCapability.Output.Reasoning("high"),
                LLMCapability.Output.ToolCalls
            ),
            compactionTriggerFraction = 0.75,
            compactionKeepRounds = 2,
        ),
        LLM(
            provider = bifrostProvider,
            modelId = "cerebras/gemma-4-31b",
            contextLength = 131072,
            maxOutputTokens = 40000,
            capabilities = setOf(
                LLMCapability.Input.Vision.Image,
                LLMCapability.Output.Reasoning("high"),
                LLMCapability.Output.ToolCalls,
            ),
            compactionTriggerFraction = 0.75,
            compactionKeepRounds = 2,
        ),
        LLM(
            provider = bifrostProvider,
            modelId = "novita/google/gemma-4-31b-it",
            contextLength = 262144,
            maxOutputTokens = 131072,
            capabilities = setOf(
                LLMCapability.Input.Vision.Image,
                LLMCapability.Output.Reasoning("high"),
                LLMCapability.Output.ToolCalls,
            ),
            compactionTriggerFraction = 0.8,
            compactionKeepRounds = 3,
        ),
    )

    val embeddingModels: List<EmbeddingModel> = listOf(
        // PoC: pinned to the bifrost gateway like the LLM entries.
        // The gateway honors a `dimensions` request field,
        // so the hand requests this exact output size
        // and the response is verified against it.
        // For other providers, check the dimensions field first.
        EmbeddingModel(
            provider = bifrostProvider,
            modelId = "zenmux sub/google/gemini-embedding-2",
            dimensions = 1536,
        ),
        EmbeddingModel(
            provider = deepinfraProvider,
            modelId = "Qwen/Qwen3-Embedding-8B",
            dimensions = 1536,
        ),
    )

    init {
        val allIds = models.map { it.id } + embeddingModels.map { it.id }
        require(allIds.distinct().size == allIds.size) {
            "Duplicate model id in catalog: " +
                    allIds.groupBy { it }.filterValues { it.size > 1 }.keys
        }
    }

    fun findModel(id: String): LLM? = models.firstOrNull { it.id == id }

    fun findEmbeddingModel(id: String): EmbeddingModel? =
        embeddingModels.firstOrNull { it.id == id }
}
