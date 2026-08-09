package info.skyblond.daapu.langchain4j

import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.AudioContent
import dev.langchain4j.data.message.ImageContent
import dev.langchain4j.data.message.PdfFileContent
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.TextContent
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.data.message.VideoContent
import dev.langchain4j.model.chat.response.ChatResponse
import dev.langchain4j.model.output.FinishReason
import dev.langchain4j.model.output.TokenUsage
import info.skyblond.daapu.history.AttachmentContent
import info.skyblond.daapu.history.AttachmentKind
import info.skyblond.daapu.history.HistoryMessage
import info.skyblond.daapu.history.HistoryPart
import info.skyblond.daapu.history.HistoryRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the neutral ↔ langchain4j history mapping: the request building block
 * of the turn loop. A mistake here either drops stored content when loading
 * history or stores something the next round cannot re-send.
 */
class Langchain4jHistoryConvertersTest {

    // ------------------------------------------------------------------
    // neutral → langchain4j (the request building path)
    // ------------------------------------------------------------------

    @Test
    fun `representative history maps to langchain4j messages`() {
        val history = listOf(
            HistoryMessage(role = HistoryRole.System, parts = listOf(HistoryPart.Text("You are Raven."))),
            HistoryMessage(
                role = HistoryRole.User,
                parts = listOf(
                    HistoryPart.Text("<injection/>"),
                    HistoryPart.Text("Hello!"),
                    HistoryPart.Attachment(
                        kind = AttachmentKind.Image,
                        content = AttachmentContent.Base64("AAAA"),
                        format = "png",
                        mimeType = "image/png",
                    ),
                ),
            ),
            HistoryMessage(
                role = HistoryRole.Assistant,
                parts = listOf(
                    HistoryPart.Reasoning(listOf("thinking...")),
                    HistoryPart.Text("Hi there"),
                    HistoryPart.ToolCall(id = "call_1", tool = "flag", args = """{"flag":true}"""),
                ),
                finishReason = "tool_calls",
            ),
            HistoryMessage(
                role = HistoryRole.Tool,
                parts = listOf(
                    HistoryPart.ToolResult(
                        id = "call_1",
                        tool = "flag",
                        parts = listOf(HistoryPart.Text("ok")),
                    )
                ),
            ),
        )

        val messages = history.toLangchain4jMessages()
        assertEquals(4, messages.size)

        val system = assertIs<SystemMessage>(messages[0])
        assertEquals("You are Raven.", system.text())

        val user = assertIs<UserMessage>(messages[1])
        assertEquals(3, user.contents().size)
        assertEquals(TextContent("<injection/>"), user.contents()[0])
        assertEquals(TextContent("Hello!"), user.contents()[1])
        val image = assertIs<ImageContent>(user.contents()[2])
        assertEquals("AAAA", image.image().base64Data())
        assertEquals("image/png", image.image().mimeType())

        val assistant = assertIs<AiMessage>(messages[2])
        assertEquals("thinking...", assistant.thinking())
        assertEquals("Hi there", assistant.text())
        val request = assistant.toolExecutionRequests().single()
        assertEquals("call_1", request.id())
        assertEquals("flag", request.name())
        assertEquals("""{"flag":true}""", request.arguments())

        val tool = assertIs<ToolExecutionResultMessage>(messages[3])
        assertEquals("call_1", tool.id())
        assertEquals("flag", tool.toolName())
        assertEquals("ok", tool.text())
    }

    @Test
    fun `multiple reasoning blocks join into a single thinking string`() {
        // langchain4j has one flat thinking string; stored blocks are joined
        // (the next store normalizes back to a singleton reasoning part)
        val history = listOf(
            HistoryMessage(
                role = HistoryRole.Assistant,
                parts = listOf(HistoryPart.Reasoning(listOf("think", "more"))),
                finishReason = "stop",
            )
        )
        val assistant = assertIs<AiMessage>(history.toLangchain4jMessages().single())
        assertEquals("think\nmore", assistant.thinking())
    }

    @Test
    fun `all attachment kinds map to their content type`() {
        val base64 = "AAAA"
        val history = listOf(
            HistoryMessage(
                role = HistoryRole.User,
                parts = listOf(
                    HistoryPart.Attachment(AttachmentKind.Video, AttachmentContent.Base64(base64), "mp4", "video/mp4"),
                    HistoryPart.Attachment(AttachmentKind.Audio, AttachmentContent.Base64(base64), "mp3", "audio/mpeg"),
                    HistoryPart.Attachment(AttachmentKind.File, AttachmentContent.Base64(base64), "pdf", "application/pdf"),
                    HistoryPart.Attachment(AttachmentKind.Image, AttachmentContent.PlainText("inline"), "txt", "text/plain"),
                ),
            )
        )
        val contents = assertIs<UserMessage>(history.toLangchain4jMessages().single()).contents()
        assertIs<VideoContent>(contents[0])
        assertIs<AudioContent>(contents[1])
        assertIs<PdfFileContent>(contents[2])
        assertIs<TextContent>(contents[3])
        assertEquals("inline", (contents[3] as TextContent).text())
    }

