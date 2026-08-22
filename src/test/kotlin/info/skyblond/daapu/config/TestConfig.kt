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
    mcp = McpConfig(servers = emptyList()),
    memory = MemoryConfig(
        compactModel = "bifrost/cerebras/gemma-4-31b",
        sstm = SstmConfig(
            extractModel = "bifrost/cerebras/gemma-4-31b",
            mergeModel = "bifrost/cerebras/gemma-4-31b",
            maxCapacity = 100,
            purgeBatchSize = 10,
        ),
        eltm = EltmConfig(
            embeddingModel = "bifrost/zenmux sub/google/gemini-embedding-2",
            writerModel = "bifrost/cerebras/gemma-4-31b",
            recallModel = "bifrost/cerebras/gemma-4-31b",
        ),
    ),
    title = TitleConfig(model = "bifrost/cerebras/gemma-4-31b"),
)
