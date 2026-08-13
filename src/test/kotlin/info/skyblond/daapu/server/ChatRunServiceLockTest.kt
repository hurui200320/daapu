package info.skyblond.daapu.server

import info.skyblond.daapu.config.testAppConfig
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame

/**
 * Pins the per-chat lock state machine in [ChatRunService]
 * ([acquireChatLock]/[releaseChatLock]/[deleteChat]).
 *
 * Only the DB-free paths are exercised: `acquireChatLock`/`releaseChatLock`
 * never touch the database, and [ChatRunService.deleteChat] throws its
 * conflict before reaching `withTransaction`. (The DB-touching paths are
 * covered by the integration convention of no-DB unit tests.)
 */
class ChatRunServiceLockTest {

    private val service = ChatRunService(testAppConfig())

    @Test
    fun `second acquire on the same chat conflicts`() {
        val lock = service.acquireChatLock("chat-1")
        assertFailsWith<ChatRunConflictException> {
            service.acquireChatLock("chat-1")
        }
        // the failed acquire must not have consumed or replaced the held lock:
        // the run can still release normally, and the chat becomes acquirable
        service.releaseChatLock("chat-1", lock)
        service.acquireChatLock("chat-1")
    }

    @Test
    fun `release evicts so reacquire gets a fresh mutex`() {
        val first = service.acquireChatLock("chat-1")
        service.releaseChatLock("chat-1", first)
        val second = service.acquireChatLock("chat-1")
        assertNotSame(first, second)
        service.releaseChatLock("chat-1", second)
    }

    @Test
    fun `delete during an active run conflicts and keeps the run lock intact`() {
        val lock = service.acquireChatLock("chat-1")
        // the conflict is thrown from inside the map's compute, before any DB
        // access, and leaves the held entry untouched
        runBlocking {
            assertFailsWith<ChatRunConflictException> {
                service.deleteChat("chat-1")
            }
        }
        // the run can still finish and release normally
        service.releaseChatLock("chat-1", lock)
        service.acquireChatLock("chat-1")
    }

    @Test
    fun `release with a stale mutex fails fast and does not evict the current entry`() {
        val first = service.acquireChatLock("chat-1")
        service.releaseChatLock("chat-1", first)
        val current = service.acquireChatLock("chat-1")
        // a stale release with the previous (already unlocked) mutex is a
        // programming error: it fails fast on unlock and must not evict the
        // entry currently in the map
        assertFailsWith<IllegalStateException> {
            service.releaseChatLock("chat-1", first)
        }
        assertFailsWith<ChatRunConflictException> {
            service.acquireChatLock("chat-1")
        }
        service.releaseChatLock("chat-1", current)
    }

    @Test
    fun `locks for different chats are independent`() {
        val a = service.acquireChatLock("chat-a")
        val b = service.acquireChatLock("chat-b")
        service.releaseChatLock("chat-a", a)
        // releasing chat-a must not have evicted chat-b's lock entry
        assertFailsWith<ChatRunConflictException> {
            service.acquireChatLock("chat-b")
        }
        service.releaseChatLock("chat-b", b)
        // after release, chat-b becomes acquirable again
        service.acquireChatLock("chat-b")
    }
}
