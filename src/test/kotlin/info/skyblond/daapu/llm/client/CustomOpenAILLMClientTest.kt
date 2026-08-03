package info.skyblond.daapu.llm.client

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.openai.OpenAIChatParams
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.streaming.requireEndFrame
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
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

    private fun withClientAndServer(
        streamingBody: String,
        nonStreamingBody: String,
        block: (CustomOpenAILLMClient) -> Unit,
    ) {
        MockChatCompletionsServer(streamingBody, nonStreamingBody).use { server ->
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
            exchange.sendResponseHeaders(200, if (isStreaming) 0 else bytes.size.toLong())
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
