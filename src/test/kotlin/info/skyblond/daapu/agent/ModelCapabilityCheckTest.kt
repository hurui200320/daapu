package info.skyblond.daapu.agent

import info.skyblond.daapu.agent.chat.*
import info.skyblond.daapu.agent.model.ModelCapabilityException
import info.skyblond.daapu.testutil.TEST_MODELS_BY_ID
import info.skyblond.daapu.testutil.testLlm
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the prompt-vs-model capability check.
 *
 * The check scans the FULL prompt (loaded history + new input): images can
 * enter the prompt from the request OR from stored history (sent to a vision
 * model earlier, re-sent when the chat switches to a text-only model), so the
 * caller must scan both — the check itself only maps attachment kinds to
 * required capabilities.
 */
class ModelCapabilityCheckTest {

    private val gptOss = testLlm("bifrost/cerebras/gpt-oss-120b")
    private val cerebrasGemma = testLlm("bifrost/cerebras/gemma-4-31b")
    private val novitaGemma = testLlm("bifrost/novita/google/gemma-4-31b-it")

    private fun chatWith(kind: AttachmentKind): List<ChatMessage> = listOf(
        ChatMessage(
            role = ChatMessageRole.User,
            parts = listOf(
                ChatMessagePart.Attachment(
                    kind = kind,
                    content = AttachmentContent.Base64("AAAA"),
                    mimeType = "application/octet-stream",
                ),
            ),
        ),
    )

    private fun chatWithToolResultAttachment(kind: AttachmentKind): List<ChatMessage> = listOf(
        ChatMessage(
            role = ChatMessageRole.ToolResult,
            parts = listOf(
                ChatMessagePart.ToolResult(
                    id = "call_1",
                    tool = "generate",
                    parts = listOf(
                        ChatMessagePart.Attachment(
                            kind = kind,
                            content = AttachmentContent.Base64("AAAA"),
                            mimeType = "application/octet-stream",
                        ),
                    ),
                ),
            ),
        ),
    )

    private fun textOnlyChat() = listOf(
        ChatMessage(ChatMessageRole.User, listOf(ChatMessagePart.Text("hi"))),
    )

    @Test
    fun `text-only prompt passes on a text-only model`() {
        gptOss.checkPromptContentCapabilities(textOnlyChat())
    }

    @Test
    fun `image in the prompt fails on a text-only model`() {
        assertFailsWith<ModelCapabilityException> {
            gptOss.checkPromptContentCapabilities(chatWith(AttachmentKind.Image))
        }
    }

    @Test
    fun `image passes on a vision model`() {
        cerebrasGemma.checkPromptContentCapabilities(chatWith(AttachmentKind.Image))
        novitaGemma.checkPromptContentCapabilities(chatWith(AttachmentKind.Image))
    }

    @Test
    fun `attachment nested in a tool result is checked too`() {
        // an MCP tool can return an image mid-run; the attachment lives inside
        // the tool_result part, so the check must descend into it
        assertFailsWith<ModelCapabilityException> {
            gptOss.checkPromptContentCapabilities(
                chatWithToolResultAttachment(AttachmentKind.Image),
            )
        }
        cerebrasGemma.checkPromptContentCapabilities(
            chatWithToolResultAttachment(AttachmentKind.Image),
        )
    }

    @Test
    fun `video audio and file fail on every catalog model`() {
        // every fixture catalog model (TEST_MODELS_BY_ID, not a hand-picked
        // list), so a future entry that silently claims video/audio support
        // is still caught here
        for (kind in listOf(AttachmentKind.Video, AttachmentKind.Audio, AttachmentKind.File)) {
            for (model in TEST_MODELS_BY_ID.values) {
                assertFailsWith<ModelCapabilityException>("$kind on ${model.id}") {
                    model.checkPromptContentCapabilities(chatWith(kind))
                }
            }
        }
    }

    @Test
    fun `error message names the model and the offending content`() {
        val e = assertFailsWith<ModelCapabilityException> {
            gptOss.checkPromptContentCapabilities(chatWith(AttachmentKind.Image))
        }
        assertNotNull(e.message)
        val message = e.message!!
        // a useful message tells the user what to do next
        assertTrue(message.contains(gptOss.id), "should name the model: $message")
        assertTrue(message.contains("image"), "should mention image: $message")
    }
}
