package info.skyblond.daapu.agent.chat

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.time.Instant
import kotlin.test.*

/**
 * DB-free tests for the neutral history JSON codec behind [ChatCodec].
 *
 * The round-trip test proves encode/decode are symmetric; the golden tests
 * pin the exact JSON shape the project owns, so a change to the format
 * (e.g. a refactor, or an incompatible framework migration) fails here first —
 * before it can brick every stored chat at load time.
 */
class ChatCodecTest {

    /** Minimal assistant meta fragment for JSON fixtures (meta is required on assistant messages). */
    private val assistantMetaJson = ""","meta":{"inputTokens":1,"outputTokens":1,"totalTokens":2}"""

    /** The fixed send time every user message in this test carries. */
    private val createdAt = Instant.parse("2026-08-18T12:34:56Z")

    private val representativeHistory: List<ChatMessage> = listOf(
        ChatMessage(
            role = ChatMessageRole.User,
            createdAt = createdAt,
            parts = listOf(
                ChatMessagePart.Text("<injection><real-time-info/></injection>"),
                ChatMessagePart.Text("Hello!"),
            ),
        ),
        ChatMessage(
            role = ChatMessageRole.Assistant,
            parts = listOf(
                ChatMessagePart.Reasoning("thinking..."),
                ChatMessagePart.Text("Hi there"),
                ChatMessagePart.ToolCall(
                    id = "call_1",
                    tool = "flag",
                    args = buildJsonObject { put("flag", true) },
                ),
            ),
            meta = ChatMessageMeta(
                inputTokens = 700,
                outputTokens = 112,
                totalTokens = 812,
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
        ChatMessage(
            role = ChatMessageRole.User,
            createdAt = createdAt,
            parts = listOf(
                ChatMessagePart.Attachment(
                    kind = AttachmentKind.Image,
                    content = AttachmentContent.Base64("AAAA"),
                    mimeType = "image/png",
                )
            ),
        ),
        // a complete chat must end with an assistant stop message
        // (ChatCodec.validateChat), so the golden history does too
        ChatMessage(
            role = ChatMessageRole.Assistant,
            parts = listOf(ChatMessagePart.Text("I see the image.")),
            meta = ChatMessageMeta(
                inputTokens = 10,
                outputTokens = 2,
                totalTokens = 12,
            ),
            finishReason = "stop",
        ),
    )

    /**
     * Exact JSON for [representativeHistory]. The format is project-owned:
     * short lowercase `"type"` discriminators.
     * Defaults (e.g. `isError = false`) are written explicitly.
     */
    private val goldenJson =
        """[{"role":"user","parts":[{"type":"text","text":"<injection><real-time-info/></injection>"},{""" +
                """"type":"text","text":"Hello!"}],"createdAt":"2026-08-18T12:34:56Z"},{"role":"assistant","parts":[{"type":"reasoning","content":""" +
                """"thinking..."},{"type":"text","text":"Hi there"},{"type":"tool_call","id":"call_1","tool":"flag",""" +
                """"args":{"flag":true}}],"meta":{"inputTokens":700,"outputTokens":112,"totalTokens":812},""" +
                """"finishReason":"tool_calls"},{"role":"tool_result","parts":[{"type":"tool_result","id":"call_1",""" +
                """"tool":"flag","parts":[{"type":"text","text":"ok"}],"isError":false}]},{"role":"user","parts":[{""" +
                """"type":"attachment","kind":"image","content":{"type":"base64","base64":"AAAA"},"mimeType":"image/png"}],"createdAt":"2026-08-18T12:34:56Z"},""" +
                """{"role":"assistant","parts":[{"type":"text","text":"I see the image."}],""" +
                """"meta":{"inputTokens":10,"outputTokens":2,"totalTokens":12},"finishReason":"stop"}]"""

    @Test
    fun `history round-trips through the codec`() {
        val encoded = ChatCodec.encodeChat(representativeHistory)
        assertEquals(representativeHistory, ChatCodec.decodeChat("chat-1", encoded))
    }

    @Test
    fun `encoding matches the golden json byte for byte`() {
        assertEquals(goldenJson, ChatCodec.encodeChat(representativeHistory))
    }

    @Test
    fun `golden json decodes to the representative history`() {
        assertEquals(representativeHistory, ChatCodec.decodeChat("chat-1", goldenJson))
    }

    @Test
    fun `empty json array decodes to an empty history`() {
        // the exact payload every brand-new chat row starts with
        // (chat_json DEFAULT '[]')
        assertEquals(emptyList(), ChatCodec.decodeChat("chat-new", "[]"))
    }

    @Test
    fun `corrupt history json fails fast instead of resetting the chat`() {
        // a corrupt row (or an incompatible format change) must throw rather
        // than silently load an empty history
        val e = assertFailsWith<IllegalStateException> {
            ChatCodec.decodeChat("chat-1", """[{"role":"no.such.Role","parts":[]}]""")
        }
        assertTrue(
            e.message!!.contains("chat-1"),
            "Error should name the affected chat, got: ${e.message}",
        )
    }

    @Test
    fun `unknown part type fails fast`() {
        assertFailsWith<IllegalStateException> {
            ChatCodec.decodeChat(
                "chat-1",
                """[{"role":"user","parts":[{"type":"no.such.Part","text":"x"}]}]""",
            )
        }
    }

    @Test
    fun `unknown keys in messages and parts are tolerated`() {
        // forward-compatible: a newer format may add fields
        val json =
            """[{"role":"user","createdAt":"2026-08-18T12:34:56Z","parts":[{"type":"text","text":"hi","extra":"x"}],"future":1},""" +
                    """{"role":"assistant","parts":[{"type":"text","text":"ok"}]""" + assistantMetaJson +
                    ""","finishReason":"stop"}]"""
        assertEquals(
            listOf(
                ChatMessage(
                    role = ChatMessageRole.User,
                    createdAt = createdAt,
                    parts = listOf(ChatMessagePart.Text("hi"))
                ),
                ChatMessage(
                    role = ChatMessageRole.Assistant,
                    parts = listOf(ChatMessagePart.Text("ok")),
                    meta = ChatMessageMeta(inputTokens = 1, outputTokens = 1, totalTokens = 2),
                    finishReason = "stop",
                ),
            ),
            ChatCodec.decodeChat("chat-1", json),
        )
    }

    @Test
    fun `assistant message without finishReason fails fast`() {
        // the streaming path only accepts responses that carried a
        // finish_reason, so a stored row missing it is a broken invariant
        val e = assertFailsWith<IllegalStateException> {
            ChatCodec.decodeChat(
                "chat-1",
                """[{"role":"assistant","parts":[{"type":"text","text":"hi"}]""" + assistantMetaJson + "}]",
            )
        }
        assertTrue(
            e.message!!.contains("finishReason") && e.message!!.contains("chat-1"),
            "Error should name the field and chat, got: ${e.message}",
        )
    }

    @Test
    fun `assistant message without meta fails fast`() {
        // meta is required on assistant messages: the hand refuses to serve
        // responses without provider-reported usage, so a stored row missing
        // it is a broken invariant
        val e = assertFailsWith<IllegalStateException> {
            ChatCodec.decodeChat(
                "chat-1",
                """[{"role":"assistant","parts":[{"type":"text","text":"hi"}],"finishReason":"stop"}]""",
            )
        }
        assertTrue(
            e.message!!.contains("meta") && e.message!!.contains("chat-1"),
            "Error should name the field and chat, got: ${e.message}",
        )
    }

    @Test
    fun `non-assistant message with finishReason fails fast`() {
        // finishReason is assistant-only; the converter silently drops
        // it on non-assistant messages, so a stored row carrying one is a
        // broken invariant
        val e = assertFailsWith<IllegalStateException> {
            ChatCodec.decodeChat(
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
    fun `user message with a response-only part fails fast`() {
        val e = assertFailsWith<IllegalStateException> {
            ChatCodec.decodeChat(
                "chat-1",
                """[{"role":"user","parts":[{"type":"reasoning","content":"think"}]}]""",
            )
        }
        assertTrue(
            e.message!!.contains("Reasoning") && e.message!!.contains("chat-1"),
            "Error should name the offending part and chat, got: ${e.message}",
        )
    }

    @Test
    fun `assistant message with a tool result part fails fast`() {
        // assistant messages may only carry text/reasoning/tool_call parts
        // (the message model rejects anything else); a stored row
        // violating that fails at construction, with the chat named
        val e = assertFailsWith<IllegalStateException> {
            ChatCodec.decodeChat(
                "chat-1",
                """[{"role":"assistant","parts":[{"type":"tool_result","id":"c1","tool":"t","parts":[{"type":"text","text":"ok"}]}]""" + assistantMetaJson + ""","finishReason":"stop"}]""",
            )
        }
        assertTrue(
            e.message!!.contains("ToolResult") && e.message!!.contains("chat-1"),
            "Error should name the offending part and chat, got: ${e.message}",
        )
    }

    @Test
    fun `tool result without a matching call fails fast`() {
        // a stored tool_result must answer a stored tool_call; an orphan
        // cannot be re-sent to strict providers (mismatched tool_call_id)
        val e = assertFailsWith<IllegalStateException> {
            ChatCodec.decodeChat(
                "chat-1",
                """[{"role":"tool_result","parts":[{"type":"tool_result","id":"c1","tool":"t","parts":[{"type":"text","text":"ok"}]}]},""" +
                        """{"role":"assistant","parts":[{"type":"text","text":"done"}]""" + assistantMetaJson + ""","finishReason":"stop"}]""",
            )
        }
        assertTrue(
            e.message!!.contains("no matching tool call") && e.message!!.contains("chat-1"),
            "Error should name the invariant and chat, got: ${e.message}",
        )
    }

    @Test
    fun `tool message with a text part fails fast`() {
        val e = assertFailsWith<IllegalStateException> {
            ChatCodec.decodeChat(
                "chat-1",
                """[{"role":"tool_result","parts":[{"type":"text","text":"hi"}]}]""",
            )
        }
        assertTrue(
            e.message!!.contains("Text") && e.message!!.contains("chat-1"),
            "Error should name the offending part and chat, got: ${e.message}",
        )
    }

    @Test
    fun `blank tool call id fails fast`() {
        val e = assertFailsWith<IllegalStateException> {
            ChatCodec.decodeChat(
                "chat-1",
                """[{"role":"assistant","parts":[{"type":"tool_call","id":"","tool":"flag","args":{}}]""" + assistantMetaJson + ""","finishReason":"tool_calls"}]""",
            )
        }
        assertTrue(
            e.message!!.contains("tool call") && e.message!!.contains("chat-1"),
            "Error should name the part and chat, got: ${e.message}",
        )
    }

    @Test
    fun `tool call without an id key fails fast`() {
        // id is non-nullable in the format: a row missing it cannot be
        // re-sent to strict providers, so it fails on load instead
        assertFailsWith<IllegalStateException> {
            ChatCodec.decodeChat(
                "chat-1",
                """[{"role":"assistant","parts":[{"type":"tool_call","tool":"flag","args":{}}]""" + assistantMetaJson + ""","finishReason":"tool_calls"}]""",
            )
        }
    }

    @Test
    fun `blank tool result id fails fast`() {
        val e = assertFailsWith<IllegalStateException> {
            ChatCodec.decodeChat(
                "chat-1",
                """[{"role":"tool_result","parts":[{"type":"tool_result","id":" ","tool":"flag","parts":[{"type":"text","text":"ok"}]}]}]""",
            )
        }
        assertTrue(
            e.message!!.contains("tool result") && e.message!!.contains("chat-1"),
            "Error should name the part and chat, got: ${e.message}",
        )
    }

    // ------------------------------------------------------------------
    // validateChat invariants: a stored chat must be re-sendable
    // ------------------------------------------------------------------

    @Test
    fun `chat not ending with an assistant message fails fast`() {
        // the turn loop only stores complete chats; a row ending in the
        // middle of a round is either corrupt or was written by a broken path
        val e = assertFailsWith<IllegalStateException> {
            ChatCodec.decodeChat(
                "chat-1",
                """[{"role":"user","createdAt":"2026-08-18T12:34:56Z","parts":[{"type":"text","text":"hi"}]}]""",
            )
        }
        assertTrue(
            e.message!!.contains("Last message is not assistant") && e.message!!.contains("chat-1"),
            "Error should name the invariant and chat, got: ${e.message}",
        )
    }

    @Test
    fun `user message without createdAt fails fast`() {
        // createdAt is required on stored user messages: the per-request
        // <meta> time anchors are regenerated from it, so a row missing it
        // can never be time-anchored (old pre-feature rows fail here)
        val e = assertFailsWith<IllegalStateException> {
            ChatCodec.decodeChat(
                "chat-1",
                """[{"role":"user","parts":[{"type":"text","text":"hi"}]},""" +
                        """{"role":"assistant","parts":[{"type":"text","text":"ok"}]""" + assistantMetaJson + ""","finishReason":"stop"}]""",
            )
        }
        assertTrue(
            e.message!!.contains("createdAt") && e.message!!.contains("chat-1"),
            "Error should name the field and chat, got: ${e.message}",
        )
    }

    @Test
    fun `createdAt round-trips with fractional seconds`() {
        // InstantIsoSerializer: the value is stored as Instant.toString()
        // (fractional seconds included) and parsed back losslessly
        val fractional = Instant.parse("2026-08-18T12:34:56.123456789Z")
        val encoded = ChatCodec.encodeChat(
            listOf(
                ChatMessage(
                    role = ChatMessageRole.User,
                    createdAt = fractional,
                    parts = listOf(ChatMessagePart.Text("hi")),
                ),
                ChatMessage(
                    role = ChatMessageRole.Assistant,
                    parts = listOf(ChatMessagePart.Text("ok")),
                    meta = ChatMessageMeta(inputTokens = 1, outputTokens = 1, totalTokens = 2),
                    finishReason = "stop",
                ),
            )
        )
        assertTrue(encoded.contains("""createdAt":"2026-08-18T12:34:56.123456789Z"""))
        assertEquals(
            fractional,
            ChatCodec.decodeChat("chat-1", encoded).first().createdAt,
        )
    }

    @Test
    fun `non-user message with createdAt fails fast`() {
        // createdAt is user-only (the same pattern as meta/finishReason):
        // assistant timing is implied by the surrounding user messages
        val e = assertFailsWith<IllegalStateException> {
            ChatCodec.decodeChat(
                "chat-1",
                """[{"role":"user","createdAt":"2026-08-18T12:34:56Z","parts":[{"type":"text","text":"hi"}]},""" +
                        """{"role":"assistant","createdAt":"2026-08-18T12:34:56Z","parts":[{"type":"text","text":"ok"}]""" +
                        assistantMetaJson + ""","finishReason":"stop"}]""",
            )
        }
        assertTrue(
            e.message!!.contains("createdAt") && e.message!!.contains("chat-1"),
            "Error should name the field and chat, got: ${e.message}",
        )
    }

    @Test
    fun `chat ending with a non-stop assistant message fails fast`() {
        // a complete chat must end with a clean `stop` (the loop only stores
        // runs that ended that way); a length-final row is a truncated answer
        val e = assertFailsWith<IllegalStateException> {
            ChatCodec.decodeChat(
                "chat-1",
                """[{"role":"assistant","parts":[{"type":"text","text":"partial"}]""" + assistantMetaJson + ""","finishReason":"length"}]""",
            )
        }
        assertTrue(
            e.message!!.contains("not naturally finished") && e.message!!.contains("chat-1"),
            "Error should name the invariant and chat, got: ${e.message}",
        )
    }

    @Test
    fun `duplicate tool call ids fail fast`() {
        // every tool_call must be answerable by exactly one tool_result;
        // duplicate call ids would make the pairing ambiguous
        val e = assertFailsWith<IllegalStateException> {
            ChatCodec.decodeChat(
                "chat-1",
                """[{"role":"assistant","parts":[{"type":"tool_call","id":"c1","tool":"a","args":{}},""" +
                        """{"type":"tool_call","id":"c1","tool":"b","args":{}}]""" + assistantMetaJson + ""","finishReason":"tool_calls"},""" +
                        """{"role":"tool_result","parts":[{"type":"tool_result","id":"c1","tool":"a","parts":[{"type":"text","text":"1"}]}]},""" +
                        """{"role":"assistant","parts":[{"type":"text","text":"done"}]""" + assistantMetaJson + ""","finishReason":"stop"}]""",
            )
        }
        assertTrue(
            e.message!!.contains("duplicate ids") && e.message!!.contains("chat-1"),
            "Error should name the invariant and chat, got: ${e.message}",
        )
    }

    @Test
    fun `the hand-pi golden fixture decodes with the daapu codec`() {
        // one schema across DB, brain, and hand: the same fixture that
        // hand-pi's convert.test.ts parses must decode here (and satisfy
        // the validateChat invariants — the run's test working directory is
        // the repo root)
        val fixture = File("hand-pi/test/fixtures/chat-golden.json")
        assertTrue(fixture.isFile, "golden fixture not found at ${fixture.absolutePath}")
        val chat = ChatCodec.decodeChat("golden", fixture.readText())
        assertNotNull(chat)
        assertEquals("stop", chat.last().finishReason)
        assertEquals(4, chat.size)
        // the round trip preserves the fixture's structure
        assertEquals(
            kotlinx.serialization.json.Json.parseToJsonElement(fixture.readText()),
            kotlinx.serialization.json.Json.parseToJsonElement(ChatCodec.encodeChat(chat)),
            "the golden fixture must round-trip structurally",
        )
    }

    @Test
    fun `tool call without a matching result fails fast`() {
        val e = assertFailsWith<IllegalStateException> {
            ChatCodec.decodeChat(
                "chat-1",
                """[{"role":"assistant","parts":[{"type":"tool_call","id":"c1","tool":"a","args":{}}]""" + assistantMetaJson + """,""" +
                        """"finishReason":"tool_calls"},{"role":"assistant","parts":[{"type":"text","text":"done"}]""" + assistantMetaJson +
                        ""","finishReason":"stop"}]""",
            )
        }
        assertTrue(
            e.message!!.contains("no matching tool result") && e.message!!.contains("chat-1"),
            "Error should name the invariant and chat, got: ${e.message}",
        )
    }
}
