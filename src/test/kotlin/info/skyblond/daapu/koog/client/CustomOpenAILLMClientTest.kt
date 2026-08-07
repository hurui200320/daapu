package info.skyblond.daapu.koog.client

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.http.client.KoogHttpClientException
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.openai.OpenAIChatParams
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.streaming.IncompleteStreamException
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.streaming.requireEndFrame
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/**
 * Verifies that [CustomOpenAILLMClient] extracts reasoning from the three
 * field shapes used by OpenAI-compatible gateways (`reasoning_details`,
 * `reasoning`, `reasoning_content`) on both the streaming and the
 * non-streaming path.
 *
 * A local [HttpServer] acts as the chat-completions endpoint; the response is
 * selected by the `stream` flag in the request body.
 */
class CustomOpenAILLMClientTest {

    private val model = LLModel(
        provider = LLMProvider.OpenAI,
        id = "test-model",
        capabilities = listOf(
            LLMCapability.Completion,
            LLMCapability.MultipleChoices,
        ),
    )

    private val testPrompt = prompt(
        id = "test",
        params = OpenAIChatParams(),
    ) {
        user("Hello")
    }

    // the test model deliberately lacks LLMCapability.Tools
    private val testTool = ToolDescriptor(name = "test_tool", description = "A tool for tests")

    @Test
    fun `non-streaming keeps reasoning_content`() {
        val body = chatCompletionBody(
            message = """{"role":"assistant","content":"Hello back","reasoning_content":"Think step by step"}"""
        )
        withClientAndServer(streamingBody = "", nonStreamingBody = body) { client ->
            val response = runBlocking { client.execute(testPrompt, model, emptyList()) }

            assertAssistant(response, text = listOf("Hello back"), reasoning = listOf("Think step by step"))
            assertEquals("stop", response.finishReason)
            assertEquals(10, response.metaInfo.inputTokensCount)
            assertEquals(5, response.metaInfo.outputTokensCount)
            assertEquals(15, response.metaInfo.totalTokensCount)
        }
    }

    @Test
    fun `non-streaming keeps reasoning`() {
        val body = chatCompletionBody(
            message = """{"role":"assistant","content":"Hello back","reasoning":"Plain reasoning text"}"""
        )
        withClientAndServer(streamingBody = "", nonStreamingBody = body) { client ->
            val response = runBlocking { client.execute(testPrompt, model, emptyList()) }

            assertAssistant(response, text = listOf("Hello back"), reasoning = listOf("Plain reasoning text"))
        }
    }

    @Test
    fun `non-streaming keeps reasoning_details`() {
        val body = chatCompletionBody(
            message = """{"role":"assistant","content":"Hello back","reasoning_details":[{"type":"reasoning.text","text":"First "},{"type":"reasoning.text","text":"second"}]}"""
        )
        withClientAndServer(streamingBody = "", nonStreamingBody = body) { client ->
            val response = runBlocking { client.execute(testPrompt, model, emptyList()) }

            assertAssistant(response, text = listOf("Hello back"), reasoning = listOf("First second"))
        }
    }

    @Test
    fun `non-streaming without reasoning has no reasoning part`() {
        val body = chatCompletionBody(
            message = """{"role":"assistant","content":"Hello back"}"""
        )
        withClientAndServer(streamingBody = "", nonStreamingBody = body) { client ->
            val response = runBlocking { client.execute(testPrompt, model, emptyList()) }

            assertAssistant(response, text = listOf("Hello back"))
        }
    }

    @Test
    fun `non-streaming parses array content`() {
        val body = chatCompletionBody(
            message = """{"role":"assistant","content":[{"type":"text","text":"Part one"},{"type":"text","text":"Part two"}]}"""
        )
        withClientAndServer(streamingBody = "", nonStreamingBody = body) { client ->
            val response = runBlocking { client.execute(testPrompt, model, emptyList()) }

            assertAssistant(response, text = listOf("Part one", "Part two"))
        }
    }

