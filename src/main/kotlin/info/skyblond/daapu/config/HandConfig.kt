package info.skyblond.daapu.config

import kotlinx.serialization.Serializable

/**
 * The hand-pi execution service (`hand-pi/`): a stateless
 * Node service that owns LLM execution (streaming, dialects, tool-call
 * accumulation, retries, usage). The brain passes provider/model
 * configuration per request; only the loopback endpoint, the shared
 * static token, and the run-policy knobs are configured here. The token
 * also authenticates the hand's tool callbacks into this process
 * (`server/endpoint/HandRoute.kt`). The run-policy knobs are REQUIRED per
 * request (the hand holds no defaults) and sent with every run.
 */
@Serializable
data class HandConfig(
    /** The hand's base URL, e.g. `http://127.0.0.1:3100`. */
    val baseUrl: String = "http://127.0.0.1:3100",
    /** The shared static token (`HAND_TOKEN` on the hand's side). */
    val token: String = "dev-token",
    /** Round cap per `/v1/run`; 0 = unlimited. */
    val maxRounds: Int = 64,
    /** Total transient attempts per `/v1/run` round (1 = a single attempt); 0 = unlimited. */
    val maxRetries: Int = 0,
    /** Stream idle timeout per round in ms; 0 = disabled. */
    val streamIdleTimeoutMs: Long = 300_000,
) {
    fun validate() {
        require(baseUrl.isNotBlank()) { "hand.baseUrl must not be blank" }
        require(baseUrl.startsWith("http://") || baseUrl.startsWith("https://")) {
            "hand.baseUrl must be an http(s) URL, got '$baseUrl'"
        }
        require(maxRounds >= 0) { "hand.maxRounds must be >= 0, got $maxRounds" }
        require(maxRetries >= 0) { "hand.maxRetries must be >= 0, got $maxRetries" }
        require(streamIdleTimeoutMs >= 0) { "hand.streamIdleTimeoutMs must be >= 0, got $streamIdleTimeoutMs" }
    }
}
