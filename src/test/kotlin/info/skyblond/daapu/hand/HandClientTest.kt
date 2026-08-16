package info.skyblond.daapu.hand

import info.skyblond.daapu.agent.chat.ChatMessage
import info.skyblond.daapu.agent.chat.ChatMessageMeta
import info.skyblond.daapu.agent.chat.ChatMessagePart
import info.skyblond.daapu.agent.chat.ChatMessageRole
import info.skyblond.daapu.testutil.MockSseResponse
import info.skyblond.daapu.testutil.MockSseServer
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Pins the HTTP hand client against a scripted raw hand server: request
 * shape (model spec, messages, tools), SSE event parsing, the terminal
 * event contract, and the connection-failure paths (a dropped stream is
 * terminal; a non-200 response surfaces the hand's error taxonomy).
 */
class HandClientTest {

    private fun modelSpec(port: Int) = HandModelSpec(
        baseUrl = "http://127.0.0.1:$port/v1",
        apiKey = "test-key",
        modelId = "cerebras/gpt-oss-120b",
        contextWindow = 131000,
        maxOutputTokens = 40000,
        reasoning = true,
        input = listOf("text"),
    )

    private fun runRequest(port: Int) = HandRunRequest(
        model = modelSpec(port),
        messages = listOf(ChatMessage(ChatMessageRole.User, listOf(ChatMessagePart.Text("hi")))),
        // the pure transport never fills the runId — HandService generates
        // it per /v1/run call (the wire contract still requires it)
        runId = "r1",
        maxTokens = 40000,
        maxRounds = 64,
        maxRetries = 0,
        streamIdleTimeoutMs = 300_000,
    )

    private fun toolSpec() = HandToolSpec(
        name = "get_weather",
        description = "weather",
        schema = buildJsonObject { put("type", "object") },
        timeoutSeconds = 30,
    )

    @Test
    fun `run parses the hand events and the request carries the model spec and tools`() {
        val server = MockSseServer { attempt ->
            if (attempt == 1) {
                MockSseResponse(
                    200,
                    listOf(
                        handSse("""{"text":"Hel"}""", event = "text_delta"),
                        handSse("""{"text":"think"}""", event = "reasoning_delta"),
                        handSse(
                            """{"message":{"role":"assistant","parts":[{"type":"text","text":"Hello"}],""" +
                                    """"meta":{"inputTokens":10,"outputTokens":2,"totalTokens":12,"modelId":"m"},"finishReason":"stop"}}""",
                            event = "assistant_message",
                        ),
                        handSse(
                            """{"attempt":2,"delayMs":200,"message":"hiccup"}""",
                            event = "retry"
                        ),
                        handSse("""{"finishReason":"stop"}""", event = "done"),
                    )
                )
            } else {
                MockSseResponse(200, emptyList())
            }
        }
        try {
            val client = HttpHandClient("http://127.0.0.1:${server.port}", "test-token")
            val events = runBlocking {
                client.run(
                    runRequest(server.port).copy(
                        tools = listOf(toolSpec()),
                        toolCallbackUrl = "http://cb"
                    )
                )
                    .toList()
            }
            assertEquals(
                listOf(
                    HandEvent.TextDelta("Hel"),
                    HandEvent.ReasoningDelta("think"),
                    HandEvent.AssistantMessage(
                        ChatMessage(
                            role = ChatMessageRole.Assistant,
                            parts = listOf(ChatMessagePart.Text("Hello")),
                            meta = ChatMessageMeta(
                                inputTokens = 10,
                                outputTokens = 2,
                                totalTokens = 12,
                                modelId = "m"
                            ),
                            finishReason = "stop",
                        )
                    ),
                    HandEvent.Retry(attempt = 2, delayMs = 200, message = "hiccup"),
                    HandEvent.Done("stop"),
                ),
                events,
            )

            // the request carried the model spec and the tools
            val request = server.lastRequest()!!
            assertTrue(
                request.contains(""""modelId":"cerebras/gpt-oss-120b""""),
                "request: $request"
            )
            assertTrue(request.contains(""""maxOutputTokens":40000"""), "request: $request")
            assertTrue(request.contains(""""reasoning":true"""), "request: $request")
            assertTrue(request.contains(""""input":["text"]"""), "request: $request")
            assertTrue(request.contains(""""name":"get_weather""""), "request: $request")
            assertTrue(request.contains(""""toolCallbackUrl":"http://cb""""), "request: $request")
            assertTrue(
                request.contains("x-daapu-token: test-token", ignoreCase = true),
                "request: $request"
            )
            client.close()
        } finally {
            server.close()
        }
    }

    @Test
    fun `a dropped stream without a terminal event is a terminal upstream failure`() {
        val server = MockSseServer {
            MockSseResponse(
                200,
                listOf(handSse("""{"text":"partial"}""", event = "text_delta"))
            )
        }
        try {
            val client = HttpHandClient("http://127.0.0.1:${server.port}", "test-token")
            val e = runBlocking {
                runCatching { client.run(runRequest(server.port)).toList() }.exceptionOrNull()
            }
            assertIs<HandUpstreamException>(e)
            client.close()
        } finally {
            server.close()
        }
    }

    @Test
    fun `a non-200 response surfaces the hand error taxonomy`() {
        val server = MockSseServer {
            MockSseResponse(
                400,
                listOf("""{"ok":false,"error":{"type":"invalid_request","message":"attachment kind 'video' is not supported"}}""")
            )
        }
        try {
            val client = HttpHandClient("http://127.0.0.1:${server.port}", "test-token")
            val e = runBlocking {
                runCatching { client.run(runRequest(server.port)).toList() }.exceptionOrNull()
            }
            val typed = assertIs<HandRunException>(e)
            assertEquals("invalid_request", typed.type)
            client.close()
        } finally {
            server.close()
        }
    }

    @Test
    fun `a non-200 response without the hand error shape is an upstream failure`() {
        val server = MockSseServer { MockSseResponse(500, emptyList()) }
        try {
            val client = HttpHandClient("http://127.0.0.1:${server.port}", "test-token")
            val e = runBlocking {
                runCatching { client.run(runRequest(server.port)).toList() }.exceptionOrNull()
            }
            assertIs<HandUpstreamException>(e)
            client.close()
        } finally {
            server.close()
        }
    }
}

private fun handSse(data: String, event: String? = null): String {
    val eventLine = event?.let { "event: $it\ndata: $data" } ?: "data: $data"
    return eventLine
}
