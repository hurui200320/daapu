package info.skyblond.daapu.agent.oneshot

import info.skyblond.daapu.agent.ModelCatalog
import info.skyblond.daapu.agent.chat.*
import info.skyblond.daapu.agent.model.LLM
import info.skyblond.daapu.agent.model.ModelCapabilityException
import info.skyblond.daapu.agent.model.ModelProvider
import info.skyblond.daapu.db.DEFAULT_CHAT_TITLE
import info.skyblond.daapu.hand.*
import info.skyblond.daapu.testutil.testHandService
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.*

class TitleGeneratorTest {

    private fun model(id: String = "bifrost/cerebras/gemma-4-31b") = ModelCatalog(
        mapOf("bifrost" to ModelProvider("bifrost", "http://127.0.0.1:9/v1", "test"))
    ).findModel(id)!!

    private fun generator(
        hand: FakeHand,
        model: LLM = model(),
        lastNRound: Int = 0,
    ) = TitleGenerator(
        model = model,
        hand = testHandService(hand),
        lastNRound = lastNRound,
        maxRetries = 0,
        streamIdleTimeoutMs = 0,
    )

    private fun user(text: String) =
        ChatMessage(
            ChatMessageRole.User,
            listOf(ChatMessagePart.Text(text)),
            createdAt = Instant.parse("2026-08-17T09:00:00Z"),
        )

    // the generation instruction is one-shot furniture: never stored, so it
    // carries no createdAt (unlike history user messages)
    private fun instruction() = ChatMessage(
        ChatMessageRole.User,
        listOf(ChatMessagePart.Text("Generate a title according to the system prompt.")),
    )

    private fun assistant(text: String) =
        ChatMessage(
            ChatMessageRole.Assistant,
            listOf(ChatMessagePart.Text(text)),
            meta = ChatMessageMeta(inputTokens = 10, outputTokens = 10, totalTokens = 20),
            finishReason = "stop"
        )

    private fun turns(n: Int) = (1..n).flatMap { listOf(user("u$it"), assistant("a$it")) }

    @Test
    fun `empty history returns the default title without calling the hand`() {
        val hand = FakeHand()
        val generator = generator(hand)
        assertEquals(DEFAULT_CHAT_TITLE, runBlocking { generator.generateTitle(emptyList()) })
        assertTrue(hand.requests.isEmpty(), "an empty chat must not call the LLM")
    }

    @Test
    fun `history is sent with the instruction and the title system prompt`() {
        val hand = FakeHand(
            runScript = { textRunFlow("My title") }
        )
        val generator = generator(hand)
        val history = turns(2)

        val title = runBlocking { generator.generateTitle(history) }

        assertEquals("My title", title)
        assertEquals(1, hand.requests.size)
        val request = hand.requests[0]
        assertEquals("cerebras/gemma-4-31b", request.model.modelId)
        // the history plus the appended generation instruction
        assertEquals(
            history + instruction(),
            request.messages
        )
        assertTrue(request.systemPrompt!!.contains("ONE LINE"))
        assertTrue(request.systemPrompt.contains("15"))
        assertEquals(ChatMessageRole.User, request.messages.last().role)
    }

    @Test
    fun `a truncated response fails`() {
        // the hand classifies a truncated round itself and fails the run
        val hand = FakeHand(
            runScript = { errorRunFlow("output_budget_exhausted", "output hit the token budget") }
        )
        val generator = generator(hand)
        val e = assertFailsWith<IllegalStateException> {
            runBlocking { generator.generateTitle(turns(1)) }
        }
        // the outer message names the wrapper only; the detail lives on the cause
        assertEquals("Title generation failed", e.message)
        val cause = assertIs<HandRunException>(e.cause)
        assertEquals("output_budget_exhausted", cause.type)
    }

    @Test
    fun `a blank response fails`() {
        // a stop with neither text nor tool calls fails the run loop itself
        val hand = FakeHand(
            runScript = {
                errorRunFlow(
                    "empty_response",
                    "assistant finished with neither text nor tool calls"
                )
            }
        )
        val generator = generator(hand)
        assertFailsWith<IllegalStateException> {
            runBlocking { generator.generateTitle(turns(1)) }
        }
    }

    @Test
    fun `a failed hand response fails`() {
        val hand = FakeHand(
            runScript = { errorRunFlow("upstream", "boom") }
        )
        val generator = generator(hand)
        val e = assertFailsWith<IllegalStateException> {
            runBlocking { generator.generateTitle(turns(1)) }
        }
        assertEquals("Title generation failed", e.message)
        val cause = assertIs<HandRunException>(e.cause)
        assertTrue(cause.message!!.contains("boom"))
    }

