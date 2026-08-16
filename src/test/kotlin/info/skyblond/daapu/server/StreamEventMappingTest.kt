package info.skyblond.daapu.server

import info.skyblond.daapu.agent.chat.ChatMessagePart
import info.skyblond.daapu.agent.persist.StreamingExecutionCallback
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the stream event → SSE event mapping of [streamEventCallback] — the
 * contract the frontend (`frontend/src/lib/api.ts`) parses for the live
 * streaming view. The event names and payloads are byte-compatible with the
 * pre-migration (koog) implementation.
 */
class StreamEventMappingTest {

    private suspend fun eventsFor(block: suspend (StreamingExecutionCallback) -> Unit): List<Pair<String, String>> {
        val emitted = mutableListOf<Pair<String, String>>()
        val callback = streamEventCallback { event, data -> emitted += event to data }
        block(callback)
        return emitted
    }

    private fun payload(json: String): JsonObject =
        Json.parseToJsonElement(json).jsonObject

    /** Reads a JSON string field verbatim (unlike `toString()`, no re-escaping). */
    private fun str(body: JsonObject, key: String): String =
        (body.getValue(key) as JsonPrimitive).content

    @Test
    fun `reasoning delta maps to reasoning event with the delta`() {
        val events = runBlocking {
            eventsFor { cb -> cb.onReasoningDelta("think") }
        }
        assertEquals(listOf("reasoning"), events.map { it.first })
        assertEquals("think", str(payload(events[0].second), "delta"))
    }

    @Test
    fun `reasoning delta without text maps to an empty delta`() {
        val events = runBlocking {
            eventsFor { cb -> cb.onReasoningDelta("") }
        }
        assertEquals("", str(payload(events[0].second), "delta"))
    }

    @Test
    fun `text delta maps to text event with the delta`() {
        val events = runBlocking {
            eventsFor { cb -> cb.onTextDelta("hello") }
        }
        assertEquals(listOf("text"), events.map { it.first })
        assertEquals("hello", str(payload(events[0].second), "delta"))
    }

    @Test
    fun `completed tool call maps to tool_call event with name and args`() {
        val events = runBlocking {
            eventsFor { cb -> cb.onToolCall("flag", buildJsonObject { put("flag", true) }) }
        }
        assertEquals(listOf("tool_call"), events.map { it.first })
        val body = payload(events[0].second)
        assertEquals("flag", str(body, "name"))
        assertEquals("""{"flag":true}""", body.getValue("args").toString())
    }

    @Test
    fun `tool results map to tool_result events`() {
        val events = runBlocking {
            eventsFor { cb ->
                cb.onToolResults(
                    listOf(
                        ChatMessagePart.ToolResult(
                            id = "call_1",
                            tool = "flag",
                            parts = listOf(ChatMessagePart.Text("true")),
                        ),
                        ChatMessagePart.ToolResult(
                            id = "call_2",
                            tool = "search",
                            parts = listOf(ChatMessagePart.Text("boom")),
                            isError = true,
                        ),
                    )
                )
            }
        }
        assertEquals(listOf("tool_result", "tool_result"), events.map { it.first })
        val first = payload(events[0].second)
        assertEquals("call_1", str(first, "id"))
        assertEquals("flag", str(first, "name"))
        assertEquals("true", str(first, "content"))
        assertEquals("false", str(first, "isError"))
        val second = payload(events[1].second)
        assertEquals("search", str(second, "name"))
        assertEquals("true", str(second, "isError"))
    }

    @Test
    fun `stream error maps to retry event with the message`() {
        val events = runBlocking {
            eventsFor { cb -> cb.onStreamError("upstream hiccup") }
        }
        assertEquals(listOf("retry"), events.map { it.first })
        assertEquals("upstream hiccup", str(payload(events[0].second), "message"))
    }
}
