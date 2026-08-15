package info.skyblond.daapu.server

import info.skyblond.daapu.agent.chat.ChatMessage
import info.skyblond.daapu.agent.chat.ChatMessagePart
import info.skyblond.daapu.agent.chat.ChatMessageRole
import info.skyblond.daapu.config.testAppConfig
import info.skyblond.daapu.hand.FakeHand
import info.skyblond.daapu.hand.assistantMessage
import info.skyblond.daapu.hand.failedCompleteResponse
import info.skyblond.daapu.hand.okCompleteResponse
import info.skyblond.daapu.memory.sstm.MemoriesWithVersion
import info.skyblond.daapu.memory.sstm.ShortTermMemory
import info.skyblond.daapu.memory.sstm.SstmService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Pins [ChatRunService.deleteChat]'s extract-before-delete behavior: the full
 * chat history is fed to the SSTM extraction pipeline while the per-chat lock
 * is held, and a failed extraction keeps the row (a retry re-extracts and the
 * merge agent deduplicates).
 */
class ChatRunServiceDeleteTest {

    /** A fake [SstmService] recording writes. */
    private class FakeSstmService : SstmService {
        val created = mutableListOf<String>()

        override suspend fun listMemories(): MemoriesWithVersion =
            MemoriesWithVersion(emptyList(), "test-version")

        override suspend fun createMemory(content: String): ShortTermMemory {
            created += content
            return ShortTermMemory(created.size.toLong(), Instant.EPOCH, content)
        }

        override suspend fun updateMemory(id: Long, content: String): ShortTermMemory =
            ShortTermMemory(id, Instant.EPOCH, content)

        override suspend fun deleteMemory(id: Long): Boolean = true
    }

    private fun user(text: String) =
        ChatMessage(ChatMessageRole.User, listOf(ChatMessagePart.Text(text)))

    /** An assistant message whose only part is one add_memory tool call. */
    private fun addMemoryRound() = assistantMessage(
        parts = listOf(
            ChatMessagePart.ToolCall(
                id = "call_1",
                tool = "add_memory",
                args = buildJsonObject { put("content", "likes coffee") },
            )
        ),
        finishReason = "tool_calls",
    )

    @Test
    fun `delete extracts SSTM from the chat history before removing the row`() = runBlocking {
        val store = FakeChatStore()
        val history = listOf(user("u1"), assistantMessage("a1"), user("u2"))
        store.seed("chat-1", chat = history)
        // complete 1 = extractor (fact text), complete 2 = merge round 1
        // (add_memory tool call), complete 3 = merge round 2 (done)
        var round = 0
        val hand = FakeHand(
            completeScript = {
                when (++round) {
                    1 -> okCompleteResponse(assistantMessage("likes coffee"))
                    2 -> okCompleteResponse(addMemoryRound())
                    else -> okCompleteResponse(assistantMessage("done"))
                }
            },
        )
        val sstm = FakeSstmService()
        val service = ChatRunService(testAppConfig(), hand = hand, chatStore = store, sstmService = sstm)

        assertTrue(service.deleteChat("chat-1"))
        assertNull(store.load("chat-1"), "the row is deleted only after extraction")
        assertEquals(listOf("likes coffee"), sstm.created)
        // the extractor call carried the raw stored history verbatim (the
        // trailing message is the extraction instruction)
        assertEquals(history, hand.completeRequests[0].messages.dropLast(1))
        // the merge rounds advertised the memory tools
        assertTrue(hand.completeRequests[1].tools!!.map { it.name }.contains("add_memory"))
    }

    @Test
    fun `a failed extraction fails the delete and keeps the row for a retry`() = runBlocking {
        val store = FakeChatStore()
        store.seed("chat-1", chat = listOf(user("u1"), assistantMessage("a1")))
        var round = 0
        val hand = FakeHand(
            completeScript = {
                when (++round) {
                    // first delete: the extractor round is truncated
                    1 -> failedCompleteResponse("output_budget_exhausted", "output hit the token budget")
                    // retried delete: extractor + merge round + done
                    2 -> okCompleteResponse(assistantMessage("likes coffee"))
                    3 -> okCompleteResponse(addMemoryRound())
                    else -> okCompleteResponse(assistantMessage("done"))
                }
            },
        )
        val sstm = FakeSstmService()
        val service = ChatRunService(testAppConfig(), hand = hand, chatStore = store, sstmService = sstm)

        assertFailsWith<IllegalStateException> { service.deleteChat("chat-1") }
        assertTrue(store.load("chat-1") != null, "a failed extraction must keep the row")
        assertTrue(sstm.created.isEmpty(), "a failed extraction must not merge anything")

        // the retried delete re-extracts the same history; the merge agent
        // deduplicates against whatever was already applied
        assertTrue(service.deleteChat("chat-1"))
        assertNull(store.load("chat-1"))
        assertEquals(listOf("likes coffee"), sstm.created)
    }

    @Test
    fun `delete of a missing chat returns false without calling the LLM`() = runBlocking {
        val hand = FakeHand(completeScript = { error("the LLM must not be called") })
        val service = ChatRunService(
            testAppConfig(),
            hand = hand,
            chatStore = FakeChatStore(),
            sstmService = FakeSstmService(),
        )

        assertFalse(service.deleteChat("nope"))
        assertTrue(hand.completeRequests.isEmpty())
    }

    @Test
    fun `delete of an empty chat skips extraction`() = runBlocking {
        val store = FakeChatStore()
        store.seed("chat-1")
        val hand = FakeHand(completeScript = { error("the LLM must not be called") })
        val service = ChatRunService(
            testAppConfig(),
            hand = hand,
            chatStore = store,
            sstmService = FakeSstmService(),
        )

        assertTrue(service.deleteChat("chat-1"))
        assertNull(store.load("chat-1"))
        assertTrue(hand.completeRequests.isEmpty())
    }

    @Test
    fun `the chat lock is held across extraction and released after the delete`() = runBlocking {
        val store = FakeChatStore()
        store.seed("chat-1", chat = listOf(user("u1"), assistantMessage("a1")))
        var round = 0
        var concurrentAcquireConflicted = false
        lateinit var service: ChatRunService
        val hand = FakeHand(
            completeScript = {
                when (++round) {
                    1 -> {
                        // mid-extraction: a new run must be rejected (409),
                        // the delete still holds the lock
                        try {
                            service.acquireChatLock("chat-1")
                        } catch (_: ChatRunConflictException) {
                            concurrentAcquireConflicted = true
                        }
                        okCompleteResponse(assistantMessage("likes coffee"))
                    }
                    2 -> okCompleteResponse(addMemoryRound())
                    else -> okCompleteResponse(assistantMessage("done"))
                }
            },
        )
        service = ChatRunService(
            testAppConfig(),
            hand = hand,
            chatStore = store,
            sstmService = FakeSstmService(),
        )

        assertTrue(service.deleteChat("chat-1"))
        assertTrue(concurrentAcquireConflicted, "a run must not start while the delete's extraction runs")
        // the lock entry was evicted with the delete: the chat is acquirable again
        val lock = service.acquireChatLock("chat-1")
        service.releaseChatLock("chat-1", lock)
    }
}
