package info.skyblond.daapu.server

import info.skyblond.daapu.agent.chat.DEFAULT_CHAT_TITLE
import info.skyblond.daapu.agent.chat.ChatRunConflictException
import info.skyblond.daapu.agent.chat.ChatService
import info.skyblond.daapu.agent.chat.ChatMessage
import info.skyblond.daapu.agent.chat.ChatMessageMeta
import info.skyblond.daapu.agent.chat.ChatMessagePart
import info.skyblond.daapu.agent.chat.ChatMessageRole
import info.skyblond.daapu.agent.chat.PostgresChatStore
import info.skyblond.daapu.config.testAppConfig
import info.skyblond.daapu.hand.FakeHand
import info.skyblond.daapu.hand.assistantMessage
import info.skyblond.daapu.testutil.DbTestBase
import info.skyblond.daapu.testutil.TestDb
import info.skyblond.daapu.testutil.chatService
import info.skyblond.daapu.agent.chat.ChatValidationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import kotlin.test.*

/**
 * Pins [ChatService.truncateChat] and [ChatService.forkChat]: both
 * mutate the stored history by message index (truncate: drop a user message
 * and everything after, WITHOUT memory extraction; fork: copy the prefix up
 * to a naturally finished assistant message into a new chat). Neither calls
 * the LLM, and truncate runs under the per-chat lock like a delete.
 */
class ChatServiceHistoryEditTest : DbTestBase() {

    private fun user(text: String) = ChatMessage(
        ChatMessageRole.User,
        listOf(ChatMessagePart.Text(text)),
        createdAt = Instant.parse("2026-08-17T09:00:00Z"),
    )

    private fun assistant(text: String, finish: String = "stop") =
        assistantMessage(text = text, finishReason = finish)

    /** user, assistant, user, assistant, user — three complete rounds. */
    private fun threeRounds() = listOf(
        user("u1"), assistant("a1"),
        user("u2"), assistant("a2"),
        user("u3"), assistant("a3"),
    )

    /** One full tool round: an assistant tool_calls message + its result. */
    private fun toolChainRound(callId: String = "call_1") = listOf(
        ChatMessage(
            ChatMessageRole.Assistant,
            listOf(
                ChatMessagePart.ToolCall(
                    id = callId,
                    tool = "add_memory",
                    args = buildJsonObject { put("content", "x") },
                )
            ),
            meta = ChatMessageMeta(inputTokens = 1, outputTokens = 1, totalTokens = 2),
            finishReason = "tool_calls",
        ),
        ChatMessage(
            ChatMessageRole.ToolResult,
            listOf(
                ChatMessagePart.ToolResult(
                    id = callId,
                    tool = "add_memory",
                    parts = listOf(ChatMessagePart.Text("ok")),
                )
            ),
        ),
    )

    private fun service(
        store: PostgresChatStore = PostgresChatStore(),
        hand: FakeHand = FakeHand(runScript = { error("the LLM must not be called") }),
    ) = chatService(testAppConfig(), hand = hand, chatStore = store)

    // ---- truncate ----

    @Test
    fun `truncate drops the target user message and everything after, resetting the eltm version`() =
        runBlocking {
            val store = PostgresChatStore()
            TestDb.seedChatRow("chat-1", messages = threeRounds(), eltmVersion = "e1")
            val srv = service(store)

            // u2 sits at index 2: keep u1/a1, drop u2/a2/u3/a3
            assertTrue(srv.truncateChat("chat-1", 2))
            assertEquals(
                listOf(user("u1"), assistant("a1")),
                store.load("chat-1")!!.content.messages,
            )
            // the ELTM tables are untouched, but the kept history may no
            // longer cover what was written from the dropped tail: the
            // version resets so the next run re-flags `eltm-updated`
            assertEquals("", store.load("chat-1")!!.content.eltmVersion)
        }

    @Test
    fun `truncate at the first message empties the chat`() = runBlocking {
        val store = PostgresChatStore()
        TestDb.seedChatRow("chat-1", messages = threeRounds(), personaId = 1L)
        val srv = service(store)

        assertTrue(srv.truncateChat("chat-1", 0))
        val entry = store.load("chat-1")!!
        assertTrue(entry.content.messages.isEmpty())
        // the title survives (the upsert never touches it)
        assertEquals(DEFAULT_CHAT_TITLE, store.load("chat-1")!!.info.title)
        // the persona record survives too: truncation drops messages, not
        // the chat's identity
        assertEquals(1L, entry.info.personaId)
    }

