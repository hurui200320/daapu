package info.skyblond.daapu.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * One OpenAI-compatible LLM provider, keyed by its id in [AppConfig.providers]
 * (`{provider.id}/{modelId}` prefixes every model id, so the id must match
 * [SAFE_ID_REGEX] — enforced at provider construction, see
 * `agent/hand/HandMappers.kt`). [baseUrl] is used as-is by the hand
 * (`hand-pi/`), which appends the OpenAI API path to it — so the value must
 * carry the full `/v1` root (e.g. `http://localhost:8000/v1`); nothing
 * appends `/v1`.
 *
 * The model catalog entries served through this gateway are declared here
 * too: [llm] holds the chat/completion models, [embedding] the embedding
 * models. Nesting them under the provider keeps every id's first segment in
 * sync with the key structurally (a dangling entry naming a nonexistent
 * provider is impossible). Both lists default to empty — a provider may
 * serve only one kind — but a catalog without at least one LLM entry fails
 * fast at boot (`ModelCatalog.fromConfig`).
 */
@Serializable
data class LlmProviderConfig(
    val apiKey: String,
    val baseUrl: String,
    /** The chat models this gateway serves; see [LlmModelEntryConfig]. */
    val llm: List<LlmModelEntryConfig> = emptyList(),
    /** The embedding models this gateway serves; see [EmbeddingModelEntryConfig]. */
    val embedding: List<EmbeddingModelEntryConfig> = emptyList(),
) {
    fun validate(id: String) {
        require(apiKey.isNotBlank()) { "providers['$id'].apiKey must not be blank" }
        require(baseUrl.isNotBlank()) { "providers['$id'].baseUrl must not be blank" }
        llm.forEachIndexed { index, entry ->
            entry.validate("providers['$id'].llm[$index]")
        }
        embedding.forEachIndexed { index, entry ->
            entry.validate("providers['$id'].embedding[$index]")
        }
    }
}

/**
 * One chat model of a provider: the raw model id (the second and following
 * segments of the composite `{provider.id}/{modelId}` wire-visible id,
 * slashes allowed — it is passed to the gateway as-is), its context/output
 * budgets, its capability set, and the per-model compaction tuning. All
 * fields are REQUIRED — the budgets drive the run loop's exhaustion
 * classification, so guessing them would misclassify every overflow.
 *
 * Uniqueness is not checked here: two entries resolving to the same
 * composite id collide on lookup, and `ModelCatalog`'s init fails fast on
 * that (across both kinds and all providers).
 */
@Serializable
data class LlmModelEntryConfig(
    val modelId: String,
    /** The model's context window in tokens, > 0. */
    val contextLength: Long,
    /** The max output tokens per round, > 0. */
    val maxOutputTokens: Long,
    /**
     * Capabilities: `image`, `video`, `audio`, `document`, `tool_calls`, `reasoning:<effort>`.
     * The token vocabulary is re-checked at catalog build (`ModelCatalog`,
     * which owns the pi-ai coupling); this layer only rejects blank and
     * duplicate tokens, so the cheapest errors surface with [validate]'s
     * owner path.
     */
    val capabilities: List<String>,
    /** See `agent/model/LLM.kt`: pre-round compaction trigger fraction, [0, 1], 0 disables. */
    val compactionTriggerFraction: Double,
    /** Complete rounds kept verbatim at the tail of a compaction, >= 1. */
    val compactionKeepRounds: Int,
) {
    fun validate(owner: String) {
        require(modelId.isNotBlank()) { "$owner.modelId must not be blank" }
        require(contextLength > 0) { "$owner.contextLength must be > 0, got $contextLength" }
        require(maxOutputTokens > 0) { "$owner.maxOutputTokens must be > 0, got $maxOutputTokens" }
        require(compactionTriggerFraction in 0.0..1.0) {
            "$owner.compactionTriggerFraction must be in [0, 1], got $compactionTriggerFraction"
        }
        require(compactionKeepRounds >= 1) {
            "$owner.compactionKeepRounds must be >= 1, got $compactionKeepRounds"
        }
        require(capabilities.none { it.isBlank() }) {
            "$owner.capabilities must not contain blank tokens: $capabilities"
        }
        val duplicate = capabilities.groupBy { it }.filterValues { it.size > 1 }.keys.firstOrNull()
        require(duplicate == null) {
            "$owner.capabilities must not contain duplicates, got '$duplicate' in $capabilities"
        }
    }
}

/**
 * One embedding model of a provider. [dimensions] pins the output size the
 * brain requests and verifies on every `/v1/embed`; it must fit the fixed
 * ELTM vector column width ([MAX_VECTOR_DIMENSIONS]) — shorter vectors are
 * zero-padded, wider ones would not fit. [additionalProperties] merges extra
 * root-level fields into the gateway request body (gateway-specific knobs
 * like deepinfra's `service_tier`); keys colliding with the hand-managed
 * fields fail fast in the agent layer (`agent/model/EmbeddingModel.kt`),
 * where the merge contract lives.
 */
@Serializable
data class EmbeddingModelEntryConfig(
    val modelId: String,
    val dimensions: Int,
    val additionalProperties: JsonObject? = null,
) {
    fun validate(owner: String) {
        require(modelId.isNotBlank()) { "$owner.modelId must not be blank" }
        // the bounds (and their message) live in ONE place (`Config.kt`),
        // shared with the runtime type (which labels itself with the composite
        // id instead); forwarding the entry path here locates the offending
        // line in the operator's config
        checkEmbeddingDimensions(dimensions, owner)
    }
}
