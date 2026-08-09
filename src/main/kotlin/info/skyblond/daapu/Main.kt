package info.skyblond.daapu

import info.skyblond.daapu.db.initDatabase
import info.skyblond.daapu.server.startWebServer

/**
 * PoC entry point: connect the PostgreSQL database, then serve the HTTP API.
 * The web UI (see `frontend/`) is the input loop; it proxies `/api` to this
 * server during development.
 */
fun main() {
    val config = appConfigFromEnv()
    // MCP tool servers are hardcoded here for the PoC (see AGENTS.md); only
    // their API keys come from the environment/`.env` (process env first,
    // then `./.env`). Add or remove entries freely — a server that cannot be
    // reached at runtime is skipped with a warning and retried on a later
    // chat run, so a broken entry never blocks the app.
    // A missing EXA_API_KEY means no MCP servers at all (default: no tools),
    // matching the README; only the LLM/database keys are required.
    val mcpServers = readEnv("EXA_API_KEY")?.let { exaKey ->
        listOf(
            McpServerConfig(
                name = "exa",
                type = McpTransportType.Http,
                url = "https://mcp.exa.ai/mcp?tools=web_search_exa,web_fetch_exa,web_search_advanced_exa",
                headers = mapOf("Authorization" to "Bearer $exaKey"),
            ),
        )
    } ?: emptyList()
    mcpServers.forEach { it.validate() }
    initDatabase(config.databaseUrl, config.databaseUser, config.databasePassword)
    startWebServer(config, mcpServers)
}
