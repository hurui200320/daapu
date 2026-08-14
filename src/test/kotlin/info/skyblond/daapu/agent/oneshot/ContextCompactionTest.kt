package info.skyblond.daapu.agent.oneshot

import info.skyblond.daapu.agent.ModelCapabilityException
import info.skyblond.daapu.agent.lc4j.MockSseResponse
import info.skyblond.daapu.agent.lc4j.MockSseServer
import info.skyblond.daapu.agent.lc4j.jsonCompletion
import info.skyblond.daapu.agent.lc4j.jsonResponse
import info.skyblond.daapu.agent.lc4j.llm.ModelCatalog
import info.skyblond.daapu.agent.lc4j.provider.BifrostProvider
import info.skyblond.daapu.chat.AttachmentContent
import info.skyblond.daapu.chat.AttachmentKind
import info.skyblond.daapu.chat.ChatMessage
import info.skyblond.daapu.chat.ChatMessageMeta
import info.skyblond.daapu.chat.ChatMessagePart
import info.skyblond.daapu.chat.ChatMessageRole
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ContextCompactionTest {

    private fun model(server: MockSseServer, id: String) = ModelCatalog(
        BifrostProvider(id = "bifrost", baseUrl = "http://127.0.0.1:${server.port}/v1", apiKey = "test")
    ).findModel(id)!!

    private fun system(text: String = "system") =
        ChatMessage(ChatMessageRole.System, listOf(ChatMessagePart.Text(text)))

    private fun user(text: String) =
        ChatMessage(ChatMessageRole.User, listOf(ChatMessagePart.Text(text)))

    private fun assistant(text: String, meta: ChatMessageMeta? = null) =
        ChatMessage(ChatMessageRole.Assistant, listOf(ChatMessagePart.Text(text)), meta = meta, finishReason = "stop")

    private fun toolCall(id: String, name: String = "tool", args: String = "{}") =
        ChatMessage(ChatMessageRole.Assistant, listOf(ChatMessagePart.ToolCall(id, name, args)), finishReason = "tool_calls")

    private fun toolResult(id: String, name: String = "tool", text: String = "ok") =
        ChatMessage(ChatMessageRole.ToolResult, listOf(ChatMessagePart.ToolResult(id, name, listOf(ChatMessagePart.Text(text)))))

    /** system + [n] complete turns (user + assistant). */
    private fun turns(n: Int) = listOf(system()) + (1..n).flatMap { listOf(user("u$it"), assistant("a$it")) }

    /** turns with realistic-length texts (the shrink guard needs a real reduction). */
    private fun longTurns(n: Int) = listOf(system()) +
            (1..n).flatMap { listOf(user("u$it " + "x".repeat(200)), assistant("a$it " + "y".repeat(200))) }

    // ------------------------------------------------------------------
    // splitMessage
    // ------------------------------------------------------------------

    @Test
    fun `split keeps the last N user turns and drops the older ones`() {
        val server = MockSseServer { MockSseResponse(200, emptyList()) }
        try {
            val compactor = ChatCompactor(model(server, "bifrost/cerebras/gemma-4-31b"), model(server, "bifrost/cerebras/gemma-4-31b").toChatModel("high"))
            val (toCompact, toPreserve) = assertNotNull(compactor.splitMessage(turns(5), lastNRound = 3))
            assertEquals(listOf(user("u1"), assistant("a1"), user("u2"), assistant("a2")), toCompact)
            assertEquals(
                listOf(user("u3"), assistant("a3"), user("u4"), assistant("a4"), user("u5"), assistant("a5")),
                toPreserve,
            )
        } finally {
            server.close()
        }
    }

    @Test
    fun `split keeps trailing tool messages of the current run in the preserved part`() {
        // mid-run shape: the current turn has a tool call whose result is
        // still pending — it must land in the preserved part, never in the
        // drop region
        val server = MockSseServer { MockSseResponse(200, emptyList()) }
        try {
            val compactor = ChatCompactor(model(server, "bifrost/cerebras/gemma-4-31b"), model(server, "bifrost/cerebras/gemma-4-31b").toChatModel("high"))
            val chat = turns(5) + toolCall("call_1", "search") + toolResult("call_1", "search")
            val (toCompact, toPreserve) = assertNotNull(compactor.splitMessage(chat, lastNRound = 3))
            assertEquals(4, toCompact.size, "only the two oldest complete turns are dropped")
            assertEquals(
                listOf(user("u3"), assistant("a3"), user("u4"), assistant("a4"),
                    user("u5"), assistant("a5"), toolCall("call_1", "search"), toolResult("call_1", "search")),
                toPreserve,
            )
        } finally {
            server.close()
        }
    }

    @Test
    fun `split fails fast for a chat without user messages`() {
        val server = MockSseServer { MockSseResponse(200, emptyList()) }
        try {
            val compactor = ChatCompactor(model(server, "bifrost/cerebras/gemma-4-31b"), model(server, "bifrost/cerebras/gemma-4-31b").toChatModel("high"))
            val systemOnly = assertFailsWith<IllegalArgumentException> {
                compactor.splitMessage(listOf(system()), lastNRound = 3)
            }
            assertTrue(systemOnly.message!!.contains("no user messages"), "the error should name the cause: ${systemOnly.message}")
            assertFailsWith<IllegalArgumentException> {
                compactor.splitMessage(emptyList(), lastNRound = 3)
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun `split shrinks the keep count instead of giving up`() {
        val server = MockSseServer { MockSseResponse(200, emptyList()) }
        try {
            val compactor = ChatCompactor(model(server, "bifrost/cerebras/gemma-4-31b"), model(server, "bifrost/cerebras/gemma-4-31b").toChatModel("high"))

            // 3 rounds with keep=3: keep shrinks to 2 so the oldest round is dropped
            val (dropped3, preserved3) = assertNotNull(compactor.splitMessage(turns(3), lastNRound = 3))
            assertEquals(listOf(user("u1"), assistant("a1")), dropped3)
            assertEquals(
                listOf(user("u2"), assistant("a2"), user("u3"), assistant("a3")),
                preserved3,
            )

            // a single round with keep=3: keep collapses to 0 — everything is dropped
            val (dropped1, preserved1) = assertNotNull(compactor.splitMessage(turns(1), lastNRound = 3))
            assertEquals(listOf(user("u1"), assistant("a1")), dropped1)
            assertTrue(preserved1.isEmpty(), "the whole body is compacted when the keep count cannot be honored")
        } finally {
            server.close()
        }
    }

    // ------------------------------------------------------------------
    // compactChat
    // ------------------------------------------------------------------

    @Test
    fun `compactChat replaces the older rounds with one marked summary`() = runBlocking {
        val server = MockSseServer { jsonResponse(jsonCompletion(content = "concise summary")) }
        try {
            val model = model(server, "bifrost/cerebras/gemma-4-31b")
            val result = assertNotNull(ChatCompactor(model, model.toChatModel("high")).compactChat(longTurns(5), excludeLastNRound = 3))
            val newChat = result.newChat
            // system head + summary user message + last 3 turns verbatim
            assertEquals(ChatMessageRole.System, newChat[0].role)
            assertEquals(ChatMessageRole.User, newChat[1].role)
            val summaryText = (newChat[1].parts.single() as ChatMessagePart.Text).text
            assertTrue(summaryText.startsWith("CONTEXT COMPACTION: "), "the summary carries the compaction marker")
            assertTrue(summaryText.endsWith("concise summary"))
            assertEquals(
                listOf(ChatMessageRole.User, ChatMessageRole.Assistant, ChatMessageRole.User,
                    ChatMessageRole.Assistant, ChatMessageRole.User, ChatMessageRole.Assistant),
                newChat.drop(2).map { it.role },
            )
            assertTrue((newChat[2].parts.single() as ChatMessagePart.Text).text.startsWith("u3 "))
            assertTrue((newChat[6].parts.single() as ChatMessagePart.Text).text.startsWith("u5 "))
            // the dropped raw messages feed the SSTM extraction
            assertEquals(4, result.droppedMessages.size, "two complete turns dropped")
            assertTrue((result.droppedMessages[0].parts.single() as ChatMessagePart.Text).text.startsWith("u1 "))
            // the summarizer input contained the drop region, the marker, the preserved tail, and the instruction
            val request = server.lastRequest()
            assertNotNull(request)
            assertTrue(request.contains("u1 "), "the drop region is part of the summarizer input")
            assertTrue(request.contains("DO NOT"), "the marker message is part of the input")
            assertTrue(request.contains("u5 "), "the preserved tail is context for the summarizer")
            assertTrue(request.contains("Summarize this chat according to system prompt."))
        } finally {
            server.close()
        }
    }

    @Test
    fun `compactChat fails fast for a chat without user messages`() = runBlocking {
        val server = MockSseServer { MockSseResponse(200, emptyList()) }
        try {
            val model = model(server, "bifrost/cerebras/gemma-4-31b")
            val e = assertFailsWith<IllegalArgumentException> {
                ChatCompactor(model, model.toChatModel("high")).compactChat(listOf(system()), excludeLastNRound = 3)
            }
            assertTrue(e.message!!.contains("no user messages"), "the error should name the cause: ${e.message}")
            assertEquals(0, server.count, "no LLM call without user messages")
        } finally {
            server.close()
        }
    }

    @Test
    fun `compactChat compacts the whole chat when there is only one round`() = runBlocking {
        // a single overflowing round must still be compacted: the keep count
        // collapses to zero and the entire body is replaced by the summary
        val server = MockSseServer { jsonResponse(jsonCompletion(content = "condensed round")) }
        try {
            val model = model(server, "bifrost/cerebras/gemma-4-31b")
            val result = assertNotNull(
                ChatCompactor(model, model.toChatModel("high")).compactChat(longTurns(1), excludeLastNRound = 3)
            )
            val newChat = result.newChat
            assertEquals(listOf(ChatMessageRole.System, ChatMessageRole.User), newChat.map { it.role })
            val summaryText = (newChat[1].parts.single() as ChatMessagePart.Text).text
            assertTrue(summaryText.startsWith("CONTEXT COMPACTION: "))
            assertTrue(summaryText.endsWith("condensed round"))
            assertEquals(
                listOf(user("u1 " + "x".repeat(200)), assistant("a1 " + "y".repeat(200))),
                result.droppedMessages,
                "the single round is dropped in full",
            )
        } finally {
            server.close()
        }
    }

    @Test
    fun `compactChat throws on a blank summary`() = runBlocking {
        val server = MockSseServer { jsonResponse(jsonCompletion(content = "")) }
        try {
            val model = model(server, "bifrost/cerebras/gemma-4-31b")
            val e = assertFailsWith<IllegalStateException> {
                ChatCompactor(model, model.toChatModel("high")).compactChat(longTurns(5), excludeLastNRound = 3)
            }
            assertTrue(e.message!!.contains("no text"), "the error should name the cause: ${e.message}")
        } finally {
            server.close()
        }
    }

    @Test
    fun `compactChat throws on a truncated summary`() = runBlocking {
        // a non-stop finish reason (length) must not be accepted as a summary
        val server = MockSseServer { jsonResponse(jsonCompletion(content = "partial", finishReason = "length")) }
        try {
            val model = model(server, "bifrost/cerebras/gemma-4-31b")
            val e = assertFailsWith<IllegalStateException> {
                ChatCompactor(model, model.toChatModel("high")).compactChat(longTurns(5), excludeLastNRound = 3)
            }
            assertTrue(e.message!!.contains("finish_reason"), "the error should name the cause: ${e.message}")
        } finally {
            server.close()
        }
    }

    @Test
    fun `compactChat wraps a summarizer http failure`() = runBlocking {
        val server = MockSseServer { MockSseResponse(500, emptyList()) }
        try {
            val model = model(server, "bifrost/cerebras/gemma-4-31b")
            val e = assertFailsWith<IllegalStateException> {
                ChatCompactor(model, model.toChatModel("high")).compactChat(longTurns(5), excludeLastNRound = 3)
            }
            assertTrue(e.message!!.contains("failed"), "the error should name the cause: ${e.message}")
            assertNotNull(e.cause, "the original LLM failure must be kept as the cause")
        } finally {
            server.close()
        }
    }

    @Test
    fun `compactChat fails fast when the model cannot see the content`() = runBlocking {
        val server = MockSseServer { MockSseResponse(200, emptyList()) }
        try {
            // a text-only compactor model with an image in the history: the
            // capability mismatch is a configuration error and must fail the
            // run, not silently skip the compaction
            val model = model(server, "bifrost/cerebras/gpt-oss-120b")
            val image = ChatMessagePart.Attachment(
                kind = AttachmentKind.Image,
                content = AttachmentContent.Base64("AAAA"),
                mimeType = "image/png",
            )
            val chat = listOf(system()) + listOf(
                ChatMessage(ChatMessageRole.User, listOf(ChatMessagePart.Text("u1 " + "x".repeat(200)), image)),
                assistant("a1 " + "y".repeat(200)),
            ) + (2..5).flatMap { listOf(user("u$it " + "x".repeat(200)), assistant("a$it " + "y".repeat(200))) }
            val e = assertFailsWith<ModelCapabilityException> {
                ChatCompactor(model, model.toChatModel("high")).compactChat(chat, excludeLastNRound = 3)
            }
            assertTrue(e.message!!.contains("image"), "the error should name the unsupported kind: ${e.message}")
            assertEquals(0, server.count, "no LLM call for an incapable model")
        } finally {
            server.close()
        }
    }

    @Test
    fun `estimateTokens prefers the last assistant input snapshot`() {
        val chat = listOf(system(), user("u"), assistant("a", meta = ChatMessageMeta(inputTokens = 200_000)))
        assertEquals(200_000, estimateTokens(chat))
    }

    @Test
    fun `estimateTokens adds chars for messages after the snapshot`() {
        val chat = listOf(
            system(),
            user("u"),
            assistant("a", meta = ChatMessageMeta(inputTokens = 100)),
            user("new input " + "x".repeat(80)),  // 90 chars -> 22 tokens
        )
        assertEquals(122, estimateTokens(chat))
    }

    @Test
    fun `estimateTokens falls back to chars when no meta is present`() {
        val chat = listOf(system("abcd"), user("abcd"), assistant("abcd"))
        assertEquals(3, estimateTokens(chat))
    }
}