    @Test
    fun `non-streaming keeps reasoning and tool calls`() {
        val body = chatCompletionBody(
            message = """{"role":"assistant","content":null,"reasoning":"Figure it out","tool_calls":[{"id":"call_1","type":"function","function":{"name":"flag","arguments":"{\"flag\":true}"}}]}""",
            finishReason = "tool_calls",
        )
        withClientAndServer(streamingBody = "", nonStreamingBody = body) { client ->
            val response = runBlocking { client.execute(testPrompt, model, emptyList()) }

            assertAssistant(response, reasoning = listOf("Figure it out"))
            val toolCalls = response.parts.filterIsInstance<MessagePart.Tool.Call>()
            assertEquals(1, toolCalls.size)
            assertEquals("call_1", toolCalls[0].id)
            assertEquals("flag", toolCalls[0].tool)
            assertEquals(
                Json.parseToJsonElement("""{"flag":true}"""),
                Json.parseToJsonElement(toolCalls[0].args),
            )
            assertEquals("tool_calls", response.finishReason)
        }
    }

    @Test
    fun `executeMultipleChoices keeps reasoning in every choice`() {
        val message = """{"role":"assistant","content":"Choice answer","reasoning":"Choice thinking"}"""
        val body = chatCompletionBody(message = message, choiceCount = 2)
        withClientAndServer(streamingBody = "", nonStreamingBody = body) { client ->
            val responses = runBlocking { client.executeMultipleChoices(testPrompt, model, emptyList()) }

            assertEquals(2, responses.size)
            responses.forEach { response ->
                assertAssistant(response, text = listOf("Choice answer"), reasoning = listOf("Choice thinking"))
            }
        }
    }

    @Test
    fun `streaming keeps reasoning_details`() {
        val body = chatCompletionChunks(
            listOf(
                chunk("""{"reasoning_details":[{"type":"reasoning.text","text":"First "}]}"""),
                chunk("""{"reasoning_details":[{"type":"reasoning.text","text":"second"}]}"""),
                chunk("""{"content":"Hello"}"""),
                chunk("""{}""", finishReason = "stop"),
            )
        )
        withClientAndServer(streamingBody = body, nonStreamingBody = "") { client ->
            val frames = runBlocking {
                client.executeStreaming(testPrompt, model, emptyList()).requireEndFrame().toList()
            }

            assertEquals(
                listOf("First ", "second"),
                frames.filterIsInstance<StreamFrame.ReasoningDelta>().map { it.text },
            )
            assertEquals(listOf("Hello"), frames.filterIsInstance<StreamFrame.TextDelta>().map { it.text })
            assertIs<StreamFrame.End>(frames.last())
        }
    }

    @Test
    fun `streaming keeps reasoning`() {
        val body = chatCompletionChunks(
            listOf(
                chunk("""{"reasoning":"Plain "}"""),
                chunk("""{"reasoning":"reasoning"}"""),
                chunk("""{"content":"Hello"}"""),
                chunk("""{}""", finishReason = "stop"),
            )
        )
        withClientAndServer(streamingBody = body, nonStreamingBody = "") { client ->
            val frames = runBlocking {
                client.executeStreaming(testPrompt, model, emptyList()).requireEndFrame().toList()
            }

            assertEquals(
                listOf("Plain ", "reasoning"),
                frames.filterIsInstance<StreamFrame.ReasoningDelta>().map { it.text },
            )
        }
    }

    @Test
    fun `streaming keeps reasoning_content`() {
        val body = chatCompletionChunks(
            listOf(
                chunk("""{"reasoning_content":"Novita "}"""),
                chunk("""{"reasoning_content":"style"}"""),
                chunk("""{"content":"Hello"}"""),
                chunk("""{}""", finishReason = "stop"),
            )
        )
        withClientAndServer(streamingBody = body, nonStreamingBody = "") { client ->
            val frames = runBlocking {
                client.executeStreaming(testPrompt, model, emptyList()).requireEndFrame().toList()
            }

            assertEquals(
                listOf("Novita ", "style"),
                frames.filterIsInstance<StreamFrame.ReasoningDelta>().map { it.text },
            )
        }
    }

    @Test
    fun `streaming keeps tool calls`() {
        val body = chatCompletionChunks(
            listOf(
                chunk("""{"reasoning":"Think","content":"Use the tool"}"""),
                chunk(
                    """{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"flag","arguments":"{\"flag\":true}"}}]}"""
                ),
                chunk("""{}""", finishReason = "tool_calls"),
            )
        )
        withClientAndServer(streamingBody = body, nonStreamingBody = "") { client ->
            val frames = runBlocking {
                client.executeStreaming(testPrompt, model, emptyList()).requireEndFrame().toList()
            }

            val toolCalls = frames.filterIsInstance<StreamFrame.ToolCallDelta>()
            assertEquals(1, toolCalls.size)
            assertEquals("call_1", toolCalls[0].id)
            assertEquals("flag", toolCalls[0].name)
            assertEquals("""{"flag":true}""", toolCalls[0].content)
        }
    }

