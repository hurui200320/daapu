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
    initDatabase(config.databaseUrl, config.databaseUser, config.databasePassword)
    startWebServer(config)
}
