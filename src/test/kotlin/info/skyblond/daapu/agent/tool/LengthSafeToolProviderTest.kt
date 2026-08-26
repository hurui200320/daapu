package info.skyblond.daapu.agent.tool

import info.skyblond.daapu.agent.chat.AttachmentContent
import info.skyblond.daapu.agent.chat.AttachmentKind
import info.skyblond.daapu.agent.chat.ChatMessagePart
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.*

/**
 * A minimal fake delegate, the same shape as the ones in the sibling tool
 * provider tests: advertises one tool, answers with the configured result
 * factory, and counts its executions so tests can assert delegation.
 */
private class FakeReplyToolProvider(
    private val reply: (ToolCallRequest) -> ChatMessagePart.ToolResult,
) : ToolProvider {
    val executed = mutableListOf<ToolCallRequest>()
    val advertised = listOf(ToolSpec("fake__read", "fake tool", buildJsonObject {}))

    override fun namespaces(): Set<String> = setOf("fake")

    override suspend fun specifications(): List<ToolSpec> = advertised

    override fun executionTimeoutSeconds(toolName: String): Long =
        if (advertised.any { it.name == toolName }) 30 else 0

    override suspend fun execute(request: ToolCallRequest): ChatMessagePart.ToolResult {
        executed += request
        return reply(request)
    }
}

class LengthSafeToolProviderTest {

    private fun toolCall(id: String, name: String = "fake__read") =
        ToolCallRequest(id = id, name = name, args = buildJsonObject {})

    private fun result(
        text: String,
        isError: Boolean = false,
        extraParts: List<ChatMessagePart.ContentPart> = emptyList(),
    ) = ChatMessagePart.ToolResult(
        id = "c1", tool = "fake__read",
        parts = listOf(ChatMessagePart.Text(text)) + extraParts,
        isError = isError,
    )

    private fun textOf(parts: List<ChatMessagePart.ContentPart>) =
        (parts.single() as ChatMessagePart.Text).text

    @Test
    fun `requires a positive maxLength`() {
        val delegate = FakeReplyToolProvider { result("ok") }
        assertFailsWith<IllegalArgumentException> { LengthSafeToolProvider(delegate, 0) }
        assertFailsWith<IllegalArgumentException> { LengthSafeToolProvider(delegate, -1) }
        LengthSafeToolProvider(delegate, 1)
    }

    @Test
    fun `delegates namespaces, specifications and execution timeout`() = runBlocking {
        val delegate = FakeReplyToolProvider { result("ok") }
        val safe = LengthSafeToolProvider(delegate, 40000)

        assertEquals(setOf("fake"), safe.namespaces())
        assertEquals(delegate.advertised, safe.specifications())
        assertEquals(30, safe.executionTimeoutSeconds("fake__read"))
        assertEquals(0, safe.executionTimeoutSeconds("unknown__tool"))

        // the execute is still routed to the delegate
        safe.execute(toolCall("c1"))
        assertEquals(listOf("c1"), delegate.executed.map { it.id })
    }

    @Test
    fun `a result fitting the cap passes through unchanged`() = runBlocking {
        val delegate = FakeReplyToolProvider { result("short reply") }
        val safe = LengthSafeToolProvider(delegate, 40000)

        val out = safe.execute(toolCall("c1"))
        assertFalse(out.isError)
        assertEquals("short reply", textOf(out.parts))
        assertEquals("c1", out.id)
        assertEquals("fake__read", out.tool)
    }

    @Test
    fun `a long result is truncated to one text part inside the cap with a marker`() = runBlocking {
        val original = "x".repeat(1000)
        val delegate = FakeReplyToolProvider { result(original) }
        val safe = LengthSafeToolProvider(delegate, 100)

        val out = safe.execute(toolCall("c1"))
        assertFalse(out.isError)
        assertEquals(1, out.parts.size, "all text merges into one part")
        val marker = "\n\n[tail truncated: the tool result was 1000 chars, capped at 100 chars]"
        val text = textOf(out.parts)
        assertEquals(100, text.length, "the merged text fits the cap exactly (marker budgeted inside)")
        assertTrue(text.endsWith(marker))
        val prefix = text.removeSuffix(marker)
        assertEquals(100 - marker.length, prefix.length)
        assertTrue(prefix.all { it == 'x' }, "the kept prefix is the head of the original")
        // the identity of the result survives the truncation
        assertEquals("c1", out.id)
        assertEquals("fake__read", out.tool)
    }

