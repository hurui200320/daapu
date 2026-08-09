package info.skyblond.daapu.langchain4j

import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.chat.response.ChatResponse
import dev.langchain4j.model.chat.response.PartialThinking
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler
import dev.langchain4j.model.openai.OpenAiStreamingChatModel
import dev.langchain4j.model.output.FinishReason
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the builder knobs of [toStreamingChatModel] against a local mock SSE
 * endpoint (trimmed port of the #1 spike harness): the per-model id, the
 * spike-verified `reasoning_effort`/`include_usage` request fields, and the
 * thinking round-trip behavior (`returnThinking`/`sendThinking`).
 *
 * These are the spike findings that must not silently regress when #6 wires
 * the factory into the turn loop, so they run offline without any gateway.
 */
class StreamingChatModelFactoryTest {

    @Test
    fun `request body carries model id, reasoning_effort and include_usage`() {
        val server = MockSseServer { stopStream() }
        try {
            val catalog = ModelCatalog("http://127.0.0.1:${server.port}/v1")
            for (model in catalog.models) {
                val rec = chat(model.toStreamingChatModel("test-key"), listOf(UserMessage.from("hi")))
                assertTrue(rec.await(15), "timed out for ${model.id}")
                assertEquals(FinishReason.STOP, rec.complete?.finishReason(), "for ${model.id}")
                val body = server.lastRequest() ?: error("no request captured for ${model.id}")
                val compact = body.replace(Regex("""\s+"""), "")
                assertTrue(compact.contains("\"model\":\"${model.id}\""), "missing model id in body: $compact")
                assertTrue(
                    compact.contains("\"reasoning_effort\":\"high\""),
                    "missing reasoning_effort in body: $compact",
                )
                assertTrue(
                    compact.contains("\"stream_options\":{\"include_usage\":true}"),
                    "missing include_usage in body: $compact",
                )
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun `reasoning deltas land in onPartialThinking and the final message`() {
        val server = MockSseServer {
            listOf(
                sseEvent(chunk(delta = """{"reasoning_content":"Let me think"}""")),
                sseEvent(chunk(delta = """{"reasoning_content":" step by step"}""")),
                sseEvent(chunk(delta = """{"content":"17 * 23 = 391"}""")),
                sseEvent(chunk(finishReason = "stop")),
                DONE,
            )
        }
        try {
            val model = catalogModel(server, "cerebras/gpt-oss-120b")
            val rec = chat(model, listOf(UserMessage.from("17 * 23?")))
            assertTrue(rec.await(15))
            assertEquals(listOf("Let me think", " step by step"), rec.thinkingPartials)
            assertEquals("Let me think step by step", rec.complete?.aiMessage()?.thinking())
            assertEquals("17 * 23 = 391", rec.complete?.aiMessage()?.text())
            assertEquals(FinishReason.STOP, rec.complete?.finishReason())
        } finally {
            server.close()
        }
    }

    @Test
    fun `sendThinking round-trips stored thinking as reasoning_content`() {
        val server = MockSseServer { stopStream() }
        try {
            val model = catalogModel(server, "cerebras/gpt-oss-120b")
            val history = listOf<ChatMessage>(
                UserMessage.from("first question"),
                AiMessage.builder().text("first answer").thinking("first thinking").build(),
                UserMessage.from("second question"),
            )
            val rec = chat(model, history)
            assertTrue(rec.await(15))
            val body = server.lastRequest() ?: error("no request captured")
            assertTrue(
                Regex(""""reasoning_content"\s*:\s*"first thinking"""").containsMatchIn(body),
                "missing reasoning_content in body: $body",
            )
        } finally {
            server.close()
        }
    }

    @Test
    fun `usage chunk populates token usage`() {
        val server = MockSseServer {
            listOf(
                sseEvent(chunk(delta = """{"content":"hi"}""")),
                sseEvent(chunk(usage = """{"prompt_tokens":12,"completion_tokens":5,"total_tokens":17}""")),
                sseEvent(chunk(finishReason = "stop")),
                DONE,
            )
        }
        try {
            val model = catalogModel(server, "cerebras/gemma-4-31b")
            val rec = chat(model, listOf(UserMessage.from("hi")))
            assertTrue(rec.await(15))
            val usage = rec.complete?.tokenUsage()
            assertEquals(12, usage?.inputTokenCount())
            assertEquals(5, usage?.outputTokenCount())
            assertEquals(17, usage?.totalTokenCount())
        } finally {
            server.close()
        }
    }

    @Test
    fun `a model without reasoning capability gets no reasoning knobs`() {
        val server = MockSseServer {
            listOf(
                sseEvent(chunk(delta = """{"reasoning_content":"secret thinking"}""")),
                sseEvent(chunk(delta = """{"content":"answer"}""")),
                sseEvent(chunk(finishReason = "stop")),
                DONE,
            )
        }
        try {
            val metadata = ModelMetadata(
                provider = ModelProvider.Cerebras,
                baseUrl = "http://127.0.0.1:${server.port}/v1",
                id = "test/no-reasoning",
                contextLength = 1000,
                maxOutputTokens = 500,
                capabilities = setOf(ModelCapability.ToolCalls),
            )
            val rec = chat(metadata.toStreamingChatModel("test-key"), listOf(UserMessage.from("hi")))
            assertTrue(rec.await(15))
            // returnThinking=false: the reasoning delta must be ignored
            assertEquals(0, rec.thinkingPartials.size)
            assertEquals(0, rec.blankPartials, "dropped reasoning deltas must not yield blank text partials")
            assertNull(rec.complete?.aiMessage()?.thinking())
            assertEquals("answer", rec.complete?.aiMessage()?.text())
            // no reasoning_effort injected either
            val body = server.lastRequest() ?: error("no request captured")
            assertFalse(body.contains("reasoning_effort"), "unexpected reasoning_effort in body: $body")
        } finally {
            server.close()
        }
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    private fun catalogModel(server: MockSseServer, id: String): OpenAiStreamingChatModel =
        ModelCatalog("http://127.0.0.1:${server.port}/v1")
            .findModel(id)!!
            .toStreamingChatModel("test-key")

    private fun stopStream() = listOf(
        sseEvent(chunk(delta = """{"content":"ok"}""")),
        sseEvent(chunk(finishReason = "stop")),
        DONE,
    )

    private fun chat(model: OpenAiStreamingChatModel, messages: List<ChatMessage>): Recorder {
        val request = ChatRequest.builder().messages(messages).build()
        val recorder = Recorder()
        model.chat(request, recorder)
        return recorder
    }
}

private class Recorder : StreamingChatResponseHandler {
    val partials = mutableListOf<String>()
    val thinkingPartials = mutableListOf<String>()
    var blankPartials = 0
    var complete: ChatResponse? = null
    var error: Throwable? = null
    var timedOut = false
    private val done = CountDownLatch(1)

    override fun onPartialResponse(text: String) {
        synchronized(this) {
            if (text.isEmpty() || text.isBlank()) blankPartials++
            partials += text
        }
    }

    override fun onPartialThinking(thinking: PartialThinking) {
        synchronized(this) { thinkingPartials += thinking.text() }
    }

    override fun onCompleteResponse(response: ChatResponse) {
        synchronized(this) { complete = response }
        done.countDown()
    }

    override fun onError(throwable: Throwable) {
        synchronized(this) { error = throwable }
        done.countDown()
    }

    fun await(timeoutSec: Long = 15): Boolean {
        val ok = done.await(timeoutSec, TimeUnit.SECONDS)
        timedOut = !ok
        return ok
    }
}

/**
 * Minimal SSE server on a random localhost port: captures the full request
 * (headers + body), then answers with the canned event lines.
 */
private class MockSseServer(private val respondLines: (MockConnection) -> List<String>) {
    private val server = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress("127.0.0.1", 0))
    }
    private val capturedRequests = CopyOnWriteArrayList<String>()
    val port: Int get() = server.localPort

    init {
        thread(isDaemon = true, name = "mock-sse") {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (_: Exception) {
                    return@thread
                }
                thread(isDaemon = true) {
                    try {
                        MockConnection(socket, capturedRequests).use { conn ->
                            conn.respond(respondLines(conn))
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    fun lastRequest(): String? = capturedRequests.lastOrNull()

    fun close() {
        server.close()
    }

    class MockConnection(
        private val socket: Socket,
        private val captured: MutableList<String>,
    ) : AutoCloseable {
        private val input = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
        private val output = socket.getOutputStream()

        init {
            var contentLength = 0
            val lines = mutableListOf<String>()
            while (true) {
                val line = input.readLine() ?: break
                if (line.isBlank()) break
                if (line.startsWith("Content-Length:", true)) {
                    contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                }
                lines += line
            }
            val body = if (contentLength > 0) {
                val buf = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = input.read(buf, read, contentLength - read)
                    if (n < 0) break
                    read += n
                }
                buf.concatToString()
            } else ""
            captured += lines.joinToString("\n") + "\n\n" + body
        }

        fun respond(lines: List<String>) {
            val payload = lines.joinToString("\n\n") + "\n\n"
            val response = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n" +
                "Content-Length: ${payload.toByteArray().size}\r\n\r\n" + payload
            output.write(response.toByteArray())
            output.flush()
        }

        override fun close() {
            socket.close()
        }
    }
}

private fun sseEvent(json: String) = "data: $json"

private const val DONE = "data: [DONE]"

private fun chunk(delta: String = "{}", finishReason: String? = null, usage: String? = null): String {
    val usagePart = usage?.let { ",\"usage\":$it" } ?: ""
    return """{"id":"chatcmpl-test","object":"chat.completion.chunk","created":1,"model":"mock",""" +
        """"choices":[{"index":0,"delta":$delta,"finish_reason":${if (finishReason == null) "null" else "\"$finishReason\""}}]$usagePart}"""
}
