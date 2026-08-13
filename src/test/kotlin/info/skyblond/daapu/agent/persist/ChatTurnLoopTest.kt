package info.skyblond.daapu.agent.persist

import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.exception.HttpException
import info.skyblond.daapu.config.McpServerConfig
import info.skyblond.daapu.config.McpTransportType
import info.skyblond.daapu.agent.ModelCapabilityException
import info.skyblond.daapu.agent.executor.StreamingExecutionCallback
import info.skyblond.daapu.agent.lc4j.executor.Lc4jStreamingExecutor
import info.skyblond.daapu.agent.lc4j.executor.MidStreamErrorChunkException
import info.skyblond.daapu.agent.lc4j.llm.ModelCatalog
import info.skyblond.daapu.agent.lc4j.provider.BifrostProvider
import info.skyblond.daapu.agent.lc4j.tool.EmptyToolProvider
import info.skyblond.daapu.agent.lc4j.tool.ToolProvider
import info.skyblond.daapu.chat.AttachmentContent
import info.skyblond.daapu.chat.AttachmentKind
import info.skyblond.daapu.chat.ChatEntry
import info.skyblond.daapu.chat.ChatMessage
import info.skyblond.daapu.chat.ChatMessagePart
import info.skyblond.daapu.chat.ChatMessageRole
import info.skyblond.daapu.chat.ChatStore
import info.skyblond.daapu.agent.lc4j.MockSseResponse
import info.skyblond.daapu.agent.lc4j.MockSseServer
import info.skyblond.daapu.agent.lc4j.SSE_DONE
import info.skyblond.daapu.agent.lc4j.sseChunk
import info.skyblond.daapu.agent.lc4j.sseEvent
import info.skyblond.daapu.mcp.McpToolProvider
import info.skyblond.daapu.mcp.MockMcpServer
import info.skyblond.daapu.mcp.MockTool
import info.skyblond.daapu.mcp.MockToolReply
import info.skyblond.daapu.memory.sstm.MemoriesWithVersion
import info.skyblond.daapu.memory.sstm.ShortTermMemory
import info.skyblond.daapu.memory.sstm.SstmService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the turn loop's behavior end-to-end against a mock SSE server: the
 * history load/store lifecycle (store only on success, injection stripped,
 * system prompt refreshed), the retry policy (only a clean stream with no
 * `finish_reason` retries), the mid-stream error-chunk scan, truncation
 * detection, capability enforcement BEFORE any LLM request, and the
 * tool-round skeleton.
 */
class ChatTurnLoopTest {

    private val systemPrompt = "You are Raven."

    private fun catalogModel(server: MockSseServer, id: String) = ModelCatalog(
        BifrostProvider(
            id = "bifrost",
            baseUrl = "http://127.0.0.1:${server.port}/v1",
            apiKey = "test-key",
        )
    ).findModel(id)!!

    /** Runs one turn; returns the outcome for assertions. */
    private fun run(
        server: MockSseServer,
        modelId: String = "bifrost/cerebras/gemma-4-31b",
        userParts: List<ChatMessagePart> = listOf(ChatMessagePart.Text("hello")),
        store: InMemoryChatStore = InMemoryChatStore(),
        sstmService: SstmService = InMemorySstmService(),
        toolProvider: ToolProvider = EmptyToolProvider,
    ): TurnOutcome {
        val model = catalogModel(server, modelId)
        val callback = RecordingCallback()
        val error = runBlocking {
            runCatching {
                runChatTurn(
                    chatId = "chat-1",
                    model = model,
                    streamingChatModel = model.toStreamingChatModel("high"),
                    userParts = userParts,
                    systemPrompt = systemPrompt,
                    chatStore = store,
                    sstmService = sstmService,
                    toolProvider = toolProvider,
                    callback = callback,
                    executor = Lc4jStreamingExecutor(),
                )
            }.exceptionOrNull()
        }
        return TurnOutcome(error, store, callback)
    }

    private fun stopStream() = listOf(
        sseEvent(sseChunk(delta = """{"content":"ok"}""")),
        sseEvent(sseChunk(finishReason = "stop")),
        SSE_DONE,
    )

