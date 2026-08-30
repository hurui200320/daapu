package info.skyblond.daapu.server

import info.skyblond.daapu.agent.chat.ChatLockPoolExhaustedException
import info.skyblond.daapu.agent.chat.ChatRunConflictException
import info.skyblond.daapu.agent.chat.ChatService
import info.skyblond.daapu.config.testAppConfig
import info.skyblond.daapu.db.AdvisoryChatLock
import info.skyblond.daapu.db.AdvisoryChatLockManager
import info.skyblond.daapu.testutil.DbTestBase
import info.skyblond.daapu.testutil.TestDb
import info.skyblond.daapu.testutil.chatService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * Pins the per-chat lock state machine in [ChatService]
 * ([ChatService.acquireChatLock] / [info.skyblond.daapu.db.AdvisoryChatLock.release]
 * / [ChatService.deleteChat]) — PostgreSQL
 * session-level advisory locks over the dedicated lock pool
 * (`db/AdvisoryChatLockManager.kt`), so every acquire/release touches the
 * testcontainers database ([DbTestBase]; the locks themselves are
 * session-scoped, invisible to `TestDb.resetAll`, and released by each
 * test's own success paths).
 */
class ChatServiceLockTest : DbTestBase() {

    private val service = chatService(testAppConfig())

    @Test
    fun `second acquire on the same chat conflicts`() = runBlocking {
        val lock = service.acquireChatLock("chat-1")
        assertFailsWith<ChatRunConflictException> {
            service.acquireChatLock("chat-1")
        }
        // the failed acquire must not have disturbed the held lock: the run
        // can still release normally, and the chat becomes acquirable again
        lock.release()
        service.acquireChatLock("chat-1").release()
    }

    @Test
    fun `release frees the chat for reacquire`() = runBlocking {
        val first = service.acquireChatLock("chat-1")
        first.release()
        val second = service.acquireChatLock("chat-1")
        // each handle pins its own lock-pool session: distinct objects
        assertNotSame(first, second)
        second.release()
    }

    @Test
    fun `delete during an active run conflicts and keeps the run lock intact`() = runBlocking {
        val lock = service.acquireChatLock("chat-1")
        // the conflict is thrown from the lock acquire, before any DB read
        assertFailsWith<ChatRunConflictException> {
            service.deleteChat("chat-1")
        }
        // the run can still finish and release normally
        lock.release()
        service.acquireChatLock("chat-1").release()
    }

    @Test
    fun `double release fails fast`() = runBlocking {
        val lock = service.acquireChatLock("chat-1")
        lock.release()
        // a stale release is a programming error: it must not silently
        // no-op (nor unlock a key the holder no longer owns)
        assertFailsWith<IllegalStateException> {
            lock.release()
        }
        // the chat is still free: the failed release took nothing
        service.acquireChatLock("chat-1").release()
    }

    @Test
    fun `locks for different chats are independent`() = runBlocking {
        val a = service.acquireChatLock("chat-a")
        val b = service.acquireChatLock("chat-b")
        a.release()
        // releasing chat-a must not have touched chat-b's lock
        assertFailsWith<ChatRunConflictException> {
            service.acquireChatLock("chat-b")
        }
        b.release()
        // after release, chat-b becomes acquirable again
        service.acquireChatLock("chat-b").release()
    }

    @Test
    fun `a foreign session holding the advisory key conflicts the acquire`() = runBlocking {
        // the real cross-session semantics: a raw JDBC session (what any
        // other daapu instance would be) takes the SAME derived key through
        // plain SQL — ChatService must treat the chat as locked, and the
        // foreign unlock must free it again
        val key = AdvisoryChatLockManager.lockKey("chat-1")
        val foreign = DriverManager.getConnection(
            TestDb.url, TestDb.user, TestDb.password
        )
        foreign.prepareStatement("SELECT pg_try_advisory_lock(?)").use { st ->
            st.setLong(1, key)
            st.executeQuery().use { rs ->
                assertTrue(rs.next() && rs.getBoolean(1), "the foreign session must take the key")
            }
        }
        try {
            assertFailsWith<ChatRunConflictException> {
                service.acquireChatLock("chat-1")
            }
        } finally {
            foreign.prepareStatement("SELECT pg_advisory_unlock(?)").use { st ->
                st.setLong(1, key)
                st.executeQuery()
            }
            foreign.close()
        }
        service.acquireChatLock("chat-1").release()
    }

    @Test
    fun `a failed unlock evicts the connection and the chat stays acquirable`() = runBlocking {
        val lock = service.acquireChatLock("chat-1")
        val key = AdvisoryChatLockManager.lockKey("chat-1")
        val foreign = DriverManager.getConnection(
            TestDb.url, TestDb.user, TestDb.password
        )
        try {
            // kill the holder's backend: the release's pg_advisory_unlock now
            // runs on a dead session and throws — the release must EVICT the
            // connection instead of re-pooling a session that may still hold
            // the lock (the anti-poison guarantee)
            // pg_locks reports a single-bigint advisory key split across two
            // oid columns: classid = high 32 bits, objid = low 32 bits
            foreign.prepareStatement(
                "SELECT pg_terminate_backend(pid) FROM pg_locks " +
                        "WHERE locktype = 'advisory' AND classid = ? AND objid = ? " +
                        "AND pid <> pg_backend_pid()"
            ).use { st ->
                st.setInt(1, (key shr 32).toInt())
                st.setInt(2, key.toInt())
                st.executeQuery().use { rs ->
                    assertTrue(rs.next(), "the lock holder's session must be found and terminated")
                }
            }
            // the termination is asynchronous: wait until the session (and
            // with it the advisory lock) is really gone before releasing, so
            // the unlock below deterministically hits the dead session
            val deadline = System.currentTimeMillis() + 10_000
            var gone = false
            while (System.currentTimeMillis() < deadline) {
                foreign.prepareStatement(
                    "SELECT 1 FROM pg_locks WHERE locktype = 'advisory' " +
                            "AND classid = ? AND objid = ?"
                ).use { st ->
                    st.setInt(1, (key shr 32).toInt())
                    st.setInt(2, key.toInt())
                    st.executeQuery().use { rs -> gone = !rs.next() }
                }
                if (gone) break
                Thread.sleep(50)
            }
            assertTrue(gone, "the terminated session must have released the advisory lock")
        } finally {
            foreign.close()
        }
        // the release swallows the dead-session error internally
        lock.release()
        // the poisoned connection was evicted, never re-pooled: the chat is
        // acquirable again on a fresh session
        service.acquireChatLock("chat-1").release()
    }

    @Test
    fun `a release whose unlock answers false re-pools the connection and leaves the real lock held`() =
        runBlocking {
            val real = service.acquireChatLock("chat-1")
            // simulate a release-contract breach: a SECOND handle over a
            // DIFFERENT session that does not hold the key. Its
            // pg_advisory_unlock answers false — the release must not throw,
            // must return the (clean) connection to its pool instead of
            // evicting it (nothing leaks, the session is innocent), and must
            // leave the REAL lock untouched.
            val otherPool = HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = TestDb.url
                    username = TestDb.user
                    password = TestDb.password
                    maximumPoolSize = 1
                    minimumIdle = 0
                }
            )
            try {
                val forged = AdvisoryChatLock(
                    otherPool,
                    otherPool.connection,
                    AdvisoryChatLockManager.lockKey("chat-1"),
                )
                forged.release()
                // the clean connection went back to the pool (eviction would
                // have physically closed it: totalConnections would be 0)
                assertEquals(
                    1,
                    otherPool.hikariPoolMXBean.totalConnections,
                    "the innocent connection must be re-pooled, not evicted",
                )
                // the real lock is untouched: the chat is still locked
                assertFailsWith<ChatRunConflictException> {
                    service.acquireChatLock("chat-1")
                }
            } finally {
                real.release()
                otherPool.close()
            }
            // after the real release the chat is acquirable again
            service.acquireChatLock("chat-1").release()
        }

    @Test
    fun `lock keys are deterministic and distinct across chats`() {
        assertEquals(AdvisoryChatLockManager.lockKey("chat-1"), AdvisoryChatLockManager.lockKey("chat-1"))
        assertNotEquals(AdvisoryChatLockManager.lockKey("chat-1"), AdvisoryChatLockManager.lockKey("chat-2"))
    }

    @Test
    fun `a full lock pool fails the acquire with the pool-exhausted error`() = runBlocking {
        // a pool of ONE: pinning its only connection on chat-a must make
        // chat-b's acquire time out into the pool-exhausted error (HTTP 503),
        // not a per-chat lock verdict. The budget is generous on purpose:
        // with minimumIdle = 0 the FIRST acquire also pays the fresh-
        // connection setup (TCP + auth), which must never flake against a
        // loaded CI — the exhaustion itself stays deterministic regardless
        // of the timeout's length (a pool at max can only queue, never serve)
        val base = testAppConfig()
        val poolOfOne = chatService(
            base.copy(
                database = base.database.copy(lockPoolSize = 1, lockConnectionTimeout = 5_000)
            )
        )
        val holder = poolOfOne.acquireChatLock("chat-a")
        try {
            assertFailsWith<ChatLockPoolExhaustedException> {
                poolOfOne.acquireChatLock("chat-b")
            }
        } finally {
            holder.release()
        }
        // the released connection makes chat-b acquirable again
        poolOfOne.acquireChatLock("chat-b").release()
    }

    @Test
    fun `a cancelled waiter takes and gives back the lock instead of leaking it`() = runBlocking {
        val outcome = CompletableDeferred<Result<AdvisoryChatLock?>>()
        val job = launch {
            try {
                // the SSE disconnect mid-acquire scenario: cancel THIS
                // coroutine right before acquiring. The blocking acquire
                // still runs to completion (the lock IS taken) and the
                // coroutine machinery discards the handle, surfacing a
                // CancellationException — the manager must release the
                // captured lock in that path, or the chat would 409 (across
                // instances) until restart
                coroutineContext.cancel()
                val lock = service.acquireChatLock("chat-1")
                outcome.complete(Result.success(lock))
                lock.release()
            } catch (e: CancellationException) {
                outcome.complete(Result.success(null))
            } catch (e: Throwable) {
                outcome.complete(Result.failure(e))
            }
        }
        job.join()
        val result = outcome.await()
        assertTrue(
            result.isSuccess,
            "the cancelled acquire must fail with a CancellationException, " +
                    "not a lock verdict: ${result.exceptionOrNull()}"
        )
        // NOTHING leaked: the chat is acquirable again
        service.acquireChatLock("chat-1").release()
    }
}
