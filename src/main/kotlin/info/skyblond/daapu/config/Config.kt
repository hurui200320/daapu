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
    val mcp: McpConfig,
    /** The harness-owned tool providers (see `ToolConfig`; today only the read-only filesystem provider). */
    val tool: ToolConfig = ToolConfig(),
    val memory: MemoryConfig,
    /** The sub-agent settings (see `agent/oneshot/investigate/InvestigatorService.kt`). */
    val agent: AgentConfig,
    /** Session-title generation (a one-shot pipeline service, see `agent/oneshot/TitleGenerator.kt`). */
    val title: TitleConfig,
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
        tool.validate()
        memory.validate()
        agent.validate()
        title.validate()
        hand.validate()
    }
}

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
    /** Transient retries per round; 0 = unlimited. */
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

/**
 * The memory pipeline settings: compaction (`agent/oneshot/compaction/`)
 * and the ELTM (external long-term memory, `memory/eltm/`). The compaction
 * trigger fraction and keep rounds are per-model (`agent/model/LLM.kt`),
 * since they depend on the model's context size. All model ids are REQUIRED
 * and reference the catalog (`agent/ModelCatalog.kt`); they are resolved
 * once at startup by the DI container (`di/AppModule.kt`) and reused for
 * every run — a chat run's own model is never used for the one-shot
 * pipeline. Catalog membership is validated at startup (the config layer
 * does not know the catalog).
 */
@Serializable
data class MemoryConfig(
    /** Catalog model id for the compaction summarizer. */
    val compactModel: String,
    /**
     * The external long-term memory (ELTM) settings.
     */
    val eltm: EltmConfig,
) {
    fun validate() {
        require(compactModel.isNotBlank()) { "memory.compactModel must not be blank" }
        eltm.validate()
    }
}

/**
 * The ELTM (external long-term memory) settings: the diary model
 * (entities/relationships/notes, see `memory/eltm/`), the models behind it,
 * and the writer's knobs. The model ids are
 * REQUIRED and reference the catalog (`agent/ModelCatalog.kt`); the
 * extraction, embedding, writer, and rewrite ids are resolved once at
 * startup by the DI container (`di/AppModule.kt`; unknown ids and a
 * writer model without tool-call support fail fast). The embedding
 * model's output dimensions must be at most
 * [MAX_VECTOR_DIMENSIONS] (pgvector's HNSW indexing limit): the ELTM
 * columns are fixed at that width and shorter vectors are zero-padded on
 * write, so switching embedding models never needs a schema change.
 */
@Serializable
data class EltmConfig(
    /**
     * Catalog model id for the memory extractor (sees the raw dropped
     * history, images included); no tool call support required. REQUIRED.
     */
    val extractionModel: String,
    /**
     * Catalog id of the embedding model (`agent/ModelCatalog.kt`), whose
     * entry carries the output dimensions. REQUIRED.
     */
    val embeddingModel: String,
    /** Catalog LLM id of the ELTM writer (a tool loop); REQUIRED. */
    val writerModel: String,
    /**
     * Catalog LLM id of the query rewrite one-shot (a no-tools `/v1/run`,
     * see `agent/oneshot/rewrite/QueryRewriteService.kt`): rewrites the
     * run's latest input into standalone retrieval queries before the chat
     * round; REQUIRED.
     */
    val rewriteModel: String,
    /**
     * How many trailing user rounds of the chat feed the query rewrite
     * one-shot; must be at least 1. REQUIRED: the round limit is related to
     * the rewrite model's context size, so it must be explicit.
     */
    val rewriteRounds: Int,
    /**
     * How many related entities the ELTM context injection (search seeded by
     * the rewritten query) puts into `<memories>`' `<related-entities>`;
     * REQUIRED — the injected size is related to the main model's context,
     * so the operator must set it explicitly. `0` skips the entity search
     * (no embed call, empty section).
     */
    val relatedEntitiesLimit: Int,
    /**
     * How many related diary notes the ELTM context injection puts into
     * `<memories>`' `<related-notes>`; REQUIRED like
     * [relatedEntitiesLimit]. `0` skips the note search (no embed call,
     * empty section); with both limits `0`, the query rewrite one-shot is
     * skipped too (it exists only to feed these searches).
     */
    val relatedNotesLimit: Int,
    /**
     * Vector cosine similarity floor for the `create_entity` near-match
     * candidates (0..1): a candidate above it is offered to the writer LLM
     * for disambiguation/merge decisions.
     */
    val entityMatchThreshold: Double = 0.5,
    /**
     * Vector cosine similarity floor for `search_notes` (0..1): the RAG
     * floor under which a diary entry is not surfaced.
     */
    val noteSearchThreshold: Double = 0.1,
    /** Round cap for the ELTM writer tool loop; `0` = unlimited. */
    val maxWriterRounds: Int = 150,
) {
    fun validate() {
        listOf(
            "memory.eltm.extractionModel" to extractionModel,
            "memory.eltm.embeddingModel" to embeddingModel,
            "memory.eltm.writerModel" to writerModel,
            "memory.eltm.rewriteModel" to rewriteModel,
        ).forEach { (name, id) ->
            require(id.isNotBlank()) { "$name must not be blank" }
        }
        require(rewriteRounds >= 1) { "memory.eltm.rewriteRounds must be >= 1, got $rewriteRounds" }
        require(relatedEntitiesLimit >= 0) {
            "memory.eltm.relatedEntitiesLimit must be >= 0, got $relatedEntitiesLimit"
        }
        require(relatedNotesLimit >= 0) {
            "memory.eltm.relatedNotesLimit must be >= 0, got $relatedNotesLimit"
        }
        require(entityMatchThreshold in 0.0..1.0) {
            "memory.eltm.entityMatchThreshold must be in [0, 1], got $entityMatchThreshold"
        }
        require(noteSearchThreshold in 0.0..1.0) {
            "memory.eltm.noteSearchThreshold must be in [0, 1], got $noteSearchThreshold"
        }
        require(maxWriterRounds >= 0) { "memory.eltm.maxWriterRounds must be >= 0, got $maxWriterRounds" }
    }
}

