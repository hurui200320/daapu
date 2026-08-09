package info.skyblond.daapu.langchain4j

/**
 * All models the web UI can pick from, on the langchain4j side of the
 * migration (#5). This is the single catalog now that the runtime switched
 * over in #6 (the koog catalog in `koog/client/` was deleted); the entries
 * are pinned by value in `ModelCatalogTest`.
 *
 * The catalog is a class (not top-level vals) because every entry's [baseUrl]
 * is stamped from the configured gateway at construction: the project routes
 * all models through the single `LLM_BASE_URL` gateway, so entries cannot be
 * framework-free constants.
 *
 * @param baseUrl the gateway every model is reached through
 *   (e.g. the bifrost endpoint from `LLM_BASE_URL`).
 */
// FIXME: extend provider from enum to class, polling base url from provider object instead of parameter
class ModelCatalog(baseUrl: String) {

    val models: List<ModelMetadata> = listOf(
        ModelMetadata(
            provider = ModelProvider.Bifrost,
            baseUrl = baseUrl,
            id = "cerebras/gpt-oss-120b",
            contextLength = 131000,
            maxOutputTokens = 40000,
            capabilities = setOf(ModelCapability.Reasoning, ModelCapability.ToolCalls),
        ),
        ModelMetadata(
            provider = ModelProvider.Bifrost,
            baseUrl = baseUrl,
            id = "cerebras/gemma-4-31b",
            contextLength = 131072,
            maxOutputTokens = 40000,
            capabilities = setOf(
                ModelCapability.VisionImage,
                ModelCapability.Reasoning,
                ModelCapability.ToolCalls,
            ),
        ),
        ModelMetadata(
            provider = ModelProvider.Bifrost,
            baseUrl = baseUrl,
            id = "novita/google/gemma-4-31b-it",
            contextLength = 262144,
            maxOutputTokens = 131072,
            capabilities = setOf(
                ModelCapability.VisionImage,
                ModelCapability.Reasoning,
                ModelCapability.ToolCalls,
            ),
        ),
    )

    fun findModel(id: String): ModelMetadata? = models.firstOrNull { it.id == id }
}

/**
 * Reasoning effort sent to reasoning-capable models only (`hasReasoning()` in
 * `StreamingChatModelFactory`). The koog path sends it unconditionally
 * (`ChatRunService`'s `OpenAIChatParams` additionalProperties); the
 * conditional send is deliberate so a future non-reasoning model never
 * receives a field it may reject.
 */
const val REASONING_EFFORT = "high"
