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
 * time any query is issued. The HikariDataSource is intentionally not closed
 * here: it lives for the whole process and is released on JVM shutdown.
 */
fun initDatabase(url: String, user: String, password: String) {
    val config = HikariConfig().apply {
        jdbcUrl = url
        username = user
        this.password = password
        maximumPoolSize = 10
    }
    val dataSource = HikariDataSource(config)

    Flyway.configure()
        .dataSource(dataSource)
        .load()
        .migrate()

    Database.connect(dataSource)
}

/**
 * Run [block] inside an Exposed transaction on [Dispatchers.IO].
 *
 * Exposed's JDBC support is blocking under the hood, so it must never run on the
 * event loop; [inTopLevelSuspendTransaction] combines the suspend-safe transaction
 * API with a thread hop to the IO dispatcher.
 */
suspend fun <T> withTransaction(block: suspend JdbcTransaction.() -> T): T =
    withContext(Dispatchers.IO) {
        inTopLevelSuspendTransaction { block() }
    }
