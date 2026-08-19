package info.skyblond.daapu.agent.model

import info.skyblond.daapu.config.MAX_VECTOR_DIMENSIONS

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
 */
class EmbeddingModel(
    val provider: ModelProvider,
    val modelId: String,
    val dimensions: Int,
) {
    val id = "${provider.id}/$modelId"

    init {
        require(dimensions > 0) { "dimensions must be > 0, got $dimensions" }
        require(dimensions <= MAX_VECTOR_DIMENSIONS) {
            "dimensions must be at most $MAX_VECTOR_DIMENSIONS (pgvector's HNSW limit " +
                    "for the vector type, the fixed ELTM column width), got $dimensions"
        }
    }
}
