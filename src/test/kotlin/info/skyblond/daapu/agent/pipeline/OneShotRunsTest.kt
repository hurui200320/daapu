package info.skyblond.daapu.agent.pipeline

import info.skyblond.daapu.agent.chat.ChatMessage
import info.skyblond.daapu.agent.chat.ChatMessageMeta
import info.skyblond.daapu.agent.chat.ChatMessagePart
import info.skyblond.daapu.agent.chat.ChatMessageRole
import info.skyblond.daapu.agent.chat.roundCount
import info.skyblond.daapu.agent.chat.takeLastNRound
import info.skyblond.daapu.hand.FakeHand
import info.skyblond.daapu.hand.HandEvent
import info.skyblond.daapu.hand.HandRunException
import info.skyblond.daapu.hand.HandRunPolicy
import info.skyblond.daapu.hand.HandRunRequest
import info.skyblond.daapu.hand.assistantMessage
import info.skyblond.daapu.hand.errorRunFlow
import info.skyblond.daapu.hand.textRunFlow
import info.skyblond.daapu.testutil.testHandService
import info.skyblond.daapu.testutil.testLlm
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OneShotRunsTest {

    // ------------------------------------------------------------------
    // roundCount / takeLastNRound
    // ------------------------------------------------------------------

    private fun user(text: String) = ChatMessage(
        ChatMessageRole.User,
        listOf(ChatMessagePart.Text(text)),
    )

    private fun assistant(text: String) = ChatMessage(
        ChatMessageRole.Assistant,
        listOf(ChatMessagePart.Text(text)),
        meta = ChatMessageMeta(inputTokens = 10, outputTokens = 10, totalTokens = 20),
        finishReason = "stop",
    )

    private fun toolResult(id: String) = ChatMessage(
        ChatMessageRole.ToolResult,
        listOf(
            ChatMessagePart.ToolResult(
                id = id,
                tool = "t",
                parts = listOf(ChatMessagePart.Text("r")),
            )
        ),
    )

    private fun turns(n: Int) = (1..n).flatMap { listOf(user("u$it"), assistant("a$it")) }

    @Test
    fun `roundCount counts the user rounds only`() {
        val chat = listOf(user("u1"), assistant("a1"), toolResult("t1"), user("u2"), assistant("a2"))
        assertEquals(2, chat.roundCount())
        assertEquals(0, listOf(assistant("a1")).roundCount())
        assertEquals(0, emptyList<ChatMessage>().roundCount())
    }

    @Test
    fun `takeLastNRound returns empty for a non-positive round count`() {
        assertTrue(turns(2).takeLastNRound(0).isEmpty())
        assertTrue(turns(2).takeLastNRound(-1).isEmpty())
    }

    @Test
    fun `takeLastNRound returns empty when the chat has no user message`() {
        assertTrue(listOf(assistant("a1")).takeLastNRound(1).isEmpty())
        assertTrue(emptyList<ChatMessage>().takeLastNRound(1).isEmpty())
    }

    @Test
    fun `takeLastNRound clamps to the first user round when fewer rounds exist`() {
        val chat = turns(2)
        assertEquals(chat, chat.takeLastNRound(5))
    }

    @Test
    fun `takeLastNRound keeps the Nth-from-last user round onward`() {
        val chat = turns(3)
        // last 2 rounds start at u2 (index 2)
        assertEquals(chat.subList(2, chat.size), chat.takeLastNRound(2))
        // last 1 round starts at u3 (index 4)
        assertEquals(chat.subList(4, chat.size), chat.takeLastNRound(1))
    }

    @Test
    fun `takeLastNRound never cuts inside a tool chain`() {
        // u1 a1 t1 a2 u2: the last round is u2 only; the tool chain of
        // round 1 stays whole in the preserved tail of the 2-round take
        val chat = listOf(
            user("u1"),
            assistant("a1"),
            toolResult("t1"),
            assistant("a2"),
            user("u2"),
        )
        assertEquals(chat.subList(4, chat.size), chat.takeLastNRound(1))
        assertEquals(chat, chat.takeLastNRound(2))
    }

    // ------------------------------------------------------------------
    // runOneShotCollect / runOneShotText
    // ------------------------------------------------------------------

    private val model = testLlm("bifrost/cerebras/gemma-4-31b")

    private fun testHand(
        runScript: suspend (HandRunRequest) -> List<HandEvent>,
    ) = testHandService(FakeHand(runScript = runScript))

    @Test
    fun `runOneShotText returns the final assistant text`() {
        val hand = testHand { textRunFlow("the answer") }
        val text = runBlocking {
            hand.runOneShotText(
                model = model,
                messages = listOf(user("q")),
                systemPrompt = "sys",
                policy = HandRunPolicy(maxRetries = 3, streamIdleTimeoutMs = 4321),
                label = "Test one-shot",
            )
        }
        assertEquals("the answer", text)
    }

    @Test
    fun `runOneShotText builds the request from the model and the policy`() {
        val fake = FakeHand(runScript = { textRunFlow("ok") })
        val hand = testHandService(fake)
        runBlocking {
            hand.runOneShotText(
                model = model,
                messages = listOf(user("q")),
                systemPrompt = "sys",
                policy = HandRunPolicy(maxRetries = 3, streamIdleTimeoutMs = 4321),
                label = "Test one-shot",
            )
        }
        assertEquals(1, fake.requests.size)
        val request = fake.requests[0]
        assertEquals("cerebras/gemma-4-31b", request.model.modelId)
        // the model's own output budget travels on the request
        assertEquals(model.maxOutputTokens, request.maxTokens)
        // the text one-shot shape: no round cap
        assertEquals(0, request.maxRounds)
        // the policy travels verbatim
        assertEquals(3, request.maxRetries)
        assertEquals(4321L, request.streamIdleTimeoutMs)
        assertEquals("sys", request.systemPrompt)
        // a tool-less run attaches neither tool URL: the hand makes no
        // brain-side HTTP call at all
        assertNull(request.toolListUrl)
        assertNull(request.toolCallbackUrl)
    }

    @Test
    fun `runOneShotCollect returns the collected messages`() {
        val hand = testHand {
            listOf(
                HandEvent.AssistantMessage(assistantMessage("round one")),
                HandEvent.Done("stop"),
            )
        }
        val messages = runBlocking {
            hand.runOneShotCollect(
                model = model,
                messages = listOf(user("q")),
                systemPrompt = "sys",
                policy = HandRunPolicy(maxRetries = 0, streamIdleTimeoutMs = 0),
                label = "Test one-shot",
            )
        }
        assertEquals(1, messages.size)
        assertEquals(ChatMessageRole.Assistant, messages[0].role)
    }

    @Test
    fun `runOneShotText wraps a hand error into the labelled IllegalStateException`() {
        val hand = testHand { errorRunFlow("output_budget_exhausted", "output hit the token budget") }
        val e = assertFailsWith<IllegalStateException> {
            runBlocking {
                hand.runOneShotText(
                    model = model,
                    messages = listOf(user("q")),
                    systemPrompt = "sys",
                    policy = HandRunPolicy(maxRetries = 0, streamIdleTimeoutMs = 0),
                    label = "Memory extraction",
                )
            }
        }
        assertEquals("Memory extraction failed", e.message)
        val cause = assertIs<HandRunException>(e.cause)
        assertEquals("output_budget_exhausted", cause.type)
    }

    @Test
    fun `runOneShotText rethrows cancellation untouched`() {
        val hand = testHand { throw CancellationException("client went away") }
        assertFailsWith<CancellationException> {
            runBlocking {
                hand.runOneShotText(
                    model = model,
                    messages = listOf(user("q")),
                    systemPrompt = "sys",
                    policy = HandRunPolicy(maxRetries = 0, streamIdleTimeoutMs = 0),
                    label = "Test one-shot",
                )
            }
        }
    }

    @Test
    fun `runOneShotText fails on an empty_response run`() {
        // a clean stop with neither text nor tool calls fails the run loop
        val hand = testHand { errorRunFlow("empty_response", "no usable content") }
        assertFailsWith<IllegalStateException> {
            runBlocking {
                hand.runOneShotText(
                    model = model,
                    messages = listOf(user("q")),
                    systemPrompt = "sys",
                    policy = HandRunPolicy(maxRetries = 0, streamIdleTimeoutMs = 0),
                    label = "Test one-shot",
                )
            }
        }
    }

    @Test
    fun `runOneShotText keeps the stage label when the final text is blank`() {
        // lastMessageText's defensive backstop (the hand would fail a real
        // no-text stop as empty_response first): the wrap must still label it
        val hand = testHand {
            listOf(
                HandEvent.AssistantMessage(assistantMessage("   ")),
                HandEvent.Done("stop"),
            )
        }
        val e = assertFailsWith<IllegalStateException> {
            runBlocking {
                hand.runOneShotText(
                    model = model,
                    messages = listOf(user("q")),
                    systemPrompt = "sys",
                    policy = HandRunPolicy(maxRetries = 0, streamIdleTimeoutMs = 0),
                    label = "Memory extraction",
                )
            }
        }
        assertEquals("Memory extraction failed", e.message)
        assertEquals("One-shot call produced no text", e.cause!!.message)
    }
}
