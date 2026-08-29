package info.skyblond.daapu.server

import info.skyblond.daapu.agent.chat.ChatRunConflictException
import info.skyblond.daapu.agent.chat.ChatService
import info.skyblond.daapu.agent.chat.ChatMessage
import info.skyblond.daapu.agent.chat.ChatMessagePart
import info.skyblond.daapu.agent.chat.ChatMessageRole
import info.skyblond.daapu.memory.eltm.EltmToolProvider
import info.skyblond.daapu.agent.context.ContextInjection
import info.skyblond.daapu.config.testAppConfig
import info.skyblond.daapu.hand.*
import info.skyblond.daapu.testutil.FakeEltmService
import info.skyblond.daapu.testutil.addEntityNoteRound
import info.skyblond.daapu.testutil.chatService
import info.skyblond.daapu.testutil.createEntityRound
import info.skyblond.daapu.testutil.writerRunFlow
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.*

/**
 * Pins [ChatService.deleteChat]'s extract-before-delete behavior: the full
 * chat history is fed to the memory extraction pipeline while the per-chat
 * lock is held, and a failed extraction keeps the row (a retry re-extracts
 * and the ELTM writer skips already-recorded content).
 */
class ChatServiceDeleteTest {

    private fun user(text: String) =
        ChatMessage(
            ChatMessageRole.User,
            listOf(ChatMessagePart.Text(text)),
            createdAt = Instant.parse("2026-08-17T09:00:00Z"),
        )

    /**
     * A fake hand dispatching on the one-shot system prompts: the extractor
     * answers [extraction], the writer runs [writerRunFlow] against [eltm].
     * The delete pipeline never calls the chat loop, so any other request
     * fails the test.
     */
    private fun oneShotHand(
        eltm: FakeEltmService,
        extraction: suspend (HandRunRequest) -> List<HandEvent> = { textRunFlow("likes coffee") },
        writer: suspend (HandRunRequest) -> List<HandEvent> = { writerRunFlow(eltm) },
    ) = FakeHand(
        runScript = { request ->
            when {
                request.systemPrompt?.startsWith("You're extracting") == true -> extraction(request)
                request.systemPrompt?.startsWith("You're maintaining an external long-term memory") == true -> writer(request)
                else -> error("unexpected run in the delete pipeline: ${request.systemPrompt}")
            }
        },
    )

    @Test
    fun `delete extracts memories from the chat history before removing the row`() = runBlocking {
        val store = FakeChatStore()
        val history = listOf(user("u1"), assistantMessage("a1"), user("u2"))
        store.seed("chat-1", chat = history)
        val eltm = FakeEltmService()
        val hand = oneShotHand(eltm)
        val service =
            chatService(testAppConfig(), hand = hand, chatStore = store, eltmService = eltm)

        assertTrue(service.deleteChat("chat-1"))
        assertNull(store.load("chat-1"), "the row is deleted only after extraction")
        val notes = eltm.notes.values.map { it.note }
        assertTrue(notes.contains("likes coffee"), "the extracted fact lands in the ELTM")
        assertEquals(2, hand.requests.size, "extractor run + writer run")
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
        // no static tool list travels in the request anymore: the writer
        // run's tools are served through the per-round GET /api/hand/tools
        // listing (pinned by HandCallbackTest), from the same provider
        assertTrue(
            EltmToolProvider(eltm).specifications().map { it.name }.contains("add_entity_note")
        )
    }

    @Test
    fun `a failed extraction fails the delete and keeps the row for a retry`() = runBlocking {
        val store = FakeChatStore()
        store.seed("chat-1", chat = listOf(user("u1"), assistantMessage("a1")))
        var rounds = 0
        val eltm = FakeEltmService()
        val hand = oneShotHand(
            eltm = eltm,
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
            chatService(testAppConfig(), hand = hand, chatStore = store, eltmService = eltm)

        assertFailsWith<IllegalStateException> { service.deleteChat("chat-1") }
        assertTrue(store.load("chat-1") != null, "a failed extraction must keep the row")
        assertTrue(eltm.notes.isEmpty(), "a failed extraction must not write anything")

        // the retried delete re-extracts the same history; the ELTM writer
        // deduplicates against whatever was already recorded
        assertTrue(service.deleteChat("chat-1"))
        assertNull(store.load("chat-1"))
        val notes = eltm.notes.values.map { it.note }
        assertTrue(notes.contains("likes coffee"))
    }

    @Test
    fun `a round-limited writer fails the delete so a retry loses nothing`() = runBlocking {
        // the writer run hits the round cap: the failed write must fail the
        // delete — the row survives, and a retry re-extracts the full
        // history instead of discarding unwritten memories
        val store = FakeChatStore()
        store.seed("chat-1", chat = listOf(user("u1"), assistantMessage("a1")))
        val eltm = FakeEltmService()
        // the writer's first, capped round already knows the user entity
        // (created by the real writer flow on a retry); the note tool call
        // targets it
        val entityId = eltm.createEntity("user", "general").entity.id
        val provider = EltmToolProvider(eltm)
        val hand = oneShotHand(
            eltm = eltm,
            writer = {
                val round = addEntityNoteRound("call_note", entityId, "2026-08-17", "likes coffee")
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
            chatService(testAppConfig(), hand = hand, chatStore = store, eltmService = eltm)

        val e = assertFailsWith<IllegalStateException> { service.deleteChat("chat-1") }
        // the writer wraps the hand's terminal failure: the round-limit
        // classification is on the cause
        val cause = assertIs<HandRunException>(e.cause)
        assertEquals("round_limit", cause.type)
        assertTrue(store.load("chat-1") != null, "a failed writer must keep the row")
        // the note applied before the cap stays; a retry deduplicates it
        val notes = eltm.notes.values.map { it.note }
        assertTrue(notes.contains("likes coffee"))
    }

    @Test
    fun `delete of a missing chat returns false without calling the LLM`() = runBlocking {
        val hand = FakeHand(runScript = { error("the LLM must not be called") })
        val service = chatService(
            testAppConfig(),
            hand = hand,
            chatStore = FakeChatStore(),
            eltmService = FakeEltmService(),
        )

        assertFalse(service.deleteChat("nope"))
        assertTrue(hand.requests.isEmpty())
    }

    @Test
    fun `delete of an empty chat skips extraction`() = runBlocking {
        val store = FakeChatStore()
        store.seed("chat-1")
        val hand = FakeHand(runScript = { error("the LLM must not be called") })
        val service = chatService(
            testAppConfig(),
            hand = hand,
            chatStore = store,
            eltmService = FakeEltmService(),
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
        lateinit var service: ChatService
        val eltm = FakeEltmService()
        val hand = oneShotHand(
            eltm = eltm,
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
        service = chatService(
            testAppConfig(),
            hand = hand,
            chatStore = store,
            eltmService = eltm,
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
