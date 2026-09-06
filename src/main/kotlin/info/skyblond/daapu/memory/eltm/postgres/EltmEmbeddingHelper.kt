package info.skyblond.daapu.memory.eltm.postgres

import info.skyblond.daapu.agent.model.EmbeddingModel
import info.skyblond.daapu.config.MAX_VECTOR_DIMENSIONS
import info.skyblond.daapu.db.padVector
import info.skyblond.daapu.hand.HandRunPolicy
import info.skyblond.daapu.hand.HandService

/**
 * Per-embed-call input cap: embedding gateways cap the `input`
 * array (and its total tokens), so an over-cap batch splits into
 * several calls instead of one request the gateway refuses.
 * Internal so the DB-backed tests can build an over-cap batch.
 */
internal const val EMBED_BATCH_SIZE = 64

/**
 * The padded vectors for a whole batch of texts, at most
 * [EMBED_BATCH_SIZE] inputs per hand `/v1/embed` call — the batch is
 * the point (never one HTTP round trip per note), the cap keeps one
 * batch inside the embedding gateway's per-request input limits. An
 * empty batch calls nothing.
 */
suspend fun HandService.embedAll(
    embeddingModel: EmbeddingModel,
    texts: List<String>,
    policy: HandRunPolicy
): List<List<Float>> =
    texts.chunked(EMBED_BATCH_SIZE).flatMap { chunk ->
        this.embed(embeddingModel, chunk, policy).vectors
            .map { padVector(it, MAX_VECTOR_DIMENSIONS) }
    }

/** The padded vector for ONE text (entity texts, search queries). */
suspend fun HandService.embedText(
    embeddingModel: EmbeddingModel,
    text: String,
    policy: HandRunPolicy
): List<Float> =
    this.embedAll(embeddingModel, listOf(text), policy).single()