    @Test
    fun `truncate of a missing chat returns false without calling the LLM`() = runBlocking {
        val srv = service()
        assertFalse(srv.truncateChat("nope", 0))
    }

    @Test
    fun `truncate rejects an out of bounds index`() = runBlocking {
        val store = PostgresChatStore()
        TestDb.seedChatRow("chat-1", messages = threeRounds())
        val srv = service(store)

        assertFailsWith<ChatValidationException> { srv.truncateChat("chat-1", -1) }
        assertFailsWith<ChatValidationException> { srv.truncateChat("chat-1", 6) }
        // the chat is untouched by a rejected truncation
        assertEquals(threeRounds(), store.load("chat-1")!!.content.messages)
    }

    @Test
    fun `truncate on an empty chat rejects any index`() = runBlocking {
        val store = PostgresChatStore()
        TestDb.seedChatRow("chat-1")
        val srv = service(store)

        assertFailsWith<ChatValidationException> { srv.truncateChat("chat-1", 0) }
        assertTrue(store.load("chat-1")!!.content.messages.isEmpty())
    }

    @Test
    fun `truncate rejects an index that is not a user message`() = runBlocking {
        val store = PostgresChatStore()
        TestDb.seedChatRow("chat-1", messages = threeRounds())
        val srv = service(store)

        // index 1 is the assistant reply to u1
        assertFailsWith<ChatValidationException> { srv.truncateChat("chat-1", 1) }
        assertEquals(threeRounds(), store.load("chat-1")!!.content.messages)
    }

    @Test
    fun `truncate at a user message keeps a preceding tool chain intact`() = runBlocking {
        val store = PostgresChatStore()
        // u1 -> a1(tool_calls) -> r1 -> a2(stop) -> u2: truncating at u2
        // (index 4) keeps the whole paired chain; splitting it would violate
        // the call/result pairing that validateChat enforces
        val chain = toolChainRound()
        // the stored chat must be complete (load validates): the trailing
        // u2/a2 pair gives the truncation target its natural position
        val history = listOf(user("u1"), chain[0], chain[1], assistant("a2"), user("u2"), assistant("a2"))
        TestDb.seedChatRow("chat-1", messages = history)
        val srv = service(store)

        assertTrue(srv.truncateChat("chat-1", 4))
        assertEquals(
            history.subList(0, 4),
            store.load("chat-1")!!.content.messages,
            "the kept prefix must still pair call_1 with its result",
        )
    }

    @Test
    fun `truncate on a compacted chat at the tail's first user message is rejected with 400`() =
        runBlocking {
            // a compacted chat: the summary user message sits directly before
            // the preserved tail's first user message — consecutive user turns.
            // Truncating at the tail's user message (index 1) would keep a
            // prefix ending mid-turn, which the stored format cannot represent:
            // it must fail with a 400 (the pre-check), not a defensive 500 from
            // validateChat
            val history = listOf(
                user("CONTEXT COMPACTION: earlier rounds summarized"),
                user("u2"), assistant("a2"),
            )
            val store = PostgresChatStore()
            TestDb.seedChatRow("chat-1", messages = history)
            val srv = service(store)

            assertFailsWith<ChatValidationException> { srv.truncateChat("chat-1", 1) }
            assertEquals(history, store.load("chat-1")!!.content.messages)
            // truncating at the summary itself (index 0) empties the chat fine
            assertTrue(srv.truncateChat("chat-1", 0))
            assertTrue(store.load("chat-1")!!.content.messages.isEmpty())
        }

    @Test
    fun `truncate is rejected while a run holds the chat lock`() = runBlocking {
        val store = PostgresChatStore()
        TestDb.seedChatRow("chat-1", messages = threeRounds())
        val srv = service(store)
        val lock = srv.acquireChatLock("chat-1")
        try {
            assertFailsWith<ChatRunConflictException> { srv.truncateChat("chat-1", 2) }
        } finally {
            lock.release()
        }
        // the chat is untouched by a rejected truncation
        assertEquals(threeRounds(), store.load("chat-1")!!.content.messages)
    }

    // ---- fork ----

    @Test
    fun `fork copies the history prefix up to the assistant stop message into a new chat`() =
        runBlocking {
            val store = PostgresChatStore()
            TestDb.seedChatRow("chat-1", messages = threeRounds(), eltmVersion = "e1")
            val srv = service(store)

            // fork at index 3 (a2): the new chat carries u1/a1/u2/a2
            val forked = srv.forkChat("chat-1", 3)!!
            assertEquals(
                listOf(user("u1"), assistant("a1"), user("u2"), assistant("a2")),
                store.load(forked.id)!!.content.messages
            )
            assertEquals(DEFAULT_CHAT_TITLE, forked.title)
            // a fork has never seen the ELTM: its first run must flag
            // `eltm-updated`, so the version starts fresh instead of copying
            assertEquals("", store.load(forked.id)!!.content.eltmVersion)
            // the source row is untouched
            assertEquals(threeRounds(), store.load("chat-1")!!.content.messages)
            assertEquals("e1", store.load("chat-1")!!.content.eltmVersion)
        }

