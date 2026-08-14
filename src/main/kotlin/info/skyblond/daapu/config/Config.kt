package info.skyblond.daapu.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Load and validate the app configuration from `./config.jsonc` (JSON with
 * C-style comments and trailing commas — what editors like VSCode accept when
 * editing the file). The file contains API keys, so it is gitignored; the
 * checked-in `config.example.jsonc` documents the shape, and
 * `config.schema.json` (draft-07) mirrors these models for editor support —
 * keep the schema in sync when the models change (see AGENTS.md).
 */
fun loadConfig(configFile: File = File("./config.jsonc")): AppConfig {
    if (!configFile.exists()) {
        throw IllegalArgumentException(
            "config file $configFile does not exist: copy config.example.jsonc to config.jsonc and fill in the values"
        )
    }
    return decodeAppConfig(configFile.readText())
}

private val CONFIG_JSON = Json {
    // jsonc: C-style comments and trailing commas. `$schema` is a known
    // field (see AppConfig.schema), and parsing is strict: an unknown key
    // fails fast (mirroring the schema's additionalProperties:false) instead
    // of being silently dropped — a config that does not match this binary's
    // models is a bug, not a future-proofing concern.
    allowComments = true
    allowTrailingComma = true
}

internal fun decodeAppConfig(text: String): AppConfig {
    val config = CONFIG_JSON.decodeFromString<AppConfig>(text)
    config.validate()
    return config
}

/**
 * The whole configuration surface of the harness. Built once at startup from
 * `config.jsonc` ([loadConfig]) and shared by the API server, the chat runs,
 * and the MCP tool provider.
 */
@Serializable
data class AppConfig(
    val database: DatabaseConfig,
    val providers: Map<String, LlmProviderConfig>,
    val server: ServerConfig = ServerConfig(),
    val mcp: McpConfig = McpConfig(),
    val memory: MemoryConfig = MemoryConfig(),
    /** The hand-pi execution service. */
    val hand: HandConfig = HandConfig(),
    // editor hint declared by config.schema.json and config.example.jsonc;
    // a known field so the strict parser accepts files that carry it
    @SerialName("\$schema")
    val schema: String? = null,
) {
    fun validate() {
        database.validate()
        providers.forEach { (id, provider) ->
            require(id.matches(SAFE_ID_REGEX)) {
                "providers key '$id' is invalid: only [0-9a-z_-] is allowed"
            }
            provider.validate(id)
        }
        server.validate()
        mcp.validate()
        memory.validate()
        hand.validate()
    }
}

/**
 * The hand-pi execution service (`hand-pi/`): a stateless
 * Node service that owns LLM execution (streaming, dialects, tool-call
 * accumulation, retries, usage). The brain passes provider/model
 * configuration per request; only the loopback endpoint and the shared
 * static token are configured here. The token also authenticates the
 * hand's tool callbacks into this process (`hand/HandCallbackRoute.kt`).
 */
@Serializable
data class HandConfig(
    /** The hand's base URL, e.g. `http://127.0.0.1:3100`. */
    val baseUrl: String = "http://127.0.0.1:3100",
    /** The shared static token (`HAND_TOKEN` on the hand's side). */
    val token: String = "dev-token",
) {
    fun validate() {
        require(baseUrl.isNotBlank()) { "hand.baseUrl must not be blank" }
        require(baseUrl.startsWith("http://") || baseUrl.startsWith("https://")) {
            "hand.baseUrl must be an http(s) URL, got '$baseUrl'"
        }
    }
}

/**
 * The memory pipeline settings (history compaction + SSTM extraction, see
 * `agent/oneshot/`). The model ids reference the catalog
 * (`agent/ModelCatalog.kt`); `null` uses the run's model.
 * Catalog membership is validated at startup by `ChatRunService` (the config
 * layer does not know the catalog).
 */
