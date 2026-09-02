package info.skyblond.daapu.config

import kotlinx.serialization.Serializable
import java.net.URI
import java.net.URISyntaxException

/**
 * The hand-pi execution service (`hand-pi/`): a stateless
 * Node service that owns LLM execution (streaming, dialects, tool-call
 * accumulation, retries, usage). The brain passes provider/model
 * configuration per request; the two endpoints (where this brain finds the
 * hand and where the hand finds this brain), the shared static token, and
 * the run-policy knobs are configured here. The token also authenticates
 * the hand's tool callbacks into this process
 * (`server/endpoint/HandRoute.kt`). The run-policy knobs are REQUIRED per
 * request (the hand holds no defaults) and sent with every run.
 */
@Serializable
data class HandConfig(
    /** The hand's base URL, e.g. `http://127.0.0.1:3100`. Validated like [selfBaseUrl]. */
    val baseUrl: String,
    /**
     * This brain's base URL as the hand reaches it, e.g. `http://127.0.0.1:8080`
     * in local development or a container/service name inside a docker
     * network. Deployment-dependent, hence REQUIRED. Both URLs share one
     * validation ([requireHttpUrl]): http(s) with a host, no query or
     * fragment, no trailing slash — the code-owned tool-listing and callback
     * paths are appended directly ([toolListUrl], [toolCallbackUrl]).
     */
    val selfBaseUrl: String,
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
        requireHttpUrl("hand.baseUrl", baseUrl)
        requireHttpUrl("hand.selfBaseUrl", selfBaseUrl)
        require(maxRounds >= 0) { "hand.maxRounds must be >= 0, got $maxRounds" }
        require(maxRetries >= 0) { "hand.maxRetries must be >= 0, got $maxRetries" }
        require(streamIdleTimeoutMs >= 0) { "hand.streamIdleTimeoutMs must be >= 0, got $streamIdleTimeoutMs" }
    }

    /**
     * Shared validation for both hand-facing URLs (mirrored by the
     * `config.schema.json` pattern): non-blank http(s) without a trailing
     * slash, then parsed with [URI] to reject what the string checks miss —
     * a missing host (`http:///x`), a query or fragment (the composed paths
     * below would be silently swallowed into it), and illegal characters
     * such as whitespace. A path prefix is allowed (e.g. behind a reverse
     * proxy) and carries into the composed URLs.
     */
    private fun requireHttpUrl(name: String, url: String) {
        require(url.isNotBlank()) { "$name must not be blank" }
        require(url.startsWith("http://") || url.startsWith("https://")) {
            "$name must be an http(s) URL, got '$url'"
        }
        require(!url.endsWith("/")) { "$name must not end with '/', got '$url'" }
        val uri = try {
            URI(url)
        } catch (e: URISyntaxException) {
            throw IllegalArgumentException("$name must be a valid URL, got '$url'", e)
        }
        require(uri.host != null) { "$name must have a host, got '$url'" }
        require(uri.query == null && uri.fragment == null) {
            "$name must not contain a query or fragment, got '$url'"
        }
    }

    /**
     * The tool-execution callback URL sent with every run: [selfBaseUrl]
     * plus the code-owned path, which MUST match the `/api`-prefixed route
     * in `server/endpoint/HandRoute.kt` (that file owns the authoritative
     * route comments).
     */
    val toolCallbackUrl: String
        get() = "$selfBaseUrl/api/hand/tool"

    /** The per-round tool-listing URL, see [toolCallbackUrl]. */
    val toolListUrl: String
        get() = "$selfBaseUrl/api/hand/tools"
}
