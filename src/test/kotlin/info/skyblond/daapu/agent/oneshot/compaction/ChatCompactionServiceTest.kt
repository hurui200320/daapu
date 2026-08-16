package info.skyblond.daapu.agent.oneshot.compaction

import info.skyblond.daapu.agent.model.ModelCapabilityException
import info.skyblond.daapu.agent.ModelCatalog
import info.skyblond.daapu.agent.model.LLM
import info.skyblond.daapu.agent.model.ModelProvider
import info.skyblond.daapu.agent.chat.*
import info.skyblond.daapu.agent.oneshot.currentPromptTokens
import info.skyblond.daapu.hand.*
import info.skyblond.daapu.testutil.testHandService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlin.test.*

class ChatCompactionServiceTest {

    private fun model(id: String = "bifrost/cerebras/gemma-4-31b") = ModelCatalog(
        mapOf("bifrost" to ModelProvider("bifrost", "http://127.0.0.1:9/v1", "test"))
    ).findModel(id)!!

    private fun compactor(
        hand: FakeHand = FakeHand(),
        model: LLM = model(),
    ) = ChatCompactionService(
        model = model,
        hand = testHandService(hand),
        maxRetries = 0,
        callbackTimeoutMs = 0,
        streamIdleTimeoutMs = 0,
    )

    private fun user(text: String) =
        ChatMessage(ChatMessageRole.User, listOf(ChatMessagePart.Text(text)))

    private fun assistant(text: String, meta: ChatMessageMeta? = null) =
        ChatMessage(
            ChatMessageRole.Assistant,
            listOf(ChatMessagePart.Text(text)),
            meta = meta ?: ChatMessageMeta(inputTokens = 10, outputTokens = 10, totalTokens = 20),
            finishReason = "stop"
        )

    private fun toolCall(
        id: String,
        name: String = "tool",
        args: JsonObject = JsonObject(emptyMap())
    ) =
        ChatMessage(
            ChatMessageRole.Assistant,
            listOf(ChatMessagePart.ToolCall(id, name, args)),
            meta = ChatMessageMeta(inputTokens = 10, outputTokens = 10, totalTokens = 20),
            finishReason = "tool_calls"
        )

    private fun toolResult(id: String, name: String = "tool", text: String = "ok") =
        ChatMessage(
            ChatMessageRole.ToolResult,
            listOf(ChatMessagePart.ToolResult(id, name, listOf(ChatMessagePart.Text(text))))
        )

    /** [n] complete turns (user + assistant). */
    private fun turns(n: Int) = (1..n).flatMap { listOf(user("u$it"), assistant("a$it")) }

    /** turns with realistic-length texts (a realistic payload for the summarizer call). */
    private fun longTurns(n: Int) =
        (1..n).flatMap {
            listOf(
                user("u$it " + "x".repeat(200)),
                assistant("a$it " + "y".repeat(200))
            )
        }

    // ------------------------------------------------------------------
    // splitMessage
    // ------------------------------------------------------------------

    @Test
    fun `split keeps the last N user turns and drops the older ones`() {
        val compactor = compactor()
        val (toCompact, toPreserve) = assertNotNull(
            compactor.splitMessage(
                turns(5),
                lastNRound = 3
            )
        )
        assertEquals(listOf(user("u1"), assistant("a1"), user("u2"), assistant("a2")), toCompact)
        assertEquals(
            listOf(
                user("u3"),
                assistant("a3"),
                user("u4"),
                assistant("a4"),
                user("u5"),
                assistant("a5")
            ),
            toPreserve,
        )
    }

    @Test
    fun `split keeps trailing tool messages of the current run in the preserved part`() {
        // mid-run shape: the current turn has a tool call whose result is
        // still pending — it must land in the preserved part, never in the
        // drop region
        val compactor = compactor()
        val chat = turns(5) + toolCall("call_1", "search") + toolResult("call_1", "search")
        val (toCompact, toPreserve) = assertNotNull(compactor.splitMessage(chat, lastNRound = 3))
        assertEquals(4, toCompact.size, "only the two oldest complete turns are dropped")
        assertEquals(
            listOf(
                user("u3"),
                assistant("a3"),
                user("u4"),
                assistant("a4"),
                user("u5"),
                assistant("a5"),
                toolCall("call_1", "search"),
                toolResult("call_1", "search")
            ),
            toPreserve,
        )
    }