@Serializable
data class MemoryConfig(
    /**
     * Pre-round compaction trigger: compact when the estimated prompt size
     * exceeds this fraction of the run model's context window. `0.0`
     * disables the proactive path (the reactive `ContextExhausted` path
     * still compacts).
     */
    val compactionTriggerFraction: Double = 0.8,
    /** Complete rounds kept verbatim at the tail of a compacted chat. */
    val compactionKeepRounds: Int = 2,
    /** Catalog model id for the compaction summarizer; null = the run's model. */
    val compactModel: String? = null,
    /**
     * Catalog model id for the memory extractor (sees the raw dropped
     * history, images included); null = the run's model.
     */
    val extractModel: String? = null,
    /** Catalog model id for the memory merger (a tool loop); null = the run's model. */
    val mergeModel: String? = null,
) {
    fun validate() {
        require(compactionTriggerFraction in 0.0..1.0) {
            "memory.compactionTriggerFraction must be in [0, 1], got $compactionTriggerFraction"
        }
        require(compactionKeepRounds >= 1) {
            "memory.compactionKeepRounds must be at least 1, got $compactionKeepRounds"
        }
        listOf(
            "memory.compactModel" to compactModel,
            "memory.extractModel" to extractModel,
            "memory.mergeModel" to mergeModel,
        ).forEach { (name, id) ->
            require(id == null || id.isNotBlank()) { "$name must not be blank when set" }
        }
    }
}

/**
 * PostgreSQL connection (pgvector enabled). All fields are required and must
 * not be blank: `DATABASE_URL=`-style holes would otherwise fail later with a
 * confusing JDBC/auth error instead of the intended config error.
 */
@Serializable
data class DatabaseConfig(
    val url: String,
    val user: String,
    val password: String,
) {
    fun validate() {
        require(url.isNotBlank()) { "database.url must not be blank" }
        require(user.isNotBlank()) { "database.user must not be blank" }
        require(password.isNotBlank()) { "database.password must not be blank" }
    }
}

/**
 * One OpenAI-compatible LLM provider, keyed by its id in [AppConfig.providers]
 * (`{provider.id}/{modelId}` prefixes every model id, so the id must match
 * [SAFE_ID_REGEX] — enforced at provider construction, see
 * `agent/hand/HandMappers.kt`). [baseUrl] is used as-is by the hand
 * (`hand-pi/`), which appends the OpenAI API path to it — so the value must
 * carry the full `/v1` root (e.g. `http://localhost:8000/v1`); nothing
 * appends `/v1`.
 */
@Serializable
data class LlmProviderConfig(
    val apiKey: String,
    val baseUrl: String,
) {
    fun validate(id: String) {
        require(apiKey.isNotBlank()) { "providers['$id'].apiKey must not be blank" }
        require(baseUrl.isNotBlank()) { "providers['$id'].baseUrl must not be blank" }
    }
}

/**
 * The HTTP API server settings.
 */
@Serializable
data class ServerConfig(
    val port: Int = 8080,
) {
    fun validate() {
        require(port in 1..65535) { "server.port must be between 1 and 65535, got $port" }
    }
}

/**
 * MCP tool servers, all tools advertised to every chat run ("one global tool
 * set" for the PoC). Empty by default: no MCP server configured means no
 * tools, matching the pre-config behavior without an EXA_API_KEY.
 */
@Serializable
data class McpConfig(
    val servers: List<McpServerConfig> = emptyList(),
) {
    fun validate() = servers.forEach { it.validate() }
}

@Serializable
enum class McpTransportType {
    @SerialName("http")
    Http,

    @SerialName("stdio")
    Stdio,
}

/**
 * One MCP tool server, configured in `config.jsonc` under `mcp.servers`
 * (before the config move the exa server was hardcoded in `Main.kt` with only
 * its API key in the environment/`.env`). Maps 1:1 to the MCP SDK
 * builders (see the #3 spike's config surface notes):
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
@Serializable
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
        // mirror config.schema.json's minimum: 1 — 0/negative would reach the
        // SDK transport builders (McpEntry) as a nonsense Duration
        require(initializationTimeoutSeconds == null || initializationTimeoutSeconds >= 1) {
            "MCP server '$namespace': initializationTimeoutSeconds must be at least 1, got $initializationTimeoutSeconds"
        }
        require(toolExecutionTimeoutSeconds == null || toolExecutionTimeoutSeconds >= 1) {
            "MCP server '$namespace': toolExecutionTimeoutSeconds must be at least 1, got $toolExecutionTimeoutSeconds"
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
