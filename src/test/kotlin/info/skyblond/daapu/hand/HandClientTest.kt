package info.skyblond.daapu.hand

import info.skyblond.daapu.agent.chat.ChatMessage
import info.skyblond.daapu.agent.chat.ChatMessageMeta
import info.skyblond.daapu.agent.chat.ChatMessagePart
import info.skyblond.daapu.agent.chat.ChatMessageRole
import info.skyblond.daapu.testutil.MockSseResponse
import info.skyblond.daapu.testutil.MockSseServer
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Pins the HTTP hand client against a scripted raw hand server: request
 * shape (model spec, messages, tool URLs), SSE event parsing, the terminal
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

    @Test
    fun `run parses the hand events and the request carries the model spec and the tool URLs`() {
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
                        toolListUrl = "http://tl",
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

            // the request carried the model spec and the tool URLs (the tool
            // set itself travels per-round via GET {toolListUrl}, not in the
            // request)
            val request = server.lastRequest()!!
            assertTrue(
                request.contains(""""modelId":"cerebras/gpt-oss-120b""""),
                "request: $request"
            )
            assertTrue(request.contains(""""maxOutputTokens":40000"""), "request: $request")
            assertTrue(request.contains(""""reasoning":true"""), "request: $request")
            assertTrue(request.contains(""""input":["text"]"""), "request: $request")
            assertTrue(request.contains(""""toolListUrl":"http://tl""""), "request: $request")
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

    @Test
    fun `embed posts the request and parses the JSON result`() {
        val server = MockSseServer {
            MockSseResponse(
                200,
                listOf(
                    """{"vectors":[[1.5,-2.0]],"dimensions":2,"usage":{"promptTokens":3,"totalTokens":3}}"""
                )
            )
        }
        try {
            val client = HttpHandClient("http://127.0.0.1:${server.port}", "test-token")
            val result = runBlocking { client.embed(embedRequest(server.port)) }
            assertEquals(listOf(listOf(1.5f, -2.0f)), result.vectors)
            assertEquals(2, result.dimensions)
            assertEquals(HandEmbedUsage(3, 3), result.usage)

            val request = server.lastRequest()!!
            assertTrue(request.contains(""""modelId":"zenmux sub/google/gemini-embedding-2""""), "request: $request")
            assertTrue(request.contains(""""dimensions":1536"""), "request: $request")
            assertTrue(request.contains(""""input":["hello","world"]"""), "request: $request")
            assertTrue(request.contains(""""maxRetries":2"""), "request: $request")
            assertTrue(request.contains(""""timeoutMs":30000"""), "request: $request")
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
    fun `embed survives a slow gateway longer than the CIO engine's default request cap`() {
        // regression: the CIO engine caps every non-SSE request at
        // `requestTimeout` (default 15s); a slow embedding gateway (e.g.
        // deepinfra) exceeded it and the embed died with
        // "Request timeout has expired". The client disables the cap, so
        // a 20s answer must still succeed (the ~20s wall time is the price
        // of pinning the fix).
        val server = MockSseServer {
            MockSseResponse(
                200,
                listOf(
                    """{"vectors":[[1.5,-2.0]],"dimensions":2,"usage":{"promptTokens":3,"totalTokens":3}}"""
                ),
                delayMs = 20_000,
            )
        }
        try {
            val client = HttpHandClient("http://127.0.0.1:${server.port}", "test-token")
            val result = runBlocking { client.embed(embedRequest(server.port)) }
            assertEquals(listOf(listOf(1.5f, -2.0f)), result.vectors)
            assertEquals(2, result.dimensions)
            client.close()
        } finally {
            server.close()
        }
    }

    @Test
    fun `embed surfaces the hand error taxonomy`() {
        val server = MockSseServer {
            MockSseResponse(
                400,
                listOf("""{"ok":false,"error":{"type":"invalid_request","message":"bad input"}}""")
            )
        }
        try {
            val client = HttpHandClient("http://127.0.0.1:${server.port}", "test-token")
            val e = runBlocking {
                runCatching { client.embed(embedRequest(server.port)) }.exceptionOrNull()
            }
            val typed = assertIs<EmbeddingException>(e)
            assertEquals("invalid_request", typed.type)
            client.close()
        } finally {
            server.close()
        }
    }

    @Test
    fun `embed without the error shape is an upstream failure`() {
        val server = MockSseServer { MockSseResponse(500, emptyList()) }
        try {
            val client = HttpHandClient("http://127.0.0.1:${server.port}", "test-token")
            val e = runBlocking {
                runCatching { client.embed(embedRequest(server.port)) }.exceptionOrNull()
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

private fun embedRequest(port: Int) = HandEmbedRequest(
    model = HandEmbedModelSpec(
        baseUrl = "http://127.0.0.1:$port/v1",
        apiKey = "test-key",
        modelId = "zenmux sub/google/gemini-embedding-2",
    ),
    dimensions = 1536,
    input = listOf("hello", "world"),
    maxRetries = 2,
    timeoutMs = 30_000,
)
