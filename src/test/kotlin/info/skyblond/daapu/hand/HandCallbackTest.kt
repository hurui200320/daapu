package info.skyblond.daapu.hand

import info.skyblond.daapu.agent.ModelCatalog
import info.skyblond.daapu.agent.chat.AttachmentContent
import info.skyblond.daapu.agent.chat.AttachmentKind
import info.skyblond.daapu.agent.chat.ChatMessagePart
import info.skyblond.daapu.agent.model.LLMCapability
import info.skyblond.daapu.agent.model.ModelProvider
import info.skyblond.daapu.agent.tool.ToolCallRequest
import info.skyblond.daapu.agent.tool.ToolProvider
import info.skyblond.daapu.agent.tool.ToolSpec
import info.skyblond.daapu.config.McpServerConfig
import info.skyblond.daapu.config.McpTransportType
import info.skyblond.daapu.config.testAppConfig
import info.skyblond.daapu.mcp.*
import info.skyblond.daapu.memory.sstm.PostgresSstmService
import info.skyblond.daapu.server.ChatRunService
import info.skyblond.daapu.server.module
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

/**
 * Pins the hand-pi tool callback route (`POST /api/hand/tool`):
 * token check, runId lookup, tool execution with the isError/fatal
 * split, and the result-attachment capability check (today's per-round
 * `LLM.checkPromptContentCapabilities` equivalent for tool results).
 */
class HandCallbackTest {

    private fun json() = Json { ignoreUnknownKeys = true }

    private fun ApplicationTestBuilder.runApplicationWithService(block: suspend (ChatRunService) -> Unit) {
        val service = ChatRunService(testAppConfig())
        application {
            module(service, PostgresSstmService())
        }
        kotlinx.coroutines.runBlocking { block(service) }
    }

    private suspend fun io.ktor.client.HttpClient.callback(
        token: String? = "dev-token",
        body: HandToolCallbackRequest,
    ): Pair<HttpStatusCode, String> {
        val response = post("/api/hand/tool") {
            contentType(ContentType.Application.Json)
            token?.let { header("x-daapu-token", it) }
            setBody(json().encodeToString(HandToolCallbackRequest.serializer(), body))
        }
        return response.status to response.bodyAsText()
    }

    private suspend fun io.ktor.client.HttpClient.toolList(
        runId: String,
        token: String? = "dev-token",
    ): Pair<HttpStatusCode, String> {
        val response = get("/api/hand/tools") {
            token?.let { header("x-daapu-token", it) }
            url.parameters.append("runId", runId)
        }
        return response.status to response.bodyAsText()
    }

    private fun textModel() = ModelCatalog(
        mapOf(
            "bifrost" to ModelProvider(
                id = "bifrost",
                baseUrl = "http://127.0.0.1:9/v1",
                apiKey = "test"
            )
        )
    ).findModel("bifrost/cerebras/gpt-oss-120b")!!

    private fun callbackRequest(
        runId: String = "run-1",
        name: String = "flag",
        args: JsonObject = JsonObject(emptyMap()),
    ) = HandToolCallbackRequest(
        runId = runId, id = "call_1", name = name, args = args,
    )

    @Test
    fun `duplicate runId registration fails fast and eviction allows reuse`() = runBlocking {
        val service = HandCallbackService("test-token")
        service.register("run-1", EmptyProvider(), textModel())
        // a second in-flight run must not silently override the first
        assertFailsWith<IllegalStateException> {
            service.register("run-1", EchoProvider(), textModel())
        }
        // the first registration is untouched
        val response =
            service.executeToolCall(callbackRequest(args = buildJsonObject { put("text", "hi") }))
        assertNull(response.fatal, "the original in-flight run must still resolve")
        // eviction frees the id for a legitimate sequential reuse
        service.unregister("run-1")
        service.register("run-1", EchoProvider(), textModel())
        val after =
            service.executeToolCall(callbackRequest(args = buildJsonObject { put("text", "hi") }))
        assertNull(after.fatal, "a re-registered runId must resolve")
        service.unregister("run-1")
    }

