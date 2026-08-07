package info.skyblond.daapu.agent

import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import info.skyblond.daapu.koog.client.Cerebras
import info.skyblond.daapu.koog.client.Novita
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * Pins the prompt-vs-model capability check that runs in the strategy's
 * preprocess node. Images can enter the prompt from the request OR from
 * stored history (sent to a vision model earlier, re-sent when the chat
 * switches to a text-only model), so the check must cover both.
 */
class ModelCapabilityCheckTest {

    private val imagePart = MessagePart.Attachment(
        source = AttachmentSource.Image(
            content = AttachmentContent.Binary.Base64("AAAA"),
            format = "png",
        )
    )

    private fun userMessage(vararg parts: MessagePart.RequestPart): Message.User =
        Message.User(listOf(*parts), RequestMetaInfo.Empty)

    @Test
    fun `text-only prompt passes on a text-only model`() {
        checkPromptContentCapabilities(
            listOf(userMessage(MessagePart.Text("hello"))),
            Cerebras.GPT_OSS_120B,
        )
    }

    @Test
    fun `image in the new input fails on a text-only model`() {
        assertFailsWith<ModelCapabilityException> {
            checkPromptContentCapabilities(
                listOf(userMessage(MessagePart.Text("look at this"), imagePart)),
                Cerebras.GPT_OSS_120B,
            )
        }
    }

    @Test
    fun `image only in history fails on a text-only model`() {
        // the user's scenario: an image was sent to a vision model and stored
        // in history; a later run with a text-only model re-sends it from
        // history, not from the request
        assertFailsWith<ModelCapabilityException> {
            checkPromptContentCapabilities(
                listOf(userMessage(imagePart), userMessage(MessagePart.Text("what was that?"))),
                Cerebras.GPT_OSS_120B,
            )
        }
    }

    @Test
    fun `image passes on a vision model`() {
        checkPromptContentCapabilities(
            listOf(userMessage(MessagePart.Text("look at this"), imagePart)),
            Cerebras.Gemma4_31B,
        )
        checkPromptContentCapabilities(
            listOf(userMessage(imagePart), userMessage(MessagePart.Text("what was that?"))),
            Novita.Gemma4_31B,
        )
    }

    @Test
    fun `error message names the model and the offending content`() {
        val e = assertFailsWith<ModelCapabilityException> {
            checkPromptContentCapabilities(
                listOf(userMessage(imagePart)),
                Cerebras.GPT_OSS_120B,
            )
        }
        assertNotNull(e.message)
        val message = e.message!!
        // a useful message tells the user what to do next
        check(message.contains(Cerebras.GPT_OSS_120B.id)) { "should name the model: $message" }
        check(message.contains("image")) { "should mention image: $message" }
    }
}