    @Test
    fun `streaming assembles a multi-chunk tool call without id`() {
        // real gateways split tool_call arguments across chunks and send
        // index-only deltas after the first one; koog's builder assembles
        // them into one ToolCallComplete (its comment references koog #2002),
        // and this client re-implements the chunk handling, so pin it
        val body = chatCompletionChunks(
            listOf(
                chunk("""{"tool_calls":[{"index":0,"type":"function","function":{"name":"flag","arguments":"{\"fl"}}]}"""),
                chunk("""{"tool_calls":[{"index":0,"function":{"arguments":"ag\":true}"}}]}"""),
                chunk("""{}""", finishReason = "tool_calls"),
            )
        )
        withClientAndServer(streamingBody = body, nonStreamingBody = "") { client ->
            val frames = runBlocking {
                client.executeStreaming(testPrompt, model, emptyList()).requireEndFrame().toList()
            }

            val completed = frames.filterIsInstance<StreamFrame.ToolCallComplete>()
            assertEquals(1, completed.size)
            // no id was streamed: it stays null here and is generated later
            // by withGeneratedToolCallIds (in Main's execution node)
            assertEquals(null, completed[0].id)
            assertEquals("flag", completed[0].name)
            assertEquals("""{"flag":true}""", completed[0].content)
        }
    }

    @Test
    fun `streaming assembles two sequential tool calls`() {
        val body = chatCompletionChunks(
            listOf(
                chunk("""{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"first","arguments":"{\"a\":"}}]}"""),
                chunk("""{"tool_calls":[{"index":0,"function":{"arguments":"1}"}}]}"""),
                chunk("""{"tool_calls":[{"index":1,"id":"call_2","type":"function","function":{"name":"second","arguments":"{}"}}]}"""),
                chunk("""{}""", finishReason = "tool_calls"),
            )
        )
        withClientAndServer(streamingBody = body, nonStreamingBody = "") { client ->
            val frames = runBlocking {
                client.executeStreaming(testPrompt, model, emptyList()).requireEndFrame().toList()
            }

            val completed = frames.filterIsInstance<StreamFrame.ToolCallComplete>()
            assertEquals(2, completed.size)
            assertEquals("call_1", completed[0].id)
            assertEquals("first", completed[0].name)
            assertEquals("""{"a":1}""", completed[0].content)
            assertEquals("call_2", completed[1].id)
            assertEquals("second", completed[1].name)
            assertEquals("{}", completed[1].content)
        }
    }

    @Test
    fun `streaming keeps reasoning_details with plain string entries`() {
        // some gateways emit reasoning_details as bare strings instead of
        // {"type":"reasoning.text","text":...} objects
        val body = chatCompletionChunks(
            listOf(
                chunk("""{"reasoning_details":["Plain ","strings"]}"""),
                chunk("""{"content":"Hello"}"""),
                chunk("""{}""", finishReason = "stop"),
            )
        )
        withClientAndServer(streamingBody = body, nonStreamingBody = "") { client ->
            val frames = runBlocking {
                client.executeStreaming(testPrompt, model, emptyList()).requireEndFrame().toList()
            }

            assertEquals(
                listOf("Plain strings"),
                frames.filterIsInstance<StreamFrame.ReasoningDelta>().map { it.text },
            )
        }
    }

    @Test
    fun `streaming skips empty content deltas`() {
        val body = chatCompletionChunks(
            listOf(
                // OpenAI-style streams open with an empty content chunk
                chunk("""{"role":"assistant","content":""}"""),
                chunk("""{"content":"Hello"}"""),
                chunk("""{}""", finishReason = "stop"),
            )
        )
        withClientAndServer(streamingBody = body, nonStreamingBody = "") { client ->
            val frames = runBlocking {
                client.executeStreaming(testPrompt, model, emptyList()).requireEndFrame().toList()
            }

            assertEquals(listOf("Hello"), frames.filterIsInstance<StreamFrame.TextDelta>().map { it.text })
            assertIs<StreamFrame.End>(frames.last())
        }
    }

