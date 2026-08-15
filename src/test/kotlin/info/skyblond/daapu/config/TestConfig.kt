package info.skyblond.daapu.config

/**
 * A valid [AppConfig] for unit tests that never touch the database or the
 * LLM (the values are placeholders; a chat run would fail, but every tested
 * path below either validates or fails before reaching them).
 */
fun testAppConfig(mcpServers: List<McpServerConfig> = emptyList()) = AppConfig(
    database = DatabaseConfig(
        url = "jdbc:postgresql://localhost:5432/postgres",
        user = "postgres",
        password = "postgres",
    ),
    providers = mapOf("bifrost" to LlmProviderConfig(apiKey = "test", baseUrl = "http://localhost:9")),
    server = ServerConfig(port = 8080),
    mcp = McpConfig(servers = mcpServers),
    memory = MemoryConfig(
        compactModel = "bifrost/cerebras/gemma-4-31b",
        extractModel = "bifrost/cerebras/gemma-4-31b",
        mergeModel = "bifrost/cerebras/gemma-4-31b",
    ),
    title = TitleConfig(model = "bifrost/cerebras/gemma-4-31b"),
)
