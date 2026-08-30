package info.skyblond.daapu.config

import kotlinx.serialization.Serializable

/**
 * PostgreSQL connection (pgvector enabled). All fields are required and must
 * not be blank: `DATABASE_URL=`-style holes would otherwise fail later with a
 * confusing JDBC/auth error instead of the intended config error.
 *
 * [lockPoolSize] sizes the DEDICATED advisory-lock pool
 * (`db/AdvisoryChatLockManager.kt`): one connection per chat run/history
 * mutation, held for the whole operation so the session-level advisory lock
 * stays taken. Separate from the main pool by design — sharing it would let
 * as many lock holders as `maximumPoolSize` starve every query (livelock) —
 * and it doubles as the cap on concurrent chat runs. [lockConnectionTimeout]
 * bounds how long a lock-pool waiter may hang in front of that pool before
 * failing with 503 (a full pool, an unreachable database, and — since the
 * pool keeps no idle connections — a fresh connection whose setup exceeds
 * the budget all land on the same Hikari timeout — see
 * `db/AdvisoryChatLockManager.kt`). It is also applied as the lock
 * connections' `statement_timeout`, bounding the advisory-lock statements
 * themselves (Hikari's timeout covers only the pool wait, not execution).
 */
@Serializable
data class DatabaseConfig(
    val url: String,
    val user: String,
    val password: String,
    val lockPoolSize: Int = 10,
    val lockConnectionTimeout: Int = 3_000,
) {
    fun validate() {
        require(url.isNotBlank()) { "database.url must not be blank" }
        require(user.isNotBlank()) { "database.user must not be blank" }
        require(password.isNotBlank()) { "database.password must not be blank" }
        require(lockPoolSize > 0) { "database.lockPoolSize must be positive, got $lockPoolSize" }
        require(lockConnectionTimeout > 0) {
            "database.lockConnectionTimeout must be positive, got $lockConnectionTimeout"
        }
    }
}
