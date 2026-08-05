package info.skyblond.daapu.koog

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Verifies that tool calls arriving without an id (gateways that stream
 * index-only `tool_calls` chunks) or with a blank id (`"id": ""` on some
 * non-streaming responses) get a stable generated id, so koog's request
 * serializer emits matching `tool_call_id`s for the call and its result
 * instead of two independent random UUIDs.
 */
class WithGeneratedToolCallIdsTest {

    private fun assistant(parts: List<MessagePart.ResponsePart>) =
        Message.Assistant(parts = parts, metaInfo = ResponseMetaInfo.Empty)

    @Test
    fun `tool call without id gets a generated call_ id`() {
        val message = assistant(
            listOf(MessagePart.Tool.Call(id = null, tool = "flag", args = "{}"))
        )
        val normalized = message.withGeneratedToolCallIds()

        val call = normalized.parts.filterIsInstance<MessagePart.Tool.Call>().single()
        assertTrue(call.id?.startsWith("call_") == true, "Expected a generated id, got ${call.id}")
        assertEquals("flag", call.tool)
    }

    @Test
    fun `blank tool call id is treated as missing`() {
        // some gateways send "id": "" on non-streaming responses; a blank id
        // is as useless as null for matching tool_call_id, so it is replaced
        val message = assistant(
            listOf(MessagePart.Tool.Call(id = "", tool = "flag", args = "{}"))
        )
        val normalized = message.withGeneratedToolCallIds()

        val call = normalized.parts.filterIsInstance<MessagePart.Tool.Call>().single()
        assertTrue(call.id?.startsWith("call_") == true, "Expected a generated id, got ${call.id}")
        assertEquals("flag", call.tool)
    }

    @Test
    fun `existing ids are kept`() {
        val message = assistant(
            listOf(
                MessagePart.Tool.Call(id = "call_given", tool = "a", args = "{}"),
                MessagePart.Tool.Call(id = null, tool = "b", args = "{}"),
            )
        )
        val normalized = message.withGeneratedToolCallIds()

        val calls = normalized.parts.filterIsInstance<MessagePart.Tool.Call>()
        assertEquals("call_given", calls[0].id)
        assertTrue(calls[1].id?.startsWith("call_") == true, "Expected a generated id, got ${calls[1].id}")
    }

    @Test
    fun `message without missing ids is returned as-is`() {
        val message = assistant(
            listOf(
                MessagePart.Text("Hello"),
                MessagePart.Tool.Call(id = "call_1", tool = "flag", args = "{}"),
            )
        )
        assertSame(message, message.withGeneratedToolCallIds())
    }
}
