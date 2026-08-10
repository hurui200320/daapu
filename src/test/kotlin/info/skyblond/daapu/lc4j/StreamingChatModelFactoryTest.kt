package info.skyblond.daapu.lc4j

import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.chat.response.ChatResponse
import dev.langchain4j.model.chat.response.PartialThinking
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler
import dev.langchain4j.model.openai.OpenAiStreamingChatModel
import dev.langchain4j.model.output.FinishReason
import info.skyblond.daapu.agent.lc4j.llm.LLM
import info.skyblond.daapu.agent.lc4j.llm.LLMCapability
import info.skyblond.daapu.agent.lc4j.llm.ModelCatalog
import info.skyblond.daapu.agent.lc4j.provider.BifrostProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the builder knobs of [LLM.toStreamingChatModel] against a local mock
 * SSE endpoint (trimmed port of the #1 spike harness): the per-model id, the
 * spike-verified `reasoning_effort`/`include_usage` request fields, and the
 * thinking round-trip behavior (`returnThinking`/`sendThinking`).
 *
 * These are the spike findings that must not silently regress when the
 * factory is wired into the turn loop, so they run offline without any
 * gateway.
 */
class StreamingChatModelFactoryTest {

    @Test
    fun `request body carries model id, reasoning_effort and include_usage`() {
        val server = MockSseServer { MockSseResponse(200, stopStream()) }
        try {
            val catalog = catalog(server)
            for (model in catalog.models) {
                val rec = chat(model.toStreamingChatModel("high"), listOf(UserMessage.from("hi")))
                assertTrue(rec.await(15), "timed out for ${model.id}")
                assertEquals(FinishReason.STOP, rec.complete?.finishReason(), "for ${model.id}")
                val body = server.lastRequest() ?: error("no request captured for ${model.id}")
                val compact = body.replace(Regex("""\s+"""), "")
                // the wire model name is the gateway's model id, not the
                // provider-prefixed catalog id
                assertTrue(compact.contains("\"model\":\"${model.modelId}\""), "missing model id in body: $compact")
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
            MockSseResponse(200, listOf(
                sseEvent(sseChunk(delta = """{"reasoning_content":"Let me think"}""")),
                sseEvent(sseChunk(delta = """{"reasoning_content":" step by step"}""")),
                sseEvent(sseChunk(delta = """{"content":"17 * 23 = 391"}""")),
                sseEvent(sseChunk(finishReason = "stop")),
                SSE_DONE,
            ))
        }
        try {
            val model = catalogModel(server, "bifrost/cerebras/gpt-oss-120b")
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
        val server = MockSseServer { MockSseResponse(200, stopStream()) }
        try {
            val model = catalogModel(server, "bifrost/cerebras/gpt-oss-120b")
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
            MockSseResponse(200, listOf(
                sseEvent(sseChunk(delta = """{"content":"hi"}""")),
                sseEvent(sseChunk(usage = """{"prompt_tokens":12,"completion_tokens":5,"total_tokens":17}""")),
                sseEvent(sseChunk(finishReason = "stop")),
                SSE_DONE,
            ))
        }
        try {
            val model = catalogModel(server, "bifrost/cerebras/gemma-4-31b")
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
            MockSseResponse(200, listOf(
                sseEvent(sseChunk(delta = """{"reasoning_content":"secret thinking"}""")),
                sseEvent(sseChunk(delta = """{"content":"answer"}""")),
                sseEvent(sseChunk(finishReason = "stop")),
                SSE_DONE,
            ))
        }
        try {
            val model = LLM(
                provider = BifrostProvider("test", "http://127.0.0.1:${server.port}/v1", "test-key"),
                modelId = "test/no-reasoning",
                contextLength = 1000,
                maxOutputTokens = 500,
                capabilities = setOf(LLMCapability.Output.ToolCalls),
            )
            val rec = chat(model.toStreamingChatModel("high"), listOf(UserMessage.from("hi")))
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

    private fun catalog(server: MockSseServer) = ModelCatalog(
        BifrostProvider(
            id = "bifrost",
            baseUrl = "http://127.0.0.1:${server.port}/v1",
            apiKey = "test-key",
        )
    )

    private fun catalogModel(server: MockSseServer, id: String): OpenAiStreamingChatModel =
        catalog(server).findModel(id)!!
            .toStreamingChatModel("high")

    private fun stopStream() = listOf(
        sseEvent(sseChunk(delta = """{"content":"ok"}""")),
        sseEvent(sseChunk(finishReason = "stop")),
        SSE_DONE,
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