    @Test
    fun `basic chat stores history with injection stripped and system refreshed`() {
        val server = MockSseServer { MockSseResponse(200, stopStream()) }
        try {
            val outcome = run(server)
            assertNull(outcome.error)

            // deltas streamed to the client
            assertEquals(listOf("ok"), outcome.callback.texts)
            assertTrue(outcome.callback.errors.isEmpty())

            // history stored: system (refreshed), user (injection stripped), assistant
            val stored = assertNotNull(outcome.store.stored, "history must be stored on success")
            assertEquals(
                listOf(ChatMessageRole.System, ChatMessageRole.User, ChatMessageRole.Assistant),
                stored.map { it.role },
            )
            assertEquals(listOf(ChatMessagePart.Text(systemPrompt)), stored[0].parts)
            assertEquals(listOf(ChatMessagePart.Text("hello")), stored[1].parts)
            assertEquals(listOf(ChatMessagePart.Text("ok")), stored[2].parts)
            assertEquals("stop", stored[2].finishReason)
        } finally {
            server.close()
        }
    }

    @Test
    fun `sstm version is persisted on the chat and the sstm-updated flag tracks changes`() {
        val server = MockSseServer { MockSseResponse(200, stopStream()) }
        try {
            val store = InMemoryChatStore()
            val sstm = InMemorySstmService(version = "v1")

            // a fresh chat has no stored version, so the first run must flag
            // the memory list as updated and persist the version it saw
            var outcome = run(server, store = store, sstmService = sstm)
            assertNull(outcome.error)
            assertTrue(
                server.lastRequest()!!.contains("<sstm-updated>true</sstm-updated>"),
                "fresh chat must flag memories as updated",
            )
            assertEquals("v1", store.storedSstmVersion, "run must persist the memory version")

            // same version as the last run: nothing changed, no flag
            outcome = run(server, store = store, sstmService = sstm)
            assertNull(outcome.error)
            assertTrue(
                server.lastRequest()!!.contains("<sstm-updated>false</sstm-updated>"),
                "an unchanged version must not flag",
            )
            assertEquals("v1", store.storedSstmVersion)

            // a memory edit bumps the version: the next run must flag again
            sstm.version = "v2"
            outcome = run(server, store = store, sstmService = sstm)
            assertNull(outcome.error)
            assertTrue(
                server.lastRequest()!!.contains("<sstm-updated>true</sstm-updated>"),
                "a changed version must flag",
            )
            assertEquals("v2", store.storedSstmVersion)
        } finally {
            server.close()
        }
    }

    @Test
    fun `sstm version is not persisted on a failed run`() {
        val server = MockSseServer { MockSseResponse(200, stopStream()) }
        try {
            val store = InMemoryChatStore()
            val sstm = InMemorySstmService(version = "v1")
            val outcome = run(server, store = store, sstmService = sstm)
            assertNull(outcome.error)
            assertEquals("v1", store.storedSstmVersion)

            // a failed run must not touch the stored version: history stays
            // at the last good state
            sstm.version = "v2"
            val failingServer = MockSseServer { MockSseResponse(500, emptyList()) }
            try {
                val failed = run(failingServer, store = store, sstmService = sstm)
                assertNotNull(failed.error)
                assertEquals("v1", store.storedSstmVersion, "failed run must not update the version")
            } finally {
                failingServer.close()
            }

            // ... so the next successful run still flags the pending change
            val retry = run(server, store = store, sstmService = sstm)
            assertNull(retry.error)
            assertTrue(
                server.lastRequest()!!.contains("<sstm-updated>true</sstm-updated>"),
                "a change missed by a failed run must flag on the next success",
            )
            assertEquals("v2", store.storedSstmVersion)
        } finally {
            server.close()
        }
    }

    @Test
    fun `reasoning deltas are forwarded and kept in stored history`() {
        val server = MockSseServer {
            MockSseResponse(
                200,
                listOf(
                    sseEvent(sseChunk(delta = """{"reasoning_content":"Let me think"}""")),
                    sseEvent(sseChunk(delta = """{"reasoning_content":" step by step"}""")),
                    sseEvent(sseChunk(delta = """{"content":"17 * 23 = 391"}""")),
                    sseEvent(sseChunk(finishReason = "stop")),
                    SSE_DONE,
                )
            )
        }
        try {
            val outcome = run(server)
            assertNull(outcome.error)
            assertEquals(listOf("Let me think", " step by step"), outcome.callback.thinkings)
            val assistant = outcome.store.stored!![2]
            // the reasoning part is kept in stored history on purpose (it is
            // re-sent as reasoning_content on later requests via sendThinking)
            assertEquals(
                listOf(
                    ChatMessagePart.Reasoning(listOf("Let me think step by step")),
                    ChatMessagePart.Text("17 * 23 = 391"),
                ),
                assistant.parts,
            )
        } finally {
            server.close()
        }
    }

