package info.skyblond.daapu.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * MCP tool servers, all tools advertised to every chat run ("one global tool
 * set" for the PoC). [customs] holds the user-configured servers keyed by
 * namespace (the key IS the namespace prefix of the advertised tool names,
 * so it must match [SAFE_ID_REGEX], must not contain `__`, and must not be a
 * reserved/harness namespace — [TOOL_RESERVED_NAMESPACES] or the dedicated
 * exa namespace [EXA_NAMESPACE]). [exa] is the REQUIRED dedicated exa server:
 * an ordinary [McpServerConfig] whose namespace is hardcoded to [EXA_NAMESPACE]
 * — the user fills the rest (type/url/headers/knobs; http for the hosted
 * `mcp.exa.ai`, stdio for a self-hosted server), and [allServers] merges it
 * under that namespace.
 */
@Serializable
data class McpConfig(
    /** The dedicated exa server, REQUIRED; namespace hardcoded to [EXA_NAMESPACE]. */
    val exa: McpServerConfig,
    val customs: Map<String, McpServerConfig> = emptyMap(),
    /**
     * Optional HTTP proxy applied to every http-type server's requests
     * (stdio servers never touch HTTP). Explicit only — no env-var pickup:
     * ktor never reads `HTTP_PROXY`-style variables, and the JVM
     * `ProxySelector` fallback (which the engines consult only when no
     * explicit proxy is set here) honors system properties, not environment
     * variables. The configured proxy replaces that fallback entirely.
     */
    val proxy: McpProxyConfig? = null,
) {
    /**
     * Every configured server, the dedicated exa merged under its hardcoded
     * namespace. Used by the DI container (`di/AppModule.kt`) to build the
     * MCP tool provider; [validate] guarantees no [customs] entry collides
     * with the exa key.
     */
    fun allServers(): Map<String, McpServerConfig> = customs + (EXA_NAMESPACE to exa)

    fun validate() {
        require(!customs.containsKey(EXA_NAMESPACE)) {
            "mcp.customs must not contain the namespace '$EXA_NAMESPACE': it is reserved for the dedicated mcp.exa server"
        }
        exa.validate(EXA_NAMESPACE)
        customs.forEach { (namespace, config) -> config.validate(namespace) }
        proxy?.validate()
    }
}

/**
 * One MCP tool server. In `config.jsonc` the servers live under
 * `mcp.customs` as a map keyed by namespace (the key is the namespace, so
 * this model carries no namespace field of its own; the dedicated exa server
 * `mcp.exa` is the same model with its namespace hardcoded to
 * [EXA_NAMESPACE]). Maps 1:1 to the MCP SDK builders (see the #3 spike's
 * config surface notes):
 *
 * - `http`: a Streamable-HTTP server ([url], optional [headers]).
 * - `stdio`: a local subprocess ([command] list; [environment] extra
 *   variables merged onto the inherited environment).
 *
 * The namespace (the `mcp.customs` map key, or [EXA_NAMESPACE] for the
 * dedicated exa server) prefixes the advertised tool names
 * (`{namespace}__{tool}`), so tools from different servers never collide in
 * the model request. Namespaces the harness reserves for its own
 * internal/harness tools ([TOOL_RESERVED_NAMESPACES]) must not be used.
 */
@Serializable
data class McpServerConfig(
    val type: McpTransportType,
    val url: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val command: List<String> = emptyList(),
    val environment: Map<String, String> = emptyMap(),
    val initializationTimeoutSeconds: Long? = null,
    // REQUIRED: every advertised tool carries it as its execution timeout
    // (seconds, 0 = no timeout), enforced on the hand tool callback path
    val toolExecutionTimeoutSeconds: Long,
    // total connect attempts including the first one (the provider connects
    // eagerly at startup and reconnects on demand — at the per-round
    // tool-list refresh, the sole reconnection point, see
    // mcp/McpToolProvider.kt); 1 means no retry. 0 is rejected by validate()
    val reconnectAttempts: Int = 3,
    // delay between two connect attempts
    val reconnectDelayMs: Long = 1000L
) {
    /**
     * Fail fast on a config that cannot work. [namespace] (the map key under
     * which this server is configured — or [EXA_NAMESPACE] for the dedicated
     * exa server) becomes part of the advertised tool name
     * (`{namespace}__{tool}`, which OpenAI-compatible gateways only accept in
     * `[0-9a-z_-]` and which must not contain the `__` separator), must not
     * collide with the namespaces reserved for internal/harness tools, and
     * each transport requires its own fields.
     */
    fun validate(namespace: String) {
        // MCP servers MUST have a namespace (their advertised names are
        // always prefixed); blank is only legal for the one-shot providers,
        // which never share a tool set
        if (namespace.isBlank()) throw IllegalArgumentException("MCP server config is missing a namespace")
        validateToolNamespaceSyntax(namespace, "MCP server")
        if (namespace in TOOL_RESERVED_NAMESPACES) {
            throw IllegalArgumentException(
                "MCP server namespace '$namespace' is reserved for internal/harness tools, " +
                        "so it cannot be used by an MCP server"
            )
        }
        when (type) {
            McpTransportType.Http -> if (url.isNullOrBlank()) {
                throw IllegalArgumentException("MCP server '$namespace': type 'http' requires a url")
            }

            McpTransportType.Stdio -> if (command.isEmpty()) {
                throw IllegalArgumentException("MCP server '$namespace': type 'stdio' requires a command")
            }
        }
        if (url != null && !url.startsWith("http://") && !url.startsWith("https://")) {
            throw IllegalArgumentException("MCP server '$namespace': url must start with http:// or https://, got '$url'")
        }
        // mirror config.schema.json's minimum: 1 — 0/negative would reach the
        // SDK transport builders (McpEntry) as a nonsense Duration
        require(initializationTimeoutSeconds == null || initializationTimeoutSeconds >= 1) {
            "MCP server '$namespace': initializationTimeoutSeconds must be at least 1, got $initializationTimeoutSeconds"
        }
        // required, 0 = no timeout
        require(toolExecutionTimeoutSeconds >= 0) {
            "MCP server '$namespace': toolExecutionTimeoutSeconds must be non-negative, got $toolExecutionTimeoutSeconds"
        }
        require(reconnectAttempts >= 1) {
            "MCP server '$namespace': reconnectAttempts must be at least 1 (it is the total number of connect attempts, the first one included)"
        }
        require(reconnectDelayMs >= 0) {
            "MCP server '$namespace': reconnectDelayMs must be non-negative"
        }
    }
}

/** The hardcoded namespace of the dedicated exa server ([McpConfig.exa]). */
const val EXA_NAMESPACE: String = "exa"

@Serializable
enum class McpTransportType {
    @SerialName("http")
    Http,

    @SerialName("stdio")
    Stdio,
}

/**
 * The optional `mcp.proxy` section: an HTTP proxy (CONNECT tunneling for both
 * http and https MCP endpoints) used by every http-type MCP server
 * ([McpServerConfig]). Applied as the ktor engine's `proxy` in
 * `mcp/McpEntry.kt`, which routes ALL transport traffic (initialize POST,
 * tool POST, SSE GET, session DELETE) through it. The config model has no
 * username/password fields, so credentials must not be configured here —
 * an authenticated proxy is not supported.
 */
@Serializable
data class McpProxyConfig(
    val host: String,
    val port: Int,
) {
    fun validate() {
        require(host.isNotBlank()) { "mcp.proxy: host must not be blank, got '$host'" }
        require(port in 1..65535) { "mcp.proxy: port must be between 1 and 65535, got $port" }
    }
}