    @Test
    fun `assistant attachment in stored history is refused`() {
        // nothing in this app produces one; failing fast beats silently
        // dropping stored content
        val history = listOf(
            HistoryMessage(
                role = HistoryRole.Assistant,
                parts = listOf(
                    HistoryPart.Attachment(AttachmentKind.Image, AttachmentContent.Base64("AAAA"), "png", "image/png"),
                ),
                finishReason = "stop",
            )
        )
        assertFailsWith<IllegalStateException> { history.toLangchain4jMessages() }
    }

    @Test
    fun `tool message with more than one tool_result part is refused`() {
        // a stored `tool` message must hold exactly one tool_result
        val history = listOf(
            HistoryMessage(
                role = HistoryRole.Tool,
                parts = listOf(
                    HistoryPart.ToolResult("c1", "a", listOf(HistoryPart.Text("1"))),
                    HistoryPart.ToolResult("c2", "b", listOf(HistoryPart.Text("2"))),
                ),
            )
        )
        assertFailsWith<IllegalStateException> { history.toLangchain4jMessages() }
    }

    // ------------------------------------------------------------------
    // ChatResponse → neutral (the store path of the turn loop)
    // ------------------------------------------------------------------

    @Test
    fun `accepted response maps to a neutral assistant message`() {
        val response = ChatResponse.builder()
            .aiMessage(
                AiMessage.builder()
                    .thinking("thinking...")
                    .text("Hi there")
                    .toolExecutionRequests(
                        listOf(
                            dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                                .id("call_1").name("flag").arguments("{}").build()
                        )
                    )
                    .build()
            )
            .modelName("cerebras/gpt-oss-120b")
            .tokenUsage(TokenUsage(700, 112, 812))
            .finishReason(FinishReason.TOOL_EXECUTION)
            .build()

        val neutral = response.toNeutralAssistantMessage()
        assertEquals(HistoryRole.Assistant, neutral.role)
        // part order mirrors the old koog stream assembly: reasoning, text, tool calls
        assertEquals(
            listOf(
                HistoryPart.Reasoning(listOf("thinking...")),
                HistoryPart.Text("Hi there"),
                HistoryPart.ToolCall(id = "call_1", tool = "flag", args = "{}"),
            ),
            neutral.parts,
        )
        assertEquals("tool_calls", neutral.finishReason)
        assertEquals(700, neutral.meta?.inputTokens)
        assertEquals(112, neutral.meta?.outputTokens)
        assertEquals(812, neutral.meta?.totalTokens)
        assertEquals("cerebras/gpt-oss-120b", neutral.meta?.modelId)
    }

    @Test
    fun `assistant message without a finish reason is refused`() {
        // the turn loop's truncation check runs before this; a null finish
        // reason here would be a programming error, pinned defensively
        val response = ChatResponse.builder()
            .aiMessage(AiMessage.builder().text("hi").build())
            .finishReason(null)
            .build()
        assertFailsWith<IllegalStateException> { response.toNeutralAssistantMessage() }
    }

    @Test
    fun `finish reason wire names are stable`() {
        // pinned by the golden-format tests: "stop", "tool_calls", ...
        assertEquals("stop", FinishReason.STOP.toWireName())
        assertEquals("length", FinishReason.LENGTH.toWireName())
        assertEquals("tool_calls", FinishReason.TOOL_EXECUTION.toWireName())
        assertEquals("content_filter", FinishReason.CONTENT_FILTER.toWireName())
        assertEquals("other", FinishReason.OTHER.toWireName())
    }

    @Test
    fun `tool call with a blank id is refused on store`() {
        // withGeneratedToolCallIds runs before this; a blank id would make the
        // re-sent history be rejected by strict providers forever
        val response = ChatResponse.builder()
            .aiMessage(
                AiMessage.builder()
                    .toolExecutionRequests(
                        listOf(
                            dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                                .id("").name("flag").arguments("{}").build()
                        )
                    )
                    .build()
            )
            .finishReason(FinishReason.TOOL_EXECUTION)
            .build()
        val e = assertFailsWith<IllegalStateException> { response.toNeutralAssistantMessage() }
        assertTrue(e.message!!.contains("tool_call"), e.message)
    }

    @Test
    fun `response without thinking stores no reasoning part`() {
        val response = ChatResponse.builder()
            .aiMessage(AiMessage.builder().text("hi").build())
            .finishReason(FinishReason.STOP)
            .build()
        val neutral = response.toNeutralAssistantMessage()
        assertEquals(listOf(HistoryPart.Text("hi")), neutral.parts)
        assertNull(neutral.meta?.inputTokens)
    }
}
