package info.skyblond.daapu.server

import info.skyblond.daapu.agent.chat.ChatInfo
import info.skyblond.daapu.agent.chat.ChatMessage
import info.skyblond.daapu.agent.chat.ChatMessagePart
import info.skyblond.daapu.agent.chat.ChatMessageRole
import info.skyblond.daapu.agent.chat.ChatStore
import info.skyblond.daapu.config.testAppConfig
import info.skyblond.daapu.hand.FakeHand
import info.skyblond.daapu.hand.assistantMessage
import info.skyblond.daapu.hand.okCompleteResponse
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins [ChatRunService]'s chat-row operations against the [ChatStore] seam:
 * the service must hold no raw DB calls, so every path is exercised through
 * an in-memory store (and a scripted [FakeHand] for the title generator).
 */
class ChatRunServiceStoreTest {

    private val store = FakeChatStore()
    private val hand = FakeHand(
        completeScript = { okCompleteResponse(assistantMessage("Generated title")) }
    )

    private fun service() = ChatRunService(testAppConfig(), hand = hand, chatStore = store)

    private fun user(text: String) =
        ChatMessage(ChatMessageRole.User, listOf(ChatMessagePart.Text(text)))

    @Test
    fun `generateTitle returns null for a missing chat without calling the LLM`() {
        assertNull(runBlocking { service().generateTitle("nope") })
        assertTrue(hand.completeRequests.isEmpty(), "a missing chat must not call the LLM")
    }

    @Test
    fun `an empty chat returns its current title without calling the LLM`() {
        store.seed("chat-1", title = "My custom title")

        val result = runBlocking { service().generateTitle("chat-1") }

        assertEquals(ChatInfo("chat-1", "My custom title"), result)
        assertTrue(hand.completeRequests.isEmpty(), "an empty chat must not call the LLM")
        assertEquals("My custom title", store.title("chat-1"), "a custom title must never be clobbered")
    }

    @Test
    fun `generateTitle persists the generated title`() {
        store.seed("chat-1", chat = listOf(user("hi"), assistantMessage("hello")))

        val result = runBlocking { service().generateTitle("chat-1") }

        assertEquals(ChatInfo("chat-1", "Generated title"), result)
        assertEquals("Generated title", store.title("chat-1"))
        assertEquals(1, hand.completeRequests.size)
    }

    @Test
    fun `a chat deleted mid-generation returns null`() {
        store.seed("chat-1", chat = listOf(user("hi"), assistantMessage("hello")))
        val deletingHand = FakeHand(
            completeScript = {
                store.deleteRow("chat-1")
                okCompleteResponse(assistantMessage("Generated title"))
            }
        )

        val result = runBlocking {
            ChatRunService(testAppConfig(), hand = deletingHand, chatStore = store).generateTitle("chat-1")
        }

        assertNull(result, "a chat that vanished before the rename must not be resurrected")
        assertNull(store.title("chat-1"))
    }

    @Test
    fun `renameChat delegates to the store`() {
        store.seed("chat-1")

        assertEquals(ChatInfo("chat-1", "renamed"), runBlocking { service().renameChat("chat-1", "renamed") })
        assertEquals("renamed", store.title("chat-1"))
        assertNull(runBlocking { service().renameChat("nope", "x") })
    }

    @Test
    fun `newChat creates a row visible in listChats`() {
        val created = runBlocking { service().newChat() }
        assertTrue(runBlocking { service().listChats() }.any { it.id == created.id })
    }

    @Test
    fun `deleteChat returns whether a row existed`() {
        store.seed("chat-1")
        assertTrue(runBlocking { service().deleteChat("chat-1") })
        assertFalse(runBlocking { service().deleteChat("chat-1") })
    }

    @Test
    fun `listChats returns the stored rows`() {
        store.seed("a", title = "A")
        store.seed("b", title = "B")
        assertEquals(
            listOf(ChatInfo("a", "A"), ChatInfo("b", "B")),
            runBlocking { service().listChats() }
        )
    }
}