    @Test
    fun `missing or wrong token is rejected`() = testApplication {
        runApplicationWithService { service ->
            service.handCallback.register("run-1", EmptyProvider(), textModel())
            val (status, _) = client.callback(token = null, body = callbackRequest())
            assertEquals(HttpStatusCode.Unauthorized, status)
            val (status2, _) = client.callback(token = "wrong", body = callbackRequest())
            assertEquals(HttpStatusCode.Unauthorized, status2)
        }
    }

    @Test
    fun `an unknown runId answers fatal`() = testApplication {
        runApplicationWithService { service ->
            val (status, body) = client.callback(body = callbackRequest(runId = "nope"))
            assertEquals(HttpStatusCode.OK, status)
            val response =
                json().parseToJsonElement(body).let { it as JsonObject }
            assertNotNull(response["fatal"])
        }
    }

    // ------------------------------------------------------------------
    // the tool-listing route (GET /api/hand/tools): the hand queries it
    // before every LLM request, so the run always sees the provider's
    // latest advertisements
    // ------------------------------------------------------------------

    @Test
    fun `the tool list serves the registered run's provider specifications`() = testApplication {
        runApplicationWithService { service ->
            service.handCallback.register("run-1", FlagProvider(), textModel())
            val (status, body) = client.toolList(runId = "run-1")
            assertEquals(HttpStatusCode.OK, status)
            val response = json().parseToJsonElement(body).let { it as JsonObject }
            val tools = response["tools"]!!.jsonArray
            assertEquals(1, tools.size)
            val tool = tools[0].jsonObject
            assertEquals("flag", tool["name"]?.jsonPrimitive?.content)
            assertEquals("a flag tool", tool["description"]?.jsonPrimitive?.content)
            assertNull(
                tool["timeoutSeconds"],
                "the execution budget is brain-side and never advertised to the hand"
            )
            assertNotNull(tool["schema"])
        }
    }

    @Test
    fun `an empty tool provider answers an empty list`() = testApplication {
        runApplicationWithService { service ->
            service.handCallback.register("run-1", EmptyProvider(), textModel())
            val (status, body) = client.toolList(runId = "run-1")
            assertEquals(HttpStatusCode.OK, status)
            val response = json().parseToJsonElement(body).let { it as JsonObject }
            assertEquals(0, response["tools"]!!.jsonArray.size)
        }
    }

    @Test
    fun `the tool list rejects a missing or wrong token`() = testApplication {
        runApplicationWithService { service ->
            service.handCallback.register("run-1", EmptyProvider(), textModel())
            val (noToken, _) = client.toolList(runId = "run-1", token = null)
            assertEquals(HttpStatusCode.Unauthorized, noToken)
            val (wrongToken, _) = client.toolList(runId = "run-1", token = "wrong")
            assertEquals(HttpStatusCode.Unauthorized, wrongToken)
        }
    }

    @Test
    fun `the tool list answers 404 for an unknown runId and 400 for a blank one`() = testApplication {
        runApplicationWithService { service ->
            val (unknown, _) = client.toolList(runId = "nope")
            assertEquals(HttpStatusCode.NotFound, unknown)
            val (blank, _) = client.toolList(runId = "")
            assertEquals(HttpStatusCode.BadRequest, blank)
        }
    }

    @Test
    fun `a provider that cannot list its tools answers 500`() = testApplication {
        runApplicationWithService { service ->
            service.handCallback.register("run-1", TransportFailingSpecProvider(), textModel())
            val (status, _) = client.toolList(runId = "run-1")
            assertEquals(HttpStatusCode.InternalServerError, status)
        }
    }

