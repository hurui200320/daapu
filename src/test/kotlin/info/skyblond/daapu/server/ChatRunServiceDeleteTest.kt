package info.skyblond.daapu.server

import info.skyblond.daapu.agent.chat.ChatMessage
import info.skyblond.daapu.agent.chat.ChatMessagePart
import info.skyblond.daapu.agent.chat.ChatMessageRole
import info.skyblond.daapu.agent.oneshot.sstm.MergeMemoryToolProvider
import info.skyblond.daapu.agent.persist.ContextInjection
import info.skyblond.daapu.config.testAppConfig
import info.skyblond.daapu.hand.*
import info.skyblond.daapu.memory.sstm.SstmService
import info.skyblond.daapu.testutil.RecordingSstmService
import info.skyblond.daapu.testutil.addMemoryRound
import info.skyblond.daapu.testutil.chatRunService
import info.skyblond.daapu.testutil.mergeRunFlow
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.*

/**
 * Pins [ChatRunService.deleteChat]'s extract-before-delete behavior: the full
 * chat history is fed to the SSTM extraction pipeline while the per-chat lock
 * is held, and a failed extraction keeps the row (a retry re-extracts and the
 * merge agent deduplicates).
 */
class ChatRunServiceDeleteTest {

    private fun user(text: String) =
        ChatMessage(
            ChatMessageRole.User,
            listOf(ChatMessagePart.Text(text)),
            createdAt = Instant.parse("2026-08-17T09:00:00Z"),
        )

    /**
     * A fake hand dispatching on the one-shot system prompts: the extractor
     * answers [extraction], the merger runs [mergeRunFlow] against [sstm].
     * The delete pipeline never calls the chat loop, so any other request
     * fails the test.
     */
    private fun oneShotHand(
        sstm: SstmService,
        extraction: suspend (HandRunRequest) -> List<HandEvent> = { textRunFlow("likes coffee") },
        merge: suspend (HandRunRequest) -> List<HandEvent> = { mergeRunFlow(sstm) },
    ) = FakeHand(
        runScript = { request ->
            when {
                request.systemPrompt?.startsWith("You're extracting") == true -> extraction(request)
                request.systemPrompt?.startsWith("You're merging") == true -> merge(request)
                else -> error("unexpected run in the delete pipeline: ${request.systemPrompt}")
            }
        },
    )

    @Test
    fun `delete extracts SSTM from the chat history before removing the row`() = runBlocking {
        val store = FakeChatStore()
        val history = listOf(user("u1"), assistantMessage("a1"), user("u2"))
        store.seed("chat-1", chat = history)
        val sstm = RecordingSstmService()
        val hand = oneShotHand(sstm)
        val service =
            chatRunService(testAppConfig(), hand = hand, chatStore = store, sstmService = sstm)

        assertTrue(service.deleteChat("chat-1"))
        assertNull(store.load("chat-1"), "the row is deleted only after extraction")
        assertEquals(listOf("likes coffee"), sstm.created)
        assertEquals(2, hand.requests.size, "extractor run + merge run")
        // the extractor call carried the stored history with every user
        // message carrying its send-time <meta> anchor (the trailing message
        // is the extraction instruction); stripping the anchors must give the
        // raw stored history back
        val contextInjection = ContextInjection()
        val extractorInput = hand.requests[0].messages.dropLast(1)
        assertEquals(
            history,
            extractorInput.mapIndexed { index, message ->
                if (message.role == ChatMessageRole.User) {
                    assertTrue(contextInjection.hasMetaPart(message))
                    message.copy(parts = message.parts.drop(1))
                } else {
                    assertEquals(history[index], message)
                    message
                }
            }
        )
        // no static tool list travels in the request anymore: the merge
        // run's tools are served through the per-round GET /api/hand/tools
        // listing (pinned by HandCallbackTest), from the same provider
        assertTrue(
            MergeMemoryToolProvider(sstm).specifications().map { it.name }.contains("add_memory")
        )
    }

