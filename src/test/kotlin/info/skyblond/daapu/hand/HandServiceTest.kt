package info.skyblond.daapu.hand

import info.skyblond.daapu.agent.ModelCatalog
import info.skyblond.daapu.agent.chat.ChatMessage
import info.skyblond.daapu.agent.chat.ChatMessagePart
import info.skyblond.daapu.agent.chat.ChatMessageRole
import info.skyblond.daapu.agent.model.EmbeddingModel
import info.skyblond.daapu.agent.model.ModelProvider
import info.skyblond.daapu.agent.tool.EmptyToolProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.*

/**
 * Pins the HandService run/callback wiring: the runId is generated per
 * `/v1/run` (internal to the run plumbing), the in-flight run is registered
 * before the request goes out and evicted when the stream ends — success,
 * terminal error, or cancellation — a duplicate runId fails fast, and the
 * callback URL is attached on every run (preserving an explicit one).
 */
class HandServiceTest {

    private fun model() = ModelCatalog(
        mapOf(
            "bifrost" to ModelProvider("bifrost", "http://127.0.0.1:9/v1", "test"),
            "deepinfra" to ModelProvider("deepinfra", "http://127.0.0.1:9/v1", "test"),
        )
    ).findModel("bifrost/cerebras/gemma-4-31b")!!

    private fun runRequest(
        runId: String? = null,
        toolListUrl: String? = null,
        toolCallbackUrl: String? = null,
    ) = HandRunRequest(
        model = model().toHandModelSpec(),
        messages = listOf(ChatMessage(ChatMessageRole.User, listOf(ChatMessagePart.Text("hi")))),
        runId = runId,
        toolListUrl = toolListUrl,
        toolCallbackUrl = toolCallbackUrl,
        maxTokens = 100,
        maxRounds = 4,
        maxRetries = 0,
        streamIdleTimeoutMs = 0,
    )

    private fun callback(runId: String) = HandToolCallbackRequest(
        runId = runId,
        id = "call_1",
        name = "flag",
        args = JsonObject(emptyMap()),
    )

    @Test
    fun `registers the in-flight run before the request and evicts it after the run`() {
        runBlocking {
            val callbackService = HandCallbackService("test-token")
            val hand = FakeHand(runScript = { request ->
                assertFalse(request.runId.isNullOrBlank(), "HandService must fill the runId")
                // while the run is in flight, its runId must resolve the provider
                val inFlight = callbackService.executeToolCall(callback(request.runId))
                assertNull(inFlight.fatal, "the in-flight run must resolve its provider")
                listOf(HandEvent.Done("stop"))
            })
            val service = HandService(hand, callbackService, "http://127.0.0.1:9/api/hand/tool", "http://127.0.0.1:9/api/hand/tools")

            service.run(runRequest(), EmptyToolProvider, model()).toList()

            // after the run, the same runId must be unknown again
            val late = callbackService.executeToolCall(callback(hand.requests.single().runId!!))
            assertNotNull(late.fatal, "a finished run must be evicted")
        }
    }

    @Test
    fun `evicts the run after a terminal hand error`() {
        runBlocking {
            val callbackService = HandCallbackService("test-token")
            val hand = FakeHand(runScript = { listOf(HandEvent.RunError("upstream", "boom")) })
            val service = HandService(hand, callbackService, "http://127.0.0.1:9/api/hand/tool", "http://127.0.0.1:9/api/hand/tools")

            service.run(runRequest(), EmptyToolProvider, model()).toList()

            val late = callbackService.executeToolCall(callback(hand.requests.single().runId!!))
            assertNotNull(late.fatal, "a failed run must be evicted")
        }
    }

    @Test
    fun `evicts the run when the stream is cancelled`() {
        runBlocking {
            val callbackService = HandCallbackService("test-token")
            val hand = FakeHand(runScript = { awaitCancellation() })
            val service = HandService(hand, callbackService, "http://127.0.0.1:9/api/hand/tool", "http://127.0.0.1:9/api/hand/tools")

            val job = launch {
                service.run(runRequest(), EmptyToolProvider, model()).toList()
            }
            // wait until the run is registered (the request is only recorded
            // after the registration), then abort the stream
            withTimeout(5_000) {
                while (hand.requests.isEmpty()) delay(10)
            }
            job.cancelAndJoin()

            val late = callbackService.executeToolCall(callback(hand.requests.single().runId!!))
            assertNotNull(late.fatal, "a cancelled run must be evicted")
        }
    }

