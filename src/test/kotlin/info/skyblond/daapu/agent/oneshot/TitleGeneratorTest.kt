package info.skyblond.daapu.agent.oneshot

import info.skyblond.daapu.agent.ModelCatalog
import info.skyblond.daapu.agent.chat.AttachmentContent
import info.skyblond.daapu.agent.chat.AttachmentKind
import info.skyblond.daapu.agent.chat.ChatMessage
import info.skyblond.daapu.agent.chat.ChatMessageMeta
import info.skyblond.daapu.agent.chat.ChatMessagePart
import info.skyblond.daapu.agent.chat.ChatMessageRole
import info.skyblond.daapu.agent.model.ModelCapabilityException
import info.skyblond.daapu.agent.model.ModelProvider
import info.skyblond.daapu.db.DEFAULT_CHAT_TITLE
import info.skyblond.daapu.hand.FakeHand
import info.skyblond.daapu.hand.HandCompleteRequest
import info.skyblond.daapu.hand.HandUpstreamException
import info.skyblond.daapu.hand.assistantMessage
import info.skyblond.daapu.hand.failedCompleteResponse
import info.skyblond.daapu.hand.okCompleteResponse
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TitleGeneratorTest {

    private fun model(id: String = "bifrost/cerebras/gemma-4-31b") = ModelCatalog(
        mapOf("bifrost" to ModelProvider("bifrost", "http://127.0.0.1:9/v1", "test"))
    ).findModel(id)!!

    private fun user(text: String) =
        ChatMessage(ChatMessageRole.User, listOf(ChatMessagePart.Text(text)))

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
        val generator = TitleGenerator(model(), hand)
        assertEquals(DEFAULT_CHAT_TITLE, runBlocking { generator.generateTitle(emptyList()) })
        assertTrue(hand.completeRequests.isEmpty(), "an empty chat must not call the LLM")
    }

    @Test
    fun `history is sent with the instruction and the title system prompt`() {
        val hand = FakeHand(
            completeScript = { okCompleteResponse(assistantMessage("My title")) }
        )
        val generator = TitleGenerator(model(), hand)
        val history = turns(2)

        val title = runBlocking { generator.generateTitle(history) }

        assertEquals("My title", title)
        assertEquals(1, hand.completeRequests.size)
        val request = hand.completeRequests[0]
        assertEquals("cerebras/gemma-4-31b", request.model.modelId)
        // the history plus the appended generation instruction
        assertEquals(history + user("Generate a title according to the system prompt."), request.messages)
        assertTrue(request.systemPrompt!!.contains("ONE LINE"))
        assertTrue(request.systemPrompt!!.contains("15"))
        assertEquals(ChatMessageRole.User, request.messages.last().role)
    }

    @Test
    fun `a truncated response fails`() {
        val hand = FakeHand(
            completeScript = { okCompleteResponse(assistantMessage("half", finishReason = "length")) }
        )
        val generator = TitleGenerator(model(), hand)
        val e = assertFailsWith<IllegalStateException> {
            runBlocking { generator.generateTitle(turns(1)) }
        }
        // the outer message names the wrapper only; the detail lives on the cause
        assertEquals("Title generation failed", e.message)
        val cause = assertIs<IllegalStateException>(e.cause)
        assertTrue(cause.message!!.contains("finish_reason"))
    }

    @Test
    fun `a blank response fails`() {
        val hand = FakeHand(
            completeScript = { okCompleteResponse(assistantMessage("")) }
        )
        val generator = TitleGenerator(model(), hand)
        assertFailsWith<IllegalStateException> {
            runBlocking { generator.generateTitle(turns(1)) }
        }
    }

    @Test
    fun `a failed hand response fails`() {
        val hand = FakeHand(
            completeScript = { failedCompleteResponse("upstream", "boom") }
        )
        val generator = TitleGenerator(model(), hand)
        val e = assertFailsWith<IllegalStateException> {
            runBlocking { generator.generateTitle(turns(1)) }
        }
        assertEquals("Title generation failed", e.message)
        val cause = assertIs<IllegalStateException>(e.cause)
        assertTrue(cause.message!!.contains("boom"))
    }

    @Test
    fun `a hand transport failure is wrapped`() {
        val hand = FakeHand(
            completeScript = { throw HandUpstreamException("hand request failed with HTTP 500") }
        )
        val generator = TitleGenerator(model(), hand)
        val e = assertFailsWith<IllegalStateException> {
            runBlocking { generator.generateTitle(turns(1)) }
        }
        assertTrue(e.message!!.startsWith("Title generation failed"), e.message)
        assertIs<HandUpstreamException>(e.cause)
    }

    @Test
    fun `history is truncated to the last N user rounds`() {
        val hand = FakeHand(
            completeScript = { okCompleteResponse(assistantMessage("My title")) }
        )
        val generator = TitleGenerator(model(), hand, lastNRound = 1)
        val history = turns(3)

        val title = runBlocking { generator.generateTitle(history) }

        assertEquals("My title", title)
        assertEquals(1, hand.completeRequests.size)
        // only the last user round survives, plus the generation instruction
        assertEquals(
            listOf(user("u3"), assistant("a3"), user("Generate a title according to the system prompt.")),
            hand.completeRequests[0].messages
        )
    }

    @Test
    fun `lastNRound beyond the round count keeps the whole history`() {
        val hand = FakeHand(
            completeScript = { okCompleteResponse(assistantMessage("My title")) }
        )
        val generator = TitleGenerator(model(), hand, lastNRound = 10)
        val history = turns(3)

        runBlocking { generator.generateTitle(history) }

        assertEquals(history + user("Generate a title according to the system prompt."), hand.completeRequests[0].messages)
    }

    @Test
    fun `an image in the dropped history does not trip the capability check`() {
        // the title generator only sees the kept tail: an image dropped by
        // the round cap must not fail a text-only title model
        val hand = FakeHand(
            completeScript = { okCompleteResponse(assistantMessage("My title")) }
        )
        val generator = TitleGenerator(model("bifrost/cerebras/gpt-oss-120b"), hand, lastNRound = 1)
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
        assertEquals(1, hand.completeRequests.size)
    }

    @Test
    fun `an image in the kept history fails fast`() {
        // the image survives the round cap and reaches the text-only model:
        // capability mismatch, no LLM call
        val hand = FakeHand()
        val generator = TitleGenerator(model("bifrost/cerebras/gpt-oss-120b"), hand, lastNRound = 1)
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
        assertTrue(hand.completeRequests.isEmpty(), "the LLM must not be called on a capability mismatch")
    }

    @Test
    fun `a text-only title model with image history fails fast`() {
        // capability mismatch is a `title.model` configuration error: fail
        // before the LLM call instead of sending a doomed prompt
        val hand = FakeHand()
        val generator = TitleGenerator(model("bifrost/cerebras/gpt-oss-120b"), hand)
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
        assertTrue(hand.completeRequests.isEmpty(), "the LLM must not be called on a capability mismatch")
    }

    @Test
    fun `a vision model accepts image history`() {
        val hand = FakeHand(
            completeScript = { okCompleteResponse(assistantMessage("My title")) }
        )
        val generator = TitleGenerator(model("bifrost/cerebras/gemma-4-31b"), hand)
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
        assertEquals(1, hand.completeRequests.size)
    }
}
