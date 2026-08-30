package info.skyblond.daapu.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import info.skyblond.daapu.config.DatabaseConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.sql.Connection
import java.sql.SQLTransientConnectionException
import java.util.concurrent.atomic.AtomicBoolean

private val logger = KotlinLogging.logger {}

/**
 * The chat lock's advisory key is taken by another SESSION (a concurrent run
 * in this process, or any other instance sharing the database). The caller
 * maps this onto its own conflict contract — ChatService rethrows it as
 * [info.skyblond.daapu.agent.chat.ChatRunConflictException] (HTTP 409).
 */
class AdvisoryLockConflictException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * The lock pool gave out no connection within `database.lockConnectionTimeout`
 * (default 3000 ms): the pool is exhausted by concurrent lock holders (every
 * holder pins one connection for the whole run/delete) — or the database is
 * unreachable, which lands on the same Hikari transient timeout and is
 * indistinguishable here (a dead DB breaks the DB-fronted app well before a
 * chat run anyway, e.g. the chat-list read). A third, rarer cause shares the
 * budget: with `minimumIdle = 0` the pool keeps no idle connections, so EVERY
 * acquire also pays the fresh-connection setup (TCP + auth + TLS handshake) —
 * a remote database with a slow handshake eats into the same timeout even when
 * the pool is neither full nor unreachable. The caller maps this onto 503
 * via [info.skyblond.daapu.agent.chat.ChatLockPoolExhaustedException].
 */
class AdvisoryLockPoolExhaustedException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * One held chat lock. The checked-out [connection] IS the lock's session:
 * the session-level advisory lock stays taken until this connection closes.
 * Obtain it only through [AdvisoryChatLockManager.acquireChatLock]; release
 * exactly once via [release].
 */
class AdvisoryChatLock internal constructor(
    private val pool: HikariDataSource,
    private val connection: Connection,
    private val key: Long,
) {
    private val released = AtomicBoolean(false)

    /**
     * Release the lock: `pg_advisory_unlock`, then return the (now clean)
     * connection to the pool. Idempotence is refused, not allowed: a double
     * release is a programming error (the Mutex contract this replaces failed
     * fast too). Non-cancellable: a cancelled caller (e.g. an SSE client
     * disconnect mid-stream) must still complete the release — it runs in the
     * `finally` of the holder's scoped block.
     *
     * If the unlock (or the return to the pool) fails — a dead session, a
     * statement timeout (see the pool's `connectionInitSql`) — the connection
     * is EVICTED instead of re-pooled: the session may still hold the advisory
     * lock, and a re-pooled holder would poison it (spurious 409s for the
     * chat until some future borrower trips over the ghost lock). Eviction
     * physically closes the session, which releases the lock server-side
     * even when the connection itself is dead. An unlock that answers
     * `false` (this session did not hold the lock) is a contract breach —
     * logged, but the connection is still clean (nothing to leak) and
     * returns to the pool.
     */
    suspend fun release(): Unit = withContext(Dispatchers.IO + NonCancellable) {
        check(released.compareAndSet(false, true)) {
            "Advisory chat lock $key is already released"
        }
        try {
            val unlocked = connection.prepareStatement("SELECT pg_advisory_unlock(?)").use { st ->
                st.setLong(1, key)
                st.executeQuery().use { rs ->
                    rs.next()
                    rs.getBoolean(1)
                }
            }
            if (!unlocked) {
                logger.warn {
                    "pg_advisory_unlock returned false for chat lock $key: this session " +
                            "did not hold the lock (a release-contract breach)"
                }
            }
            // the session is clean again: hand it back to the pool
            connection.close()
        } catch (e: Exception) {
            // the session may still hold the lock — never let it back into
            // the pool; the eviction closes it physically either way. Logged:
            // a steady stream of these means dead sessions (network drops, a
            // statement timeout) are a recurring operational problem.
            logger.warn(e) {
                "pg_advisory_unlock failed for chat lock $key: evicting the " +
                        "connection instead of re-pooling a possible lock holder"
            }
            runCatching { pool.evictConnection(connection) }
        }
    }
}