    @Test
    fun `generates a fresh runId per run and preserves an explicit one`() = runBlocking {
        val callbackService = HandCallbackService("test-token")
        val hand = FakeHand()
        val service = HandService(hand, callbackService, "http://127.0.0.1:9/api/hand/tool", "http://127.0.0.1:9/api/hand/tools")

        service.run(runRequest(), EmptyToolProvider, model()).toList()
        service.run(runRequest(), EmptyToolProvider, model()).toList()
        service.run(runRequest(runId = "explicit-1"), EmptyToolProvider, model()).toList()

        val ids = hand.requests.map { it.runId!! }
        assertTrue(ids[0].isNotBlank(), "a runId must be generated when absent")
        assertTrue(ids[0] != ids[1], "each hand run gets its own runId")
        assertEquals("explicit-1", ids[2], "an explicit runId is preserved")
    }

    @Test
    fun `attaches the callback and tool-list URLs on every run and preserves explicit ones`() = runBlocking {
        val callbackService = HandCallbackService("test-token")
        val hand = FakeHand()
        val service = HandService(
            hand, callbackService,
            "http://127.0.0.1:9/api/hand/tool",
            "http://127.0.0.1:9/api/hand/tools",
        )

        service.run(runRequest(), EmptyToolProvider, model()).toList()
        service.run(runRequest(toolCallbackUrl = "http://custom-callback"), EmptyToolProvider, model()).toList()
        service.run(
            runRequest(
                toolListUrl = "http://custom-tools",
                toolCallbackUrl = "http://custom-callback",
            ),
            EmptyToolProvider,
            model(),
        ).toList()

        assertEquals("http://127.0.0.1:9/api/hand/tool", hand.requests[0].toolCallbackUrl)
        assertEquals(
            "http://127.0.0.1:9/api/hand/tools",
            hand.requests[0].toolListUrl,
            "the tool-list URL is attached on every run (the hand re-queries " +
                    "the tool set before each LLM request)",
        )
        assertEquals("http://custom-callback", hand.requests[1].toolCallbackUrl)
        assertEquals(
            "http://127.0.0.1:9/api/hand/tools",
            hand.requests[1].toolListUrl,
            "the tool-list URL is attached even when the callback URL is explicit",
        )
        assertEquals("http://custom-callback", hand.requests[2].toolCallbackUrl)
        assertEquals(
            "http://custom-tools",
            hand.requests[2].toolListUrl,
            "an explicit tool-list URL is preserved"
        )
    }

    @Test
    fun `a reused explicit runId fails fast instead of overriding the in-flight run`() {
        runBlocking {
            val callbackService = HandCallbackService("test-token")
            val hand = FakeHand(runScript = { awaitCancellation() })
            val service = HandService(hand, callbackService, "http://127.0.0.1:9/api/hand/tool", "http://127.0.0.1:9/api/hand/tools")

            val first = launch {
                runCatching {
                    service.run(runRequest(runId = "dup"), EmptyToolProvider, model()).toList()
                }
            }
            // wait until the first run is in flight, then reuse its runId
            withTimeout(5_000) {
                while (hand.requests.isEmpty()) delay(10)
            }
            val error = assertFailsWith<IllegalStateException> {
                service.run(runRequest(runId = "dup"), EmptyToolProvider, model()).toList()
            }
            assertTrue(
                error.message!!.contains("dup"),
                "the error must name the runId: ${error.message}"
            )
            assertEquals(
                1,
                hand.requests.size,
                "the duplicate must fail before the hand is contacted",
            )
            first.cancelAndJoin()
        }
    }