    @Test
    fun `split fails fast for a chat without user messages`() {
        val compactor = compactor()
        val systemOnly = assertFailsWith<IllegalArgumentException> {
            compactor.splitMessage(emptyList(), lastNRound = 3)
        }
        assertTrue(
            systemOnly.message!!.contains("no user messages"),
            "the error should name the cause: ${systemOnly.message}"
        )
        assertFailsWith<IllegalArgumentException> {
            compactor.splitMessage(emptyList(), lastNRound = 3)
        }
    }

    @Test
    fun `split shrinks the keep count instead of giving up`() {
        val compactor = compactor()

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
        assertTrue(
            preserved1.isEmpty(),
            "the whole body is compacted when the keep count cannot be honored"
        )
    }

    // ------------------------------------------------------------------
    // compactChat
    // ------------------------------------------------------------------

    @Test
    fun `compactChat replaces the older rounds with one marked summary`() = runBlocking {
        val hand = FakeHand(
            runScript = { textRunFlow("concise summary") },
        )
        val result = assertNotNull(
            compactor(hand).compactChat(longTurns(5), excludeLastNRound = 3)
        )
        val newChat = result.newChat
        // summary user message + last 3 turns verbatim
        assertEquals(ChatMessageRole.User, newChat[0].role)
        val summaryText = (newChat[0].parts.single() as ChatMessagePart.Text).text
        assertTrue(
            summaryText.startsWith("CONTEXT COMPACTION: "),
            "the summary carries the compaction marker"
        )
        assertTrue(summaryText.endsWith("concise summary"))
        assertEquals(
            listOf(
                ChatMessageRole.User, ChatMessageRole.Assistant, ChatMessageRole.User,
                ChatMessageRole.Assistant, ChatMessageRole.User, ChatMessageRole.Assistant
            ),
            newChat.drop(1).map { it.role },
        )
        assertTrue((newChat[1].parts.single() as ChatMessagePart.Text).text.startsWith("u3 "))
        assertTrue((newChat[5].parts.single() as ChatMessagePart.Text).text.startsWith("u5 "))
        // the dropped raw messages feed the SSTM extraction
        assertEquals(4, result.droppedMessages.size, "two complete turns dropped")
        assertTrue(
            (result.droppedMessages[0].parts.single() as ChatMessagePart.Text).text.startsWith(
                "u1 "
            )
        )
        // the summarizer input contained the drop region, the marker, the preserved tail, and the instruction
        val request = ChatCodec.encodeChat(hand.requests.single().messages)
        assertTrue(request.contains("u1 "), "the drop region is part of the summarizer input")
        assertTrue(request.contains("DO NOT"), "the marker message is part of the input")
        assertTrue(request.contains("u5 "), "the preserved tail is context for the summarizer")
        assertTrue(request.contains("Summarize this chat according to system prompt."))
    }

    @Test
    fun `compactChat fails fast for a chat without user messages`() = runBlocking {
        val hand = FakeHand()
        val e = assertFailsWith<IllegalArgumentException> {
            compactor(hand).compactChat(emptyList(), excludeLastNRound = 3)
        }
        assertTrue(
            e.message!!.contains("no user messages"),
            "the error should name the cause: ${e.message}"
        )
        assertTrue(hand.requests.isEmpty(), "no LLM call without user messages")
    }

    @Test
    fun `compactChat compacts the whole chat when there is only one round`() = runBlocking {
        // a single overflowing round must still be compacted: the keep count
        // collapses to zero and the entire body is replaced by the summary
        val hand = FakeHand(
            runScript = { textRunFlow("condensed round") },
        )
        val result = assertNotNull(
            compactor(hand).compactChat(longTurns(1), excludeLastNRound = 3)
        )
        val newChat = result.newChat
        assertEquals(listOf(ChatMessageRole.User), newChat.map { it.role })
        val summaryText = (newChat[0].parts.single() as ChatMessagePart.Text).text
        assertTrue(summaryText.startsWith("CONTEXT COMPACTION: "))
        assertTrue(summaryText.endsWith("condensed round"))
        assertEquals(
            listOf(user("u1 " + "x".repeat(200)), assistant("a1 " + "y".repeat(200))),
            result.droppedMessages,
            "the single round is dropped in full",
        )
    }