    @Test
    fun `streaming without finish_reason do not emit end frame`() {
        // a dropped connection can surface as a normal flow completion; the
        // missing finish_reason means the stream is truncated and must not
        // be accepted as a complete response
        val body = chatCompletionChunks(
            listOf(
                chunk("""{"content":"Hello"}"""),
                chunk("""{"content":" world"}"""),
            )
        )
        withClientAndServer(streamingBody = body, nonStreamingBody = "") { client ->
            assertFailsWith<IncompleteStreamException> {
                runBlocking {
                    client.executeStreaming(testPrompt, model, emptyList())
                        .requireEndFrame().toList()
                }
            }
        }
    }

    @Test
    fun `execute with tools on tool-incapable model fails fast`() {
        val body = chatCompletionBody(
            message = """{"role":"assistant","content":"Hello back"}"""
        )
        withClientAndServer(streamingBody = "", nonStreamingBody = body) { client ->
            assertFailsWith<IllegalArgumentException> {
                runBlocking { client.execute(testPrompt, model, listOf(testTool)) }
            }
        }
    }

    @Test
    fun `streaming with tools on tool-incapable model fails fast`() {
        withClientAndServer(streamingBody = "", nonStreamingBody = "") { client ->
            // the check is eager: it throws before the returned flow is collected
            assertFailsWith<IllegalArgumentException> {
                client.executeStreaming(testPrompt, model, listOf(testTool))
            }
        }
    }

    @Test
    fun `streaming usage chunk populates End metaInfo`() {
        // with stream_options.include_usage, the usage arrives as a final
        // chunk with empty choices; Main's length-classification depends on
        // these counts, so a parsing regression would silently degrade it
        // to the fail-fast no-usage path
        val body = chatCompletionChunks(
            listOf(
                chunk("""{"content":"Hello"}"""),
                chunk("""{}""", finishReason = "stop"),
                """{"id":"chatcmpl-2","object":"chat.completion.chunk","created":1234,"model":"test-model","choices":[],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}""",
            )
        )
        withClientAndServer(streamingBody = body, nonStreamingBody = "") { client ->
            val frames = runBlocking {
                client.executeStreaming(testPrompt, model, emptyList()).requireEndFrame().toList()
            }

            val end = frames.filterIsInstance<StreamFrame.End>().single()
            assertEquals("stop", end.finishReason)
            assertEquals(10, end.metaInfo.inputTokensCount)
            assertEquals(5, end.metaInfo.outputTokensCount)
            assertEquals(15, end.metaInfo.totalTokensCount)
        }
    }

    @Test
    fun `streaming http error keeps KoogHttpClientException with status code`() {
        // Main's retry guard rethrows permanent 4xx but retries 429/5xx, so
        // the status code must survive the SSE-specific error path
        withClientAndServer(
            streamingBody = """{"error":{"message":"Rate limited"}}""",
            nonStreamingBody = "",
            statusCode = 429,
        ) { client ->
            val e = assertFailsWith<KoogHttpClientException> {
                runBlocking {
                    client.executeStreaming(testPrompt, model, emptyList()).requireEndFrame().toList()
                }
            }
            assertEquals(429, e.statusCode)
        }
    }

    @Test
    fun `streaming mid-stream error chunk throws instead of completing empty`() {
        val body = chatCompletionChunks(
            listOf(
                chunk("""{"content":"Hello"}"""),
                // gateways may deliver failures as a mid-stream SSE data chunk
                // instead of an HTTP error status
                """{"error":{"message":"upstream connection reset","type":"server_error"}}""",
            )
        )
        withClientAndServer(streamingBody = body, nonStreamingBody = "") { client ->
            // exceptions thrown while collecting the SSE flow surface at the
            // http client's emit() and get wrapped into KoogHttpClientException
            val e = assertFailsWith<KoogHttpClientException> {
                runBlocking {
                    client.executeStreaming(testPrompt, model, emptyList()).requireEndFrame().toList()
                }
            }
            assertTrue(e.message!!.contains("upstream connection reset"), "Unexpected message: ${e.message}")
            // Main's retry guard rethrows permanent 4xx; a mid-stream error
            // must NOT look like one, or it would never be retried
            assertTrue(
                e.statusCode == null || e.statusCode !in 400..499,
                "Mid-stream error must not carry a permanent 4xx status, got ${e.statusCode}"
            )
        }
    }