    @Test
    fun `runCollect returns every message of a tool round in order`() = runBlocking {
        val hand = FakeHand(
            runScript = {
                val call = ChatMessagePart.ToolCall(
                    id = "call_1",
                    tool = "flag",
                    args = JsonObject(emptyMap()),
                )
                listOf(
                    HandEvent.AssistantMessage(
                        assistantMessage(parts = listOf(call), finishReason = "tool_calls")
                    ),
                    HandEvent.ToolCall("call_1", "flag", call.args),
                    HandEvent.ToolResult(
                        "call_1",
                        "flag",
                        listOf(ChatMessagePart.Text("done")),
                        false
                    ),
                    HandEvent.AssistantMessage(assistantMessage("finished")),
                    HandEvent.Done("stop"),
                )
            }
        )
        val service =
            HandService(hand, HandCallbackService("test-token"), "http://127.0.0.1:9/api/hand/tool", "http://127.0.0.1:9/api/hand/tools")

        val messages =
            service.runCollect(runRequest(), EmptyToolProvider, model())

        assertEquals(3, messages.size, "assistant + tool result + final assistant")
        assertTrue(messages[0].parts.single() is ChatMessagePart.ToolCall)
        val toolResult = assertIs<ChatMessagePart.ToolResult>(messages[1].parts.single())
        assertEquals("call_1", toolResult.id)
        assertEquals("flag", toolResult.tool)
        assertFalse(toolResult.isError)
        assertEquals("finished", (messages[2].parts.single() as ChatMessagePart.Text).text)
    }

    @Test
    fun `runCollect throws the hand error taxonomy on a terminal error`() = runBlocking {
        val hand = FakeHand(runScript = { errorRunFlow("round_limit", "maxRounds reached") })
        val service =
            HandService(hand, HandCallbackService("test-token"), "http://127.0.0.1:9/api/hand/tool", "http://127.0.0.1:9/api/hand/tools")

        val e = assertFailsWith<HandRunException> {
            service.runCollect(runRequest(), EmptyToolProvider, model())
        }
        assertEquals("round_limit", e.type)
    }

    @Test
    fun `runCollect fails a stream without a terminal event`() = runBlocking {
        val hand =
            FakeHand(runScript = { listOf(HandEvent.AssistantMessage(assistantMessage("partial"))) })
        val service =
            HandService(hand, HandCallbackService("test-token"), "http://127.0.0.1:9/api/hand/tool", "http://127.0.0.1:9/api/hand/tools")

        val e = assertFailsWith<IllegalStateException> {
            service.runCollect(runRequest(), EmptyToolProvider, model())
        }
        assertTrue(e.message!!.contains("terminal event"), "message: ${e.message}")
    }

    @Test
    fun `runCollectPartial returns every message and no exception on success`() = runBlocking {
        val hand = FakeHand(
            runScript = {
                val call = ChatMessagePart.ToolCall(
                    id = "call_1",
                    tool = "flag",
                    args = JsonObject(emptyMap()),
                )
                listOf(
                    HandEvent.AssistantMessage(
                        assistantMessage(parts = listOf(call), finishReason = "tool_calls")
                    ),
                    HandEvent.ToolCall("call_1", "flag", call.args),
                    HandEvent.ToolResult(
                        "call_1",
                        "flag",
                        listOf(ChatMessagePart.Text("done")),
                        false
                    ),
                    HandEvent.AssistantMessage(assistantMessage("finished")),
                    HandEvent.Done("stop"),
                )
            }
        )
        val service =
            HandService(hand, HandCallbackService("test-token"), "http://127.0.0.1:9/api/hand/tool", "http://127.0.0.1:9/api/hand/tools")

        val result = service.runCollectPartial(runRequest(), EmptyToolProvider, model())

        assertNull(result.exception, "a successful run carries no exception")
        assertEquals(3, result.result.size, "assistant + tool result + final assistant")
        assertTrue(result.result[0].parts.single() is ChatMessagePart.ToolCall)
        assertEquals(
            "finished",
            (result.result[2].parts.single() as ChatMessagePart.Text).text
        )
    }