/**
 * The sub-agent settings: the investigate agent (`agent/oneshot/investigate/
 * InvestigatorService.kt`), a `runCollect` tool loop that gathers
 * information from the ELTM (read-only) and the web (MCP tools) on behalf
 * of the main agent.
 */
@Serializable
data class AgentConfig(
    /** The investigate sub-agent settings. */
    val investigator: InvestigatorConfig,
) {
    fun validate() {
        investigator.validate()
    }
}

/**
 * The investigate sub-agent settings (see [AgentConfig]). The model id is
 * REQUIRED and references the catalog (`agent/ModelCatalog.kt`); it is
 * resolved once at startup by the DI container (`di/AppModule.kt`) like
 * the memory pipeline models — unknown ids and a model without tool-call
 * support fail fast. [allowedNamespaces] is the sub-agent's tool whitelist
 * over its OWN tool set (the read-only `eltm` tools plus the MCP servers,
 * built separately from the chat loop's set): REQUIRED, non-empty, and
 * every entry must be a namespace that set serves (a typo — or an attempt
 * to whitelist `gsg` itself, which would enable recursion — fails fast at
 * boot via the `WhitelistedToolProvider` construction).
 */
@Serializable
data class InvestigatorConfig(
    /** Catalog LLM id of the investigate sub-agent (a tool loop); REQUIRED. */
    val model: String,
    /** Round cap for the investigate tool loop; `0` = unlimited. */
    val maxRounds: Int = 150,
    /**
     * The namespaces the investigate sub-agent may execute (a whitelist
     * over its own tool set — the read-only `eltm` tools plus the MCP
     * servers); REQUIRED, non-empty. Entries are validated like any tool
     * namespace.
     */
    val allowedNamespaces: List<String>,
) {
    fun validate() {
        require(model.isNotBlank()) { "agent.investigator.model must not be blank" }
        require(maxRounds >= 0) {
            "agent.investigator.maxRounds must be >= 0, got $maxRounds"
        }
        require(allowedNamespaces.isNotEmpty()) {
            "agent.investigator.allowedNamespaces must not be empty"
        }
        allowedNamespaces.forEach {
            require(it.isNotBlank()) {
                "agent.investigator.allowedNamespaces entries must not be blank"
            }
            validateToolNamespaceSyntax(it, "agent.investigator.allowedNamespaces")
        }
    }
}

/**
 * The session-title generation settings (see `agent/oneshot/TitleGenerator.kt`).
 * The model id is REQUIRED and references the catalog
 * (`agent/ModelCatalog.kt`); it is resolved once at startup by the DI
 * container (`di/AppModule.kt`), like the memory pipeline models — a
 * chat run's own model is never used for it.
 */
