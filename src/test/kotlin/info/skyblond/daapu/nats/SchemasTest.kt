package info.skyblond.daapu.nats

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Round-trip + wire-format tests for the NATS schemas shared with the Python
 * `xmpp-bridge`. The bridge serializes with msgspec `rename="camel"`, so JSON
 * keys must be camelCase here (including `from` and `stanzaId`). These tests
 * guard the cross-language contract against silent drift.
 */
class SchemasTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `IncomingMessage round-trips and uses camelCase wire keys`() {
        val original = IncomingMessage(
            account = "bot@example.com",
            from = "peer@example.com",
            body = "hello",
            encrypted = true,
            type = MessageType.DM,
            timestamp = 1_700_000_000L,
            stanzaId = "id-1",
        )
        val encoded = json.encodeToString(IncomingMessage.serializer(), original)

        // Wire keys must match the Python msgspec structs (rename="camel").
        val obj: JsonObject = json.parseToJsonElement(encoded).jsonObject
        assertEquals("bot@example.com", obj["account"]!!.jsonPrimitive.content)
        assertEquals("peer@example.com", obj["from"]!!.jsonPrimitive.content)
        assertEquals("hello", obj["body"]!!.jsonPrimitive.content)
        assertEquals(true, obj["encrypted"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("DM", obj["type"]!!.jsonPrimitive.content)
        assertEquals(1_700_000_000L, obj["timestamp"]!!.jsonPrimitive.content.toLong())
        assertEquals("id-1", obj["stanzaId"]!!.jsonPrimitive.content)

        val decoded = json.decodeFromString(IncomingMessage.serializer(), encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `IncomingMessage stanzaId is optional`() {
        val original = IncomingMessage(
            account = "a@b",
            from = "c@d",
            body = "x",
            encrypted = false,
            type = MessageType.MUC,
            timestamp = 0L,
        )
        val encoded = json.encodeToString(IncomingMessage.serializer(), original)
        val obj = json.parseToJsonElement(encoded).jsonObject
        assertNull(obj["stanzaId"]?.jsonPrimitive?.contentOrNull)
        val decoded = json.decodeFromString(IncomingMessage.serializer(), encoded)
        assertNull(decoded.stanzaId)
        assertEquals(original, decoded)
    }

    @Test
    fun `SendTextMessageRequest encodes forceEncrypted by default`() {
        val req = SendTextMessageRequest(to = "to@b", text = "hi")
        val encoded = json.encodeToString(SendTextMessageRequest.serializer(), req)
        val obj = json.parseToJsonElement(encoded).jsonObject
        assertEquals("to@b", obj["to"]!!.jsonPrimitive.content)
        assertEquals("hi", obj["text"]!!.jsonPrimitive.content)
        assertEquals(false, obj["forceEncrypted"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `CommandReply round-trips with and without error`() {
        val ok = CommandReply(ok = true)
        val err = CommandReply(ok = false, error = "boom")

        val okEnc = json.encodeToString(CommandReply.serializer(), ok)
        val errEnc = json.encodeToString(CommandReply.serializer(), err)

        assertEquals(ok, json.decodeFromString<CommandReply>(okEnc))
        assertEquals(err, json.decodeFromString<CommandReply>(errEnc))

        val errObj = json.parseToJsonElement(errEnc).jsonObject
        assertEquals("boom", errObj["error"]!!.jsonPrimitive.content)
        // encodeDefaults -> ok field always present
        assertTrue(okEnc.contains("\"ok\""))
    }
}
