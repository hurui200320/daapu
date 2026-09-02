package info.skyblond.daapu.config

import info.skyblond.daapu.agent.tool.SAFE_ID_REGEX
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
    /** The sub-agent settings (see `agent/pipeline/investigate/InvestigatorService.kt`). */
    val agent: AgentConfig,
    /** Session-title generation (a one-shot pipeline service, see `agent/pipeline/TitleGenerator.kt`). */
    val title: TitleConfig,
    /** The hand-pi execution service. */
    val hand: HandConfig,
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
