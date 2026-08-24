package info.skyblond.daapu.config

/**
 * A valid [AppConfig] for unit tests that never touch the database or the
 * LLM (the values are placeholders; a chat run would fail, but every tested
 * path below either validates or fails before reaching them).
 */
fun testAppConfig() = AppConfig(
    database = DatabaseConfig(
        url = "jdbc:postgresql://localhost:5432/postgres",
        user = "postgres",
        password = "postgres",
    ),
    providers = mapOf(
        "bifrost" to LlmProviderConfig(
            apiKey = "test",
            baseUrl = "http://localhost:9"
        ),
        "deepinfra" to LlmProviderConfig(
            apiKey = "test",
            baseUrl = "http://localhost:9"
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
