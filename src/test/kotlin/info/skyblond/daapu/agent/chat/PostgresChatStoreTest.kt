package info.skyblond.daapu.agent.chat

import info.skyblond.daapu.agent.persona.DEFAULT_PERSONA_ID
import info.skyblond.daapu.hand.assistantMessage
import info.skyblond.daapu.testutil.DbTestBase
import info.skyblond.daapu.testutil.TestDb
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * DB-backed tests for [PostgresChatStore] against the throwaway
 * testcontainers PostgreSQL (`testutil/TestDb.kt`): CRUD round trips, the
 * store upsert's title preservation, the persona-record readback, and the
 * fail-fast validation before anything is written.
 */
class PostgresChatStoreTest : DbTestBase() {
    private val store = PostgresChatStore()

    /** A minimal valid chat: one user message, one assistant stop. */
    private fun validChat(): List<ChatMessage> = listOf(
        userMessage("hello"),
        assistantMessage("hi"),
    )

    private fun userMessage(text: String) = ChatMessage(
        role = ChatMessageRole.User,
        parts = listOf(ChatMessagePart.Text(text)),
        createdAt = Instant.parse("2026-08-17T10:00:00Z"),
    )

    @Test
    fun `newChat inserts the default title and persona record`() = runBlocking {
        val chat = store.newChat()
        assertTrue(chat.id.isNotBlank())
        assertEquals(DEFAULT_CHAT_TITLE, chat.title)
        assertEquals(DEFAULT_PERSONA_ID, chat.personaId)

        val loaded = store.load(chat.id)
        assertEquals(chat, loaded?.info)
        assertEquals(emptyList(), loaded?.content?.messages)
        assertEquals("", loaded?.content?.eltmVersion, "a fresh chat never flags eltm-updated")
    }

    @Test
    fun `load returns null for a missing chat`() = runBlocking {
        assertNull(store.load("no-such-chat"))
    }

    @Test
    fun `store upserts messages and stamps version and persona without touching the title`() =
        runBlocking {
            val chat = store.newChat(personaId = 7L)
            store.store(chat.id, ChatContent(validChat(), "42", 7L))

            val loaded = store.load(chat.id)
            assertEquals(validChat(), loaded?.content?.messages)
            assertEquals("42", loaded?.content?.eltmVersion)
            assertEquals(7L, loaded?.content?.personaId)
            // the upsert never writes the title: the row keeps what it was
            // created with
            assertEquals(DEFAULT_CHAT_TITLE, loaded?.info?.title)
        }

    @Test
    fun `store refuses an invalid chat before writing anything`() = runBlocking {
        val chat = store.newChat()
        // a user message without createdAt fails the store-side validation
        val invalid = listOf(
            ChatMessage(role = ChatMessageRole.User, parts = listOf(ChatMessagePart.Text("no stamp"))),
        )
        try {
            store.store(chat.id, ChatContent(invalid, "", DEFAULT_PERSONA_ID))
            fail("storing a chat without createdAt must fail fast")
        } catch (expected: IllegalArgumentException) {
            // nothing was written: the row still holds the empty history
            assertEquals(emptyList(), store.load(chat.id)?.content?.messages)
        }
    }

    @Test
    fun `rename updates the title and reads back the actual persona record`() = runBlocking {
        val chat = store.newChat(personaId = 3L)
        store.store(chat.id, ChatContent(validChat(), "", 3L))

        val renamed = store.rename(chat.id, "my chat")
        assertEquals("my chat", renamed?.title)
        assertEquals(3L, renamed?.personaId, "the returned record is the row's ACTUAL persona")
        assertEquals("my chat", store.load(chat.id)?.info?.title)
    }

    @Test
    fun `rename returns null for a missing chat`() = runBlocking {
        assertNull(store.rename("no-such-chat", "x"))
    }

    @Test
    fun `listChats is newest-first by id`() = runBlocking {
        // fixed ids, not two newChat() calls: newChatId() is
        // `${millis}-${random}`, so same-millisecond creations order by the
        // RANDOM suffix and would make the assertion a coin flip
        TestDb.seedChatRow("chat-a", title = "A")
        TestDb.seedChatRow("chat-b", title = "B")
        val listed = store.listChats()
        assertEquals(listOf("chat-b", "chat-a"), listed.map { it.id })
    }

    @Test
    fun `delete removes the row exactly once`() = runBlocking {
        val chat = store.newChat()
        assertTrue(store.delete(chat.id))
        assertNull(store.load(chat.id))
        assertFalse(store.delete(chat.id))
    }

    @Test
    fun `messages round trip through the JSON column unchanged`() = runBlocking {
        val chat = store.newChat()
        val messages = validChat()
        store.store(chat.id, ChatContent(messages, "7", 0L))
        val loaded = assertIs<ChatEntry>(store.load(chat.id))
        assertEquals(messages, loaded.content.messages)
        assertEquals("7", loaded.content.eltmVersion)
    }
}
