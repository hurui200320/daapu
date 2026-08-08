package info.skyblond.daapu.koog

import ai.koog.prompt.message.AttachmentContent as KoogAttachmentContent
import ai.koog.prompt.message.AttachmentSource
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import info.skyblond.daapu.history.AttachmentContent
import info.skyblond.daapu.history.AttachmentKind
import info.skyblond.daapu.history.HistoryMeta
import info.skyblond.daapu.history.HistoryMessage
import info.skyblond.daapu.history.HistoryPart
import info.skyblond.daapu.history.HistoryRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant

/**
 * Pins the koog ↔ neutral history mapping. The round-trip test proves that a
 * stored-then-loaded history is byte-identical at the koog level, so the
 * provider's load(store(x)) is an identity for every shape this app produces.
 */
class KoogHistoryConvertersTest {

    private val representativeHistory: List<Message> = listOf(
        Message.System(listOf(MessagePart.Text("You are Raven.")), RequestMetaInfo.Empty),
        Message.User(
            parts = listOf(
                MessagePart.Text("<injection><real-time-info/></injection>"),
                MessagePart.Text("Hello!"),
            ),
            metaInfo = RequestMetaInfo.Empty,
        ),
        Message.Assistant(
            parts = listOf(
                MessagePart.Reasoning(content = listOf("thinking...")),
                MessagePart.Text("Hi there"),
                MessagePart.Tool.Call(id = "call_1", tool = "flag", args = """{"flag":true}"""),
            ),
            metaInfo = ResponseMetaInfo(
                timestamp = Instant.parse("2026-08-08T00:00:00Z"),
                totalTokensCount = 812,
                inputTokensCount = 700,
                outputTokensCount = 112,
            ),
            finishReason = "tool_calls",
        ),
        Message.User(
            parts = listOf(
                MessagePart.Tool.Result(id = "call_1", tool = "flag", parts = listOf(MessagePart.Text("ok"))),
            ),
            metaInfo = RequestMetaInfo.Empty,
        ),
        Message.User(
            parts = listOf(
                MessagePart.Attachment(
                    source = AttachmentSource.Image(
                        content = KoogAttachmentContent.Binary.Base64("AAAA"),
                        format = "png",
                        mimeType = "image/png",
                    )
                ),
            ),
            metaInfo = RequestMetaInfo.Empty,
        ),
    )

    private val expectedNeutral: List<HistoryMessage> = listOf(
        HistoryMessage(
            role = HistoryRole.System,
            parts = listOf(HistoryPart.Text("You are Raven.")),
        ),
        HistoryMessage(
            role = HistoryRole.User,
            parts = listOf(
                HistoryPart.Text("<injection><real-time-info/></injection>"),
                HistoryPart.Text("Hello!"),
            ),
        ),
        HistoryMessage(
            role = HistoryRole.Assistant,
            parts = listOf(
                HistoryPart.Reasoning(listOf("thinking...")),
                HistoryPart.Text("Hi there"),
                HistoryPart.ToolCall(id = "call_1", tool = "flag", args = """{"flag":true}"""),
            ),
            meta = HistoryMeta(
                timestamp = "2026-08-08T00:00:00Z",
                inputTokens = 700,
                outputTokens = 112,
                totalTokens = 812,
            ),
            finishReason = "tool_calls",
        ),
        // a user message holding only tool results becomes a `tool` message
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
        HistoryMessage(
            role = HistoryRole.User,
            parts = listOf(
                HistoryPart.Attachment(
                    kind = AttachmentKind.Image,
                    content = AttachmentContent.Base64("AAAA"),
                    format = "png",
                    mimeType = "image/png",
                )
            ),
        ),
    )

    @Test
    fun `koog history converts to the neutral format`() {
        assertEquals(expectedNeutral, representativeHistory.toNeutralHistory())
    }

    @Test
    fun `neutral history round-trips back to koog`() {
        assertEquals(representativeHistory, representativeHistory.toNeutralHistory().toKoogHistory())
    }

    @Test
    fun `empty meta info sentinels normalize to absent meta`() {
        val neutral = listOf(
            Message.User(listOf(MessagePart.Text("hi")), RequestMetaInfo.Empty),
            Message.System(listOf(MessagePart.Text("sys")), RequestMetaInfo.Empty),
        ).toNeutralHistory()
        assert(neutral.all { it.meta == null })
    }