    @Test
    fun `runCollectPartial keeps the partial history on a terminal hand error`() = runBlocking {
        val hand = FakeHand(
            runScript = {
                val call = ChatMessagePart.ToolCall(
                    id = "call_1",
                    tool = "flag",
                    args = JsonObject(emptyMap()),
                )
                listOf(
                    HandEvent.AssistantMessage(
                        assistantMessage(parts = listOf(call), finishReason = "tool_calls")
                    ),
                    HandEvent.ToolCall("call_1", "flag", call.args),
                    HandEvent.ToolResult(
                        "call_1",
                        "flag",
                        listOf(ChatMessagePart.Text("done")),
                        false
                    ),
                    HandEvent.AssistantMessage(assistantMessage("partial")),
                    HandEvent.RunError("round_limit", "maxRounds reached"),
                )
            }
        )
        val service =
            HandService(hand, HandCallbackService("test-token"), "http://127.0.0.1:9/api/hand/tool", "http://127.0.0.1:9/api/hand/tools")

        val result = service.runCollectPartial(runRequest(), EmptyToolProvider, model())

        assertEquals("round_limit", result.exception?.type)
        assertEquals(3, result.result.size, "the messages before the error must survive")
        assertTrue(result.result[0].parts.single() is ChatMessagePart.ToolCall)
        val toolResult = assertIs<ChatMessagePart.ToolResult>(result.result[1].parts.single())
        assertEquals("call_1", toolResult.id)
        assertEquals("partial", (result.result[2].parts.single() as ChatMessagePart.Text).text)
    }

    @Test
    fun `runCollect rethrows the exception captured by runCollectPartial`() = runBlocking {
        val hand = FakeHand(
            runScript = {
                listOf(
                    HandEvent.AssistantMessage(assistantMessage("partial")),
                    HandEvent.RunError("context_exhausted", "window full"),
                )
            }
        )
        val service =
            HandService(hand, HandCallbackService("test-token"), "http://127.0.0.1:9/api/hand/tool", "http://127.0.0.1:9/api/hand/tools")

        val e = assertFailsWith<HandRunException> {
            service.runCollect(runRequest(), EmptyToolProvider, model())
        }
        assertEquals("context_exhausted", e.type)
    }

    private fun embeddingModel(dimensions: Int = 1536) = EmbeddingModel(
        provider = ModelProvider("bifrost", "http://127.0.0.1:9/v1", "test"),
        modelId = "zenmux sub/google/gemini-embedding-2",
        dimensions = dimensions,
    )

    @Test
    fun `embed relays the happy path and passes the caller's knobs through`() = runBlocking {
        val hand = FakeHand()
        val service =
            HandService(hand, HandCallbackService("test-token"), "http://127.0.0.1:9/api/hand/tool", "http://127.0.0.1:9/api/hand/tools")

        val result = service.embed(embeddingModel(), listOf("hello", "world"), maxRetries = 2, timeoutMs = 30_000)

        assertEquals(2, result.vectors.size, "one vector per input item")
        assertEquals(1536, result.dimensions)
        assertEquals(HandEmbedUsage(10, 10), result.usage)
        val request = hand.embedRequests.single()
        assertEquals("zenmux sub/google/gemini-embedding-2", request.model.modelId)
        assertEquals("http://127.0.0.1:9/v1", request.model.baseUrl)
        assertEquals(1536, request.dimensions)
        assertEquals(listOf("hello", "world"), request.input)
        assertEquals(2, request.maxRetries)
        assertEquals(30_000, request.timeoutMs)
        assertEquals(null, request.additionalProperties, "no extra properties by default")
    }

    @Test
    fun `embed passes the catalog entry's additionalProperties through`() = runBlocking {
        val hand = FakeHand()
        val service =
            HandService(hand, HandCallbackService("test-token"), "http://127.0.0.1:9/api/hand/tool", "http://127.0.0.1:9/api/hand/tools")
        val model = EmbeddingModel(
            provider = ModelProvider("bifrost", "http://127.0.0.1:9/v1", "test"),
            modelId = "zenmux sub/google/gemini-embedding-2",
            dimensions = 1536,
            additionalProperties = buildJsonObject { put("service_tier", "priority") },
        )

        service.embed(model, listOf("hello"), maxRetries = 2, timeoutMs = 30_000)

        val request = hand.embedRequests.single()
        assertEquals(
            buildJsonObject { put("service_tier", "priority") },
            request.additionalProperties,
        )
    }

    @Test
    fun `embedding model rejects additionalProperties colliding with the hand-managed fields`() {
        // the catalog is authored once; a collision with the hand's own
        // gateway body fields is a catalog bug, so it must fail at boot
        for (key in listOf("model", "input", "dimensions")) {
            val e = assertFailsWith<IllegalArgumentException> {
                EmbeddingModel(
                    provider = ModelProvider("bifrost", "http://127.0.0.1:9/v1", "test"),
                    modelId = "zenmux sub/google/gemini-embedding-2",
                    dimensions = 1536,
                    additionalProperties = buildJsonObject { put(key, "sneaky") },
                )
            }
            assertTrue(
                e.message!!.contains(key),
                "the error must name the colliding key '$key': ${e.message}",
            )
        }
    }

