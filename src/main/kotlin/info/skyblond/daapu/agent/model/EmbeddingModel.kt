package info.skyblond.daapu.agent.model

import info.skyblond.daapu.config.MAX_VECTOR_DIMENSIONS
import kotlinx.serialization.json.JsonObject

/**
 * One catalog entry for an embedding model: the gateway ([provider]) and
 * the model id served by it, plus the [dimensions] of every produced
 * vector. [id] is the wire-visible lookup key (`{provider.id}/{modelId}`),
 * the shape `memory.eltm.embeddingModel` will reference.
 *
 * [dimensions] is the output dimensionality the brain pins: the hand sends
 * it on every `/v1/embed` request (the gateway honors it — never truncated)
 * and verifies the hand-reported response against it. It must be at most
 * [MAX_VECTOR_DIMENSIONS]: the ELTM vector columns are fixed at that width
 * (pgvector's HNSW indexing limit for the `vector` type) and every vector
 * is zero-padded to it on write — cosine similarity is invariant under
 * zero-padding, so switching embedding models never needs a schema change,
 * only that the output dimensions do not exceed the column width.
 *
 * [additionalProperties] are extra root-level fields merged into the
 * gateway request body on EVERY `/v1/embed` call (gateway-specific knobs
 * the brain's contract does not model, e.g. deepinfra's
 * `service_tier: "priority"` for a faster tier); `null` = none. The keys
 * must not collide with the hand-managed gateway body fields (`model`,
 * `input`, `dimensions`) — the hand rejects a collision too, but a catalog
 * entry is authored once, so this fail-fast at boot catches it earlier.
 */
class EmbeddingModel(
    val provider: ModelProvider,
    val modelId: String,
    val dimensions: Int,
    val additionalProperties: JsonObject? = null,
) {
    val id = "${provider.id}/$modelId"

    init {
        require(dimensions > 0) { "dimensions must be > 0, got $dimensions" }
        require(dimensions <= MAX_VECTOR_DIMENSIONS) {
            "dimensions must be at most $MAX_VECTOR_DIMENSIONS (pgvector's HNSW limit " +
                    "for the vector type, the fixed ELTM column width), got $dimensions"
        }
        val collidingKey = additionalProperties?.keys?.firstOrNull { it in RESERVED_GATEWAY_FIELDS }
        require(collidingKey == null) {
            "additionalProperties key '$collidingKey' collides with the hand-managed " +
                    "embedding request field of the same name"
        }
    }

    private companion object {
        /** The fields the hand itself puts into the `{baseUrl}/embeddings` request body. */
        val RESERVED_GATEWAY_FIELDS = setOf("model", "input", "dimensions")
    }
}
