package info.skyblond.daapu

import info.skyblond.daapu.config.loadConfig
import info.skyblond.daapu.db.initDatabase
import info.skyblond.daapu.server.startWebServer

/**
 * PoC entry point: load the configuration (`./config.jsonc`, see
 * `config/Config.kt`), connect the PostgreSQL database, then serve the HTTP
 * API. The web UI (see `frontend/`) is the input loop; it proxies `/api` to
 * this server during development.
 */
fun main() {
    val config = loadConfig()
    // the MCP tool servers come from config.jsonc too (mcp.customs, validated
    // by loadConfig): the provider connects eagerly at construction, so a
    // server that cannot be reached aborts startup instead of silently
    // degrading every chat run.
    initDatabase(config.database.url, config.database.user, config.database.password)
    startWebServer(config)
}