    @Test
    fun `embed maps each hand error type to EmbeddingException with the right type`() = runBlocking {
        for ((type, thrown) in mapOf(
            "auth" to EmbeddingException("auth", "bad key"),
            "invalid_request" to EmbeddingException("invalid_request", "input too large"),
            "upstream" to EmbeddingException("upstream", "boom"),
        )) {
            val hand = FakeHand(embedScript = { throw thrown })
            val service = HandService(
                hand, HandCallbackService("test-token"),
                "http://127.0.0.1:9/api/hand/tool", "http://127.0.0.1:9/api/hand/tools",
            )
            val e = assertFailsWith<EmbeddingException> {
                service.embed(embeddingModel(), listOf("x"), maxRetries = 0, timeoutMs = 0)
            }
            assertEquals(type, e.type)
        }
    }

    @Test
    fun `embed wraps transport failures as upstream`() = runBlocking {
        val transport = HandUpstreamException("connection refused")
        val hand = FakeHand(embedScript = { throw transport })
        val service =
            HandService(hand, HandCallbackService("test-token"), "http://127.0.0.1:9/api/hand/tool", "http://127.0.0.1:9/api/hand/tools")

        val e = assertFailsWith<EmbeddingException> {
            service.embed(embeddingModel(), listOf("x"), maxRetries = 0, timeoutMs = 0)
        }
        assertEquals("upstream", e.type)
        assertEquals(transport, e.cause, "the wrapped transport failure must be preserved as the cause")
    }

    @Test
    fun `embed fails fast on a dimensions drift between the hand and the catalog`() = runBlocking {
        // the hand answers with 2 dims but the catalog pins 1536
        val model = embeddingModel(dimensions = 1536)
        val drifted = FakeHand(
            embedScript = {
                HandEmbedResult(
                    vectors = listOf(listOf(1f, 2f)),
                    dimensions = 2,
                    usage = null,
                )
            }
        )
        val driftedService = HandService(
            drifted, HandCallbackService("test-token"),
            "http://127.0.0.1:9/api/hand/tool", "http://127.0.0.1:9/api/hand/tools",
        )
        val e = assertFailsWith<IllegalStateException> {
            driftedService.embed(model, listOf("x"), maxRetries = 0, timeoutMs = 0)
        }
        assertTrue(e.message!!.contains("1536"), "the error must name the expected dimensions: ${e.message}")
    }

    @Test
    fun `embed fails fast on a vector count mismatch between the hand and the inputs`() = runBlocking {
        // the hand must return exactly one vector per input item; a count
        // mismatch would silently misalign the caller's per-item associations
        val truncated = FakeHand(
            embedScript = {
                HandEmbedResult(
                    vectors = listOf(listOf(1f)),
                    dimensions = 1,
                    usage = null,
                )
            }
        )
        val truncatedService = HandService(
            truncated, HandCallbackService("test-token"),
            "http://127.0.0.1:9/api/hand/tool", "http://127.0.0.1:9/api/hand/tools",
        )
        val e = assertFailsWith<IllegalStateException> {
            truncatedService.embed(
                embeddingModel(dimensions = 1), listOf("x", "y"),
                maxRetries = 0, timeoutMs = 0,
            )
        }
        assertTrue(
            e.message!!.contains("1 vectors for 2 inputs"),
            "the error must name both counts: ${e.message}",
        )
    }

    @Test
    fun `embed rethrows cancellation`() = runBlocking {
        val hand = FakeHand(embedScript = { throw CancellationException("cancelled") })
        val service =
            HandService(hand, HandCallbackService("test-token"), "http://127.0.0.1:9/api/hand/tool", "http://127.0.0.1:9/api/hand/tools")

        val e = runCatching { service.embed(embeddingModel(), listOf("x"), maxRetries = 0, timeoutMs = 0) }
            .exceptionOrNull()
        assertIs<CancellationException>(e)
    }
}
