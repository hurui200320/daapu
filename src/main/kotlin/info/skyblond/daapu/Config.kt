package info.skyblond.daapu

import java.io.File

// TODO: pull config from a jsonc file?

/**
 * Read a configuration value from the process environment first (used by
 * docker compose via `environment:`/`env_file:`), falling back to the local
 * `./.env` file for development.
 *
 * Blank values count as missing: `DATABASE_URL=` would otherwise pass
 * [requireEnv] and fail later with a confusing JDBC/auth error.
 */
fun readEnv(key: String): String? =
    System.getenv(key)?.takeIf { it.isNotBlank() } ?: readEnvFile(key)

/**
 * Parse a minimal `.env` file: blank lines and whole-line comments (lines
 * starting with `#`) are skipped; both the key and value is trimmed.
 * Inline comment like # after the value is not supported.
 */
private fun readEnvFile(key: String): String? {
    val envFile = File("./.env")
    // A missing file is the same as a missing key; without this check the
    // FileNotFoundException below would escape requireEnv() and report an
    // unhelpful I/O error instead of the intended "key not present" message.
    if (!envFile.exists()) return null
    return envFile.useLines { lines ->
        lines.asSequence()
            // filter blank line and comment line (start with #)
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .mapNotNull { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) return@mapNotNull null
                line.substring(0, separator).trim() to line.substring(separator + 1).trim()
            }
            .toMap()[key]
            // a blank value in .env is the same as a missing key
            ?.takeIf { it.isNotBlank() }
    }
}

fun requireEnv(key: String): String =
    readEnv(key) ?: throw IllegalArgumentException("$key is not present in the environment or .env")

data class AppConfig(
    val databaseUrl: String,
    val databaseUser: String,
    val databasePassword: String,
    val llmApiKey: String,
    val llmBaseUrl: String,
    val httpPort: Int,
)

fun appConfigFromEnv(): AppConfig = AppConfig(
    databaseUrl = requireEnv("DATABASE_URL"),
    databaseUser = requireEnv("DATABASE_USER"),
    databasePassword = requireEnv("DATABASE_PASSWORD"),
    llmApiKey = requireEnv("LLM_API_KEY"),
    llmBaseUrl = requireEnv("LLM_BASE_URL"),
    httpPort = readEnv("HTTP_PORT")?.toIntOrNull() ?: 8080,
)

enum class McpTransportType {
    Http,
    Stdio,
}

/**
 * One MCP tool server, hardcoded in `Main.kt` (PoC choice — see AGENTS.md);
 * only its API keys come from the environment/`.env` (e.g. the exa server's
 * `Authorization` header built from the optional `EXA_API_KEY`). Maps 1:1
 * to the langchain4j-mcp builders (see the #3 spike's config surface notes):
 *
 * - `http`: a Streamable-HTTP server ([url], optional [headers]).
 * - `stdio`: a local subprocess ([command] list; [environment] extra
 *   variables merged onto the inherited environment).
 *
 * [namespace] namespaces the advertised tool names (`{namespace}__{tool}`),
 * so tools from different servers never collide in the model request.
 * Namespaces the harness reserves for its own internal/harness tools
 * ([MCP_RESERVED_NAMESPACES]) must not be used.
 */
data class McpServerConfig(
    val namespace: String,
    val type: McpTransportType,
    val url: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val command: List<String> = emptyList(),
    val environment: Map<String, String> = emptyMap(),
    val initializationTimeoutSeconds: Long? = null,
    val toolExecutionTimeoutSeconds: Long? = null,
    // total connect attempts including the first one (the provider connects
    // eagerly at startup and reconnects in-turn on demand, see
    // mcp/McpToolProvider.kt); 1 means no retry. 0 is rejected by validate()
    val reconnectAttempts: Int = 3,
    // delay between two connect attempts
    val reconnectDelayMs: Long = 1000L
) {
    /**
     * Fail fast on a config that cannot work: the namespace becomes part of
     * the advertised tool name (`{namespace}__{tool}`, which
     * OpenAI-compatible gateways only accept in `[0-9a-z_-]` and which must
     * not contain the `__` separator), must not collide with the namespaces
     * reserved for internal/harness tools, and each transport requires its
     * own fields.
     */
    fun validate() {
        if (namespace.isBlank()) throw IllegalArgumentException("MCP server config is missing a namespace")
        if (!namespace.matches(SAFE_ID_REGEX)) {
            throw IllegalArgumentException(
                "MCP server namespace '$namespace' is invalid: tool names are prefixed with it, so only [0-9a-z_-] is allowed"
            )
        }
        if (namespace.contains("__")) {
            throw IllegalArgumentException(
                "MCP server namespace '$namespace' is invalid: it must not contain '__', which separates the parts of advertised tool names"
            )
        }
        if (namespace in MCP_RESERVED_NAMESPACES) {
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
        require(reconnectAttempts >= 1) {
            "MCP server '$namespace': reconnectAttempts must be at least 1 (it is the total number of connect attempts, the first one included)"
        }
        require(reconnectDelayMs >= 0) {
            "MCP server '$namespace': reconnectDelayMs must be non-negative"
        }
    }
}

/**
 * Namespaces reserved for the harness's own internal/harness tools: an MCP
 * server must not use one of these, or its advertised tool names would
 * collide with the internal tools' namespaces. All lowercase, matching
 * [SAFE_ID_REGEX] — the check in [McpServerConfig.validate] is exact.
 */
val MCP_RESERVED_NAMESPACES: Set<String> = setOf("system", "inner", "internal", "gsg")

/**
 * Charset for ids that become part of wire-visible strings: MCP namespaces
 * (prefixed onto every advertised tool name) and provider ids (prefixed onto
 * every model id served via `/api/models` and stored in chat history).
 * OpenAI-compatible gateways only accept `[0-9a-z_-]` in such strings;
 * uppercase is rejected so the reserved-namespace check stays an exact match.
 */
val SAFE_ID_REGEX: Regex = Regex("[0-9a-z_-]+")