    @Test
    fun `multiple text parts merge in order and attachments survive`() = runBlocking {
        val attachment = ChatMessagePart.Attachment(
            kind = AttachmentKind.Image,
            content = AttachmentContent.Base64("YWJj"),
            mimeType = "image/png",
        )
        val delegate = FakeReplyToolProvider {
            ChatMessagePart.ToolResult(
                id = "c1", tool = "fake__read",
                parts = listOf(
                    ChatMessagePart.Text("head"),
                    attachment,
                    ChatMessagePart.Text("x".repeat(500)),
                ),
            )
        }
        val safe = LengthSafeToolProvider(delegate, 100)

        val out = safe.execute(toolCall("c1"))
        assertEquals(2, out.parts.size, "the attachment survives, the texts merge into one")
        assertSame(attachment, out.parts[0], "the attachment keeps its original order, ahead of the merged text")
        val marker = "\n\n[tail truncated: the tool result was 505 chars, capped at 100 chars]"
        val text = textOf(out.parts.drop(1))
        assertTrue(text.length <= 100, "the merged text must never exceed the cap, got ${text.length}")
        assertTrue(text.endsWith(marker))
        val prefix = text.removeSuffix(marker)
        assertTrue(
            prefix.startsWith("head\n"),
            "the merged text is the head of head + '\n' + tail, got: ${prefix.take(20)}",
        )
        assertTrue(prefix.drop("head\n".length).all { it == 'x' })
    }

    @Test
    fun `a result exactly at the cap passes through unchanged`() = runBlocking {
        val original = "x".repeat(100)
        val delegate = FakeReplyToolProvider { result(original) }
        val safe = LengthSafeToolProvider(delegate, 100)

        val out = safe.execute(toolCall("c1"))
        assertFalse(out.isError)
        assertEquals(original, textOf(out.parts))
    }

    @Test
    fun `multiple text parts whose joined text is exactly the cap pass through unchanged`() =
        runBlocking {
            // the cap applies to the JOINED text (parts joined by '\n'), so
            // 40 + 1 + 59 = 100 fits and must keep its two-part shape
            val delegate = FakeReplyToolProvider {
                ChatMessagePart.ToolResult(
                    id = "c1", tool = "fake__read",
                    parts = listOf(
                        ChatMessagePart.Text("a".repeat(40)),
                        ChatMessagePart.Text("b".repeat(59)),
                    ),
                )
            }
            val safe = LengthSafeToolProvider(delegate, 100)

            val out = safe.execute(toolCall("c1"))
            assertFalse(out.isError)
            assertEquals(2, out.parts.size, "a joined text exactly at the cap is untouched")
            assertEquals("a".repeat(40), (out.parts[0] as ChatMessagePart.Text).text)
            assertEquals("b".repeat(59), (out.parts[1] as ChatMessagePart.Text).text)
        }

    @Test
    fun `a cap smaller than the marker falls back to the marker alone`() = runBlocking {
        // the marker is budgeted INSIDE the cap; a cap too small to hold it
        // (unusable for real tool results anyway) yields the marker only,
        // which then exceeds the cap
        val delegate = FakeReplyToolProvider { result("x".repeat(1000)) }
        val safe = LengthSafeToolProvider(delegate, 10)

        val out = safe.execute(toolCall("c1"))
        assertFalse(out.isError)
        val marker = "\n\n[tail truncated: the tool result was 1000 chars, capped at 10 chars]"
        assertEquals(marker, textOf(out.parts))
        assertTrue(marker.length > 10, "the marker alone overflows a cap that cannot hold it")
    }

