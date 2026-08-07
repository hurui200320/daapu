package info.skyblond.daapu.server

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import info.skyblond.daapu.agent.StreamExecutionCallback
import info.skyblond.daapu.koog.PostgresChatHistoryProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the frame → SSE event mapping of [streamEventCallback] — the contract
 * the frontend (`frontend/src/lib/api.ts`) parses for the live streaming view.
 */
class StreamEventMappingTest {

    private suspend fun eventsFor(block: suspend (StreamExecutionCallback) -> Unit): List<Pair<String, String>> {
        val emitted = mutableListOf<Pair<String, String>>()
        val callback = streamEventCallback { event, data -> emitted += event to data }
        block(callback)
        return emitted
    }

    private fun payload(json: String): JsonObject =
        PostgresChatHistoryProvider.json.parseToJsonElement(json).jsonObject

    /** Reads a JSON string field verbatim (unlike `toString()`, no re-escaping). */
    private fun str(body: JsonObject, key: String): String =
        (body.getValue(key) as kotlinx.serialization.json.JsonPrimitive).content

    @Test
    fun `reasoning delta maps to reasoning event with the delta`() {
        val events = runBlocking {
            eventsFor { cb -> cb.onFrame(StreamFrame.ReasoningDelta(text = "think")) }
        }
        assertEquals(listOf("reasoning"), events.map { it.first })
        assertEquals("think", str(payload(events[0].second), "delta"))
    }

    @Test
    fun `reasoning delta without text maps to an empty delta`() {
        val events = runBlocking {
            eventsFor { cb -> cb.onFrame(StreamFrame.ReasoningDelta(text = null)) }
        }
        assertEquals("", str(payload(events[0].second), "delta"))
    }

    @Test
    fun `text delta maps to text event with the delta`() {
        val events = runBlocking {
            eventsFor { cb -> cb.onFrame(StreamFrame.TextDelta(text = "hello")) }
        }
        assertEquals(listOf("text"), events.map { it.first })
        assertEquals("hello", str(payload(events[0].second), "delta"))
    }

    @Test
    fun `completed tool call maps to tool_call event with name and args`() {
        val events = runBlocking {
            eventsFor { cb ->
                cb.onFrame(StreamFrame.ToolCallComplete(id = "call_1", name = "flag", content = """{"flag":true}"""))
            }
        }
        assertEquals(listOf("tool_call"), events.map { it.first })
        val body = payload(events[0].second)
        assertEquals("flag", str(body, "name"))
        assertEquals("""{"flag":true}""", str(body, "args"))
    }

    @Test
    fun `non-visible frames emit nothing`() {
        val events = runBlocking {
            eventsFor { cb ->
                cb.onFrame(StreamFrame.End(finishReason = "stop"))
                cb.onFrame(StreamFrame.ToolCallDelta(id = "call_1", name = "flag", content = "{"))
            }
        }
        assertTrue(events.isEmpty())
    }

    @Test
    fun `tool results map to tool_result events`() {
        val events = runBlocking {
            eventsFor { cb ->
                cb.onToolResults(
                    listOf(
                        MessagePart.Tool.Result(id = "call_1", tool = "flag", output = "true", isError = false),
                        MessagePart.Tool.Result(id = "call_2", tool = "search", output = "boom", isError = true),
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
            eventsFor { cb -> cb.onStreamError(RuntimeException("upstream hiccup")) }
        }
        assertEquals(listOf("retry"), events.map { it.first })
        assertEquals("upstream hiccup", str(payload(events[0].second), "message"))
    }

    @Test
    fun `assistant message emits nothing`() {
        // the frontend syncs via the `done` history reload instead
        val events = runBlocking {
            eventsFor { cb -> cb.onAssistantMessage(Message.Assistant(parts = listOf(MessagePart.Text("hi")), metaInfo = ResponseMetaInfo.Empty)) }
        }
        assertTrue(events.isEmpty())
    }
}