    @Test
    fun `fork inherits the source chat's persona record`() = runBlocking {
        // the persona record is part of the chat's identity: a fork of a
        // conversation continues that conversation's persona (the user can
        // switch it later — the record is never authoritative for runs)
        val store = PostgresChatStore()
        TestDb.seedChatRow("chat-1", messages = threeRounds(), personaId = 1L)
        val srv = service(store)

        val forked = srv.forkChat("chat-1", 3)!!
        assertEquals(1L, forked.personaId)
        assertEquals(1L, store.load(forked.id)!!.info.personaId)
        assertEquals(1L, store.load("chat-1")!!.info.personaId, "the source is untouched")
    }

    @Test
    fun `fork of a missing chat returns null without calling the LLM`() = runBlocking {
        val srv = service()
        assertNull(srv.forkChat("nope", 0))
    }

    @Test
    fun `fork on an empty chat rejects any index`() = runBlocking {
        val store = PostgresChatStore()
        TestDb.seedChatRow("chat-1")
        val srv = service(store)

        assertFailsWith<ChatValidationException> { srv.forkChat("chat-1", 0) }
        assertTrue(store.listChats(null).chats.size == 1, "a rejected fork must not create a chat")
    }

    @Test
    fun `fork rejects an out of bounds index`() = runBlocking {
        val store = PostgresChatStore()
        TestDb.seedChatRow("chat-1", messages = threeRounds())
        val srv = service(store)

        assertFailsWith<ChatValidationException> { srv.forkChat("chat-1", -1) }
        assertFailsWith<ChatValidationException> { srv.forkChat("chat-1", 6) }
        assertTrue(store.listChats(null).chats.size == 1, "a rejected fork must not create a chat")
    }

    @Test
    fun `fork rejects an index that is not a naturally finished assistant message`() = runBlocking {
        val store = PostgresChatStore()
        TestDb.seedChatRow("chat-1", messages = threeRounds())
        val srv = service(store)

        // index 0 is a user message
        assertFailsWith<ChatValidationException> { srv.forkChat("chat-1", 0) }
        // a tool_calls assistant (not "stop") must be refused — it sits
        // mid-tool-chain and forking there would split a call/result pair.
        // The stored chat must still be a complete one (load validates), so
        // the tool_calls message sits mid-history, closed by its result and
        // a later stop message
        val chain = toolChainRound()
        TestDb.seedChatRow(
            "chat-2",
            messages = listOf(
                user("u1"),
                chain[0], chain[1],
                assistant("a2"),
            ),
        )
        assertFailsWith<ChatValidationException> { srv.forkChat("chat-2", 1) }
        assertTrue(
            store.listChats(null).chats.size == 2,
            "the rejected forks must not create chats: ${store.listChats(null).chats}"
        )
    }

    @Test
    fun `fork at the stop message after a tool chain copies the paired chain`() = runBlocking {
        val store = PostgresChatStore()
        // u1 -> a1(tool_calls) -> r1 -> a2(stop): forking at index 3 (a2)
        // copies the whole chain into the new chat — the call/result pair
        // must survive the prefix copy (validateChat would reject a split)
        val chain = toolChainRound()
        val history = listOf(user("u1"), chain[0], chain[1], assistant("a2"))
        TestDb.seedChatRow("chat-1", messages = history)
        val srv = service(store)

        val forked = srv.forkChat("chat-1", 3)!!
        assertEquals(history, store.load(forked.id)!!.content.messages)
    }

    @Test
    fun `fork succeeds while the source chat is locked by a run`() = runBlocking {
        val store = PostgresChatStore()
        TestDb.seedChatRow("chat-1", messages = threeRounds())
        val srv = service(store)
        val lock = srv.acquireChatLock("chat-1")
        try {
            // fork is a pure read + insert into a NEW row: a locked source
            // chat must not block it (the snapshot may lack the in-flight turn)
            val forked = srv.forkChat("chat-1", 1)!!
            assertEquals(
                listOf(user("u1"), assistant("a1")),
                store.load(forked.id)!!.content.messages
            )
        } finally {
            lock.release()
        }
    }
}