    @Test
    fun `the cut never splits a surrogate pair`() = runBlocking {
        // the cap counts UTF-16 code units: a naive take at the cap could
        // cut between an emoji's surrogate halves and leave a lone high
        // surrogate at the end of the kept prefix — malformed text the
        // model would see. The marker is 70 units for a 105-unit result
        // capped at 100, so the kept prefix is 30 units; the emoji sits
        // with its high half at index 29, exactly the cut — it must be
        // dropped, not kept.
        val emoji = "\uD83D\uDE00"
        val original = "x".repeat(29) + emoji + "x".repeat(74)
        assertEquals(105, original.length, "the fixture must land the cut inside the pair")
        val delegate = FakeReplyToolProvider { result(original) }
        val safe = LengthSafeToolProvider(delegate, 100)

        val out = safe.execute(toolCall("c1"))
        val marker = "\n\n[tail truncated: the tool result was 105 chars, capped at 100 chars]"
        assertEquals(70, marker.length, "the marker length pins the cut position")
        val text = textOf(out.parts)
        assertTrue(text.endsWith(marker))
        val prefix = text.removeSuffix(marker)
        assertEquals("x".repeat(29), prefix, "the dangling high surrogate is dropped, one unit short of the cap")
        assertEquals(29 + marker.length, text.length)
        assertTrue(text.none { it.isHighSurrogate() || it.isLowSurrogate() })
    }

    @Test
    fun `multiple attachments keep their order ahead of the merged text`() = runBlocking {
        val first = ChatMessagePart.Attachment(
            kind = AttachmentKind.Image,
            content = AttachmentContent.Base64("Zmlyc3Q="),
            mimeType = "image/png",
        )
        val second = ChatMessagePart.Attachment(
            kind = AttachmentKind.Image,
            content = AttachmentContent.Base64("c2Vjb25k"),
            mimeType = "image/png",
        )
        val delegate = FakeReplyToolProvider {
            ChatMessagePart.ToolResult(
                id = "c1", tool = "fake__read",
                parts = listOf(
                    ChatMessagePart.Text("short head"),
                    first,
                    ChatMessagePart.Text("x".repeat(500)),
                    second,
                ),
            )
        }
        val safe = LengthSafeToolProvider(delegate, 100)

        val out = safe.execute(toolCall("c1"))
        assertEquals(3, out.parts.size, "both attachments survive, the texts merge into one")
        assertSame(first, out.parts[0], "the first attachment keeps its order")
        assertSame(second, out.parts[1], "the second attachment keeps its order")
        val text = textOf(out.parts.drop(2))
        assertTrue(text.endsWith("\n\n[tail truncated: the tool result was 511 chars, capped at 100 chars]"))
        assertTrue(text.startsWith("short head\nx"), "the merged text leads with the head text part")
    }

    @Test
    fun `an error result passes through unchanged even when long`() = runBlocking {
        // BY DESIGN: a tool error is a short, concise description of the
        // failure — never a content dump — and the model needs it verbatim
        // to recover; truncating it would hide the cause of a failed call
        val original = "Error: something went wrong, details: " + "y".repeat(10_000)
        val delegate = FakeReplyToolProvider { result(original, isError = true) }
        val safe = LengthSafeToolProvider(delegate, 50)

        val out = safe.execute(toolCall("c1"))
        assertTrue(out.isError)
        assertEquals(1, out.parts.size)
        assertEquals(original, textOf(out.parts))
    }

    @Test
    fun `a result with no text parts passes through unchanged`() = runBlocking {
        val attachment = ChatMessagePart.Attachment(
            kind = AttachmentKind.Image,
            content = AttachmentContent.Base64("YWJj"),
            mimeType = "image/png",
        )
        val delegate = FakeReplyToolProvider {
            ChatMessagePart.ToolResult(
                id = "c1", tool = "fake__read", parts = listOf(attachment),
            )
        }
        val safe = LengthSafeToolProvider(delegate, 50)

        val out = safe.execute(toolCall("c1"))
        assertSame(attachment, out.parts.single())
    }
}
