package info.skyblond.daapu.agent

import info.skyblond.daapu.agent.model.EmbeddingModel
import info.skyblond.daapu.agent.model.LLM
import info.skyblond.daapu.agent.model.LLMCapability
import info.skyblond.daapu.agent.model.ModelProvider
import info.skyblond.daapu.config.EmbeddingModelEntryConfig
import info.skyblond.daapu.config.LlmModelEntryConfig
import info.skyblond.daapu.config.LlmProviderConfig
import info.skyblond.daapu.config.SAFE_ID_REGEX

/**
 * The model catalog: every model the API can serve, across all configured
 * providers, built once at startup from the config
 * ([fromConfig] over `providers.*.llm/embedding`, see `di/AppModule.kt`).
 *
 * Model ids are unique across the whole catalog — a duplicate would produce
 * colliding composite ids (`{provider.id}/{modelId}`) that break `findModel`
 * — and an empty LLM catalog is a wiring bug (every one-shot pipeline model
 * and every chat request resolves through it), so both fail fast in the init
 * below.
 */
class ModelCatalog(
    models: List<LLM>,
    embeddingModels: List<EmbeddingModel>,
) {
    val models: List<LLM> = models.toList()
    val embeddingModels: List<EmbeddingModel> = embeddingModels.toList()

    init {
        require(models.isNotEmpty()) {
            "The model catalog is empty: declare at least one chat model under providers.<id>.llm in the config"
        }
        val allIds = models.map { it.id } + embeddingModels.map { it.id }
        require(allIds.distinct().size == allIds.size) {
            "Duplicate model id in catalog: " +
                    allIds.groupBy { it }.filterValues { it.size > 1 }.keys
        }
    }

    fun findModel(id: String): LLM? = models.firstOrNull { it.id == id }

    fun findEmbeddingModel(id: String): EmbeddingModel? =
        embeddingModels.firstOrNull { it.id == id }

    companion object {
        /**
         * Build the catalog from the config's provider sections: each provider
         * becomes one [ModelProvider], its
         * entries map onto `LLM`/`EmbeddingModel` carrying the config-declared
         * metadata. Token-level and range validation already ran on config load
         * (`LlmProviderConfig.validate`); the mapping itself is total, with
         * four defensive re-checks: the provider-id charset below (the same
         * rule [info.skyblond.daapu.config.AppConfig.validate] applies on load,
         * repeated here so direct construction cannot skip it), plus the
         * unknown-token guard, the reasoning-effort whitelist, and the
         * single-reasoning-effort rule in [toLlm] further down (the JSON
         * Schema's pattern is documentation only). All fail fast at
         * boot — a bad effort would otherwise surface as a per-run upstream
         * error on every request carrying `reasoning_effort`.
         */
        fun fromConfig(providers: Map<String, LlmProviderConfig>): ModelCatalog {
            // the id prefixes every wire-visible model id ({provider.id}/{modelId}),
            // so it must match the SAFE_ID_CHARSET contract before anything is built
            providers.keys.forEach { id ->
                require(id.matches(SAFE_ID_REGEX)) {
                    "providers key '$id' is invalid: only [0-9a-z_-] is allowed"
                }
            }
            val builtProviders = providers.entries.associate { (id, config) ->
                id to ModelProvider(
                    id = id,
                    baseUrl = config.baseUrl,
                    apiKey = config.apiKey,
                )
            }
            return ModelCatalog(
                models = providers.flatMap { (id, config) ->
                    config.llm.map { entry ->
                        entry.toLlm(
                            provider = builtProviders.getValue(id),
                        )
                    }
                },
                embeddingModels = providers.flatMap { (id, config) ->
                    config.embedding.map { entry ->
                        entry.toEmbeddingModel(
                            provider = builtProviders.getValue(id),
                        )
                    }
                },
            )
        }

        /**
         * Map one validated config entry onto the runtime chat-model type.
         * Capability-token parse errors name only the offending token, so they
         * are prefixed with the composite id here, where the operator's config
         * key is known; [LLM]'s constructor range checks self-label with the
         * field name instead (unreachable through validated configs — a
         * defensive net for direct construction only). At most ONE reasoning
         * effort is accepted per entry: the model runs with a single
         * `reasoning_effort` ([LLM.reasoningEffort] picks one), so two
         * declared efforts would silently resolve by config order.
         */
        private fun LlmModelEntryConfig.toLlm(provider: ModelProvider): LLM {
            val parsedCapabilities = capabilities.map { token ->
                try {
                    parseCapabilityString(token)
                } catch (e: IllegalArgumentException) {
                    // the token-parse errors name only the token: prefix them with
                    // the owning config path here, where the operator's
                    // config key is known (the embedding mapping needs no such
                    // wrapper — its constructor errors self-label, see below)
                    throw IllegalArgumentException("${provider.id}/${modelId} ${e.message}", e)
                }
            }
            val efforts = parsedCapabilities.filterIsInstance<LLMCapability.Output.Reasoning>()
            require(efforts.size <= 1) {
                "${provider.id}/$modelId declares ${efforts.size} reasoning capabilities " +
                        "(${efforts.joinToString(", ") { it.reasoningEffort }}): at most one " +
                        "'${REASONING_PREFIX}<effort>' token is allowed"
            }
            return LLM(
                provider = provider,
                modelId = modelId,
                contextLength = contextLength,
                maxOutputTokens = maxOutputTokens,
                capabilities = parsedCapabilities.toSet(),
                compactionTriggerFraction = compactionTriggerFraction,
                compactionKeepRounds = compactionKeepRounds,
            )
        }

        /**
         * Map one validated config entry onto the runtime embedding-model type.
         * The runtime type's constructor checks self-label with the composite
         * id (see `EmbeddingModel.init`), so no prefixing happens here — unlike
         * [toLlm], whose capability-token parse errors name only the token.
         */
        private fun EmbeddingModelEntryConfig.toEmbeddingModel(
            provider: ModelProvider,
        ): EmbeddingModel =
            EmbeddingModel(
                provider = provider,
                modelId = modelId,
                dimensions = dimensions,
                additionalProperties = additionalProperties,
            )

        private const val REASONING_PREFIX = "reasoning:"

        /**
         * The efforts the hand's `reasoning_effort` field accepts (the pi-ai
         * union, see `LLMCapability.Output.Reasoning`). The config layer only
         * sees a free-form string, so the effort is re-checked here at catalog
         * build: an out-of-union value would boot cleanly and then fail every
         * request with an upstream error instead.
         */
        private val REASONING_EFFORTS = setOf("minimal", "low", "medium", "high", "xhigh", "max")

        private fun parseCapabilityString(str: String): LLMCapability = when {
            str == "image" -> LLMCapability.Input.Vision.Image
            str == "video" -> LLMCapability.Input.Vision.Video
            str == "audio" -> LLMCapability.Input.Audio
            str == "document" -> LLMCapability.Input.Document
            str == "tool_calls" -> LLMCapability.Output.ToolCalls
            str.startsWith(REASONING_PREFIX) -> {
                val effort = str.removePrefix(REASONING_PREFIX)
                require(effort in REASONING_EFFORTS) {
                    "Unknown reasoning effort '$effort' in capability token '$str': " +
                            "effort must be one of ${REASONING_EFFORTS.sorted().joinToString(", ")}"
                }
                LLMCapability.Output.Reasoning(effort)
            }

            else -> throw IllegalArgumentException(
                "Unknown capability token '$str': expected one of image, video, audio, document, " +
                        "tool_calls, or '${REASONING_PREFIX}<effort>' (effort: " +
                        "${REASONING_EFFORTS.sorted().joinToString(", ")})"
            )
        }
    }
}
