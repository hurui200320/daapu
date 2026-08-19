package info.skyblond.daapu.agent.model

/**
 * One catalog entry for an embedding model: the gateway ([provider]) and
 * the model id served by it, plus the [dimensions] of every produced
 * vector. [id] is the wire-visible lookup key (`{provider.id}/{modelId}`),
 * the shape `memory.eltm.embeddingModel` will reference.
 *
 * [dimensions] is the output dimensionality the brain pins: the hand sends
 * it on every `/v1/embed` request (the gateway honors it — never truncated)
 * and verifies the hand-reported response against it. It MUST also match
 * the `vector(N)` column of the ELTM migration, enforced at startup by the
 * `memory_meta.embedding_dim` check.
 */
class EmbeddingModel(
    val provider: ModelProvider,
    val modelId: String,
    val dimensions: Int,
) {
    val id = "${provider.id}/$modelId"

    init {
        require(dimensions > 0) { "dimensions must be > 0, got $dimensions" }
    }
}
