package info.skyblond.daapu

import java.io.File

/**
 * Read a configuration value from the process environment first (used by
 * docker compose via `environment:`/`env_file:`), falling back to the local
 * `./.env` file for development.
 *
 * Blank values count as missing: `DATABASE_URL=` would otherwise pass
 * [requireEnv] and fail later with a confusing JDBC/auth error.
 */
fun readEnv(key: String): String? =
    System.getenv(key)?.takeIf { it.isNotBlank() } ?: readEnvFile(key)

/**
 * Parse a minimal `.env` file: blank lines and whole-line comments (lines
 * starting with `#`) are skipped; both the key and value is trimmed.
 * Inline comment like # after the value is not supported.
 */
private fun readEnvFile(key: String): String? {
    val envFile = File("./.env")
    // A missing file is the same as a missing key; without this check the
    // FileNotFoundException below would escape requireEnv() and report an
    // unhelpful I/O error instead of the intended "key not present" message.
    if (!envFile.exists()) return null
    return envFile.useLines { lines ->
        lines.asSequence()
            // filter blank line and comment line (start with #)
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .mapNotNull { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) return@mapNotNull null
                line.substring(0, separator).trim() to line.substring(separator + 1).trim()
            }
            .toMap()[key]
            // a blank value in .env is the same as a missing key
            ?.takeIf { it.isNotBlank() }
    }
}

fun requireEnv(key: String): String =
    readEnv(key) ?: throw IllegalArgumentException("$key is not present in the environment or .env")

data class AppConfig(
    val databaseUrl: String,
    val databaseUser: String,
    val databasePassword: String,
    val llmApiKey: String,
    val llmBaseUrl: String,
    val httpPort: Int,
)

fun appConfigFromEnv(): AppConfig = AppConfig(
    databaseUrl = requireEnv("DATABASE_URL"),
    databaseUser = requireEnv("DATABASE_USER"),
    databasePassword = requireEnv("DATABASE_PASSWORD"),
    llmApiKey = requireEnv("LLM_API_KEY"),
    llmBaseUrl = requireEnv("LLM_BASE_URL"),
    httpPort = readEnv("HTTP_PORT")?.toIntOrNull() ?: 8080,
)