package info.skyblond.daapu.agent.lc4j.chat

import dev.langchain4j.agent.tool.ToolExecutionRequest
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
import info.skyblond.daapu.chat.AttachmentContent
import info.skyblond.daapu.chat.AttachmentKind
import info.skyblond.daapu.chat.ChatMessage
import info.skyblond.daapu.chat.ChatMessagePart
import info.skyblond.daapu.chat.ChatMessageRole
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
            ChatMessage(role = ChatMessageRole.System, parts = listOf(ChatMessagePart.Text("You are Raven."))),
            ChatMessage(
                role = ChatMessageRole.User,
                parts = listOf(
                    ChatMessagePart.Text("<injection/>"),
                    ChatMessagePart.Text("Hello!"),
                    ChatMessagePart.Attachment(
                        kind = AttachmentKind.Image,
                        content = AttachmentContent.Base64("AAAA"),
                        mimeType = "image/png",
                    ),
                ),
            ),
            ChatMessage(
                role = ChatMessageRole.Assistant,
                parts = listOf(
                    ChatMessagePart.Reasoning(listOf("thinking...")),
                    ChatMessagePart.Text("Hi there"),
                    ChatMessagePart.ToolCall(id = "call_1", tool = "flag", args = """{"flag":true}"""),
                ),
                finishReason = "tool_calls",
            ),
            ChatMessage(
                role = ChatMessageRole.ToolResult,
                parts = listOf(
                    ChatMessagePart.ToolResult(
                        id = "call_1",
                        tool = "flag",
                        parts = listOf(ChatMessagePart.Text("ok")),
                    )
                ),
            ),
        )

        val messages = history.toLc4jMessages()
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
            ChatMessage(
                role = ChatMessageRole.Assistant,
                parts = listOf(ChatMessagePart.Reasoning(listOf("think", "more"))),
                finishReason = "stop",
            )
        )
        val assistant = assertIs<AiMessage>(history.toLc4jMessages().single())
        assertEquals("think\nmore", assistant.thinking())
    }

    @Test
    fun `all attachment kinds map to their content type`() {
        val base64 = "AAAA"
        val history = listOf(
            ChatMessage(
                role = ChatMessageRole.User,
                parts = listOf(
                    ChatMessagePart.Attachment(AttachmentKind.Video, AttachmentContent.Base64(base64), "video/mp4"),
                    ChatMessagePart.Attachment(AttachmentKind.Audio, AttachmentContent.Base64(base64), "audio/mpeg"),
                    ChatMessagePart.Attachment(AttachmentKind.File, AttachmentContent.Base64(base64), "application/pdf"),
                    ChatMessagePart.Attachment(AttachmentKind.Image, AttachmentContent.PlainText("inline"), "text/plain"),
                ),
            )
        )
        val contents = assertIs<UserMessage>(history.toLc4jMessages().single()).contents()
        assertIs<VideoContent>(contents[0])
        assertIs<AudioContent>(contents[1])
        assertIs<PdfFileContent>(contents[2])
        assertIs<TextContent>(contents[3])
        assertEquals("inline", (contents[3] as TextContent).text())
    }

    @Test
    fun `tool message with multiple tool_result parts maps to one message per part`() {
        // one neutral tool message may carry several tool_result parts; each
        // becomes its own langchain4j message (the turn loop stores one part
        // per message, so this only normalizes exotic rows)
        val history = listOf(
            ChatMessage(
                role = ChatMessageRole.ToolResult,
                parts = listOf(
                    ChatMessagePart.ToolResult("c1", "a", listOf(ChatMessagePart.Text("1"))),
                    ChatMessagePart.ToolResult("c2", "b", listOf(ChatMessagePart.Text("2"))),
                ),
            )
        )
        val messages = history.toLc4jMessages()
        assertEquals(2, messages.size)
        assertEquals("c1", assertIs<ToolExecutionResultMessage>(messages[0]).id())
        assertEquals("c2", assertIs<ToolExecutionResultMessage>(messages[1]).id())
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
                            ToolExecutionRequest.builder()
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
        assertEquals(ChatMessageRole.Assistant, neutral.role)
        // part order mirrors the old koog stream assembly: reasoning, text, tool calls
        assertEquals(
            listOf(
                ChatMessagePart.Reasoning(listOf("thinking...")),
                ChatMessagePart.Text("Hi there"),
                ChatMessagePart.ToolCall(id = "call_1", tool = "flag", args = "{}"),
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
                            ToolExecutionRequest.builder()
                                .id("").name("flag").arguments("{}").build()
                        )
                    )
                    .build()
            )
            .finishReason(FinishReason.TOOL_EXECUTION)
            .build()
        val e = assertFailsWith<IllegalStateException> { response.toNeutralAssistantMessage() }
        assertTrue(e.message!!.contains("Tool call"), e.message)
    }

    @Test
    fun `response without thinking stores no reasoning part`() {
        val response = ChatResponse.builder()
            .aiMessage(AiMessage.builder().text("hi").build())
            .finishReason(FinishReason.STOP)
            .build()
        val neutral = response.toNeutralAssistantMessage()
        assertEquals(listOf(ChatMessagePart.Text("hi")), neutral.parts)
        assertNull(neutral.meta?.inputTokens)
    }
}