    @Test
    fun `the tool list answers the registered provider even while the run is in flight`() = testApplication {
        runApplicationWithService { service ->
            service.handCallback.register("run-1", FlagProvider(), textModel())
            val (before, _) = client.toolList(runId = "run-1")
            assertEquals(HttpStatusCode.OK, before)
            service.handCallback.unregister("run-1")
            val (after, _) = client.toolList(runId = "run-1")
            assertEquals(HttpStatusCode.NotFound, after, "an evicted run must no longer resolve")
        }
    }

    @Test
    fun `a tool call executes and returns its parts`() = testApplication {
        runApplicationWithService { service ->
            val provider = EchoProvider()
            service.handCallback.register("run-1", provider, textModel())
            val (status, body) = client.callback(body = callbackRequest(args = buildJsonObject {
                put(
                    "text",
                    "hi"
                )
            }))
            assertEquals(HttpStatusCode.OK, status)
            val response =
                json().parseToJsonElement(body).let { it as JsonObject }
            assertNull(response["fatal"], "a successful tool must not answer fatal: $body")
            assertEquals("""[{"type":"text","text":"hi"}]""", response["parts"].toString())
            assertEquals("false", response["isError"].toString())
            val executed = provider.executed.single()
            assertEquals("call_1", executed.id)
            assertEquals("flag", executed.name)
            assertEquals(buildJsonObject { put("text", "hi") }, executed.args)
        }
    }

    @Test
    fun `a tool-level error is an isError result, not fatal`() = testApplication {
        runApplicationWithService { service ->
            service.handCallback.register("run-1", FailingProvider(), textModel())
            val (status, body) = client.callback(body = callbackRequest())
            assertEquals(HttpStatusCode.OK, status)
            val response =
                json().parseToJsonElement(body).let { it as JsonObject }
            assertNull(response["fatal"])
            assertEquals("true", response["isError"].toString())
        }
    }

    @Test
    fun `an MCP transport failure answers fatal`() = testApplication {
        runApplicationWithService { service ->
            service.handCallback.register("run-1", TransportFailingProvider(), textModel())
            val (status, body) = client.callback(body = callbackRequest())
            assertEquals(HttpStatusCode.OK, status)
            val response =
                json().parseToJsonElement(body).let { it as JsonObject }
            assertTrue(response["fatal"].toString().contains("transport"))
        }
    }

    @Test
    fun `a result attachment the model cannot see answers fatal`() = testApplication {
        runApplicationWithService { service ->
            // gpt-oss-120b is text-only: an image result must fail the run
            // (today's per-round capability check equivalent)
            service.handCallback.register("run-1", ImageProvider(), textModel())
            val (status, body) = client.callback(body = callbackRequest())
            assertEquals(HttpStatusCode.OK, status)
            val response =
                json().parseToJsonElement(body).let { it as JsonObject }
            val fatal = response["fatal"].toString()
            assertTrue(
                fatal.contains("does not support image"),
                "fatal must name the mismatch: $fatal"
            )
        }
    }

    @Test
    fun `a result attachment passes for a vision model`() = testApplication {
        runApplicationWithService { service ->
            val visionModel = ModelCatalog(
                mapOf(
                    "bifrost" to ModelProvider(
                        id = "bifrost",
                        baseUrl = "http://127.0.0.1:9/v1",
                        apiKey = "test"
                    )
                )
            ).findModel("bifrost/cerebras/gemma-4-31b")!!
            assertTrue(visionModel.supports(LLMCapability.Input.Vision.Image))
            service.handCallback.register("run-1", ImageProvider(), visionModel)
            val (status, body) = client.callback(body = callbackRequest())
            assertEquals(HttpStatusCode.OK, status)
            val response =
                json().parseToJsonElement(body).let { it as JsonObject }
            assertNull(response["fatal"])
        }
    }

