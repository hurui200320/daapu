package info.skyblond.daapu.server

import info.skyblond.daapu.agent.chat.ChatRunConflictException
import info.skyblond.daapu.agent.chat.ChatService
import info.skyblond.daapu.agent.chat.*
import info.skyblond.daapu.agent.persona.DEFAULT_PERSONA_ID
import info.skyblond.daapu.config.testAppConfig
import info.skyblond.daapu.hand.FakeHand
import info.skyblond.daapu.hand.assistantMessage
import info.skyblond.daapu.hand.textRunFlow
import info.skyblond.daapu.testutil.DbTestBase
import info.skyblond.daapu.testutil.TestDb
import info.skyblond.daapu.testutil.chatService
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.*

/**
 * Pins [ChatService]'s chat-row operations against the [ChatStore] seam:
 * the service must hold no raw DB calls, so every path runs through the
 * real [PostgresChatStore] over the test database (and a scripted
 * [FakeHand] for the title generator).
 */
class ChatServiceStoreTest : DbTestBase() {

    private val store = PostgresChatStore()
    private val hand = FakeHand(
        runScript = { textRunFlow("Generated title") }
    )

    private fun service() = chatService(
        testAppConfig(),
        hand = hand,
        chatStore = store,
    )

    private fun user(text: String) =
        ChatMessage(
            ChatMessageRole.User,
            listOf(ChatMessagePart.Text(text)),
            createdAt = Instant.parse("2026-08-17T09:00:00Z"),
        )

    @Test
    fun `generateTitle returns null for a missing chat without calling the LLM`() {
        assertNull(runBlocking { service().generateTitle("nope") })
        assertTrue(hand.requests.isEmpty(), "a missing chat must not call the LLM")
    }

    @Test
    fun `an empty chat returns its current title without calling the LLM`() = runBlocking {
        TestDb.seedChatRow("chat-1", title = "My custom title")

        val result = service().generateTitle("chat-1")

        assertEquals(ChatInfo("chat-1", "My custom title", DEFAULT_PERSONA_ID), result)
        assertTrue(hand.requests.isEmpty(), "an empty chat must not call the LLM")
        assertEquals(
            "My custom title",
            store.load("chat-1")?.info?.title,
            "a custom title must never be clobbered"
        )
    }

    @Test
    fun `generateTitle persists the generated title`() = runBlocking {
        TestDb.seedChatRow("chat-1", messages = listOf(user("hi"), assistantMessage("hello")))

        val result = service().generateTitle("chat-1")

        assertEquals(ChatInfo("chat-1", "Generated title", DEFAULT_PERSONA_ID), result)
        assertEquals("Generated title", store.load("chat-1")?.info?.title)
        assertEquals(1, hand.requests.size)
    }

    @Test
    fun `a chat deleted mid-generation returns null`() = runBlocking {
        TestDb.seedChatRow("chat-1", messages = listOf(user("hi"), assistantMessage("hello")))
        val deletingHand = FakeHand(
            runScript = {
                store.delete("chat-1")
                textRunFlow("Generated title")
            }
        )

        val result = chatService(
            testAppConfig(),
            hand = deletingHand,
            chatStore = store
        ).generateTitle("chat-1")

        assertNull(result, "a chat that vanished before the rename must not be resurrected")
        assertNull(store.load("chat-1"))
    }

    @Test
    fun `renameChat delegates to the store`() = runBlocking {
        TestDb.seedChatRow("chat-1")

        assertEquals(
            ChatInfo("chat-1", "renamed", DEFAULT_PERSONA_ID),
            service().renameChat("chat-1", "renamed"))
        assertEquals("renamed", store.load("chat-1")?.info?.title)
        assertNull(service().renameChat("nope", "x"))
    }

    @Test
    fun `newChat creates a row visible in listChats`() {
        val created = runBlocking { service().newChat() }
        assertTrue(runBlocking { service().listChats() }.any { it.id == created.id })
    }

    @Test
    fun `deleteChat returns whether a row existed`() {
        TestDb.seedChatRow("chat-1")
        assertTrue(runBlocking { service().deleteChat("chat-1") })
        assertFalse(runBlocking { service().deleteChat("chat-1") })
    }

    @Test
    fun `listChats returns the stored rows newest-first`() {
        TestDb.seedChatRow("a", title = "A")
        TestDb.seedChatRow("b", title = "B")
        assertEquals(
            listOf(ChatInfo("b", "B", DEFAULT_PERSONA_ID), ChatInfo("a", "A", DEFAULT_PERSONA_ID)),
            runBlocking { service().listChats() }
        )
    }
}
