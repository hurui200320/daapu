package info.skyblond.daapu.history

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * DB-free tests for the neutral history JSON codec behind [HistoryCodec].
 *
 * The round-trip test proves encode/decode are symmetric; the golden tests
 * pin the exact JSON shape the project owns, so a change to the format
 * (e.g. a refactor, or an incompatible framework migration) fails here first —
 * before it can brick every stored chat at load time.
 */
class HistoryCodecTest {

    private val representativeHistory: List<HistoryMessage> = listOf(
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

    /**
     * Exact JSON for [representativeHistory]. The format is project-owned:
     * short lowercase `"type"` discriminators, no koog or langchain4j names.
     */
    private val goldenJson =
        """[{"role":"system","parts":[{"type":"text","text":"You are Raven."}]},{""" +
            """"role":"user","parts":[{"type":"text","text":"<injection><real-time-info/></injection>"},{""" +
            """"type":"text","text":"Hello!"}]},{"role":"assistant","parts":[{"type":"reasoning","content":[""" +
            """"thinking..."]},{"type":"text","text":"Hi there"},{"type":"tool_call","id":"call_1","tool":"flag",""" +
            """"args":"{\"flag\":true}"}],"meta":{"timestamp":"2026-08-08T00:00:00Z","inputTokens":700,""" +
            """"outputTokens":112,"totalTokens":812},"finishReason":"tool_calls"},{"role":"tool","parts":[{""" +
            """"type":"tool_result","id":"call_1","tool":"flag","parts":[{"type":"text","text":"ok"}]}]},{""" +
            """"role":"user","parts":[{"type":"attachment","kind":"image","content":{"type":"base64",""" +
            """"base64":"AAAA"},"format":"png","mimeType":"image/png"}]}]"""

    @Test
    fun `history round-trips through the codec`() {
        val encoded = HistoryCodec.encodeHistory(representativeHistory)
        assertEquals(representativeHistory, HistoryCodec.decodeHistory("chat-1", encoded))
    }

    @Test
    fun `encoding matches the golden json byte for byte`() {
        assertEquals(goldenJson, HistoryCodec.encodeHistory(representativeHistory))
    }

    @Test
    fun `golden json decodes to the representative history`() {
        assertEquals(representativeHistory, HistoryCodec.decodeHistory("chat-1", goldenJson))
    }

    @Test
    fun `empty json array decodes to an empty history`() {
        // the exact payload every brand-new chat row starts with
        // (history_json DEFAULT '[]')
        assertEquals(emptyList(), HistoryCodec.decodeHistory("chat-new", "[]"))
    }

    @Test
    fun `corrupt history json fails fast instead of resetting the chat`() {
        // a corrupt row (or an incompatible format change) must throw rather
        // than silently load an empty history
        val e = assertFailsWith<IllegalStateException> {
            HistoryCodec.decodeHistory("chat-1", """[{"role":"no.such.Role","parts":[]}]""")
        }
        assertTrue(
            e.message!!.contains("chat-1"),
            "Error should name the affected chat, got: ${e.message}",
        )
    }

    @Test
    fun `unknown part type fails fast`() {
        assertFailsWith<IllegalStateException> {
            HistoryCodec.decodeHistory(
                "chat-1",
                """[{"role":"user","parts":[{"type":"no.such.Part","text":"x"}]}]""",
            )
        }
    }

    @Test
    fun `unknown keys in messages and parts are tolerated`() {
        // forward-compatible: a newer format may add fields
        val json = """[{"role":"user","parts":[{"type":"text","text":"hi","extra":"x"}],"future":1}]"""
        assertEquals(
            listOf(HistoryMessage(role = HistoryRole.User, parts = listOf(HistoryPart.Text("hi")))),
            HistoryCodec.decodeHistory("chat-1", json),
        )
    }

    @Test
    fun `assistant message without finishReason fails fast`() {
        // the streaming path only accepts responses that carried a
        // finish_reason, so a stored row missing it is a broken invariant
        val e = assertFailsWith<IllegalStateException> {
            HistoryCodec.decodeHistory(
                "chat-1",
                """[{"role":"assistant","parts":[{"type":"text","text":"hi"}]}]""",
            )
        }
        assertTrue(
            e.message!!.contains("finishReason") && e.message!!.contains("chat-1"),
            "Error should name the field and chat, got: ${e.message}",
        )
    }

    @Test
    fun `non-assistant message with finishReason fails fast`() {
        // finishReason is assistant-only; the converter silently drops
        // it on non-assistant messages, so a stored row carrying one is a
        // broken invariant
        val e = assertFailsWith<IllegalStateException> {
            HistoryCodec.decodeHistory(
                "chat-1",
                """[{"role":"user","parts":[{"type":"text","text":"hi"}],"finishReason":"stop"}]""",
            )
        }
        assertTrue(
            e.message!!.contains("finishReason") && e.message!!.contains("chat-1"),
            "Error should name the field and chat, got: ${e.message}",
        )
    }

    @Test
    fun `system message with a non-text part fails fast`() {
        // the converter maps system messages to text parts only; a
        // stored row violating that fails here with the chat named instead
        // of a generic converter error at load
        val e = assertFailsWith<IllegalStateException> {
            HistoryCodec.decodeHistory(
                "chat-1",
                """[{"role":"system","parts":[{"type":"reasoning","content":["think"]}]}]""",
            )
        }
        assertTrue(
            e.message!!.contains("Reasoning") && e.message!!.contains("chat-1"),
            "Error should name the offending part and chat, got: ${e.message}",
        )
    }

    @Test
    fun `user message with a response-only part fails fast`() {
        val e = assertFailsWith<IllegalStateException> {
            HistoryCodec.decodeHistory(
                "chat-1",
                """[{"role":"user","parts":[{"type":"reasoning","content":["think"]}]}]""",
            )
        }
        assertTrue(
            e.message!!.contains("Reasoning") && e.message!!.contains("chat-1"),
            "Error should name the offending part and chat, got: ${e.message}",
        )
    }

    @Test
    fun `assistant message with a tool result part fails fast`() {
        val e = assertFailsWith<IllegalStateException> {
            HistoryCodec.decodeHistory(
                "chat-1",
                """[{"role":"assistant","parts":[{"type":"tool_result","id":"c1","tool":"t","parts":[{"type":"text","text":"ok"}]}],"finishReason":"stop"}]""",
            )
        }
        assertTrue(
            e.message!!.contains("ToolResult") && e.message!!.contains("chat-1"),
            "Error should name the offending part and chat, got: ${e.message}",
        )
    }

    @Test
    fun `tool message with a text part fails fast`() {
        val e = assertFailsWith<IllegalStateException> {
            HistoryCodec.decodeHistory(
                "chat-1",
                """[{"role":"tool","parts":[{"type":"text","text":"hi"}]}]""",
            )
        }
        assertTrue(
            e.message!!.contains("Text") && e.message!!.contains("chat-1"),
            "Error should name the offending part and chat, got: ${e.message}",
        )
    }

    @Test
    fun `unparseable meta timestamp fails fast`() {
        val e = assertFailsWith<IllegalStateException> {
            HistoryCodec.decodeHistory(
                "chat-1",
                """[{"role":"user","parts":[{"type":"text","text":"hi"}],"meta":{"timestamp":"not-a-time"}}]""",
            )
        }
        assertTrue(
            e.message!!.contains("timestamp") && e.message!!.contains("chat-1"),
            "Error should name the timestamp and chat, got: ${e.message}",
        )
    }

    @Test
    fun `blank tool call id fails fast`() {
        val e = assertFailsWith<IllegalStateException> {
            HistoryCodec.decodeHistory(
                "chat-1",
                """[{"role":"assistant","parts":[{"type":"tool_call","id":"","tool":"flag","args":"{}"}],"finishReason":"tool_calls"}]""",
            )
        }
        assertTrue(
            e.message!!.contains("tool_call") && e.message!!.contains("chat-1"),
            "Error should name the part and chat, got: ${e.message}",
        )
    }

    @Test
    fun `tool call without an id key fails fast`() {
        // id is non-nullable in the format: a row missing it cannot be
        // re-sent to strict providers, so it fails on load instead
        assertFailsWith<IllegalStateException> {
            HistoryCodec.decodeHistory(
                "chat-1",
                """[{"role":"assistant","parts":[{"type":"tool_call","tool":"flag","args":"{}"}],"finishReason":"tool_calls"}]""",
            )
        }
    }

    @Test
    fun `blank tool result id fails fast`() {
        val e = assertFailsWith<IllegalStateException> {
            HistoryCodec.decodeHistory(
                "chat-1",
                """[{"role":"tool","parts":[{"type":"tool_result","id":" ","tool":"flag","parts":[{"type":"text","text":"ok"}]}]}]""",
            )
        }
        assertTrue(
            e.message!!.contains("tool_result") && e.message!!.contains("chat-1"),
            "Error should name the part and chat, got: ${e.message}",
        )
    }
}