    @Test
    fun `an overrunning tool is cancelled and answers an isError timeout result`() =
        testApplication {
            runApplicationWithService { service ->
                val provider = SlowProvider(timeoutSeconds = 1)
                service.handCallback.register("run-1", provider, textModel())
                val start = System.currentTimeMillis()
                val (status, body) = client.callback(body = callbackRequest())
                val elapsed = System.currentTimeMillis() - start
                assertEquals(HttpStatusCode.OK, status)
                val response = json().parseToJsonElement(body)
                    .let { it as JsonObject }
                assertNull(response["fatal"], "a timeout is a tool-level error, not a fatal: $body")
                assertEquals("true", response["isError"].toString())
                assertTrue(
                    response["parts"].toString().contains("timed out after 1s"),
                    "the timeout result must name the tool and budget: $body",
                )
                assertTrue(
                    elapsed < 3_000,
                    "the timeout must answer within the budget: ${elapsed}ms"
                )
                assertTrue(
                    provider.cancelled,
                    "the tool execution must be cancelled with the timeout"
                )
            }
        }

    @Test
    fun `a tool with a disabled timeout answers normally after the budget would have expired`() =
        testApplication {
            runApplicationWithService { service ->
                val provider = SlowProvider(delayMs = 1_500)
                service.handCallback.register("run-1", provider, textModel())
                val (status, body) = client.callback(body = callbackRequest())
                assertEquals(HttpStatusCode.OK, status)
                val response = json().parseToJsonElement(body)
                    .let { it as JsonObject }
                assertNull(response["fatal"])
                assertEquals("false", response["isError"].toString())
            }
        }

    @Test
    fun `an overrunning MCP tool answers an isError timeout result without a retry and keeps the connection`() =
        testApplication {
            runApplicationWithService { service ->
                // the real MCP stack: the provider's in-flight call hangs until
                // the budget expires (enforced by the callback route's
                // withTimeout, sourced from the server's REQUIRED config)
                val server = MockMcpServer(
                    listOf(
                        MockTool(name = "hang", description = "hangs", handler = {
                            Thread.sleep(30_000)
                            MockToolReply("late")
                        }),
                        MockTool(name = "echo", description = "echo", handler = { args ->
                            MockToolReply(args["text"]?.jsonPrimitive?.content ?: "")
                        }),
                    )
                )
                val provider = McpToolProvider(
                    listOf(
                        McpServerConfig(
                            namespace = "calc",
                            type = McpTransportType.Http,
                            url = server.baseUrl,
                            toolExecutionTimeoutSeconds = 1,
                            reconnectAttempts = 1,
                            reconnectDelayMs = 50,
                        )
                    )
                )
                try {
                    kotlinx.coroutines.runBlocking { provider.specifications() }
                    service.handCallback.register("run-1", provider, textModel())
                    val start = System.currentTimeMillis()
                    val (status, body) = client.callback(
                        body = callbackRequest(name = "calc__hang")
                    )
                    val elapsed = System.currentTimeMillis() - start
                    assertEquals(HttpStatusCode.OK, status)
                    val response = json().parseToJsonElement(body)
                        .let { it as JsonObject }
                    assertNull(
                        response["fatal"],
                        "a timeout is a tool-level error, not a fatal: $body"
                    )
                    assertEquals("true", response["isError"].toString())
                    assertTrue(
                        response["parts"].toString().contains("timed out after 1s"),
                        "the timeout result must name the tool and budget: $body",
                    )
                    assertTrue(
                        elapsed < 3_000,
                        "the timeout must answer within the budget: ${elapsed}ms"
                    )
                    assertEquals(1, server.toolCalls.size, "a timeout must not retry the call")

                    // the connection survives the client-side abort: the next call
                    // reuses it (no reconnect) and succeeds
                    val (status2, body2) = client.callback(
                        body = callbackRequest(
                            name = "calc__echo",
                            args = buildJsonObject { put("text", "hi") },
                        )
                    )
                    assertEquals(HttpStatusCode.OK, status2)
                    assertEquals(
                        1,
                        server.initializeCount.get(),
                        "a timeout is not a transport failure: the connection must be kept"
                    )
                    assertEquals(2, server.toolCalls.size)
                    assertTrue(body2.contains("hi"), "the kept connection must still work: $body2")
                } finally {
                    provider.close()
                    server.close()
                }
            }
        }