    @Test
    fun `http 500 fails the run without storing`() {
        // the exception-based retry policy was removed in the executor
        // refactor: only a clean stream with no finish_reason is retried now,
        // HTTP-level failures fail the run immediately
        val server = MockSseServer { MockSseResponse(500, emptyList()) }
        try {
            // a chat with existing history: a failed run must leave it untouched
            val seed = listOf(ChatMessage(ChatMessageRole.User, listOf(ChatMessagePart.Text("old"))))
            val store = InMemoryChatStore(seed)
            val outcome = run(server, store = store)

            val status = generateSequence(assertNotNull(outcome.error)) { it.cause }
                .filterIsInstance<HttpException>()
                .map { it.statusCode() }
                .firstOrNull()
            assertEquals(500, status)
            assertEquals(1, server.count, "an http failure must not be retried")
            assertEquals(0, outcome.store.storeCount, "a failed run must never store")
            assertEquals(seed, outcome.store.stored, "history stays at the last good state")
        } finally {
            server.close()
        }
    }

    @Test
    fun `mid-stream error chunk with numeric 403 fails the run without storing`() {
        val server = MockSseServer {
            MockSseResponse(
                200,
                listOf(
                    sseEvent(sseChunk(delta = """{"content":"partial"}""")),
                    sseEvent("""{"error":{"message":"Content policy violation","type":"moderation","code":403}}"""),
                    SSE_DONE,
                )
            )
        }
        try {
            // a chat with existing history: a failed run must leave it untouched
            val seed = listOf(ChatMessage(ChatMessageRole.User, listOf(ChatMessagePart.Text("old"))))
            val store = InMemoryChatStore(seed)
            val outcome = run(server, store = store)

            val e = assertIs<HttpException>(outcome.error)
            assertEquals(403, e.statusCode())
            assertTrue(outcome.callback.errors.isEmpty(), "a permanent error must not be retried")
            assertEquals(1, server.count, "the run must not retry the request")
            assertEquals(0, outcome.store.storeCount, "a failed run must never store")
            assertEquals(seed, outcome.store.stored, "history stays at the last good state")
        } finally {
            server.close()
        }
    }

    @Test
    fun `mid-stream error chunk without a code fails the run`() {
        // a code-less error chunk used to be retried as transient; with the
        // exception-based retry policy gone it fails the run immediately
        val server = MockSseServer {
            MockSseResponse(
                200,
                listOf(
                    sseEvent(sseChunk(delta = """{"content":"partial"}""")),
                    sseEvent("""{"error":{"message":"upstream connection reset"}}"""),
                    SSE_DONE,
                )
            )
        }
        try {
            val outcome = run(server)
            assertIs<MidStreamErrorChunkException>(outcome.error)
            assertEquals(1, server.count, "a mid-stream error chunk must not be retried")
            assertEquals(0, outcome.store.storeCount, "a failed run must never store")
        } finally {
            server.close()
        }
    }

    @Test
    fun `mid-stream error chunk with a numeric code fails the run`() {
        // a numeric code (here 429) is thrown as HttpException by the error
        // chunk scan and fails the run; classifying it back into the
        // transient/retry bucket is a TODO
        val server = MockSseServer {
            MockSseResponse(
                200,
                listOf(
                    sseEvent(sseChunk(delta = """{"content":"partial"}""")),
                    sseEvent("""{"error":{"message":"Rate limited","type":"rate_limit","code":429}}"""),
                    SSE_DONE,
                )
            )
        }
        try {
            val outcome = run(server)
            val e = assertIs<HttpException>(outcome.error)
            assertEquals(429, e.statusCode())
            assertEquals(1, server.count, "a mid-stream error chunk must not be retried")
            assertEquals(0, outcome.store.storeCount, "a failed run must never store")
        } finally {
            server.close()
        }
    }

