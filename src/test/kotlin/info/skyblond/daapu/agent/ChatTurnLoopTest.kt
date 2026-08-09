package info.skyblond.daapu.agent

import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.exception.HttpException
import info.skyblond.daapu.history.AttachmentContent
import info.skyblond.daapu.history.AttachmentKind
import info.skyblond.daapu.history.HistoryMessage
import info.skyblond.daapu.history.HistoryPart
import info.skyblond.daapu.history.HistoryRole
import info.skyblond.daapu.history.HistoryStore
import info.skyblond.daapu.langchain4j.MockSseResponse
import info.skyblond.daapu.langchain4j.MockSseServer
import info.skyblond.daapu.langchain4j.ModelCatalog
import info.skyblond.daapu.langchain4j.SSE_DONE
import info.skyblond.daapu.langchain4j.toStreamingChatModel
import info.skyblond.daapu.langchain4j.sseChunk
import info.skyblond.daapu.langchain4j.sseEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the turn loop's behavior end-to-end against a mock SSE server: the
 * history load/store lifecycle (store only on success, injection stripped,
 * system prompt refreshed), the retry policy, the mid-stream error-chunk
 * scan, truncation detection, capability enforcement BEFORE any LLM request,
 * and the tool-round skeleton. These are the invariants that must survive the
 * koog → langchain4j migration.
 */
class ChatTurnLoopTest {

    private val systemPrompt = "You are Raven."

    private fun catalogModel(server: MockSseServer, id: String) =
        ModelCatalog("http://127.0.0.1:${server.port}/v1").findModel(id)!!

