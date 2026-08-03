package info.skyblond.daapu

import com.zaxxer.hikari.HikariDataSource
import info.skyblond.daapu.db.initDatabase
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.testcontainers.postgresql.PostgreSQLContainer

/**
 * Shared Testcontainer used by integration tests.
 *
 * The image matches production (`compose.yaml`): pgvector is available so the
 * `CREATE EXTENSION IF NOT EXISTS vector` migration works in tests too. One
 * container and one Hikari pool are shared across all tests in the JVM, so the
 * Postgres connection limit is never exhausted by per-test pools.
 */
class DaapuPostgres : PostgreSQLContainer("pgvector/pgvector:pg18-trixie") {
    init {
        withDatabaseName("postgres")
        withUsername("postgres")
        withPassword("postgres")
    }

    fun appConfig(): AppConfig = AppConfig(
        databaseUrl = jdbcUrl,
        databaseUser = username,
        databasePassword = password,
        sessionCookieKey = "test-session-cookie-key",
    )

    companion object {
        private var container: DaapuPostgres? = null
        private var dataSource: HikariDataSource? = null

        fun shared(): DaapuPostgres = synchronized(this) {
            container ?: DaapuPostgres().also {
                it.start()
                container = it
            }
        }

        fun sharedDataSource(): HikariDataSource = synchronized(this) {
            dataSource ?: run {
                val db = shared()
                // Runs Flyway migrations and connects Exposed, exactly once.
                initDatabase(db.jdbcUrl, db.username, db.password).also { dataSource = it }
            }
        }
    }
}

/**
 * Boot the application against the shared pgvector container and run [block].
 *
 * The container is shared across all tests in the JVM, so tables are truncated
 * before each test to keep test classes independent (usernames are UNIQUE).
 */
fun withDb(block: suspend ApplicationTestBuilder.() -> Unit) {
    val db = DaapuPostgres.shared()
    val dataSource = DaapuPostgres.sharedDataSource()
    testApplication {
        application {
            configureApp(db.appConfig(), dataSource)
            // Remove all rows so each test starts from an empty database.
            truncateAll()
        }
        block()
    }
}

/**
 * Remove all rows so each test starts from an empty database. `users` cascades
 * to `chats` and `messages`.
 */
private fun truncateAll() {
    val connection = java.sql.DriverManager.getConnection(
        DaapuPostgres.shared().jdbcUrl,
        DaapuPostgres.shared().username,
        DaapuPostgres.shared().password,
    )
    connection.use {
        it.createStatement().use { stmt ->
            stmt.execute("TRUNCATE TABLE users CASCADE")
        }
    }
}