    @Test
    fun `http 401 fails the run without storing`() {
        // the HTTP error status must survive langchain4j's exception mapping
        // as an HttpException walkable in the cause chain
        val server = MockSseServer { MockSseResponse(401, emptyList()) }
        try {
            // a chat with existing history: a failed run must leave it untouched
            val seed = listOf(ChatMessage(ChatMessageRole.User, listOf(ChatMessagePart.Text("old"))))
            val store = InMemoryChatStore(seed)
            val outcome = run(server, store = store)

            val status = generateSequence(assertNotNull(outcome.error)) { it.cause }
                .filterIsInstance<HttpException>()
                .map { it.statusCode() }
                .firstOrNull()
            assertEquals(401, status)
            assertEquals(1, server.count, "a permanent error must not be retried")
            assertEquals(0, outcome.store.storeCount, "a failed run must never store")
            assertEquals(seed, outcome.store.stored, "history stays at the last good state")
        } finally {
            server.close()
        }
    }

    @Test
    fun `truncated stream without finish reason is retried then succeeds`() {
        // langchain4j silently accepts a clean EOF without finish_reason;
        // the turn loop must detect it itself and retry (the only transient
        // bucket left after the executor refactor)
        val server = MockSseServer { attempt ->
            if (attempt == 1) {
                MockSseResponse(
                    200,
                    listOf(
                        sseEvent(sseChunk(delta = """{"content":"partial"}""")),
                        SSE_DONE,
                    )
                )
            } else {
                MockSseResponse(200, stopStream())
            }
        }
        try {
            val outcome = run(server)
            assertNull(outcome.error)
            assertEquals(1, outcome.callback.errors.size, "one retry expected")
            assertNotNull(outcome.store.stored)
        } finally {
            server.close()
        }
    }

    @Test
    fun `empty response with a named reason fails fast without storing`() {
        val server = MockSseServer {
            MockSseResponse(200, listOf(sseEvent(sseChunk(finishReason = "content_filter")), SSE_DONE))
        }
        try {
            val outcome = run(server)
            val e = assertIs<IllegalStateException>(outcome.error)
            assertTrue(e.message!!.contains("finish_reason"), "error should name the finish reason: ${e.message}")
            assertEquals(1, server.count, "a named empty reason must not be retried")
            assertEquals(0, outcome.store.storeCount, "a failed run must never store")
        } finally {
            server.close()
        }
    }

    @Test
    fun `length below the threshold fails fast with output exhaustion`() {
        val server = MockSseServer {
            MockSseResponse(
                200,
                listOf(
                    sseEvent(
                        sseChunk(
                            finishReason = "length",
                            usage = """{"prompt_tokens":20,"completion_tokens":0,"total_tokens":20}""",
                        )
                    ),
                    SSE_DONE,
                )
            )
        }
        try {
            val outcome = run(server)
            val e = assertIs<IllegalStateException>(outcome.error)
            assertTrue(e.message!!.contains("output budget"), "error should explain the failure: ${e.message}")
            assertEquals(1, server.count)
            assertEquals(0, outcome.store.storeCount, "a failed run must never store")
        } finally {
            server.close()
        }
    }

    @Test
    fun `length with partial text also fails the run`() {
        // a response that hit the output budget is never accepted, even with
        // partial text: a truncated answer is not worth storing (a chat must
        // end with a clean stop, see ChatCodec.validateChat)
        val server = MockSseServer {
            MockSseResponse(
                200,
                listOf(
                    sseEvent(sseChunk(delta = """{"content":"partial answer"}""")),
                    sseEvent(
                        sseChunk(
                            finishReason = "length",
                            usage = """{"prompt_tokens":20,"completion_tokens":16,"total_tokens":36}""",
                        )
                    ),
                    SSE_DONE,
                )
            )
        }
        try {
            val outcome = run(server)
            assertIs<IllegalStateException>(outcome.error)
            assertEquals(0, outcome.store.storeCount, "a failed run must never store")
        } finally {
            server.close()
        }
    }

    @Test
    fun `capability violation fails before any HTTP request`() {
        val server = MockSseServer { MockSseResponse(200, stopStream()) }
        try {
            // an image with a text-only model: the check must fail up front
            val outcome = run(
                server,
                modelId = "bifrost/cerebras/gpt-oss-120b",
                userParts = listOf(
                    ChatMessagePart.Text("look"),
                    ChatMessagePart.Attachment(
                        kind = AttachmentKind.Image,
                        content = AttachmentContent.Base64("AAAA"),
                        mimeType = "image/png",
                    ),
                ),
            )
            assertIs<ModelCapabilityException>(outcome.error)
            assertEquals(0, server.count, "no LLM request must be made")
            assertEquals(0, outcome.store.storeCount, "a failed run must never store")
        } finally {
            server.close()
        }
    }

