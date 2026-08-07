package info.skyblond.daapu.koog

import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * DB-free tests for the JSON codec behind [PostgresChatHistoryProvider].
 *
 * The round-trip test proves encode/decode are symmetric; the golden tests
 * pin the exact JSON shape produced by the current koog version, so a koog
 * upgrade that changes the `Message` serialization fails here — before it
 * can brick every stored chat at load time.
 */
class PostgresChatHistoryProviderTest {

    private val json = PostgresChatHistoryProvider.json

    private val representativeHistory: List<Message> = listOf(
        Message.System("You are Raven.", RequestMetaInfo.Empty),
        Message.User(
            listOf(
                MessagePart.Text("<injection><real-time-info/></injection>"),
                MessagePart.Text("Hello!"),
            ),
            RequestMetaInfo.Empty,
        ),
        Message.Assistant(
            parts = listOf(
                MessagePart.Reasoning(content = listOf("thinking...")),
                MessagePart.Text("Hi there"),
                MessagePart.Tool.Call(id = "call_1", tool = "flag", args = """{"flag":true}"""),
            ),
            metaInfo = ResponseMetaInfo.Empty,
            finishReason = "tool_calls",
        ),
        Message.User(
            listOf(MessagePart.Tool.Result(id = "call_1", tool = "flag", output = "ok")),
            RequestMetaInfo.Empty,
        ),
        Message.User(
            listOf(
                // base64 image attachment: pins the discriminator/field names
                // the frontend's types.ts relies on for history image rendering
                MessagePart.Attachment(
                    source = AttachmentSource.Image(
                        content = AttachmentContent.Binary.Base64("AAAA"),
                        format = "png",
                        mimeType = "image/png",
                    )
                ),
            ),
            RequestMetaInfo.Empty,
        ),
    )

    @Test
    fun `history round-trips through the provider codec`() {
        val encoded = json.encodeToString(representativeHistory)
        assertEquals(representativeHistory, json.decodeFromString<List<Message>>(encoded))
    }

    /**
     * Exact JSON produced by koog 1.1.1 for [representativeHistory]. If a
     * koog upgrade changes the `Message` serialization, decoding stored
     * histories would break at load time; this test fails first.
     */
    private val goldenJson =
        """[{"type":"ai.koog.prompt.message.Message.System","parts":[{"text":"You are Raven."}],"metaInfo":{"timestamp":"-100001-12-31T23:59:59.999999999Z"}},{"type":"ai.koog.prompt.message.Message.User","parts":[{"type":"ai.koog.prompt.message.MessagePart.Text","text":"<injection><real-time-info/></injection>"},{"type":"ai.koog.prompt.message.MessagePart.Text","text":"Hello!"}],"metaInfo":{"timestamp":"-100001-12-31T23:59:59.999999999Z"}},{"type":"ai.koog.prompt.message.Message.Assistant","parts":[{"type":"ai.koog.prompt.message.MessagePart.Reasoning","content":["thinking..."]},{"type":"ai.koog.prompt.message.MessagePart.Text","text":"Hi there"},{"type":"ai.koog.prompt.message.MessagePart.Tool.Call","id":"call_1","tool":"flag","args":"{\"flag\":true}"}],"metaInfo":{"timestamp":"-100001-12-31T23:59:59.999999999Z"},"finishReason":"tool_calls"},{"type":"ai.koog.prompt.message.Message.User","parts":[{"type":"ai.koog.prompt.message.MessagePart.Tool.Result","id":"call_1","tool":"flag","parts":[{"type":"ai.koog.prompt.message.MessagePart.Text","text":"ok"}]}],"metaInfo":{"timestamp":"-100001-12-31T23:59:59.999999999Z"}},{"type":"ai.koog.prompt.message.Message.User","parts":[{"type":"ai.koog.prompt.message.MessagePart.Attachment","source":{"type":"ai.koog.prompt.message.AttachmentSource.Image","content":{"type":"ai.koog.prompt.message.AttachmentContent.Binary.Base64","base64":"AAAA"},"format":"png"}}],"metaInfo":{"timestamp":"-100001-12-31T23:59:59.999999999Z"}}]"""

    @Test
    fun `golden json from koog 1_1_1 still decodes`() {
        assertEquals(representativeHistory, json.decodeFromString<List<Message>>(goldenJson))
    }

    @Test
    fun `encoding still matches the golden json byte for byte`() {
        assertEquals(goldenJson, json.encodeToString(representativeHistory))
    }

    @Test
    fun `empty json array decodes to an empty history`() {
        // the exact payload every brand-new chat row starts with
        // (history_json DEFAULT '[]')
        assertEquals(emptyList(), PostgresChatHistoryProvider.decodeHistory("chat-new", "[]"))
    }

    @Test
    fun `corrupt history json fails fast instead of resetting the chat`() {
        // a corrupt row (or a koog upgrade that changed the Message format)
        // must throw rather than silently load an empty history
        val e = assertFailsWith<IllegalStateException> {
            PostgresChatHistoryProvider.decodeHistory("chat-1", """[{"type":"no.such.Type"}]""")
        }
        assertTrue(
            e.message!!.contains("chat-1"),
            "Error should name the affected chat, got: ${e.message}",
        )
    }
}
