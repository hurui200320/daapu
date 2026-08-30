package info.skyblond.daapu.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.inTopLevelSuspendTransaction

/**
 * Set up the connection pool, run Flyway migrations, and connect Exposed.
 *
 * Migrations run before Exposed connects so that the schema always exists by the
 * time any query is issued. On a migration failure the pool is closed before the
 * error propagates (fail fast — the process is coming down either way, but no
 * connections are left parked open). On success the HikariDataSource is
 * intentionally not closed here: it lives for the whole process and is released
 * on JVM shutdown.
 */
fun initDatabase(url: String, user: String, password: String) {
    val config = HikariConfig().apply {
        jdbcUrl = url
        username = user
        this.password = password
        maximumPoolSize = 10
    }
    val dataSource = HikariDataSource(config)

    try {
        Flyway.configure()
            .dataSource(dataSource)
            .load()
            .migrate()
    } catch (e: Exception) {
        dataSource.close()
        throw e
    }

    Database.connect(dataSource)
}

/**
 * Run [block] inside an Exposed transaction on [Dispatchers.IO].
 *
 * Exposed's JDBC support is blocking under the hood, so it must never run on the
 * event loop; [inTopLevelSuspendTransaction] combines the suspend-safe transaction
 * API with a thread hop to the IO dispatcher.
 *
 * ALWAYS a NEW top-level transaction: it never joins an outer one (this helper
 * uses `inTopLevelSuspendTransaction`, not Exposed's nesting-aware
 * `suspendTransaction`), takes its own pooled connection, and cannot see another
 * transaction's uncommitted state. Do not nest calls — pass data in and return
 * data out instead. Two nesting-specific hazards this rule avoids: the inner
 * call checks a SECOND connection out of the (small) pool while the outer one is
 * held, and after an inner failure the outer transaction can be left aborted
 * (PostgreSQL refuses every further statement in it).
 *
 * Note on failures: an [java.sql.SQLException] that ESCAPES [block] makes
 * Exposed re-run the whole block (up to the database config's
 * `defaultMaxAttempts`, 3 by default). Side effects inside [block] that are not
 * transactional (e.g. HTTP calls) are therefore re-executed too — convert
 * expected SQL errors to non-SQL exceptions inside the block instead of letting
 * them escape.
 */
suspend fun <T> withTransaction(block: suspend JdbcTransaction.() -> T): T =
    withContext(Dispatchers.IO) {
        inTopLevelSuspendTransaction { block() }
    }
