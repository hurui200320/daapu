package info.skyblond.daapu.lc4j

import info.skyblond.daapu.agent.ModelCapabilityException
import info.skyblond.daapu.agent.checkPromptContentCapabilities
import info.skyblond.daapu.agent.lc4j.llm.ModelCatalog
import info.skyblond.daapu.agent.lc4j.provider.BifrostProvider
import info.skyblond.daapu.chat.AttachmentContent
import info.skyblond.daapu.chat.AttachmentKind
import info.skyblond.daapu.chat.ChatMessage
import info.skyblond.daapu.chat.ChatMessagePart
import info.skyblond.daapu.chat.ChatMessageRole
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

    private val catalog = ModelCatalog(BifrostProvider("bifrost", "http://gateway.example/v1", "test-key"))
    private val gptOss = catalog.findModel("bifrost/cerebras/gpt-oss-120b")!!
    private val cerebrasGemma = catalog.findModel("bifrost/cerebras/gemma-4-31b")!!
    private val novitaGemma = catalog.findModel("bifrost/novita/google/gemma-4-31b-it")!!

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
        checkPromptContentCapabilities(textOnlyChat(), gptOss)
    }

    @Test
    fun `image in the prompt fails on a text-only model`() {
        assertFailsWith<ModelCapabilityException> {
            checkPromptContentCapabilities(chatWith(AttachmentKind.Image), gptOss)
        }
    }

    @Test
    fun `image passes on a vision model`() {
        checkPromptContentCapabilities(chatWith(AttachmentKind.Image), cerebrasGemma)
        checkPromptContentCapabilities(chatWith(AttachmentKind.Image), novitaGemma)
    }

    @Test
    fun `attachment nested in a tool result is checked too`() {
        // an MCP tool can return an image mid-run; the attachment lives inside
        // the tool_result part, so the check must descend into it
        assertFailsWith<ModelCapabilityException> {
            checkPromptContentCapabilities(chatWithToolResultAttachment(AttachmentKind.Image), gptOss)
        }
        checkPromptContentCapabilities(
            chatWithToolResultAttachment(AttachmentKind.Image),
            cerebrasGemma,
        )
    }

    @Test
    fun `video audio and file fail on every catalog model`() {
        for (kind in listOf(AttachmentKind.Video, AttachmentKind.Audio, AttachmentKind.File)) {
            for (model in catalog.models) {
                assertFailsWith<ModelCapabilityException>("$kind on ${model.id}") {
                    checkPromptContentCapabilities(chatWith(kind), model)
                }
            }
        }
    }

    @Test
    fun `error message names the model and the offending content`() {
        val e = assertFailsWith<ModelCapabilityException> {
            checkPromptContentCapabilities(chatWith(AttachmentKind.Image), gptOss)
        }
        assertNotNull(e.message)
        val message = e.message!!
        // a useful message tells the user what to do next
        assertTrue(message.contains(gptOss.id), "should name the model: $message")
        assertTrue(message.contains("image"), "should mention image: $message")
    }
}