    @Test
    fun `compactChat throws on a blank summary`() = runBlocking {
        // a stop with neither text nor tool calls fails the run loop itself
        val hand = FakeHand(
            runScript = { errorRunFlow("empty_response", "assistant finished with neither text nor tool calls") },
        )
        val e = assertFailsWith<IllegalStateException> {
            compactor(hand).compactChat(longTurns(5), excludeLastNRound = 3)
        }
        // the outer message names the wrapper only; the detail lives on the cause
        assertEquals("Compaction summarization failed", e.message)
        val cause = assertIs<HandRunException>(e.cause)
        assertEquals("empty_response", cause.type)
    }

    @Test
    fun `compactChat throws on a truncated summary`() = runBlocking {
        // a length finish classifies as a hand error, never a clean stop:
        // the compactor must reject it
        val hand = FakeHand(
            runScript = { errorRunFlow("output_budget_exhausted", "output hit the token budget") },
        )
        val e = assertFailsWith<IllegalStateException> {
            compactor(hand).compactChat(longTurns(5), excludeLastNRound = 3)
        }
        assertEquals("Compaction summarization failed", e.message)
        val cause = assertIs<HandRunException>(e.cause)
        assertEquals("output_budget_exhausted", cause.type)
    }

    @Test
    fun `compactChat wraps a summarizer connection failure`() = runBlocking {
        val hand = FakeHand(
            runScript = { throw HandUpstreamException("hand request failed with HTTP 500") },
        )
        val e = assertFailsWith<IllegalStateException> {
            compactor(hand).compactChat(longTurns(5), excludeLastNRound = 3)
        }
        assertTrue(e.message!!.contains("failed"), "the error should name the cause: ${e.message}")
        assertNotNull(e.cause, "the original LLM failure must be kept as the cause")
    }

    @Test
    fun `compactChat fails fast when the model cannot see the content`() = runBlocking {
        val hand = FakeHand()
        // a text-only compactor model with an image in the history: the
        // capability mismatch is a configuration error and must fail the
        // run, not silently skip the compaction
        val textOnly = model("bifrost/cerebras/gpt-oss-120b")
        val image = ChatMessagePart.Attachment(
            kind = AttachmentKind.Image,
            content = AttachmentContent.Base64("AAAA"),
            mimeType = "image/png",
        )
        val chat = listOf(
            ChatMessage(
                ChatMessageRole.User,
                listOf(ChatMessagePart.Text("u1 " + "x".repeat(200)), image)
            ),
            assistant("a1 " + "y".repeat(200)),
        ) + (2..5).flatMap {
            listOf(
                user("u$it " + "x".repeat(200)),
                assistant("a$it " + "y".repeat(200))
            )
        }
        val e = assertFailsWith<ModelCapabilityException> {
            compactor(hand, textOnly).compactChat(chat, excludeLastNRound = 3)
        }
        assertTrue(
            e.message!!.contains("image"),
            "the error should name the unsupported kind: ${e.message}"
        )
        assertTrue(hand.requests.isEmpty(), "no LLM call for an incapable model")
    }

    @Test
    fun `currentPromptTokens uses the last assistant input snapshot`() {
        val chat = listOf(
            user("u"),
            assistant("a", meta = ChatMessageMeta(inputTokens = 90, outputTokens = 10, totalTokens = 100)),
            assistant("b", meta = ChatMessageMeta(inputTokens = 200_000, outputTokens = 10, totalTokens = 200_010)),
        )
        assertEquals(200_000, currentPromptTokens(chat))
    }

    @Test
    fun `currentPromptTokens counts only the last assistant even with messages after it`() {
        // the trigger runs on the stored chat (which ends with the assistant
        // message); trailing user input is not part of the snapshot
        val chat = listOf(
            user("u"),
            assistant("a", meta = ChatMessageMeta(inputTokens = 100, outputTokens = 5, totalTokens = 105)),
            user("new input " + "x".repeat(80)),
        )
        assertEquals(100, currentPromptTokens(chat))
    }

    @Test
    fun `currentPromptTokens is zero for a chat without an assistant message`() {
        // a brand-new chat has no measured snapshot yet; the proactive
        // trigger stays quiet and the reactive path guards overflow
        assertEquals(0, currentPromptTokens(listOf(user("abcd"))))
        assertEquals(0, currentPromptTokens(emptyList()))
    }
}
