package info.skyblond.daapu.lc4j

import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.exception.HttpException
import info.skyblond.daapu.agent.lc4j.executor.StreamSignal
import info.skyblond.daapu.agent.lc4j.executor.findErrorChunk
import info.skyblond.daapu.agent.lc4j.executor.streamSignals
import info.skyblond.daapu.agent.lc4j.llm.ModelCatalog
import info.skyblond.daapu.agent.lc4j.provider.BifrostProvider
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The gateway-quirk matrix, re-expressed against the langchain4j stack
 * (issue #7): the old `CustomOpenAILLMClientTest` suite (682 lines, mock
 * `HttpServer`) documented every gateway quirk we know independent of any
 * framework — reasoning dialects, empty deltas, truncated streams, usage
 * chunks, mid-stream error chunks, HTTP status preservation. This is its
 * direct replacement, exercised end-to-end through the real wiring
 * ([ModelCatalog] → [toStreamingChatModel] → [streamSignals]) against a local
 * mock SSE endpoint.
 *
 * Parity verdict per quirk (the old suite's coverage in brackets):
 * - reasoning `reasoning_content` dialect streams as thinking: **covered by
 *   config** (`returnThinking(true)`, factory test + here).
 * - reasoning plain `delta.reasoning` dialect (bifrost/Cerebras): **covered
 *   by the `ReasoningRewriteHttpClient` decorator** (wired via the factory's
 *   `httpClientBuilder`) — pinned end-to-end here.
 * - reasoning `reasoning_details` structured dialect: **NOT supported** —
 *   consciously dropped. The rewrite decorator leaves it untouched and the
 *   stock parser ignores it, so thinking from that dialect is silently lost.
 *   No gateway in the current catalog emits it (bifrost streams plain
 *   `reasoning`, Novita `reasoning_content` — verified live in spike #1), so
 *   carrying a second decorator shape would be untested dead weight; if a
 *   gateway ever emits it, extend [info.skyblond.daapu.agent.lc4j.provider.client.ReasoningRewriteHttpClient] (the `spike
 *   #1` report anticipated this shape) and add a fixture here.
 * - id-less streamed tool calls: langchain4j keeps the gateway's (missing)
 *   id as `null` — it does NOT assign one. The project's sanitizer
 *   (`AiMessage.withGeneratedToolCallIds`, applied by the turn loop) is the
 *   replacement for `withGeneratedToolCallIds` (koog); the end-to-end
 *   guarantee lives in `ChatTurnLoopTest`'s id-less tool-call test.
 * - empty opening `content: ""` deltas: skipped out of the box (pinned here).
 * - missing `finish_reason` ⇒ truncated stream: langchain4j completes
 *   normally with `finishReason() == null` — no `requireEndFrame` equivalent;
 *   the turn loop implements truncation detection itself (pinned here at the
 *   model level and in `ChatTurnLoopTest`).
 * - `usage` chunks: hardcoded-on `include_usage` populates `TokenUsage`
 *   (pinned here and in the factory test).
 * - mid-stream `{"error": ...}` chunks: retained verbatim in
 *   `rawServerSentEvents()`; [findErrorChunk] extracts the numeric `code`
 *   (spike #2 finding; the loop maps it to [HttpException]).
 * - HTTP error statuses: preserved as [HttpException] walkable in the
 *   exception cause chain (pinned here).
 * - tool-capability fail-fast (the old client threw when tools were passed to
 *   a model lacking `LLMCapability.Tools`): **NOT supported** — consciously
 *   dropped. langchain4j has no equivalent gate, and it is unexercisable
 *   today: every catalog model advertises `ToolCalls` and `EmptyToolProvider`
 *   advertises no tools. If #8 lands real tools with a non-tool model in the
 *   catalog, add the check back in the loop's pre-send step (like
 *   `checkPromptContentCapabilities`).
 * - non-streaming path (the old suite's `execute`/`executeMultipleChoices`
 *   half): N/A — the runtime is streaming-only (`OpenAiStreamingChatModel`);
 *   nothing in the new stack calls a non-streaming completion.
 */
class GatewayQuirkParityTest {

    private fun model(server: MockSseServer) = ModelCatalog(
        BifrostProvider(
            id = "bifrost",
            baseUrl = "http://127.0.0.1:${server.port}/v1",
            apiKey = "test-key",
        )
    )
        .findModel("bifrost/cerebras/gemma-4-31b")!!
        .toStreamingChatModel("high")

    private fun roundTrip(server: MockSseServer): List<StreamSignal> =
        runBlocking { model(server).streamSignals(listOf(UserMessage.from("hi"))).toList() }

    private fun completed(signals: List<StreamSignal>) =
        signals.filterIsInstance<StreamSignal.Completed>().single()

    @Test
    fun `streaming keeps reasoning_content`() {
        val server = MockSseServer {
            MockSseResponse(
                200,
                listOf(
                    sseEvent(sseChunk(delta = """{"reasoning_content":"Novita "}""")),
                    sseEvent(sseChunk(delta = """{"reasoning_content":"style"}""")),
                    sseEvent(sseChunk(delta = """{"content":"Hello"}""")),
                    sseEvent(sseChunk(finishReason = "stop")),
                    SSE_DONE,
                )
            )
        }
        try {
            val signals = roundTrip(server)
            assertEquals(
                listOf("Novita ", "style"),
                signals.filterIsInstance<StreamSignal.ThinkingDelta>().map { it.text },
            )
            assertEquals(
                listOf("Hello"),
                signals.filterIsInstance<StreamSignal.TextDelta>().map { it.text },
            )
            assertEquals("Novita style", completed(signals).response.aiMessage().thinking())
        } finally {
            server.close()
        }
    }

    @Test
    fun `streaming keeps plain reasoning via the rewrite decorator`() {
        // the bifrost/Cerebras dialect (delta.reasoning, plain text) would be
        // silently dropped by the stock parser; the factory-wired
        // ReasoningRewriteHttpClient normalizes it to reasoning_content, so
        // this pins the decorator end-to-end (spike #1's central gap)
        val server = MockSseServer {
            MockSseResponse(
                200,
                listOf(
                    sseEvent(sseChunk(delta = """{"reasoning":"Plain "}""")),
                    sseEvent(sseChunk(delta = """{"reasoning":"reasoning"}""")),
                    sseEvent(sseChunk(delta = """{"content":"Hello"}""")),
                    sseEvent(sseChunk(finishReason = "stop")),
                    SSE_DONE,
                )
            )
        }
        try {
            val signals = roundTrip(server)
            assertEquals(
                listOf("Plain ", "reasoning"),
                signals.filterIsInstance<StreamSignal.ThinkingDelta>().map { it.text },
            )
            assertEquals("Plain reasoning", completed(signals).response.aiMessage().thinking())
        } finally {
            server.close()
        }
    }

    @Test
    fun `streaming keeps reasoning_content as thinking after the rewrite`() {
        // a stream mixing both dialects (e.g. reasoning then content) must not
        // double-count or corrupt the final message
        val server = MockSseServer {
            MockSseResponse(
                200,
                listOf(
                    sseEvent(sseChunk(delta = """{"reasoning":"think "}""")),
                    sseEvent(sseChunk(delta = """{"reasoning_content":"more"}""")),
                    sseEvent(sseChunk(delta = """{"content":"answer"}""")),
                    sseEvent(sseChunk(finishReason = "stop")),
                    SSE_DONE,
                )
            )
        }
        try {
            val signals = roundTrip(server)
            assertEquals(
                listOf("think ", "more"),
                signals.filterIsInstance<StreamSignal.ThinkingDelta>().map { it.text },
            )
            assertEquals("think more", completed(signals).response.aiMessage().thinking())
        } finally {
            server.close()
        }
    }

    @Test
    fun `streaming skips empty content deltas`() {
        // OpenAI-style streams open with {"delta":{"role":"assistant","content":""}};
        // langchain4j skips empty deltas (no fabricated empty text part)
        val server = MockSseServer {
            MockSseResponse(
                200,
                listOf(
                    sseEvent(sseChunk(delta = """{"role":"assistant","content":""}""")),
                    sseEvent(sseChunk(delta = """{"content":"Hello"}""")),
                    sseEvent(sseChunk(finishReason = "stop")),
                    SSE_DONE,
                )
            )
        }
        try {
            val signals = roundTrip(server)
            assertEquals(
                listOf("Hello"),
                signals.filterIsInstance<StreamSignal.TextDelta>().map { it.text },
            )
            assertEquals("Hello", completed(signals).response.aiMessage().text())
        } finally {
            server.close()
        }
    }

    @Test
    fun `streaming assembles a multi-chunk tool call without an id`() {
        // real gateways split tool_call arguments across chunks and send
        // index-only deltas after the first one; langchain4j assembles them
        // into one complete call. The id is NOT assigned by langchain4j — it
        // stays null here and is generated later by withGeneratedToolCallIds
        // in the turn loop (pinned end-to-end in ChatTurnLoopTest).
        val server = MockSseServer {
            MockSseResponse(
                200,
                listOf(
                    sseEvent(sseChunk(delta = """{"tool_calls":[{"index":0,"type":"function","function":{"name":"flag","arguments":"{\"fl"}}]}""")),
                    sseEvent(sseChunk(delta = """{"tool_calls":[{"index":0,"function":{"arguments":"ag\":true}"}}]}""")),
                    sseEvent(sseChunk(finishReason = "tool_calls")),
                    SSE_DONE,
                )
            )
        }
        try {
            val signals = roundTrip(server)
            val done = signals.filterIsInstance<StreamSignal.ToolCallDone>()
            assertEquals(1, done.size)
            assertEquals("flag", done[0].name)
            assertEquals("""{"flag":true}""", done[0].args)
        } finally {
            server.close()
        }
    }

    @Test
    fun `streaming assembles two sequential tool calls`() {
        val server = MockSseServer {
            MockSseResponse(
                200,
                listOf(
                    sseEvent(sseChunk(delta = """{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"first","arguments":"{\"a\":"}}]}""")),
                    sseEvent(sseChunk(delta = """{"tool_calls":[{"index":0,"function":{"arguments":"1}"}}]}""")),
                    sseEvent(sseChunk(delta = """{"tool_calls":[{"index":1,"id":"call_2","type":"function","function":{"name":"second","arguments":"{}"}}]}""")),
                    sseEvent(sseChunk(finishReason = "tool_calls")),
                    SSE_DONE,
                )
            )
        }
        try {
            val signals = roundTrip(server)
            val done = signals.filterIsInstance<StreamSignal.ToolCallDone>()
            assertEquals(2, done.size)
            assertEquals("first" to """{"a":1}""", done[0].name to done[0].args)
            assertEquals("second" to "{}", done[1].name to done[1].args)
        } finally {
            server.close()
        }
    }

    @Test
    fun `streaming usage chunk populates token usage`() {
        val server = MockSseServer {
            MockSseResponse(
                200,
                listOf(
                    sseEvent(sseChunk(delta = """{"content":"Hello"}""")),
                    sseEvent(sseChunk(usage = """{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}""")),
                    sseEvent(sseChunk(finishReason = "stop")),
                    SSE_DONE,
                )
            )
        }
        try {
            val signals = roundTrip(server)
            val usage = completed(signals).response.tokenUsage()
            assertEquals(10, usage?.inputTokenCount())
            assertEquals(5, usage?.outputTokenCount())
            assertEquals(15, usage?.totalTokenCount())
        } finally {
            server.close()
        }
    }

    @Test
    fun `streaming without finish_reason completes with finishReason null`() {
        // a dropped connection can surface as a normal flow completion;
        // langchain4j has no requireEndFrame equivalent, so the missing
        // finish_reason (truncation) must be detected by the turn loop
        val server = MockSseServer {
            MockSseResponse(
                200,
                listOf(
                    sseEvent(sseChunk(delta = """{"content":"Hello"}""")),
                    sseEvent(sseChunk(delta = """{"content":" world"}""")),
                    SSE_DONE,
                )
            )
        }
        try {
            val signals = roundTrip(server)
            val response = completed(signals).response
            assertNull(response.finishReason(), "a truncated stream must not carry a finish reason")
            assertEquals("Hello world", response.aiMessage().text())
        } finally {
            server.close()
        }
    }

    @Test
    fun `mid-stream error chunk with a numeric code is retained and extractable`() {
        // OpenRouter-style permanent failure: the chunk is kept verbatim in
        // the response metadata (rawServerSentEvents) and findErrorChunk
        // extracts the numeric code for the retry policy
        val server = MockSseServer {
            MockSseResponse(
                200,
                listOf(
                    sseEvent(sseChunk(delta = """{"content":"Hello"}""")),
                    sseEvent("""{"error":{"message":"Content policy violation","type":"moderation","code":403}}"""),
                    SSE_DONE,
                )
            )
        }
        try {
            val signals = roundTrip(server)
            val response = completed(signals).response
            assertNull(response.finishReason(), "the error chunk completes the stream without a finish reason")
            val (code, data) = response.findErrorChunk()
                ?: error("error chunk must be retained in the response metadata")
            assertEquals(403, code)
            assertTrue(data.contains("Content policy violation"), "raw chunk: $data")
        } finally {
            server.close()
        }
    }

    @Test
    fun `mid-stream error chunk with a transient code stays extractable`() {
        val server = MockSseServer {
            MockSseResponse(
                200,
                listOf(
                    sseEvent(sseChunk(delta = """{"content":"Hello"}""")),
                    sseEvent("""{"error":{"message":"Rate limited","type":"rate_limit","code":429}}"""),
                    SSE_DONE,
                )
            )
        }
        try {
            val signals = roundTrip(server)
            val (code, _) = completed(signals).response.findErrorChunk()
                ?: error("error chunk must be retained in the response metadata")
            assertEquals(429, code)
        } finally {
            server.close()
        }
    }

    @Test
    fun `mid-stream error chunk with a string code yields no numeric code`() {
        // OpenAI-style errors carry string codes (e.g. "content_policy_violation");
        // without a numeric code the policy treats them as transient, same as
        // an uncoded error chunk
        val server = MockSseServer {
            MockSseResponse(
                200,
                listOf(
                    sseEvent(sseChunk(delta = """{"content":"Hello"}""")),
                    sseEvent("""{"error":{"message":"Content policy violation","type":"invalid_request_error","code":"content_policy_violation"}}"""),
                    SSE_DONE,
                )
            )
        }
        try {
            val signals = roundTrip(server)
            val (code, _) = completed(signals).response.findErrorChunk()
                ?: error("error chunk must be retained in the response metadata")
            assertNull(code, "a string code must not be treated as a numeric one")
        } finally {
            server.close()
        }
    }

    @Test
    fun `http error status survives as HttpException in the cause chain`() {
        // the retry policy walks the cause chain for the first non-2xx
        // HttpException status: 429 must survive langchain4j's exception
        // mapping (RateLimitException etc.) as a walkable HttpException
        val server = MockSseServer {
            MockSseResponse(429, listOf(sseEvent("""{"error":{"message":"Rate limited"}}""")))
        }
        try {
            val signals = roundTrip(server)
            val failed = signals.filterIsInstance<StreamSignal.Failed>().single()
            val status = generateSequence(failed.error) { it.cause }
                .filterIsInstance<HttpException>()
                .map { it.statusCode() }
                .firstOrNull()
            assertEquals(429, status, "cause chain: ${failed.error}")
        } finally {
            server.close()
        }
    }
}