/**
 * The per-chat lock over PostgreSQL session-level advisory locks
 * (`pg_try_advisory_lock`), replacing ChatService's in-JVM Mutex map: the
 * lock is now crash-safe (a dead session releases it) and cross-instance
 * (any process sharing the database observes the same lock).
 *
 * REQUIREMENT: session-level advisory locks live in a real PostgreSQL
 * session, so `database.url` must be a DIRECT connection (or a
 * session-pooling proxy). A transaction-pooling proxy (PgBouncer in
 * transaction mode) returns the session to its pool between transactions —
 * the advisory lock would silently evaporate MID-RUN, exactly the corruption
 * this lock exists to prevent. The same crash-safety also covers network
 * infrastructure dropping a long-held session mid-run (NAT/firewall idle
 * timeouts): the server releases the lock when the session dies, and
 * `tcpKeepAlive=true` on the JDBC URL is the usual mitigation. A session
 * that HANGS (packets silently dropped) cannot be detected from here — the
 * pool's `statement_timeout` (see the pool KDoc) bounds the advisory
 * statements themselves so the failure surfaces into the ordinary error
 * paths instead of pinning a thread forever.
 *
 * HOW IT WORKS: a session-level advisory lock is bound to a CONNECTION, not
 * a transaction, and a chat run spans many short transactions over minutes —
 * so every holder checks out ONE connection from a DEDICATED pool and keeps
 * it for the whole operation (run, delete-with-extraction, truncate),
 * releasing it back on unlock. The pool is deliberately separate from the
 * main one: lock connections held for minutes on the shared pool would let
 * as many concurrent runs as the pool size starve every query (livelock).
 * Its size (`database.lockPoolSize`) doubles as the cap on concurrent chat
 * runs — a pool connection timeout answers with
 * [AdvisoryLockPoolExhaustedException] after `database.lockConnectionTimeout`
 * ms, whether the pool is exhausted or the database unreachable (Hikari's
 * transient timeout covers both — see the exception's KDoc).
 *
 * The lock key is a bigint derived deterministically from the chat id
 * ([lockKey], SHA-256 over a fixed namespace prefix + id): chat ids are
 * opaque strings, and the astronomically unlikely hash collision only makes
 * two different chats exclude each other (a spurious 409 that clears on
 * release) — it can never let two holders of the SAME chat in.
 *
 * Non-reentrant like the Mutex it replaces: each holder owns its own
 * session, so a second acquire for the same chat always conflicts. Cleanup:
 * Koin `onClose` calls [close] at JVM shutdown.
 */
class AdvisoryChatLockManager(database: DatabaseConfig) {

    private val connectionTimeoutMs = database.lockConnectionTimeout