    @Test
    fun `image with a vision model passes the capability check`() {
        val server = MockSseServer { MockSseResponse(200, stopStream()) }
        try {
            val outcome = run(
                server,
                modelId = "bifrost/cerebras/gemma-4-31b",
                userParts = listOf(
                    ChatMessagePart.Attachment(
                        kind = AttachmentKind.Image,
                        content = AttachmentContent.Base64("AAAA"),
                        mimeType = "image/png",
                    ),
                ),
            )
            assertNull(outcome.error)
            assertNotNull(outcome.store.stored)
        } finally {
            server.close()
        }
    }

    @Test
    fun `tool call round executes the empty registry and completes the next round`() {
        val toolCallDelta1 = """{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"flag","arguments":""}}]}"""
        val toolCallDelta2 = """{"tool_calls":[{"index":0,"function":{"arguments":"{}"}}]}"""
        val server = MockSseServer { attempt ->
            if (attempt == 1) {
                MockSseResponse(
                    200,
                    listOf(
                        sseEvent(sseChunk(delta = toolCallDelta1)),
                        sseEvent(sseChunk(delta = toolCallDelta2)),
                        sseEvent(sseChunk(finishReason = "tool_calls")),
                        SSE_DONE,
                    )
                )
            } else {
                MockSseResponse(200, stopStream())
            }
        }
        try {
            val outcome = run(server)
            assertNull(outcome.error)

            // the tool call was streamed to the client and the empty registry
            // answered it with an explicit error result
            assertEquals(listOf("flag" to "{}"), outcome.callback.toolCalls)
            val toolResult = outcome.callback.toolResults.single()
            assertEquals("call_1", toolResult.id)
            assertEquals("flag", toolResult.tool)
            assertTrue(toolResult.isError, "empty registry must answer with an error result")

            // stored history: user, assistant(tool_call), tool(result), assistant(answer)
            val stored = assertNotNull(outcome.store.stored)
            assertEquals(
                listOf(ChatMessageRole.System, ChatMessageRole.User, ChatMessageRole.Assistant, ChatMessageRole.ToolResult, ChatMessageRole.Assistant),
                stored.map { it.role },
            )
            assertEquals(
                listOf(ChatMessagePart.ToolCall(id = "call_1", tool = "flag", args = "{}")),
                stored[2].parts,
            )
            assertEquals("tool_calls", stored[2].finishReason)
            val storedResult = assertIs<ChatMessagePart.ToolResult>(stored[3].parts.single())
            assertEquals("call_1", storedResult.id)
            assertEquals(true, storedResult.isError)
            assertEquals(listOf(ChatMessagePart.Text("ok")), stored[4].parts)
        } finally {
            server.close()
        }
    }

    @Test
    fun `id-less streamed tool call gets a generated id matching its result`() {
        // gateways that stream tool_calls without id fields: langchain4j
        // yields a blank id on the final ChatResponse's requests, so
        // withGeneratedToolCallIds (inside Lc4jStreamingExecutor) must give
        // the call a stable id — otherwise the stored history carries a
        // tool_call_id that never matches, and strict providers reject every
        // later run of the chat with a 400
        val toolCall1 = """{"tool_calls":[{"index":0,"type":"function","function":{"name":"flag","arguments":"{\"fl"}}]}"""
        val toolCall2 = """{"tool_calls":[{"index":0,"function":{"arguments":"ag\":true}"}}]}"""
        val server = MockSseServer { attempt ->
            if (attempt == 1) {
                MockSseResponse(
                    200,
                    listOf(
                        sseEvent(sseChunk(delta = toolCall1)),
                        sseEvent(sseChunk(delta = toolCall2)),
                        sseEvent(sseChunk(finishReason = "tool_calls")),
                        SSE_DONE,
                    )
                )
            } else {
                MockSseResponse(200, stopStream())
            }
        }
        try {
            val outcome = run(server)
            assertNull(outcome.error)

            val stored = assertNotNull(outcome.store.stored)
            val call = assertIs<ChatMessagePart.ToolCall>(stored[2].parts.single())
            assertTrue(call.id.startsWith("call_"), "Expected a generated id, got ${call.id}")
            assertEquals("flag", call.tool)
            assertEquals("""{"flag":true}""", call.args)
            val result = assertIs<ChatMessagePart.ToolResult>(stored[3].parts.single())
            assertEquals(call.id, result.id, "the tool result must reference the generated id")
        } finally {
            server.close()
        }
    }

