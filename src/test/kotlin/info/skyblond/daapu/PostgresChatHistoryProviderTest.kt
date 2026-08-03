package info.skyblond.daapu

import info.skyblond.daapu.db.Chats
import info.skyblond.daapu.db.withTransaction
import info.skyblond.daapu.llm.PostgresChatHistoryProvider
import ai.koog.prompt.message.Message
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.insert
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration test for the koog + PostgreSQL pipeline: a chat run against the
 * mock executor must stream a reply and persist the updated conversation
 * (user message + assistant reply) through [PostgresChatHistoryProvider].
 */
class PostgresChatHistoryProviderTest {

    @Test
    fun `koog chat memory persists messages through the provider`() = runBlocking {
        DaapuPostgres.sharedDataSource()
        truncateAll()

        val chatId = withTransaction {
            Chats.insert {}.get(Chats.id)
        }
        val provider = PostgresChatHistoryProvider()
        val agent = mockChatAgentService(provider)

        var streamed = ""
        val reply = agent.streamReply(chatId, "hello there") { streamed += it }

        assertEquals("Reply to: ", reply)
        assertEquals(reply, streamed)

        val history = provider.load(chatId.toString())
        // koog ChatMemory persists the system prompt alongside the conversation.
        assertEquals(3, history.size)
        assertTrue(history[0] is Message.System)
        assertTrue(history[0].textContent().contains("You are a helpful assistant."))
        assertTrue(history[1] is Message.User)
        assertTrue(history[1].textContent().contains("hello there"))
        assertTrue(history[2] is Message.Assistant)
        assertTrue(history[2].textContent().contains("Reply to: "))
    }
}