    // ktor's SSE plugin wraps exceptions from the session body in an
    // SSEClientException carrying the (successful) response, and koog re-wraps
    // that with its status — so the chain is KoogHttpClientException(200) →
    // SSEClientException → KoogHttpClientException(code). The 2xx is the HTTP
    // response status of the otherwise-successful stream, not an error code;
    // skip it like isRetryableStreamError does.
    private fun KoogHttpClientException.effectiveStatusCode(): Int? =
        generateSequence(this as Throwable) { it.cause }
            .filterIsInstance<KoogHttpClientException>()
            .mapNotNull { it.statusCode }
            .firstOrNull { it !in 200..299 }

    @Test
    fun `streaming mid-stream error chunk with a numeric code carries it through the wrapper`() {
        // OpenRouter-style permanent failure delivered as a mid-stream error
        // chunk with an HTTP-ish numeric `code` (e.g. a moderation rejection
        // mapped to 403): the code must survive koog's SSE re-wrap so the
        // retry policy can fail the run instead of retrying forever
        val body = chatCompletionChunks(
            listOf(
                chunk("""{"content":"Hello"}"""),
                """{"error":{"message":"Content policy violation","type":"moderation","code":403}}""",
            )
        )
        withClientAndServer(streamingBody = body, nonStreamingBody = "") { client ->
            val e = assertFailsWith<KoogHttpClientException> {
                runBlocking {
                    client.executeStreaming(testPrompt, model, emptyList()).requireEndFrame().toList()
                }
            }
            assertEquals(403, e.effectiveStatusCode())
        }
    }

    @Test
    fun `streaming mid-stream error chunk with a transient code stays retryable`() {
        val body = chatCompletionChunks(
            listOf(
                chunk("""{"content":"Hello"}"""),
                """{"error":{"message":"Rate limited","type":"rate_limit","code":429}}""",
            )
        )
        withClientAndServer(streamingBody = body, nonStreamingBody = "") { client ->
            val e = assertFailsWith<KoogHttpClientException> {
                runBlocking {
                    client.executeStreaming(testPrompt, model, emptyList()).requireEndFrame().toList()
                }
            }
            assertEquals(429, e.effectiveStatusCode())
        }
    }

    @Test
    fun `streaming mid-stream error chunk with a string code stays retryable`() {
        // OpenAI-style errors carry string codes (e.g. "content_policy_violation");
        // without a numeric code the policy treats them as transient, same as
        // an uncoded error chunk
        val body = chatCompletionChunks(
            listOf(
                chunk("""{"content":"Hello"}"""),
                """{"error":{"message":"Content policy violation","type":"invalid_request_error","code":"content_policy_violation"}}""",
            )
        )
        withClientAndServer(streamingBody = body, nonStreamingBody = "") { client ->
            val e = assertFailsWith<KoogHttpClientException> {
                runBlocking {
                    client.executeStreaming(testPrompt, model, emptyList()).requireEndFrame().toList()
                }
            }
            assertEquals(null, e.effectiveStatusCode())
        }
    }

    @Test
    fun `non-streaming generates a stable id for tool calls without one`() {
        val body = chatCompletionBody(
            message = """{"role":"assistant","content":null,"tool_calls":[{"type":"function","function":{"name":"flag","arguments":"{\"flag\":true}"}}]}""",
            finishReason = "tool_calls",
        )
        withClientAndServer(streamingBody = "", nonStreamingBody = body) { client ->
            val response = runBlocking { client.execute(testPrompt, model, emptyList()) }

            val toolCall = response.parts.filterIsInstance<MessagePart.Tool.Call>().single()
            assertTrue(toolCall.id?.startsWith("call_") == true, "Expected a generated id, got ${toolCall.id}")
            assertEquals("flag", toolCall.tool)
        }
    }

