package info.skyblond.daapu.server

import info.skyblond.daapu.agent.chat.ChatMessage
import info.skyblond.daapu.agent.chat.ChatMessageMeta
import info.skyblond.daapu.agent.chat.ChatMessagePart
import info.skyblond.daapu.agent.chat.ChatMessageRole
import info.skyblond.daapu.agent.chat.ChatService
import info.skyblond.daapu.agent.chat.ChatValidationException
import info.skyblond.daapu.agent.chat.PostgresChatStore
import info.skyblond.daapu.agent.persona.DEFAULT_PERSONA_ID
import info.skyblond.daapu.config.testAppConfig
import info.skyblond.daapu.hand.FakeHand
import info.skyblond.daapu.hand.assistantMessage
import info.skyblond.daapu.testutil.DbTestBase
import info.skyblond.daapu.testutil.TestDb
import info.skyblond.daapu.testutil.chatService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Pins [ChatService.exportChat] and [ChatService.importChat]: the export is
 * a snapshot read of the full row (title + history, no lock), the import
 * creates a NEW chat reusing the exported title with fork-like fresh state
 * (empty ELTM fingerprint, default persona record) and applies the SAME
 * completeness validation as any stored chat — mapped to the client-error
 * [ChatValidationException] because the data is user-supplied.
 */
class ChatServiceExportImportTest : DbTestBase() {

    private fun user(text: String) = ChatMessage(
        ChatMessageRole.User,
        listOf(ChatMessagePart.Text(text)),
        createdAt = Instant.parse("2026-08-17T09:00:00Z"),
    )

    private fun assistant(text: String, finish: String = "stop") =
        assistantMessage(text = text, finishReason = finish)

    /** user, assistant, user, assistant, user, assistant — three complete rounds. */
    private fun threeRounds() = listOf(
        user("u1"), assistant("a1"),
        user("u2"), assistant("a2"),
        user("u3"), assistant("a3"),
    )

    /** An assistant tool_calls message with no result yet (for the pair check). */
    private fun toolCallsAssistant(callId: String) = ChatMessage(
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
    )

    private fun service(
        store: PostgresChatStore = PostgresChatStore(),
        hand: FakeHand = FakeHand(runScript = { error("the LLM must not be called") }),
    ) = chatService(testAppConfig(), hand = hand, chatStore = store)

    // ---- export ----

    @Test
    fun `export returns the chat's title and full message history`() = runBlocking {
        val store = PostgresChatStore()
        TestDb.seedChatRow(
            "chat-1",
            title = "My chat",
            messages = threeRounds(),
            eltmVersion = "e1",
            personaId = 1L,
        )
        val srv = service(store)

        val entry = srv.exportChat("chat-1")!!
        assertEquals("My chat", entry.info.title)
        assertEquals(threeRounds(), entry.content.messages)
        assertEquals(1L, entry.info.personaId, "the full row is exported; the ROUTE drops the persona record")
    }

    @Test
    fun `export of a missing chat returns null`() = runBlocking {
        assertNull(service().exportChat("nope"))
    }

    @Test
    fun `export succeeds while a run holds the chat lock`() = runBlocking {
        val store = PostgresChatStore()
        TestDb.seedChatRow("chat-1", title = "My chat", messages = threeRounds())
        val srv = service(store)
        val lock = srv.acquireChatLock("chat-1")
        try {
            // a snapshot read like fork's source read: no lock required
            assertEquals(threeRounds(), srv.exportChat("chat-1")!!.content.messages)
        } finally {
            lock.release()
        }
    }

    // ---- import ----

    @Test
    fun `import creates a new chat reusing the trimmed title and storing the messages`() = runBlocking {
        val store = PostgresChatStore()
        TestDb.seedChatRow("chat-1", title = "the original", messages = threeRounds())
        val srv = service(store)

        val imported = srv.importChat("  Imported chat  ", threeRounds())
        assertEquals("Imported chat", imported.title)
        assertTrue(imported.id != "chat-1", "a fresh id is minted, never the payload's (there is none)")
        val entry = store.load(imported.id)!!
        assertEquals(threeRounds(), entry.content.messages)
        // fork-like fresh state: never seen the ELTM, no persona record
        assertEquals("", entry.content.eltmVersion)
        assertEquals(DEFAULT_PERSONA_ID, entry.info.personaId)
        // the source row is untouched
        assertEquals("the original", store.load("chat-1")!!.info.title)
        assertEquals(threeRounds(), store.load("chat-1")!!.content.messages)
    }

    @Test
    fun `import of an empty history creates a titled empty chat`() = runBlocking {
        val store = PostgresChatStore()
        val srv = service(store)

        val imported = srv.importChat("Empty import", emptyList())
        val entry = store.load(imported.id)!!
        assertTrue(entry.content.messages.isEmpty())
        assertEquals("Empty import", entry.info.title)
    }

    @Test
    fun `import rejects a blank title without creating anything`() = runBlocking {
        val store = PostgresChatStore()
        val srv = service(store)

        assertFailsWith<ChatValidationException> { srv.importChat("   ", threeRounds()) }
        assertTrue(store.listChats(null).chats.isEmpty())
    }

    @Test
    fun `import rejects a history ending with a user message`() = runBlocking {
        val store = PostgresChatStore()
        val srv = service(store)

        assertFailsWith<ChatValidationException> { srv.importChat("title", listOf(user("u1"))) }
        assertTrue(store.listChats(null).chats.isEmpty())
    }

    @Test
    fun `import rejects an assistant ending that did not stop naturally`() = runBlocking {
        val store = PostgresChatStore()
        val srv = service(store)

        assertFailsWith<ChatValidationException> {
            srv.importChat("title", listOf(user("u1"), assistant("a1", finish = "tool_calls")))
        }
        assertTrue(store.listChats(null).chats.isEmpty())
    }

    @Test
    fun `import rejects a user message without createdAt`() = runBlocking {
        val store = PostgresChatStore()
        val srv = service(store)

        val anchorless = ChatMessage(ChatMessageRole.User, listOf(ChatMessagePart.Text("u1")))
        assertFailsWith<ChatValidationException> {
            srv.importChat("title", listOf(anchorless, assistant("a1")))
        }
        assertTrue(store.listChats(null).chats.isEmpty())
    }

    @Test
    fun `import rejects a split tool pair`() = runBlocking {
        val store = PostgresChatStore()
        val srv = service(store)

        // the tool call has no matching result: the pairing invariant fails
        val split = listOf(user("u1"), toolCallsAssistant("call_1"), assistant("a1"))
        assertFailsWith<ChatValidationException> { srv.importChat("title", split) }
        assertTrue(store.listChats(null).chats.isEmpty())
    }
}
