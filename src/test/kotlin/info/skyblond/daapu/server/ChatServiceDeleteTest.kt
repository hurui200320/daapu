package info.skyblond.daapu.server

import info.skyblond.daapu.agent.chat.ChatRunConflictException
import info.skyblond.daapu.agent.chat.ChatService
import info.skyblond.daapu.agent.chat.ChatMessage
import info.skyblond.daapu.agent.chat.ChatMessagePart
import info.skyblond.daapu.agent.chat.ChatMessageRole
import info.skyblond.daapu.agent.chat.PostgresChatStore
import info.skyblond.daapu.config.testAppConfig
import info.skyblond.daapu.hand.FakeHand
import info.skyblond.daapu.hand.assistantMessage
import info.skyblond.daapu.memory.eltm.PostgresExtractionQueue
import info.skyblond.daapu.testutil.DbTestBase
import info.skyblond.daapu.testutil.TestDb
import info.skyblond.daapu.testutil.chatService
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.*

/**
 * Pins [ChatService.deleteChat]'s enqueue-then-delete behavior: the deletion
 * snapshots the history into the background extraction queue
 * (`memory/eltm/ExtractionQueue.kt`) and removes the chats row right away —
 * no LLM call on the request path. The per-chat lock still serializes the
 * delete against runs (the run's final store upsert would otherwise
 * resurrect the deleted row).
 */
class ChatServiceDeleteTest : DbTestBase() {

    private fun user(text: String) =
        ChatMessage(
            ChatMessageRole.User,
            listOf(ChatMessagePart.Text(text)),
            createdAt = Instant.parse("2026-08-17T09:00:00Z"),
        )

    @Test
    fun `delete enqueues the history and removes the row without any LLM call`() = runBlocking {
        val store = PostgresChatStore()
        // a COMPLETE stored chat: the real store validates on load, so the
        // seed must be a history the production writer could have stored
        val history = listOf(user("u1"), assistantMessage("a1"), user("u2"), assistantMessage("a2"))
        TestDb.seedChatRow("chat-1", messages = history)
        val hand = FakeHand(runScript = { error("the LLM must not be called on the delete path") })
        val service = chatService(testAppConfig(), hand = hand, chatStore = store)

        assertTrue(service.deleteChat("chat-1"))
        assertNull(store.load("chat-1"), "the row is deleted right away")
        assertTrue(hand.requests.isEmpty(), "the extraction runs in the background, never at delete time")
        assertEquals(1, TestDb.allExtractionJobs().size, "one queue job carries the snapshot")
        // read the snapshot back through the queue's own seam: the storage
        // format inside `pending_extractions` is the queue's detail, not
        // this test's
        val claimed = PostgresExtractionQueue(jobTimeoutMinutes = 30, retryDelayMinutes = 5).claim()
        assertNotNull(claimed)
        assertEquals(history, claimed.messages, "the snapshot is the deleted chat's full history")
    }

    @Test
    fun `delete of a missing chat returns false and enqueues nothing`() = runBlocking {
        val hand = FakeHand(runScript = { error("the LLM must not be called") })
        val service = chatService(testAppConfig(), hand = hand)

        assertFalse(service.deleteChat("nope"))
        assertTrue(hand.requests.isEmpty())
        assertTrue(TestDb.allExtractionJobs().isEmpty())
    }

    @Test
    fun `delete of an empty chat removes the row without enqueueing`() = runBlocking {
        val store = PostgresChatStore()
        TestDb.seedChatRow("chat-1")
        val hand = FakeHand(runScript = { error("the LLM must not be called") })
        val service = chatService(testAppConfig(), hand = hand, chatStore = store)

        assertTrue(service.deleteChat("chat-1"))
        assertNull(store.load("chat-1"))
        assertTrue(TestDb.allExtractionJobs().isEmpty(), "no history — nothing to extract")
        assertTrue(hand.requests.isEmpty())
    }

    @Test
    fun `delete refuses while a run holds the chat lock and succeeds after release`() = runBlocking {
        val store = PostgresChatStore()
        TestDb.seedChatRow("chat-1", messages = listOf(user("u1"), assistantMessage("a1")))
        val service = chatService(testAppConfig(), chatStore = store)

        // a held lock (an in-flight run) must conflict the delete: the run's
        // final store upsert would otherwise resurrect the deleted row
        val lock = service.acquireChatLock("chat-1")
        try {
            assertFailsWith<ChatRunConflictException> { service.deleteChat("chat-1") }
            assertTrue(store.load("chat-1") != null, "a conflicting delete keeps the row")
            assertTrue(TestDb.allExtractionJobs().isEmpty(), "a conflicting delete enqueues nothing")
        } finally {
            lock.release()
        }
        // after the release the delete goes through
        assertTrue(service.deleteChat("chat-1"))
        assertNull(store.load("chat-1"))
        assertEquals(1, TestDb.allExtractionJobs().size)
    }
}
