package info.skyblond.daapu

import java.io.File

/**
 * Read a configuration value from the process environment first (used by
 * docker compose via `environment:`/`env_file:`), falling back to the local
 * `./.env` file for development.
 */
fun readEnv(key: String): String? = System.getenv(key) ?: readEnvFile(key)

/**
 * Parse a minimal `.env` file (comments after `#` are stripped; lines without
 * an `=` are skipped).
 */
private fun readEnvFile(key: String): String? {
    val envFile = File("./.env")
    // A missing file is the same as a missing key; without this check the
    // FileNotFoundException below would escape requireEnv() and report an
    // unhelpful I/O error instead of the intended "key not present" message.
    if (!envFile.exists()) return null
    return envFile.useLines { lines ->
        lines.asSequence()
            .map { it.split('#')[0].trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) return@mapNotNull null
                line.substring(0, separator).trim() to line.substring(separator + 1)
            }
            .toMap()[key]
    }
}

fun requireEnv(key: String): String =
    readEnv(key) ?: throw IllegalArgumentException("$key is not present in the environment or .env")

data class AppConfig(
    val databaseUrl: String,
    val databaseUser: String,
    val databasePassword: String,
    val sessionCookieKey: String,
    val llmApiKey: String? = null,
    val llmBaseUrl: String = "https://api.openai.com",
    val llmModel: String = "gpt-4o-mini",
    val llmSystemPrompt: String = "You are a helpful assistant.",
)

fun appConfigFromEnv(): AppConfig = AppConfig(
    databaseUrl = requireEnv("DATABASE_URL"),
    databaseUser = requireEnv("DATABASE_USER"),
    databasePassword = requireEnv("DATABASE_PASSWORD"),
    sessionCookieKey = requireEnv("SESSION_COOKIE_KEY"),
    llmApiKey = readEnv("LLM_API_KEY")?.takeIf { it.isNotBlank() },
    llmBaseUrl = readEnv("LLM_BASE_URL") ?: "https://api.openai.com",
    llmModel = readEnv("LLM_MODEL") ?: "gpt-4o-mini",
    // TODO: make this configurable for each chat
    llmSystemPrompt = readEnv("LLM_SYSTEM_PROMPT") ?: "You are a helpful assistant.",
)