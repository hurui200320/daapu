package info.skyblond.daapu

import java.io.File

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
 * [name] is used to namespace the advertised tool names (`{name}_{tool}`), so
 * tools from different servers never collide in the model request.
 */
data class McpServerConfig(
    val name: String,
    val type: McpTransportType,
    val url: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val command: List<String> = emptyList(),
    val environment: Map<String, String> = emptyMap(),
    val initializationTimeoutSeconds: Long? = null,
    val toolExecutionTimeoutSeconds: Long? = null,
) {
    /**
     * Fail fast on a config that cannot work: the name becomes part of the
     * advertised tool name (`{name}_{tool}`, which OpenAI-compatible gateways
     * only accept in `[a-zA-Z0-9_-]`), and each transport requires its own
     * fields.
     */
    fun validate() {
        if (name.isBlank()) throw IllegalArgumentException("MCP server config is missing a name")
        if (!name.matches(Regex("[a-zA-Z0-9_-]+"))) {
            throw IllegalArgumentException(
                "MCP server name '$name' is invalid: tool names are prefixed with it, so only [a-zA-Z0-9_-] is allowed"
            )
        }
        when (type) {
            McpTransportType.Http -> if (url.isNullOrBlank()) {
                throw IllegalArgumentException("MCP server '$name': type 'http' requires a url")
            }
            McpTransportType.Stdio -> if (command.isEmpty()) {
                throw IllegalArgumentException("MCP server '$name': type 'stdio' requires a command")
            }
        }
        if (url != null && !url.startsWith("http://") && !url.startsWith("https://")) {
            throw IllegalArgumentException("MCP server '$name': url must start with http:// or https://, got '$url'")
        }
    }
}