    @Test
    fun `tool calls in one round execute in parallel`() {
        val toolCallA1 = """{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"tool_a","arguments":""}}]}"""
        val toolCallA2 = """{"tool_calls":[{"index":0,"function":{"arguments":"{}"}}]}"""
        val toolCallB1 = """{"tool_calls":[{"index":1,"id":"call_2","type":"function","function":{"name":"tool_b","arguments":""}}]}"""
        val toolCallB2 = """{"tool_calls":[{"index":1,"function":{"arguments":"{}"}}]}"""
        val server = MockSseServer { attempt ->
            if (attempt == 1) {
                MockSseResponse(
                    200,
                    listOf(
                        // index-sequential chunk order: openai4j's streaming
                        // tool-call builder flushes a call when the index
                        // changes, so interleaved indexes would corrupt it
                        sseEvent(sseChunk(delta = toolCallA1)),
                        sseEvent(sseChunk(delta = toolCallA2)),
                        sseEvent(sseChunk(delta = toolCallB1)),
                        sseEvent(sseChunk(delta = toolCallB2)),
                        sseEvent(sseChunk(finishReason = "tool_calls")),
                        SSE_DONE,
                    )
                )
            } else {
                MockSseResponse(200, stopStream())
            }
        }
        val tools = RendezvousToolProvider(expectedCalls = 2)
        try {
            val outcome = run(server, toolProvider = tools)
            assertNull(outcome.error)

            // the rendezvous only releases when every call of the round is in
            // flight: parallel execution completes immediately, a sequential
            // one would time out and fail the maxInFlight assertion
            assertEquals(2, tools.maxInFlight.get())

            // both tool calls streamed, results reported in request order
            assertEquals(
                listOf("tool_a" to "{}", "tool_b" to "{}"),
                outcome.callback.toolCalls,
            )
            assertEquals(
                listOf("tool_a" to "result", "tool_b" to "result"),
                outcome.callback.toolResults.map { it.tool to it.textContent() },
            )
            assertTrue(outcome.callback.toolResults.none { it.isError })

            // stored history: assistant(tool_call x2), tool(result), tool(result), assistant(answer)
            val stored = assertNotNull(outcome.store.stored)
            assertEquals(
                listOf(
                    ChatMessageRole.System, ChatMessageRole.User, ChatMessageRole.Assistant,
                    ChatMessageRole.ToolResult, ChatMessageRole.ToolResult, ChatMessageRole.Assistant,
                ),
                stored.map { it.role },
            )
            assertEquals(2, stored[2].parts.count { it is ChatMessagePart.ToolCall })
            assertEquals(1, stored[3].parts.count { it is ChatMessagePart.ToolResult })
            assertEquals(1, stored[4].parts.count { it is ChatMessagePart.ToolResult })
            assertEquals(listOf(ChatMessagePart.Text("ok")), stored[5].parts)
        } finally {
            server.close()
        }
    }