    @Test
    fun `mixed user message splits into order preserving user and tool messages`() {
        val koog = listOf(
            Message.User(
                parts = listOf(
                    MessagePart.Text("before"),
                    MessagePart.Tool.Result(id = "a", tool = "t", parts = listOf(MessagePart.Text("1"))),
                    MessagePart.Tool.Result(id = "b", tool = "t", parts = listOf(MessagePart.Text("2"))),
                    MessagePart.Text("after"),
                ),
                metaInfo = RequestMetaInfo.Empty,
            )
        )
        assertEquals(
            listOf(
                HistoryMessage(role = HistoryRole.User, parts = listOf(HistoryPart.Text("before"))),
                HistoryMessage(
                    role = HistoryRole.Tool,
                    parts = listOf(
                        HistoryPart.ToolResult(id = "a", tool = "t", parts = listOf(HistoryPart.Text("1"))),
                        HistoryPart.ToolResult(id = "b", tool = "t", parts = listOf(HistoryPart.Text("2"))),
                    )
                ),
                HistoryMessage(role = HistoryRole.User, parts = listOf(HistoryPart.Text("after"))),
            ),
            koog.toNeutralHistory(),
        )
    }

    @Test
    fun `bytes attachment converts to base64 and round-trips`() {
        val koog = Message.User(
            parts = listOf(
                MessagePart.Attachment(
                    source = AttachmentSource.Image(
                        content = KoogAttachmentContent.Binary.Bytes(byteArrayOf(0, 0, 0)),
                        format = "png",
                    )
                ),
            ),
            metaInfo = RequestMetaInfo.Empty,
        )
        val neutral = listOf(koog).toNeutralHistory()
        assertEquals(
            listOf(
                HistoryMessage(
                    role = HistoryRole.User,
                    parts = listOf(
                        HistoryPart.Attachment(
                            kind = AttachmentKind.Image,
                            content = AttachmentContent.Base64("AAAA"),
                            format = "png",
                            mimeType = "image/png",
                        )
                    )
                )
            ),
            neutral,
        )
        // Bytes normalizes to Base64 (both are base64 strings), so the
        // round-trip is the Base64 variant of the same bytes
        val expectedRoundTrip = Message.User(
            parts = listOf(
                MessagePart.Attachment(
                    source = AttachmentSource.Image(
                        content = KoogAttachmentContent.Binary.Base64("AAAA"),
                        format = "png",
                    )
                ),
            ),
            metaInfo = RequestMetaInfo.Empty,
        )
        assertEquals(listOf(expectedRoundTrip), neutral.toKoogHistory())
    }

    @Test
    fun `url attachment is refused at the boundary`() {
        // the neutral format deliberately does not support external URLs
        // (nothing in this app produces them, and stored external resources
        // are a risk we don't need yet), so koog URL content must not slip
        // into history_json
        val koog = Message.User(
            parts = listOf(
                MessagePart.Attachment(
                    source = AttachmentSource.Image(
                        content = KoogAttachmentContent.URL("https://example.com/pic.png"),
                        format = "png",
                        fileName = "pic.png",
                    )
                ),
            ),
            metaInfo = RequestMetaInfo.Empty,
        )
        assertFailsWith<IllegalStateException> { listOf(koog).toNeutralHistory() }
    }

    @Test
    fun `plain text file attachment converts to text content`() {
        val koog = Message.User(
            parts = listOf(
                MessagePart.Attachment(
                    source = AttachmentSource.File(
                        content = KoogAttachmentContent.PlainText("some text"),
                        format = "md",
                        mimeType = "text/markdown",
                        fileName = "notes.md",
                    )
                ),
            ),
            metaInfo = RequestMetaInfo.Empty,
        )
        val neutral = listOf(koog).toNeutralHistory()
        val part = neutral.single().parts.single() as HistoryPart.Attachment
        assertEquals(AttachmentKind.File, part.kind)
        assertEquals(AttachmentContent.PlainText("some text"), part.content)
        assertEquals(listOf(koog), neutral.toKoogHistory())
    }

