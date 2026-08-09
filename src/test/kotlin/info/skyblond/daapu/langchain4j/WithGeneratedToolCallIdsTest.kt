package info.skyblond.daapu.langchain4j

import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.data.message.AiMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Verifies that tool calls arriving without an id (gateways that stream
 * index-only `tool_calls` chunks) or with a blank id (`"id": ""` on some
 * non-streaming responses) get a stable generated id, so the re-sent history
 * carries matching `tool_call_id`s for the call and its result instead of
 * two independent random ids (strict providers reject the mismatch with a 400).
 */
class WithGeneratedToolCallIdsTest {

    private fun request(id: String?, name: String = "flag", args: String = "{}") =
        ToolExecutionRequest.builder()
            .id(id)
            .name(name)
            .arguments(args)
            .build()

    private fun assistant(requests: List<ToolExecutionRequest>, text: String? = null) =
        AiMessage.builder().text(text).toolExecutionRequests(requests).build()

    @Test
    fun `tool call without id gets a generated call_ id`() {
        val message = assistant(listOf(request(id = null)))
        val normalized = message.withGeneratedToolCallIds()

        val call = normalized.toolExecutionRequests().single()
        assertTrue(call.id().startsWith("call_"), "Expected a generated id, got ${call.id()}")
        assertEquals("flag", call.name())
    }

    @Test
    fun `blank tool call id is treated as missing`() {
        // some gateways send "id": "" on non-streaming responses; a blank id
        // is as useless as null for matching tool_call_id, so it is replaced
        val message = assistant(listOf(request(id = "")))
        val normalized = message.withGeneratedToolCallIds()

        val call = normalized.toolExecutionRequests().single()
        assertTrue(call.id().startsWith("call_"), "Expected a generated id, got ${call.id()}")
        assertEquals("flag", call.name())
    }

    @Test
    fun `existing ids are kept`() {
        val message = assistant(
            listOf(
                request(id = "call_given", name = "a"),
                request(id = null, name = "b"),
            )
        )
        val normalized = message.withGeneratedToolCallIds()

        val calls = normalized.toolExecutionRequests()
        assertEquals("call_given", calls[0].id())
        assertTrue(calls[1].id().startsWith("call_"), "Expected a generated id, got ${calls[1].id()}")
    }

    @Test
    fun `message without missing ids is returned as-is`() {
        val message = assistant(
            listOf(request(id = "call_1")),
            text = "Hello",
        )
        assertSame(message, message.withGeneratedToolCallIds())
    }

    @Test
    fun `text and thinking survive normalization`() {
        val message = AiMessage.builder()
            .text("answer")
            .thinking("thinking")
            .toolExecutionRequests(listOf(request(id = null)))
            .build()
        val normalized = message.withGeneratedToolCallIds()
        assertEquals("answer", normalized.text())
        assertEquals("thinking", normalized.thinking())
        assertEquals(1, normalized.toolExecutionRequests().size)
    }
}