    @Test
    fun `non-streaming replaces a blank tool call id with a generated one`() {
        // some gateways send "id": ""; a blank id never matches a
        // tool_call_id, so it must be treated as missing
        val body = chatCompletionBody(
            message = """{"role":"assistant","content":null,"tool_calls":[{"id":"","type":"function","function":{"name":"flag","arguments":"{}"}}]}""",
            finishReason = "tool_calls",
        )
        withClientAndServer(streamingBody = "", nonStreamingBody = body) { client ->
            val response = runBlocking { client.execute(testPrompt, model, emptyList()) }

            val toolCall = response.parts.filterIsInstance<MessagePart.Tool.Call>().single()
            assertTrue(toolCall.id?.startsWith("call_") == true, "Expected a generated id, got ${toolCall.id}")
            assertEquals("flag", toolCall.tool)
        }
    }

    @Test
    fun `non-streaming http error keeps KoogHttpClientException with status code`() {
        withClientAndServer(
            streamingBody = "",
            nonStreamingBody = """{"error":{"message":"Invalid API key"}}""",
            statusCode = 401,
        ) { client ->
            val e = assertFailsWith<KoogHttpClientException> {
                runBlocking { client.execute(testPrompt, model, emptyList()) }
            }
            assertEquals(401, e.statusCode)
        }
    }

    private fun withClientAndServer(
        streamingBody: String,
        nonStreamingBody: String,
        statusCode: Int = 200,
        block: (CustomOpenAILLMClient) -> Unit,
    ) {
        MockChatCompletionsServer(streamingBody, nonStreamingBody, statusCode).use { server ->
            CustomOpenAILLMClient("test-key", OpenAIClientSettings(baseUrl = server.baseUrl)).use { client ->
                block(client)
            }
        }
    }

    private fun assertAssistant(
        response: Message.Assistant,
        text: List<String> = emptyList(),
        reasoning: List<String> = emptyList(),
    ) {
        assertEquals(
            text,
            response.parts.filterIsInstance<MessagePart.Text>().map { it.text },
        )
        assertEquals(
            reasoning,
            response.parts.filterIsInstance<MessagePart.Reasoning>().map { it.content.joinToString("") },
        )
        assertTrue(
            response.parts.all { it is MessagePart.Text || it is MessagePart.Reasoning || it is MessagePart.Tool.Call },
            "Unexpected response parts: ${response.parts}",
        )
    }

    private fun chunk(delta: String, finishReason: String? = null): String {
        val finish = finishReason?.let { """, "finish_reason": "$it"""" } ?: ""
        return """{"id":"chatcmpl-2","object":"chat.completion.chunk","created":1234,"model":"test-model","choices":[{"index":0,"delta":$delta$finish}]}"""
    }

    private fun chatCompletionChunks(chunks: List<String>): String =
        chunks.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n"

    private fun chatCompletionBody(
        message: String,
        finishReason: String = "stop",
        usage: String = """{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}""",
        choiceCount: Int = 1,
    ): String {
        val choices = (0 until choiceCount).joinToString(",\n") { index ->
            """{"index":$index,"finish_reason":"$finishReason","message":$message}"""
        }
        return """
            {
              "id": "chatcmpl-1",
              "object": "chat.completion",
              "created": 1234,
              "model": "test-model",
              "choices": [
                $choices
              ],
              "usage": $usage
            }
        """.trimIndent()
    }
}

/**
 * Serves canned chat-completions responses. The response is picked by the
 * `stream` flag in the request body so one server can serve both the
 * non-streaming and the streaming path.
 */
private class MockChatCompletionsServer(
    private val streamingBody: String,
    private val nonStreamingBody: String,
    private val statusCode: Int = 200,
) : AutoCloseable {
    private val executor = Executors.newCachedThreadPool()

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/v1/chat/completions") { exchange ->
            val requestBody = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            val isStreaming = requestBody.contains("\"stream\":true")
            val response = if (isStreaming) streamingBody else nonStreamingBody
            exchange.responseHeaders.add(
                "Content-Type",
                if (isStreaming) "text/event-stream" else "application/json",
            )
            val bytes = response.toByteArray(Charsets.UTF_8)
            // SSE responses use chunked encoding (length 0); the fixed-length
            // form is not reliably streamed by all HTTP client engines.
            exchange.sendResponseHeaders(statusCode, if (isStreaming) 0 else bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        setExecutor(this@MockChatCompletionsServer.executor)
        start()
    }

    val baseUrl: String = "http://127.0.0.1:${server.address.port}"

    override fun close() {
        server.stop(0)
        executor.shutdown()
    }
}