    // ------------------------------------------------------------------
    // providers
    // ------------------------------------------------------------------

    private class EmptyProvider : ToolProvider {
        override suspend fun specifications(): List<ToolSpec> = emptyList()

        override suspend fun execute(request: ToolCallRequest): ChatMessagePart.ToolResult =
            ChatMessagePart.ToolResult(
                id = request.id,
                tool = request.name,
                parts = listOf(ChatMessagePart.Text("Error: no tools")),
                isError = true,
            )
    }

    private class EchoProvider : ToolProvider {
        val executed = mutableListOf<ToolCallRequest>()

        override suspend fun specifications(): List<ToolSpec> = emptyList()

        override suspend fun execute(request: ToolCallRequest): ChatMessagePart.ToolResult {
            executed += request
            return ChatMessagePart.ToolResult(
                id = request.id,
                tool = request.name,
                parts = listOf(ChatMessagePart.Text("hi")),
            )
        }
    }

    /** Advertises one tool (`flag`), for the tool-listing route tests. */
    private class FlagProvider : ToolProvider {
        override suspend fun specifications(): List<ToolSpec> = listOf(
            ToolSpec(
                name = "flag",
                description = "a flag tool",
                schema = buildJsonObject {},
            )
        )

        override suspend fun execute(request: ToolCallRequest): ChatMessagePart.ToolResult =
            ChatMessagePart.ToolResult(
                id = request.id,
                tool = request.name,
                parts = listOf(ChatMessagePart.Text("flag")),
            )
    }

    private class FailingProvider : ToolProvider {
        override suspend fun specifications(): List<ToolSpec> = emptyList()

        override suspend fun execute(request: ToolCallRequest): ChatMessagePart.ToolResult =
            ChatMessagePart.ToolResult(
                id = request.id,
                tool = request.name,
                parts = listOf(ChatMessagePart.Text("the tool failed")),
                isError = true,
            )
    }

    private class TransportFailingProvider : ToolProvider {
        override suspend fun specifications(): List<ToolSpec> = emptyList()

        override suspend fun execute(request: ToolCallRequest): ChatMessagePart.ToolResult =
            throw McpTransportException("MCP transport failure", RuntimeException("boom"))
    }

    private class TransportFailingSpecProvider : ToolProvider {
        override suspend fun specifications(): List<ToolSpec> =
            throw McpTransportException("MCP transport failure", RuntimeException("boom"))

        override suspend fun execute(request: ToolCallRequest): ChatMessagePart.ToolResult =
            throw McpTransportException("MCP transport failure", RuntimeException("boom"))
    }

    private class SlowProvider(
        private val delayMs: Long = 5_000,
        private val timeoutSeconds: Long = 0,
    ) : ToolProvider {
        /** true when the execution ended through cancellation. */
        @Volatile
        var cancelled = false

        override fun executionTimeoutSeconds(toolName: String): Long = timeoutSeconds

        override suspend fun specifications(): List<ToolSpec> = emptyList()

        override suspend fun execute(request: ToolCallRequest): ChatMessagePart.ToolResult {
            try {
                kotlinx.coroutines.delay(delayMs)
                return ChatMessagePart.ToolResult(
                    id = request.id,
                    tool = request.name,
                    parts = listOf(ChatMessagePart.Text("slow result")),
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancelled = true
                throw e
            }
        }
    }

    private class ImageProvider : ToolProvider {
        override suspend fun specifications(): List<ToolSpec> = emptyList()

        override suspend fun execute(request: ToolCallRequest): ChatMessagePart.ToolResult =
            ChatMessagePart.ToolResult(
                id = request.id,
                tool = request.name,
                parts = listOf(
                    ChatMessagePart.Attachment(
                        kind = AttachmentKind.Image,
                        content = AttachmentContent.Base64("AAAA"),
                        mimeType = "image/png",
                    )
                ),
            )
    }
}
