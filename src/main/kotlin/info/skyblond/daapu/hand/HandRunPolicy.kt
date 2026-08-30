package info.skyblond.daapu.hand

/**
 * The hand's per-request run policy: `hand.maxRetries` +
 * `hand.streamIdleTimeoutMs` — the two knobs always travel together, so
 * every run/embed seam takes them as ONE value instead of a loose pair.
 * [streamIdleTimeoutMs] is the per-round stream idle timeout on `/v1/run`
 * and doubles as the per-attempt `timeoutMs` on `/v1/embed` (the same
 * `hand.streamIdleTimeoutMs` config knob feeds both — see `HandConfig`).
 * The chat loop, every one-shot pipeline (compaction, extraction, query
 * rewrite, title, the investigator, the ELTM writer) and the ELTM
 * embeddings share this policy, so transient failures retry with the
 * same budget/backoff as the chat loop.
 * The wire request DTOs keep their own fields (`HandRunRequest`,
 * `HandEmbedRequest`); this type is the brain-side carrier only.
 */
data class HandRunPolicy(
    val maxRetries: Int,
    val streamIdleTimeoutMs: Long,
) {
    init {
        // production sites build this from the validated `HandConfig`; the
        // checks keep direct constructions (scripts, tests) on the same
        // fail-fast contract as the config knob itself
        require(maxRetries >= 0) { "hand.maxRetries must be >= 0, got $maxRetries" }
        require(streamIdleTimeoutMs >= 0) { "hand.streamIdleTimeoutMs must be >= 0, got $streamIdleTimeoutMs" }
    }
}
