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

/**
 * The canonical bounds check for an embedding model's output dimensions,
 * shared by the config layer (`LlmProviderConfig`) and the runtime type
 * (`agent/model/EmbeddingModel.kt`) so the contract has ONE source of truth.
 * Throws [IllegalArgumentException] when [dimensions] is unusable: it must be
 * positive and at most [MAX_VECTOR_DIMENSIONS] — pgvector's HNSW limit for
 * the `vector` type, the fixed ELTM column width (shorter vectors are
 * zero-padded on write, wider ones would not fit). [owner] prefixes the
 * violation message with the caller's location so the error points at the
 * offending entry: the config path (`providers['<id>'].embedding[<i>]`) or
 * the runtime model's composite id.
 */
fun checkEmbeddingDimensions(dimensions: Int, owner: String) {
    if (dimensions <= 0) throw IllegalArgumentException("$owner.dimensions must be > 0, got $dimensions")
    if (dimensions > MAX_VECTOR_DIMENSIONS) throw IllegalArgumentException(
        "$owner.dimensions must be at most $MAX_VECTOR_DIMENSIONS (pgvector's HNSW limit " +
                "for the vector type, the fixed ELTM column width), got $dimensions"
    )
}