    @Test
    fun `tool call round executes through the MCP provider with paired history`() {
        // end-to-end: the loop advertises the MCP server's tools
        // (namespaced), executes the model's call against the server, and
        // stores the result as a tool message whose id pairs with the call
        val mcpServer = MockMcpServer(listOf(mcpAddTool()))
        val mcpProvider = McpToolProvider(
            listOf(McpServerConfig(namespace = "calc", type = McpTransportType.Http, url = mcpServer.baseUrl))
        )
        val toolCall1 = """{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"calc__add","arguments":""}}]}"""
        val toolCall2 = """{"tool_calls":[{"index":0,"function":{"arguments":"{\"a\":1,\"b\":2}"}}]}"""
        val server = MockSseServer { attempt ->
            if (attempt == 1) {
                MockSseResponse(
                    200,
                    listOf(
                        sseEvent(sseChunk(delta = toolCall1)),
                        sseEvent(sseChunk(delta = toolCall2)),
                        sseEvent(sseChunk(finishReason = "tool_calls")),
                        SSE_DONE,
                    )
                )
            } else {
                MockSseResponse(200, stopStream())
            }
        }
        try {
            val outcome = run(server, toolProvider = mcpProvider)
            assertNull(outcome.error)

            // the call streamed with the advertised name, the server executed
            // the raw tool with the parsed arguments
            assertEquals(listOf("calc__add" to """{"a":1,"b":2}"""), outcome.callback.toolCalls)
            val (rawName, args) = mcpServer.toolCalls.single()
            assertEquals("add", rawName)
            assertEquals("1", args["a"]?.jsonPrimitive?.let { it.content })

            // the result streamed and stored, paired with the call id
            val toolResult = outcome.callback.toolResults.single()
            assertEquals("call_1", toolResult.id)
            assertEquals("calc__add", toolResult.tool)
            assertEquals("1 + 2 = 3", toolResult.textContent())
            assertTrue(!toolResult.isError)

            val stored = assertNotNull(outcome.store.stored)
            val call = assertIs<ChatMessagePart.ToolCall>(stored[2].parts.single())
            assertEquals("call_1", call.id)
            assertEquals("calc__add", call.tool)
            val result = assertIs<ChatMessagePart.ToolResult>(stored[3].parts.single())
            assertEquals("call_1", result.id, "the stored result must pair with the stored call id")
            assertEquals(listOf(ChatMessagePart.Text("1 + 2 = 3")), result.parts)
            assertEquals(listOf(ChatMessagePart.Text("ok")), stored[4].parts)
        } finally {
            mcpProvider.close()
            mcpServer.close()
            server.close()
        }
    }

    @Test
    fun `tool result attachment fails the next round on a text-only model`() {
        // the capability check runs per round against the current prompt: a
        // tool returning an image mid-run must be caught before the next
        // round's request, not sent to a model that cannot see it
        val server = MockSseServer { attempt ->
            if (attempt == 1) {
                MockSseResponse(
                    200,
                    listOf(
                        sseEvent(sseChunk(delta = """{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"gen","arguments":""}}]}""")),
                        sseEvent(sseChunk(delta = """{"tool_calls":[{"index":0,"function":{"arguments":"{}"}}]}""")),
                        sseEvent(sseChunk(finishReason = "tool_calls")),
                        SSE_DONE,
                    )
                )
            } else {
                MockSseResponse(200, stopStream())
            }
        }
        val toolProvider = object : ToolProvider {
            override suspend fun specifications(): List<ToolSpecification> = emptyList()

            override suspend fun execute(request: ToolExecutionRequest): ChatMessagePart.ToolResult =
                ChatMessagePart.ToolResult(
                    id = request.id(),
                    tool = request.name(),
                    parts = listOf(
                        ChatMessagePart.Attachment(
                            kind = AttachmentKind.Image,
                            content = AttachmentContent.Base64("AAAA"),
                            mimeType = "image/png",
                        ),
                    ),
                )
        }
        try {
            val outcome = run(
                server,
                modelId = "bifrost/cerebras/gpt-oss-120b",
                toolProvider = toolProvider,
            )
            assertIs<ModelCapabilityException>(outcome.error)
            assertEquals(1, server.count, "the second round must not hit the LLM")
            assertEquals(0, outcome.store.storeCount, "a failed run must never store")
        } finally {
            server.close()
        }
    }

    @Test
    fun `existing history is loaded and extended`() {
        val server = MockSseServer { MockSseResponse(200, stopStream()) }
        try {
            val seed = listOf(
                ChatMessage(ChatMessageRole.System, listOf(ChatMessagePart.Text("old system"))),
                ChatMessage(ChatMessageRole.User, listOf(ChatMessagePart.Text("earlier"))),
                ChatMessage(
                    ChatMessageRole.Assistant,
                    listOf(ChatMessagePart.Text("earlier reply")),
                    finishReason = "stop",
                ),
            )
            val store = InMemoryChatStore(seed)
            val outcome = run(server, store = store)
            assertNull(outcome.error)
            val stored = assertNotNull(outcome.store.stored)
            assertEquals(5, stored.size, "seed + new user + new assistant")
            // the system prompt is refreshed in place at index 0
            assertEquals(listOf(ChatMessagePart.Text(systemPrompt)), stored[0].parts)
            assertEquals(listOf(ChatMessagePart.Text("earlier")), stored[1].parts)
            assertEquals(listOf(ChatMessagePart.Text("earlier reply")), stored[2].parts)
            assertEquals(listOf(ChatMessagePart.Text("hello")), stored[3].parts)
            assertEquals(listOf(ChatMessagePart.Text("ok")), stored[4].parts)
        } finally {
            server.close()
        }
    }
}