    /** Runs one turn; returns the outcome for assertions. */
    private fun run(
        server: MockSseServer,
        modelId: String = "cerebras/gemma-4-31b",
        userParts: List<HistoryPart> = listOf(HistoryPart.Text("hello")),
        store: InMemoryHistoryStore = InMemoryHistoryStore(),
        toolProvider: ToolProvider = EmptyToolProvider,
    ): TurnOutcome {
        val model = catalogModel(server, modelId)
        val callback = RecordingCallback()
        val error = runBlocking {
            runCatching {
                runChatTurn(
                    chatId = "chat-1",
                    model = model,
                    streamingChatModel = model.toStreamingChatModel("test-key"),
                    userParts = userParts,
                    systemPrompt = systemPrompt,
                    historyStore = store,
                    loadMemories = { emptyList() },
                    toolProvider = toolProvider,
                    callback = callback,
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
                listOf(HistoryRole.System, HistoryRole.User, HistoryRole.Assistant),
                stored.map { it.role },
            )
            assertEquals(listOf(HistoryPart.Text(systemPrompt)), stored[0].parts)
            assertEquals(listOf(HistoryPart.Text("hello")), stored[1].parts)
            assertEquals(listOf(HistoryPart.Text("ok")), stored[2].parts)
            assertEquals("stop", stored[2].finishReason)
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
                    HistoryPart.Reasoning(listOf("Let me think step by step")),
                    HistoryPart.Text("17 * 23 = 391"),
                ),
                assistant.parts,
            )
        } finally {
            server.close()
        }
    }

    @Test
    fun `http 500 is retried with a retry event then succeeds`() {
        val server = MockSseServer { attempt ->
            if (attempt == 1) MockSseResponse(500, emptyList())
            else MockSseResponse(200, stopStream())
        }
        try {
            val outcome = run(server)
            assertNull(outcome.error)
            assertEquals(1, outcome.callback.errors.size, "one retry expected")
            assertEquals(listOf("ok"), outcome.callback.texts, "deltas only from the successful round")
            assertNotNull(outcome.store.stored)
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
            val seed = listOf(HistoryMessage(HistoryRole.User, listOf(HistoryPart.Text("old"))))
            val store = InMemoryHistoryStore(seed)
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
    fun `mid-stream error chunk without a code is retried then succeeds`() {
        val server = MockSseServer { attempt ->
            if (attempt == 1) {
                MockSseResponse(
                    200,
                    listOf(
                        sseEvent(sseChunk(delta = """{"content":"partial"}""")),
                        sseEvent("""{"error":{"message":"upstream connection reset"}}"""),
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
            assertEquals(1, outcome.callback.errors.size)
            assertEquals(listOf("partial", "ok"), outcome.callback.texts)
            assertNotNull(outcome.store.stored)
        } finally {
            server.close()
        }
    }

    @Test
    fun `mid-stream error chunk with a transient numeric code is retried then succeeds`() {
        // a numeric code is only permanent when it maps to a 4xx (except
        // 408/429): a rate-limit 429 mid-stream must be retried, not fail
        val server = MockSseServer { attempt ->
            if (attempt == 1) {
                MockSseResponse(
                    200,
                    listOf(
                        sseEvent(sseChunk(delta = """{"content":"partial"}""")),
                        sseEvent("""{"error":{"message":"Rate limited","type":"rate_limit","code":429}}"""),
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
            assertEquals(1, outcome.callback.errors.size)
            assertEquals(listOf("partial", "ok"), outcome.callback.texts)
            assertNotNull(outcome.store.stored)
        } finally {
            server.close()
        }
    }

    @Test
    fun `http 401 fails the run without storing`() {
        // the HTTP error status must survive langchain4j's exception mapping
        // as an HttpException walkable in the cause chain, so the retry
        // policy fails the run instead of retrying a config error forever
        val server = MockSseServer { MockSseResponse(401, emptyList()) }
        try {
            // a chat with existing history: a failed run must leave it untouched
            val seed = listOf(HistoryMessage(HistoryRole.User, listOf(HistoryPart.Text("old"))))
            val store = InMemoryHistoryStore(seed)
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
        // the turn loop must detect it itself (spike #1 finding)
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
            assertEquals(1, outcome.callback.errors.size)
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
            assertIs<EmptyPermanentResponseException>(outcome.error)
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
            assertIs<OutputExhaustionException>(outcome.error)
            assertEquals(1, server.count)
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
                modelId = "cerebras/gpt-oss-120b",
                userParts = listOf(
                    HistoryPart.Text("look"),
                    HistoryPart.Attachment(
                        kind = AttachmentKind.Image,
                        content = AttachmentContent.Base64("AAAA"),
                        format = "png",
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
                modelId = "cerebras/gemma-4-31b",
                userParts = listOf(
                    HistoryPart.Attachment(
                        kind = AttachmentKind.Image,
                        content = AttachmentContent.Base64("AAAA"),
                        format = "png",
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
            assertEquals("flag", toolResult.name)
            assertTrue(toolResult.isError, "empty registry must answer with an error result")

            // stored history: user, assistant(tool_call), tool(result), assistant(answer)
            val stored = assertNotNull(outcome.store.stored)
            assertEquals(
                listOf(HistoryRole.System, HistoryRole.User, HistoryRole.Assistant, HistoryRole.Tool, HistoryRole.Assistant),
                stored.map { it.role },
            )
            assertEquals(
                listOf(HistoryPart.ToolCall(id = "call_1", tool = "flag", args = "{}")),
                stored[2].parts,
            )
            assertEquals("tool_calls", stored[2].finishReason)
            val storedResult = assertIs<HistoryPart.ToolResult>(stored[3].parts.single())
            assertEquals("call_1", storedResult.id)
            assertEquals(true, storedResult.isError)
            assertEquals(listOf(HistoryPart.Text("ok")), stored[4].parts)
        } finally {
            server.close()
        }
    }

    @Test
    fun `id-less streamed tool call gets a generated id matching its result`() {
        // gateways that stream tool_calls without id fields: langchain4j
        // yields a blank id — `null` on the streamed `ToolCallDone` signal,
        // `""` on the final ChatResponse's requests — so
        // withGeneratedToolCallIds (the replacement for the koog sanitizer)
        // must give the call a stable id — otherwise the stored history
        // carries a tool_call_id that never matches, and strict providers
        // reject every later run of the chat with a 400
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
            val call = assertIs<HistoryPart.ToolCall>(stored[2].parts.single())
            assertTrue(call.id.startsWith("call_"), "Expected a generated id, got ${call.id}")
            assertEquals("flag", call.tool)
            assertEquals("""{"flag":true}""", call.args)
            val result = assertIs<HistoryPart.ToolResult>(stored[3].parts.single())
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
                outcome.callback.toolResults.map { it.name to it.content },
            )
            assertTrue(outcome.callback.toolResults.none { it.isError })

            // stored history: assistant(tool_call x2), tool(result), tool(result), assistant(answer)
            val stored = assertNotNull(outcome.store.stored)
            assertEquals(
                listOf(
                    HistoryRole.System, HistoryRole.User, HistoryRole.Assistant,
                    HistoryRole.Tool, HistoryRole.Tool, HistoryRole.Assistant,
                ),
                stored.map { it.role },
            )
            assertEquals(2, stored[2].parts.count { it is HistoryPart.ToolCall })
            assertEquals(1, stored[3].parts.count { it is HistoryPart.ToolResult })
            assertEquals(1, stored[4].parts.count { it is HistoryPart.ToolResult })
            assertEquals(listOf(HistoryPart.Text("ok")), stored[5].parts)
        } finally {
            server.close()
        }
    }

    @Test
    fun `existing history is loaded and extended`() {
        val server = MockSseServer { MockSseResponse(200, stopStream()) }
        try {
            val seed = listOf(
                HistoryMessage(HistoryRole.System, listOf(HistoryPart.Text("old system"))),
                HistoryMessage(HistoryRole.User, listOf(HistoryPart.Text("earlier"))),
                HistoryMessage(
                    HistoryRole.Assistant,
                    listOf(HistoryPart.Text("earlier reply")),
                    finishReason = "stop",
                ),
            )
            val store = InMemoryHistoryStore(seed)
            val outcome = run(server, store = store)
            assertNull(outcome.error)
            val stored = assertNotNull(outcome.store.stored)
            assertEquals(5, stored.size, "seed + new user + new assistant")
            // the system prompt is refreshed in place at index 0
            assertEquals(listOf(HistoryPart.Text(systemPrompt)), stored[0].parts)
            assertEquals(listOf(HistoryPart.Text("earlier")), stored[1].parts)
            assertEquals(listOf(HistoryPart.Text("earlier reply")), stored[2].parts)
            assertEquals(listOf(HistoryPart.Text("hello")), stored[3].parts)
            assertEquals(listOf(HistoryPart.Text("ok")), stored[4].parts)
        } finally {
            server.close()
        }
    }
}

private class TurnOutcome(
    val error: Throwable?,
    val store: InMemoryHistoryStore,
    val callback: RecordingCallback,
)

private class InMemoryHistoryStore(seed: List<HistoryMessage>? = null) : HistoryStore {
    var stored: List<HistoryMessage>? = seed
        private set
    var storeCount = 0
        private set

    override suspend fun load(chatId: String): List<HistoryMessage> = stored ?: emptyList()

    override suspend fun store(chatId: String, messages: List<HistoryMessage>) {
        storeCount++
        stored = messages
    }
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

    override fun specifications(): List<ToolSpecification> = emptyList()

    override suspend fun execute(request: ToolExecutionRequest): ToolResultInfo =
        withContext(Dispatchers.IO) {
            val current = inFlight.incrementAndGet()
            maxInFlight.updateAndGet { maxOf(it, current) }
            arrived.countDown()
            arrived.await(5, TimeUnit.SECONDS)
            inFlight.decrementAndGet()
            ToolResultInfo(request.id(), request.name(), "result", isError = false)
        }
}

private class RecordingCallback : StreamExecutionCallback {
    val texts = mutableListOf<String>()
    val thinkings = mutableListOf<String>()
    val toolCalls = mutableListOf<Pair<String, String>>()
    val toolResults = mutableListOf<ToolResultInfo>()
    val errors = mutableListOf<Throwable>()

    override suspend fun onTextDelta(text: String) {
        texts += text
    }

    override suspend fun onReasoningDelta(text: String) {
        thinkings += text
    }

    override suspend fun onToolCall(name: String, args: String) {
        toolCalls += name to args
    }

    override suspend fun onToolResults(results: List<ToolResultInfo>) {
        toolResults += results
    }

    override suspend fun onStreamError(error: Throwable) {
        errors += error
    }
}