    @Test
    fun `a hand transport failure is wrapped`() {
        val hand = FakeHand(
            runScript = { throw HandUpstreamException("hand request failed with HTTP 500") }
        )
        val generator = generator(hand)
        val e = assertFailsWith<IllegalStateException> {
            runBlocking { generator.generateTitle(turns(1)) }
        }
        assertTrue(e.message!!.startsWith("Title generation failed"), e.message)
        assertIs<HandUpstreamException>(e.cause)
    }

    @Test
    fun `history is truncated to the last N user rounds`() {
        val hand = FakeHand(
            runScript = { textRunFlow("My title") }
        )
        val generator = generator(hand, lastNRound = 1)
        val history = turns(3)

        val title = runBlocking { generator.generateTitle(history) }

        assertEquals("My title", title)
        assertEquals(1, hand.requests.size)
        // only the last user round survives, plus the generation instruction
        assertEquals(
            listOf(
                user("u3"),
                assistant("a3"),
                instruction(),
            ),
            hand.requests[0].messages
        )
    }

    @Test
    fun `lastNRound beyond the round count keeps the whole history`() {
        val hand = FakeHand(
            runScript = { textRunFlow("My title") }
        )
        val generator = generator(hand, lastNRound = 10)
        val history = turns(3)

        runBlocking { generator.generateTitle(history) }

        assertEquals(
            history + instruction(),
            hand.requests[0].messages
        )
    }

    @Test
    fun `an image in the dropped history does not trip the capability check`() {
        // the title generator only sees the kept tail: an image dropped by
        // the round cap must not fail a text-only title model
        val hand = FakeHand(
            runScript = { textRunFlow("My title") }
        )
        val generator = generator(hand, model("bifrost/cerebras/gpt-oss-120b"), lastNRound = 1)
        val history = turns(1) + ChatMessage(
            ChatMessageRole.User,
            listOf(
                ChatMessagePart.Attachment(
                    kind = AttachmentKind.Image,
                    content = AttachmentContent.Base64("AAAA"),
                    mimeType = "image/png",
                )
            ),
        ) + turns(1)

        val title = runBlocking { generator.generateTitle(history) }

        assertEquals("My title", title)
        assertEquals(1, hand.requests.size)
    }

    @Test
    fun `an image in the kept history fails fast`() {
        // the image survives the round cap and reaches the text-only model:
        // capability mismatch, no LLM call
        val hand = FakeHand()
        val generator = generator(hand, model("bifrost/cerebras/gpt-oss-120b"), lastNRound = 1)
        val history = turns(1) + ChatMessage(
            ChatMessageRole.User,
            listOf(
                ChatMessagePart.Attachment(
                    kind = AttachmentKind.Image,
                    content = AttachmentContent.Base64("AAAA"),
                    mimeType = "image/png",
                )
            ),
        )

        assertFailsWith<ModelCapabilityException> {
            runBlocking { generator.generateTitle(history) }
        }
        assertTrue(hand.requests.isEmpty(), "the LLM must not be called on a capability mismatch")
    }

    @Test
    fun `a text-only title model with image history fails fast`() {
        // capability mismatch is a `title.model` configuration error: fail
        // before the LLM call instead of sending a doomed prompt
        val hand = FakeHand()
        val generator = generator(hand, model("bifrost/cerebras/gpt-oss-120b"))
        val history = turns(1) + ChatMessage(
            ChatMessageRole.User,
            listOf(
                ChatMessagePart.Attachment(
                    kind = AttachmentKind.Image,
                    content = AttachmentContent.Base64("AAAA"),
                    mimeType = "image/png",
                )
            ),
        )
        assertFailsWith<ModelCapabilityException> {
            runBlocking { generator.generateTitle(history) }
        }
        assertTrue(hand.requests.isEmpty(), "the LLM must not be called on a capability mismatch")
    }

    @Test
    fun `a vision model accepts image history`() {
        val hand = FakeHand(
            runScript = { textRunFlow("My title") }
        )
        val generator = generator(hand, model("bifrost/cerebras/gemma-4-31b"))
        val history = turns(1) + ChatMessage(
            ChatMessageRole.User,
            listOf(
                ChatMessagePart.Attachment(
                    kind = AttachmentKind.Image,
                    content = AttachmentContent.Base64("AAAA"),
                    mimeType = "image/png",
                )
            ),
        )
        val title = runBlocking { generator.generateTitle(history) }
        assertEquals("My title", title)
        assertEquals(1, hand.requests.size)
    }
}
