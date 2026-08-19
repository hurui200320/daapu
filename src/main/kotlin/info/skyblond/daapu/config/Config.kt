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
    val memory: MemoryConfig,
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
        memory.validate()
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
 * (`hand/HandCallbackRoute.kt`). The run-policy knobs are REQUIRED per
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
 * The memory pipeline settings: compaction (`agent/oneshot/compaction/`),
 * the SSTM (short-term memory, `agent/oneshot/sstm/`), and the ELTM
 * (external long-term memory, `memory/eltm/`). The compaction trigger
 * fraction and keep rounds are per-model (`agent/model/LLM.kt`), since they
 * depend on the model's context size. All model ids are REQUIRED and
 * reference the catalog (`agent/ModelCatalog.kt`); they are resolved once at
 * `ChatRunService` construction and reused for every run — a chat run's own
 * model is never used for the one-shot pipeline. Catalog membership is
 * validated at startup by `ChatRunService` (the config layer does not know
 * the catalog).
 */
@Serializable
data class MemoryConfig(
    /** Catalog model id for the compaction summarizer. */
    val compactModel: String,
    /** The short-term memory (SSTM) settings. */
    val sstm: SstmConfig,
    /**
     * The external long-term memory (ELTM) settings.
     */
    val eltm: EltmConfig,
) {
    fun validate() {
        require(compactModel.isNotBlank()) { "memory.compactModel must not be blank" }
        sstm.validate()
        eltm.validate()
    }
}

/**
 * The SSTM (short-term memory) settings: the extractor/merger one-shots
 * that write it (`agent/oneshot/sstm/`) and the capacity/purge knobs that
 * evict it into the ELTM (the only eviction path; the SSTM is always fully
 * in context, so the capacity is the capacity lever, never retrieval). The
 * two model ids are REQUIRED and reference the catalog
 * (`agent/ModelCatalog.kt`); they are resolved once at `ChatRunService`
 * construction (unknown ids and a merge model without tool-call support
 * fail fast).
 */
@Serializable
data class SstmConfig(
    /**
     * Catalog model id for the memory extractor (sees the raw dropped
     * history, images included).
     */
    val extractModel: String,
    /** Catalog model id for the memory merger (a tool loop). */
    val mergeModel: String,
    /** Round cap for the merge tool loop; `0` = unlimited. */
    val maxMergeRounds: Int = 150,
    /**
     * SSTM capacity: the total char length (`String.length`, UTF-16 units)
     * of all memory contents; when the SSTM exceeds it after an update, the
     * oldest entries are purged into the ELTM. A model-agnostic proxy for
     * the injected SSTM size, so the context injection never blows up the
     * context regardless of the model.
     */
    val maxCapacity: Int,
    /** How many SSTM entries one purge batch moves into the ELTM. */
    val purgeBatchSize: Int,
) {
    fun validate() {
        listOf(
            "memory.sstm.extractModel" to extractModel,
            "memory.sstm.mergeModel" to mergeModel,
        ).forEach { (name, id) ->
            require(id.isNotBlank()) { "$name must not be blank" }
        }
        require(maxMergeRounds >= 0) { "memory.sstm.maxMergeRounds must be >= 0, got $maxMergeRounds" }
        require(maxCapacity > 0) { "memory.sstm.maxCapacity must be > 0, got $maxCapacity" }
        require(purgeBatchSize >= 1) { "memory.sstm.purgeBatchSize must be >= 1, got $purgeBatchSize" }
    }
}

/**
 * The ELTM (external long-term memory) settings: the diary model
 * (entities/relationships/notes, see `memory/eltm/`), the models behind it,
 * and the recall tool's knobs. All three model ids are
 * REQUIRED and reference the catalog (`agent/ModelCatalog.kt`); they are
 * resolved once at `ChatRunService` construction (unknown ids and a
 * writer/recall model without tool-call support fail fast). The embedding
 * model's output dimensions must be at most
 * [MAX_VECTOR_DIMENSIONS] (pgvector's HNSW indexing limit): the ELTM
 * columns are fixed at that width and shorter vectors are zero-padded on
 * write, so switching embedding models never needs a schema change.
 */
@Serializable
data class EltmConfig(
    /**
     * Catalog id of the embedding model (`agent/ModelCatalog.kt`), whose
     * entry carries the output dimensions. REQUIRED.
     */
    val embeddingModel: String,
    /** Catalog LLM id of the ELTM writer (a tool loop); REQUIRED. */
    val writerModel: String,
    /** Catalog LLM id of the recall sub-session (a tool loop); REQUIRED. */
    val recallModel: String,
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
    /** Execution budget of one recall tool call in seconds; `0` = none. */
    val recallTimeoutSeconds: Long = 600,
    /** Round cap for the ELTM writer tool loop; `0` = unlimited. */
    val maxWriterRounds: Int = 150,
) {
    fun validate() {
        listOf(
            "memory.eltm.embeddingModel" to embeddingModel,
            "memory.eltm.writerModel" to writerModel,
            "memory.eltm.recallModel" to recallModel,
        ).forEach { (name, id) ->
            require(id.isNotBlank()) { "$name must not be blank" }
        }
        require(entityMatchThreshold in 0.0..1.0) {
            "memory.eltm.entityMatchThreshold must be in [0, 1], got $entityMatchThreshold"
        }
        require(noteSearchThreshold in 0.0..1.0) {
            "memory.eltm.noteSearchThreshold must be in [0, 1], got $noteSearchThreshold"
        }
        require(recallTimeoutSeconds >= 0) {
            "memory.eltm.recallTimeoutSeconds must be >= 0, got $recallTimeoutSeconds"
        }
        require(maxWriterRounds >= 0) { "memory.eltm.maxWriterRounds must be >= 0, got $maxWriterRounds" }
    }
}

/**
 * The session-title generation settings (see `agent/oneshot/TitleGenerator.kt`).
 * The model id is REQUIRED and references the catalog
 * (`agent/ModelCatalog.kt`); it is resolved once at `ChatRunService`
 * construction, like the memory pipeline models — a chat run's own model is
 * never used for it.
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
 * ([TOOL_RESERVED_NAMESPACES]) must not be used.
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
 * Namespaces reserved for the harness's own internal/harness tools: an MCP
 * server must not use one of these, or its advertised tool names would
 * collide with the internal tools' namespaces. All lowercase, matching
 * [SAFE_ID_REGEX] — the check in [McpServerConfig.validate] is exact.
 */
val TOOL_RESERVED_NAMESPACES: Set<String> = setOf(
    "system", "inner", "internal", "gsg",
    "sstm", "eltm", "harness"
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
 * The fixed width of every pgvector column in the ELTM tables
 * (`db/Tables.kt`, `V1__init.sql`): pgvector's HNSW indexing limit for the
 * `vector` type. Embedding models may output fewer dimensions. The service
 * zero-pads every vector (and every query) to this width on writing. Cosine
 * similarity is invariant under zero-padding, and the fixed width means
 * switching embedding models never requires a schema change (the model's
 * output dimensions must only not exceed this limit).
 */
const val MAX_VECTOR_DIMENSIONS: Int = 2000
