package info.skyblond.daapu.config

/**
 * A valid [AppConfig] for unit tests that never touch the database or the
 * LLM (the values are placeholders; a chat run would fail, but every tested
 * path below either validates or fails before reaching them).
 *
 * The provider entries mirror `testutil/TestModels.kt` — the same ids and
 * metadata that raw-catalog tests resolve through `testLlm`.
 */

/** The three test chat models, in catalog order. */
internal fun testLlmEntries(): List<LlmModelEntryConfig> = listOf(
    LlmModelEntryConfig(
        modelId = "cerebras/gpt-oss-120b",
        contextLength = 131000,
        maxOutputTokens = 40000,
        capabilities = listOf("reasoning:high", "tool_calls"),
        compactionTriggerFraction = 0.75,
        compactionKeepRounds = 2,
    ),
    LlmModelEntryConfig(
        modelId = "cerebras/gemma-4-31b",
        contextLength = 131072,
        maxOutputTokens = 40000,
        capabilities = listOf("image", "reasoning:high", "tool_calls"),
        compactionTriggerFraction = 0.75,
        compactionKeepRounds = 2,
    ),
    LlmModelEntryConfig(
        modelId = "novita/google/gemma-4-31b-it",
        contextLength = 262144,
        maxOutputTokens = 131072,
        capabilities = listOf("image", "reasoning:high", "tool_calls"),
        compactionTriggerFraction = 0.8,
        compactionKeepRounds = 3,
    ),
)

fun testAppConfig() = AppConfig(
    database = DatabaseConfig(
        url = "jdbc:postgresql://localhost:5432/postgres",
        user = "postgres",
        password = "postgres",
    ),
    providers = mapOf(
        "bifrost" to LlmProviderConfig(
            apiKey = "test",
            baseUrl = "http://localhost:9",
            llm = testLlmEntries(),
            embedding = listOf(
                EmbeddingModelEntryConfig(
                    modelId = "zenmux sub/google/gemini-embedding-2",
                    dimensions = 1536,
                ),
            ),
        ),
        "deepinfra" to LlmProviderConfig(
            apiKey = "test",
            baseUrl = "http://localhost:9",
            embedding = listOf(
                EmbeddingModelEntryConfig(
                    modelId = "Qwen/Qwen3-Embedding-8B",
                    dimensions = 1536,
                ),
            )
        )
    ),
    server = ServerConfig(port = 8080),
    // exa is REQUIRED; tests override the McpToolProvider with an empty
    // provider (TestDi), so the placeholder URL is never connected
    mcp = McpConfig(
        customs = emptyMap(),
        exa = McpServerConfig(
            type = McpTransportType.Http,
            url = "https://mcp.exa.ai/mcp",
            toolExecutionTimeoutSeconds = 120,
        ),
    ),
    memory = MemoryConfig(
        compactModel = "bifrost/cerebras/gemma-4-31b",
        eltm = EltmConfig(
            extractionModel = "bifrost/cerebras/gemma-4-31b",
            embeddingModel = "bifrost/zenmux sub/google/gemini-embedding-2",
            writerModel = "bifrost/cerebras/gemma-4-31b",
            rewriteModel = "bifrost/cerebras/gemma-4-31b",
            rewriteRounds = 5,
            relatedEntitiesLimit = 5,
            relatedNotesLimit = 5,
        ),
    ),
    agent = AgentConfig(
        investigator = InvestigatorConfig(
            model = "bifrost/cerebras/gemma-4-31b",
            allowedNamespaces = listOf("eltm"),
        ),
    ),
    title = TitleConfig(model = "bifrost/cerebras/gemma-4-31b"),
)