    @Test
    fun `tool result with error flag and attachment content round-trips`() {
        val koog = Message.User(
            parts = listOf(
                MessagePart.Tool.Result(
                    id = "call_9",
                    tool = "search",
                    isError = true,
                    parts = listOf(
                        MessagePart.Text("boom"),
                        MessagePart.Attachment(
                            source = AttachmentSource.Video(
                                content = KoogAttachmentContent.Binary.Base64("VklERU8="),
                                format = "mp4",
                            )
                        ),
                    ),
                ),
            ),
            metaInfo = RequestMetaInfo.Empty,
        )
        assertEquals(listOf(koog), listOf(koog).toNeutralHistory().toKoogHistory())
    }

    @Test
    fun `nanosecond precision timestamps round-trip`() {
        // koog timestamps carry nanosecond precision; the neutral format
        // stores them as ISO-8601 strings and re-parses with
        // kotlin.time.Instant.parse — pin that the full precision survives
        val koog = Message.Assistant(
            parts = listOf(MessagePart.Text("hi")),
            metaInfo = ResponseMetaInfo(
                timestamp = Instant.parse("2026-08-08T00:00:00.123456789Z"),
                totalTokensCount = 10,
                inputTokensCount = 5,
                outputTokensCount = 5,
            ),
            finishReason = "stop",
        )
        assertEquals(listOf(koog), listOf(koog).toNeutralHistory().toKoogHistory())
    }

    @Test
    fun `distant past timestamp with token counts round-trips`() {
        // a non-Empty ResponseMetaInfo whose timestamp is the Empty sentinel's
        // DISTANT_PAST must survive the string round-trip too
        val koog = Message.Assistant(
            parts = listOf(MessagePart.Text("hi")),
            metaInfo = ResponseMetaInfo(
                timestamp = Instant.DISTANT_PAST,
                totalTokensCount = 10,
            ),
            finishReason = "stop",
        )
        assertEquals(listOf(koog), listOf(koog).toNeutralHistory().toKoogHistory())
    }

    @Test
    fun `user message with request meta info timestamp round-trips`() {
        // the representative history only exercises the Empty sentinels; pin
        // that a non-empty RequestMetaInfo timestamp survives the string
        // round-trip too
        val koog = Message.User(
            parts = listOf(MessagePart.Text("hi")),
            metaInfo = RequestMetaInfo(timestamp = Instant.parse("2026-08-08T01:02:03Z")),
        )
        assertEquals(listOf(koog), listOf(koog).toNeutralHistory().toKoogHistory())
    }

    @Test
    fun `response meta info with model id round-trips`() {
        val koog = Message.Assistant(
            parts = listOf(MessagePart.Text("hi")),
            metaInfo = ResponseMetaInfo(
                timestamp = Instant.parse("2026-08-08T00:00:00Z"),
                totalTokensCount = 10,
                inputTokensCount = 4,
                outputTokensCount = 6,
                modelId = "cerebras/gpt-oss-120b",
            ),
            finishReason = "stop",
        )
        assertEquals(listOf(koog), listOf(koog).toNeutralHistory().toKoogHistory())
    }

    @Test
    fun `tool call without an id is refused at the boundary`() {
        // `withGeneratedToolCallIds` guarantees ids on accepted messages, but
        // the converter must not silently store a call without one: koog
        // would re-send it with mismatched random ids and strict providers
        // would reject the history forever
        val koog = Message.Assistant(
            parts = listOf(
                MessagePart.Tool.Call(id = null, tool = "flag", args = "{}"),
            ),
            metaInfo = ResponseMetaInfo.Empty,
            finishReason = "tool_calls",
        )
        assertFailsWith<IllegalStateException> { listOf(koog).toNeutralHistory() }
    }

    @Test
    fun `blank tool result id is refused at the boundary`() {
        val koog = Message.User(
            parts = listOf(
                MessagePart.Tool.Result(id = "", tool = "flag", parts = listOf(MessagePart.Text("ok"))),
            ),
            metaInfo = RequestMetaInfo.Empty,
        )
        assertFailsWith<IllegalStateException> { listOf(koog).toNeutralHistory() }
    }
}