    @Test
    fun `a failed extraction fails the delete and keeps the row for a retry`() = runBlocking {
        val store = FakeChatStore()
        store.seed("chat-1", chat = listOf(user("u1"), assistantMessage("a1")))
        var rounds = 0
        val sstm = RecordingSstmService()
        val hand = oneShotHand(
            sstm = sstm,
            extraction = {
                // first delete: the extractor round is truncated; the
                // retried delete extracts normally
                if (++rounds == 1) {
                    errorRunFlow("output_budget_exhausted", "output hit the token budget")
                } else {
                    textRunFlow("likes coffee")
                }
            },
        )
        val service =
            chatRunService(testAppConfig(), hand = hand, chatStore = store, sstmService = sstm)

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
    fun `a round-limited merger fails the delete so a retry loses nothing`() = runBlocking {
        // the merge run hits the round cap: the failed merge must fail the
        // delete — the row survives, and a retry re-extracts the full
        // history instead of discarding unmerged memories
        val store = FakeChatStore()
        store.seed("chat-1", chat = listOf(user("u1"), assistantMessage("a1")))
        val sstm = RecordingSstmService()
        val provider = MergeMemoryToolProvider(sstm)
        val hand = oneShotHand(
            sstm = sstm,
            merge = {
                val round = addMemoryRound("call_1", "likes coffee")
                listOf(HandEvent.AssistantMessage(round)) +
                        toolRoundEvents(round, provider) +
                        listOf(
                            HandEvent.RunError(
                                "round_limit",
                                "maxRounds (150) reached at round 150"
                            )
                        )
            },
        )
        val service =
            chatRunService(testAppConfig(), hand = hand, chatStore = store, sstmService = sstm)

        val e = assertFailsWith<HandRunException> { service.deleteChat("chat-1") }
        assertEquals("round_limit", e.type)
        assertTrue(store.load("chat-1") != null, "a failed merge must keep the row")
        // the memory applied before the cap stays; a retry deduplicates it
        assertEquals(listOf("likes coffee"), sstm.created)
    }

    @Test
    fun `delete of a missing chat returns false without calling the LLM`() = runBlocking {
        val hand = FakeHand(runScript = { error("the LLM must not be called") })
        val service = chatRunService(
            testAppConfig(),
            hand = hand,
            chatStore = FakeChatStore(),
            sstmService = RecordingSstmService(),
        )

        assertFalse(service.deleteChat("nope"))
        assertTrue(hand.requests.isEmpty())
    }

    @Test
    fun `delete of an empty chat skips extraction`() = runBlocking {
        val store = FakeChatStore()
        store.seed("chat-1")
        val hand = FakeHand(runScript = { error("the LLM must not be called") })
        val service = chatRunService(
            testAppConfig(),
            hand = hand,
            chatStore = store,
            sstmService = RecordingSstmService(),
        )

        assertTrue(service.deleteChat("chat-1"))
        assertNull(store.load("chat-1"))
        assertTrue(hand.requests.isEmpty())
    }

    @Test
    fun `the chat lock is held across extraction and released after the delete`() = runBlocking {
        val store = FakeChatStore()
        store.seed("chat-1", chat = listOf(user("u1"), assistantMessage("a1")))
        var concurrentAcquireConflicted = false
        lateinit var service: ChatRunService
        val sstm = RecordingSstmService()
        val hand = oneShotHand(
            sstm = sstm,
            extraction = {
                // mid-extraction: a new run must be rejected (409), the
                // delete still holds the lock
                try {
                    service.acquireChatLock("chat-1")
                } catch (_: ChatRunConflictException) {
                    concurrentAcquireConflicted = true
                }
                textRunFlow("likes coffee")
            },
        )
        service = chatRunService(
            testAppConfig(),
            hand = hand,
            chatStore = store,
            sstmService = sstm,
        )

        assertTrue(service.deleteChat("chat-1"))
        assertTrue(
            concurrentAcquireConflicted,
            "a run must not start while the delete's extraction runs"
        )
        // the lock entry was evicted with the delete: the chat is acquirable again
        val lock = service.acquireChatLock("chat-1")
        service.releaseChatLock("chat-1", lock)
    }
}