private class TurnOutcome(
    val error: Throwable?,
    val store: InMemoryChatStore,
    val callback: RecordingCallback,
)

private fun mcpAddTool(): MockTool = MockTool(
    name = "add",
    description = "Add two numbers a and b",
    handler = { args ->
        fun num(key: String) = args[key]?.jsonPrimitive?.let { it.content.toLongOrNull() } ?: 0L
        MockToolReply("${num("a")} + ${num("b")} = ${num("a") + num("b")}")
    },
)

private class InMemoryChatStore(seed: List<ChatMessage>? = null) : ChatStore {
    var stored: List<ChatMessage>? = seed
        private set
    var storeCount = 0
        private set
    var storedSstmVersion: String? = null
        private set

    override suspend fun load(chatId: String): ChatEntry =
        ChatEntry(stored ?: emptyList(), storedSstmVersion ?: "")

    override suspend fun store(chatId: String, chat: ChatEntry) {
        storeCount++
        stored = chat.chat
        storedSstmVersion = chat.sstmVersion
    }
}

/**
 * A fake [SstmService] whose version is settable, so tests can verify the
 * loop persists the version it saw and flips the `sstm-updated` flag when
 * the version changes between runs.
 */
private class InMemorySstmService(
    private val memories: List<ShortTermMemory> = emptyList(),
    var version: String = "test-version",
) : SstmService {
    override suspend fun listMemories(): MemoriesWithVersion =
        MemoriesWithVersion(memories, version)

    override suspend fun createMemory(content: String): ShortTermMemory =
        error("not used in loop tests")

    override suspend fun updateMemory(id: Long, content: String): ShortTermMemory? =
        error("not used in loop tests")

    override suspend fun deleteMemory(id: Long): Boolean =
        error("not used in loop tests")
}

/**
 * A tool provider that only returns from [execute] once every call of the
 * round is in flight: the count-down latch opens when all [expectedCalls]
 * have arrived, so parallel execution completes immediately while a
 * sequential caller would block the IO thread for the full 5s timeout.
 * [maxInFlight] then distinguishes the two cases.
 */
private class RendezvousToolProvider(
    private val expectedCalls: Int,
) : ToolProvider {
    val maxInFlight = AtomicInteger(0)
    private val inFlight = AtomicInteger(0)
    private val arrived = CountDownLatch(expectedCalls)

    override suspend fun specifications(): List<ToolSpecification> = emptyList()

    override suspend fun execute(request: ToolExecutionRequest): ChatMessagePart.ToolResult =
        withContext(Dispatchers.IO) {
            val current = inFlight.incrementAndGet()
            maxInFlight.updateAndGet { maxOf(it, current) }
            arrived.countDown()
            arrived.await(5, TimeUnit.SECONDS)
            inFlight.decrementAndGet()
            ChatMessagePart.ToolResult(
                id = request.id(),
                tool = request.name(),
                parts = listOf(ChatMessagePart.Text("result")),
            )
        }
}

private class RecordingCallback : StreamingExecutionCallback {
    val texts = mutableListOf<String>()
    val thinkings = mutableListOf<String>()
    val toolCalls = mutableListOf<Pair<String, String>>()
    val toolResults = mutableListOf<ChatMessagePart.ToolResult>()
    val errors = mutableListOf<String>()

    override suspend fun onTextDelta(text: String) {
        texts += text
    }

    override suspend fun onReasoningDelta(text: String) {
        thinkings += text
    }

    override suspend fun onToolCall(name: String, args: String) {
        toolCalls += name to args
    }

    override suspend fun onToolResults(results: List<ChatMessagePart.ToolResult>) {
        toolResults += results
    }

    override suspend fun onStreamError(error: String) {
        errors += error
    }
}

private fun ChatMessagePart.ToolResult.textContent(): String =
    parts.filterIsInstance<ChatMessagePart.Text>().joinToString("") { it.text }