    /**
     * The dedicated pool. `maximumPoolSize` = `database.lockPoolSize` (the
     * concurrent-holder cap); no Flyway — migrations are the main pool's
     * job (db/Database.kt), and advisory locks touch no tables. `minimumIdle
     * = 0` + a short [IDLE_TIMEOUT_MS]: a connection exists only while a
     * lock is held (plus a small idle tail) — the pool never parks idle
     * sessions (an idle retirement also CLOSES the session, releasing any
     * advisory lock still on it), and a hold is never starved by
     * housekeeping. The zero idle count also means every acquire pays the
     * fresh-connection setup (TCP + auth + TLS) out of the same
     * `connectionTimeout` budget as a full pool or an unreachable database —
     * see [AdvisoryLockPoolExhaustedException]. `connectionTimeout` =
     * `database.lockConnectionTimeout` (default 3s, deliberately far below
     * Hikari's 30s default): a waiter must not hang half a minute in front
     * of a full pool — that both stalls the client and pins a
     * `Dispatchers.IO` thread, the same dispatcher ALL database access runs
     * on. Construction keeps Hikari's default fail-fast (one validation
     * connection): an unreachable database aborts the boot, in line with
     * the main pool's init.
     *
     * `connectionInitSql` sets every lock connection's `statement_timeout`
     * to [connectionTimeoutMs]. Hikari's `connectionTimeout` bounds only the
     * POOL WAIT, never statement execution, and the acquire/release are
     * non-cancellable — so without it a blackhole network (packets silently
     * dropped, unlike a reset that errors immediately) would hang the
     * `pg_try_advisory_lock`/`pg_advisory_unlock` call forever, pinning an
     * IO thread and, on release, leaking the holder's coroutine and
     * connection until JVM death. With the timeout the hung statement errors
     * into the EXISTING failure paths: an acquire becomes a 500 (a lock
     * verdict would be a lie), a release evicts the connection (which
     * releases the lock server-side if the session ever recovers). The
     * session-wide GUC applies to nothing else — lock connections run only
     * the advisory statements.
     */
    private val pool = HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = database.url
            username = database.user
            password = database.password
            maximumPoolSize = database.lockPoolSize
            connectionTimeout = connectionTimeoutMs.toLong()
            minimumIdle = 0
            idleTimeout = IDLE_TIMEOUT_MS.toLong()
            poolName = "daapu-chat-advisory-locks"
            connectionInitSql = "SET statement_timeout = $connectionTimeoutMs"
        }
    )

    /**
     * Take the chat lock or fail fast: `pg_try_advisory_lock` is
     * non-blocking (no queuing — the Mutex contract this replaces answered
     * with an immediate 409 too). Blocking JDBC runs on [Dispatchers.IO],
     * non-cancellable like [AdvisoryChatLock.release] (the whole
     * checkout→tryLock path holds no suspension points). A lock conflict
     * throws [AdvisoryLockConflictException]; a pool connection timeout
     * (Hikari's transient timeout after `connectionTimeout` ms — a full
     * pool OR an unreachable database, indistinguishable here) throws
     * [AdvisoryLockPoolExhaustedException]; any other SQL failure propagates
     * as-is — a 500, not a lock verdict (the pool's `statement_timeout`
     * turns a hung session into this path too, bounding the acquire).
     *
     * CANCELLATION SAFETY: the blocking part runs inside a
     * `withContext(NonCancellable)`, but that does NOT deliver its result to
     * a cancelled caller — if the caller's job is cancelled (an SSE client
     * disconnecting mid-acquire), the block still runs to completion (the
     * lock IS taken) and withContext DISCARDS the handle on exit, throwing
     * [kotlinx.coroutines.CancellationException]. Returning the handle
     * through the block would therefore leak both the pool connection and
     * the held advisory lock (409ing the chat across instances until
     * restart). Instead the handle is captured in the outer scope, and a
     * [kotlinx.coroutines.CancellationException] from the machinery releases
     * the captured lock before rethrowing: a cancelled waiter takes the lock
     * and immediately gives it back — nothing leaks. Every other path hands
     * the handle to the caller (whose `finally` releases it) or releases its
     * connection inside [takeChatLock].
     */
    suspend fun acquireChatLock(chatId: String): AdvisoryChatLock {
        var lock: AdvisoryChatLock? = null
        try {
            withContext(Dispatchers.IO + NonCancellable) {
                lock = takeChatLock(chatId)
            }
        } catch (e: CancellationException) {
            // the block completed (the lock was taken) but the machinery
            // discarded the handle: release it before propagating. release()
            // has its own failure discipline (evict-on-error) and only throws
            // on a double release — impossible here, the caller never saw
            // the handle
            lock?.release()
            throw e
        }
        return checkNotNull(lock) { "the lock block completed without a handle" }
    }

    /**
     * The blocking acquire: check out a lock-pool connection, take the
     * session-level advisory lock on it, and wrap both into the handle. No
     * suspension points — cancellation cannot interrupt it mid-flight.
     * Every failure path closes the checked-out connection before throwing.
     */
    private fun takeChatLock(chatId: String): AdvisoryChatLock {
        val connection = try {
            pool.connection
        } catch (e: SQLTransientConnectionException) {
            throw AdvisoryLockPoolExhaustedException(
                "The chat lock pool is exhausted (size ${pool.maximumPoolSize}): " +
                        "too many concurrent chat runs or history edits",
                e,
            )
        }
        val key = lockKey(chatId)
        val acquired = try {
            connection.prepareStatement(
                "SELECT pg_try_advisory_lock(?)"
            ).use { st ->
                st.setLong(1, key)
                st.executeQuery().use { rs ->
                    rs.next()
                    rs.getBoolean(1)
                }
            }
        } catch (e: Exception) {
            // the connection never became a lock holder: hand it back
            runCatching { connection.close() }
            throw e
        }
        return if (acquired) {
            AdvisoryChatLock(pool, connection, key)
        } else {
            runCatching { connection.close() }
            throw AdvisoryLockConflictException(
                "Chat '$chatId' is currently locked by another session"
            )
        }
    }

    fun close() {
        pool.close()
    }

    companion object {
        private const val KEY_NAMESPACE = "daapu-chat-lock"

        /**
         * How long a returned connection may sit idle in the lock pool before
         * housekeeping retires (and physically closes) it: long enough that a
         * quick back-to-back acquire can reuse it, short enough that no idle
         * session lingers — an idle retirement closes the session, releasing
         * any advisory lock still on it. Kept well below any realistic lock
         * hold, so housekeeping can never race an in-flight acquire for a
         * connection that a holder is about to need.
         */
        private const val IDLE_TIMEOUT_MS = 10_000

        /**
         * The advisory lock key for a chat id: SHA-256 over a fixed namespace
         * prefix + the id, first 8 bytes as a signed long. The prefix keeps
         * daapu's keys disjoint from any other advisory-lock user of the same
         * database. Internal so tests can take the same key through raw SQL
         * (no manager instance — no pool — needed).
         */
        internal fun lockKey(chatId: String): Long {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("$KEY_NAMESPACE:$chatId".toByteArray(Charsets.UTF_8))
            var key = 0L
            for (i in 0 until 8) {
                key = (key shl 8) or (digest[i].toLong() and 0xff)
            }
            return key
        }
    }
}