@Serializable
data class TitleConfig(
    /** Catalog model id for the title generator. */
    val model: String,
    /**
     * How many trailing user rounds of the history feed the title generator;
     * `0` (default) means the whole history.
     */
    val lastNRound: Int = 0,
) {
    fun validate() {
        require(model.isNotBlank()) { "title.model must not be blank" }
        require(lastNRound >= 0) { "title.lastNRound must be >= 0, got $lastNRound" }
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

/**
 * The tool provider settings, the harness-owned tools that are not MCP
 * servers: today only the read-only filesystem provider (`tool.fs`), the
 * replacement for the vanilla filesystem MCP server's read-only half (that
 * server has no read-only mode; ours is hardcoded to it).
 */
@Serializable
data class ToolConfig(
    /** The read-only filesystem tool provider, see [FsToolConfig]. */
    val fs: FsToolConfig = FsToolConfig(),
) {
    fun validate() = fs.validate()
}

/**
 * The read-only filesystem tool provider (`agent/tool/filesystem/
 * FsToolProvider.kt`): a local mock of the read-only tools of the vanilla
 * filesystem MCP server, restricted to [allowedDirs] with [blacklists] glob
 * patterns on top (e.g. any `.env` file at any depth, anything under
 * `secrets` — blob/gitignore-style patterns). Its namespace is hardcoded to
 * `fs` (advertised names
 * `fs__read_text_file`, ...), so it cannot coexist with an MCP server under
 * the same namespace: a user who wants read-write access uses the vanilla
 * filesystem MCP server instead and keeps this provider disabled — enabling
 * both fails fast at boot on the duplicate namespace.
 *
 * All fields are ignored while [enabled] is false.
 */
@Serializable
data class FsToolConfig(
    /** Whether the provider is built into the chat loop's (and the investigate sub-agent's) tool set. */
    val enabled: Boolean = false,
    /**
     * The directories the LLM may access, REQUIRED non-empty when enabled.
     * `~` is expanded; each directory must exist and be a directory at boot
     * (the provider canonicalizes them and fails fast otherwise — a typo'd
     * path must abort startup, not silently expose nothing).
     */
    val allowedDirs: List<String> = emptyList(),
    /**
     * Glob patterns (blob/gitignore syntax, e.g. any `.env` file at any
     * depth or anything under `secrets`) matched against the paths relative
     * to the allowed directories. A blacklisted path is refused as the
     * TARGET of a tool call (reading a blocked file, listing a blocked
     * folder, ...); listing and search results that merely contain
     * blacklisted entries are returned as-is. Blank patterns are rejected
     * (a blank pattern matches everything).
     */
    val blacklists: List<String> = emptyList(),
) {
    fun validate() {
        if (!enabled) return
        require(allowedDirs.isNotEmpty()) {
            "tool.fs.allowedDirs must not be empty when tool.fs.enabled is true: the LLM would have no directory to access"
        }
        require(allowedDirs.none { it.isBlank() }) {
            "tool.fs.allowedDirs must not contain blank entries"
        }
        require(blacklists.none { it.isBlank() }) {
            "tool.fs.blacklists must not contain blank entries (a blank pattern matches everything)"
        }
    }
}

/**
 * Namespaces reserved for the harness's own internal/harness tools: an MCP
 * server must not use one of these, or its advertised tool names would
 * collide with the internal tools' namespaces. All lowercase, matching
 * [SAFE_ID_REGEX] — the check in [McpServerConfig.validate] is exact.
 */
val TOOL_RESERVED_NAMESPACES: Set<String> = setOf(
    "system", "inner", "internal", "gsg",
    "eltm", "harness"
)

/**
 * Charset for ids that become part of wire-visible strings: MCP namespaces
 * (prefixed onto every advertised tool name) and provider ids (prefixed onto
 * every model id served via `/api/models` and stored in chat history).
 * OpenAI-compatible gateways only accept `[0-9a-z_-]` in such strings;
 * uppercase is rejected so the reserved-namespace check stays an exact match.
 */
val SAFE_ID_REGEX: Regex = Regex("[0-9a-z_-]+")

/**
 * Fail fast on a namespace that cannot become part of an advertised tool
 * name. A blank namespace is allowed (the one-shot providers' default: tools
 * are advertised unprefixed, e.g. `agent/oneshot/eltm/EltmToolProvider.kt`);
 * a non-blank one must match [SAFE_ID_REGEX] and must not contain the `__`
 * separator that joins namespaces to tool names. Shared by
 * [McpServerConfig.validate] and the namespaced tool providers; reserved
 * names are a caller-specific concern ([TOOL_RESERVED_NAMESPACES] applies to
 * MCP servers only — the internal tools own those namespaces).
 */
fun validateToolNamespaceSyntax(namespace: String, owner: String) {
    if (namespace.isBlank()) return
    if (!namespace.matches(SAFE_ID_REGEX)) {
        throw IllegalArgumentException(
            "$owner namespace '$namespace' is invalid: tool names are prefixed with it, so only [0-9a-z_-] is allowed"
        )
    }
    if (namespace.contains("__")) {
        throw IllegalArgumentException(
            "$owner namespace '$namespace' is invalid: it must not contain '__', which separates the parts of advertised tool names"
        )
    }
}

/**
 * The fixed width of every pgvector column in the ELTM tables
 * (`db/Tables.kt`, `V1__init.sql`): pgvector's HNSW indexing limit for the
 * `vector` type. Embedding models may output fewer dimensions. The service
 * zero-pads every vector (and every query) to this width on writing. Cosine
 * similarity is invariant under zero-padding, and the fixed width means
 * switching embedding models never requires a schema change (the model's
 * output dimensions must only not exceed this limit).
 */
const val MAX_VECTOR_DIMENSIONS: Int = 2000
