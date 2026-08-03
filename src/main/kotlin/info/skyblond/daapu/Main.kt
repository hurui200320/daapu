package info.skyblond.daapu

import info.skyblond.daapu.db.initDatabase
import info.skyblond.daapu.llm.ChatAgentService
import info.skyblond.daapu.llm.LlmFactory
import info.skyblond.daapu.llm.PostgresChatHistoryProvider
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("Application")

/**
 * PoC entry point: connect the PostgreSQL database and build the koog agent
 * stack. The actual PoC loop comes later.
 */
fun main() {
    val config = appConfigFromEnv()

    val dataSource = initDatabase(config.databaseUrl, config.databaseUser, config.databasePassword)

    val historyProvider = PostgresChatHistoryProvider()
    val apiKey = requireEnv("LLM_API_KEY")
    val executor = LlmFactory.createExecutor(apiKey, readEnv("LLM_BASE_URL") ?: "https://api.openai.com")
    val model = LlmFactory.createModel(readEnv("LLM_MODEL") ?: "gpt-4o-mini")
    ChatAgentService(
        promptExecutor = executor,
        llmModel = model,
        systemPrompt = readEnv("LLM_SYSTEM_PROMPT") ?: "You are a helpful assistant.",
        historyProvider = historyProvider,
    )

    logger.info("Database initialized; koog agent stack ready (dataSource={})", dataSource)
}
