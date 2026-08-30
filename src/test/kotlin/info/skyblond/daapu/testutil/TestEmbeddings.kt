package info.skyblond.daapu.testutil

import info.skyblond.daapu.hand.HandEmbedRequest
import info.skyblond.daapu.hand.HandEmbedResult
import info.skyblond.daapu.hand.HandEmbedUsage
import java.security.MessageDigest
import kotlin.math.sqrt

/**
 * A deterministic embed script for [info.skyblond.daapu.hand.FakeHand]:
 * the same text always produces the same vector, so vector-search
 * assertions are stable across runs. Explicitly registered texts use
 * their registered vector (exact control over similarity); everything
 * else falls back to a unit vector derived from the SHA-256 of the text —
 * distinct texts are near-orthogonal at realistic dimensionalities
 * (cosine ≈ 0, below every threshold in `testAppConfig`), identical texts
 * are similarity 1.0.
 *
 * To make a query match a stored row, register the query text with the
 * SAME vector as the row's embedding text (the composed texts differ —
 * e.g. `entityEmbeddingText("kindle", "device", ...)` is "kindle device",
 * not "kindle" — and hash vectors of different texts are orthogonal).
 */
class DeterministicEmbeddings {

    private val registered = mutableMapOf<String, List<Float>>()

    /**
     * Pin [text] to [vector]. The vector must match the embedding model's
     * dimensionality — a mismatch fails the embed request loudly, never a
     * silent fallback (the test means to pin the exact similarity, and a
     * silently-hash-vectorized pin would corrupt it).
     */
    fun register(text: String, vector: List<Float>) {
        require(vector.isNotEmpty()) { "registered vector for \"$text\" must not be empty" }
        registered[text] = vector
    }

    /** The `FakeHand(embedScript = ...)` value. */
    val script: suspend (HandEmbedRequest) -> HandEmbedResult = { request ->
        HandEmbedResult(
            vectors = request.input.map { vectorFor(it, request.dimensions) },
            dimensions = request.dimensions,
            usage = HandEmbedUsage(
                promptTokens = request.input.sumOf { it.length },
                totalTokens = request.input.sumOf { it.length },
            ),
        )
    }

    private fun vectorFor(text: String, dimensions: Int): List<Float> {
        registered[text]?.let { pinned ->
            require(pinned.size == dimensions) {
                "registered vector for \"$text\" has ${pinned.size} dimensions, " +
                        "the request wants $dimensions"
            }
            return pinned
        }
        val values = ArrayList<Float>(dimensions)
        var counter = 0
        while (values.size < dimensions) {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("$text#$counter".toByteArray())
            var i = 0
            while (i + 3 < digest.size && values.size < dimensions) {
                val packed = ((digest[i].toInt() and 0xFF) shl 24) or
                        ((digest[i + 1].toInt() and 0xFF) shl 16) or
                        ((digest[i + 2].toInt() and 0xFF) shl 8) or
                        (digest[i + 3].toInt() and 0xFF)
                values.add(packed.toFloat() / Int.MAX_VALUE)
                i += 4
            }
            counter++
        }
        val norm = sqrt(values.sumOf { it.toDouble() * it })
        return values.map { (it / norm).toFloat() }
    }
}
