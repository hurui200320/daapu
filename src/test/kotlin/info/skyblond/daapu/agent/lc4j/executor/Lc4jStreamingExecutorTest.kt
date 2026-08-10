package info.skyblond.daapu.agent.lc4j.executor

import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.exception.HttpException
import dev.langchain4j.http.client.sse.ServerSentEvent
import dev.langchain4j.model.chat.response.ChatResponse
import dev.langchain4j.model.openai.OpenAiChatResponseMetadata
import dev.langchain4j.model.output.FinishReason
import dev.langchain4j.model.output.TokenUsage
import info.skyblond.daapu.agent.executor.StreamingExecutionResult
import info.skyblond.daapu.chat.ChatMessagePart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Pins [classifyStreamResult] (the outcome routing of one completed streaming
 * round) plus the mid-stream error-chunk scan and generated tool-call ids.
 * Pure unit tests: `ChatResponse`s are built directly, no network involved.
 */
class Lc4jStreamingExecutorTest {

    // cerebras-like limits: 128K context, 40K output
    private val contextLength = 131072L
    private val maxOutputTokens = 40000L

    // prompt tokens above this means the input is crowding the output room
    private val threshold = contextLength - maxOutputTokens // 91072

    private fun request(id: String? = "call_1", name: String = "flag", args: String = "{}") =
        ToolExecutionRequest.builder()
            .id(id)
            .name(name)
            .arguments(args)
            .build()

    private fun assistant(
        text: String? = null,
        requests: List<ToolExecutionRequest> = emptyList(),
    ) = AiMessage.builder()
        .text(text)
        .toolExecutionRequests(requests)
        .build()

    private fun response(
        ai: AiMessage,
        finishReason: FinishReason?,
        usage: TokenUsage? = null,
    ) = ChatResponse.builder()
        .aiMessage(ai)
        .finishReason(finishReason)
        .tokenUsage(usage)
        .modelName("mock")
        .build()

    @Test
    fun `stop with text completes without tool calls`() {
        val result = classifyStreamResult(
            response(assistant(text = "hi"), FinishReason.STOP, TokenUsage(10, 5, 15)),
            contextLength,
            maxOutputTokens,
        )
        val completed = assertIs<StreamingExecutionResult.Completed>(result)
        assertEquals(
            listOf("hi"),
            completed.assistant.parts.filterIsInstance<ChatMessagePart.Text>().map { it.text },
        )
        assertTrue(completed.toolCallRequests.isEmpty())
        assertEquals("stop", completed.assistant.finishReason)
    }

    @Test
    fun `stop with tool calls completes with the requests`() {
        val result = classifyStreamResult(
            response(assistant(requests = listOf(request())), FinishReason.STOP),
            contextLength,
            maxOutputTokens,
        )
        val completed = assertIs<StreamingExecutionResult.Completed>(result)
        assertEquals(listOf("flag"), completed.toolCallRequests.map { it.name() })
        assertEquals("stop", completed.assistant.finishReason)
    }

    @Test
    fun `empty stop is permanent`() {
        val result = classifyStreamResult(
            response(assistant(), FinishReason.STOP),
            contextLength,
            maxOutputTokens,
        )
        assertEquals(StreamingExecutionResult.EmptyPermanent("stop"), result)
    }

    @Test
    fun `length below the threshold is output budget exhausted`() {
        // at or below contextLength - maxOutputTokens the output cap bound on
        // its own: compaction cannot help
        val result = classifyStreamResult(
            response(assistant(), FinishReason.LENGTH, TokenUsage(threshold.toInt(), 0, threshold.toInt())),
            contextLength,
            maxOutputTokens,
        )
        assertEquals(StreamingExecutionResult.OutputBudgetExhausted, result)
    }

    @Test
    fun `length above the threshold is context exhausted`() {
        // the prompt crowds the context window: compacting frees output room
        val result = classifyStreamResult(
            response(assistant(), FinishReason.LENGTH, TokenUsage(threshold.toInt() + 1, 0, threshold.toInt() + 1)),
            contextLength,
            maxOutputTokens,
        )
        assertEquals(StreamingExecutionResult.ContextExhausted, result)
    }

    @Test
    fun `length without usage data is output budget exhausted`() {
        // cannot tell which limit bound without input tokens: fail fast
        // rather than compacting blindly
        val result = classifyStreamResult(
            response(assistant(), FinishReason.LENGTH),
            contextLength,
            maxOutputTokens,
        )
        assertEquals(StreamingExecutionResult.OutputBudgetExhausted, result)
    }

    @Test
    fun `length with partial text is never accepted`() {
        // a truncated answer is not worth storing (a chat must end with a
        // clean stop, see ChatCodec.validateChat), even with partial text
        val result = classifyStreamResult(
            response(assistant(text = "partial"), FinishReason.LENGTH, TokenUsage(20, 16, 36)),
            contextLength,
            maxOutputTokens,
        )
        assertEquals(StreamingExecutionResult.OutputBudgetExhausted, result)
    }

    @Test
    fun `tool execution with requests completes`() {
        val result = classifyStreamResult(
            response(assistant(requests = listOf(request())), FinishReason.TOOL_EXECUTION),
            contextLength,
            maxOutputTokens,
        )
        val completed = assertIs<StreamingExecutionResult.Completed>(result)
        assertEquals(1, completed.toolCallRequests.size)
        assertEquals("tool_calls", completed.assistant.finishReason)
    }

    @Test
    fun `tool execution without requests is permanent`() {
        val result = classifyStreamResult(
            response(assistant(), FinishReason.TOOL_EXECUTION),
            contextLength,
            maxOutputTokens,
        )
        assertEquals(StreamingExecutionResult.EmptyPermanent("tool_calls"), result)
    }

    @Test
    fun `content filter is permanent`() {
        val result = classifyStreamResult(
            response(assistant(), FinishReason.CONTENT_FILTER),
            contextLength,
            maxOutputTokens,
        )
        assertEquals(StreamingExecutionResult.EmptyPermanent("content_filter"), result)
    }

    @Test
    fun `unknown finish reason is permanent`() {
        val result = classifyStreamResult(
            response(assistant(), FinishReason.OTHER),
            contextLength,
            maxOutputTokens,
        )
        assertEquals(StreamingExecutionResult.EmptyPermanent("other"), result)
    }

    @Test
    fun `missing finish reason is transient`() {
        // a clean EOF without finish_reason is a truncated stream (or an
        // unknown reason mapped to null): the only retryable outcome
        val result = classifyStreamResult(
            response(assistant(text = "partial"), null),
            contextLength,
            maxOutputTokens,
        )
        assertEquals(StreamingExecutionResult.EmptyTransient, result)
    }

    @Test
    fun `mid-stream error chunk with a numeric code throws HttpException`() {
        val response = errorChunkResponse("""{"error":{"message":"Content policy violation","type":"moderation","code":403}}""")
        val e = assertFailsWith<HttpException> {
            classifyStreamResult(response, contextLength, maxOutputTokens)
        }
        assertEquals(403, e.statusCode())
    }

    @Test
    fun `mid-stream error chunk without a code throws MidStreamErrorChunkException`() {
        val response = errorChunkResponse("""{"error":{"message":"upstream connection reset"}}""")
        assertFailsWith<MidStreamErrorChunkException> {
            classifyStreamResult(response, contextLength, maxOutputTokens)
        }
    }

    @Test
    fun `non-error sse chunks do not trip the error scan`() {
        val response = ChatResponse.builder()
            .aiMessage(assistant(text = "hi"))
            .metadata(
                OpenAiChatResponseMetadata.builder()
                    .finishReason(FinishReason.STOP)
                    .rawServerSentEvents(listOf(ServerSentEvent("", """{"choices":[]}""")))
                    .build()
            )
            .build()
        assertIs<StreamingExecutionResult.Completed>(
            classifyStreamResult(response, contextLength, maxOutputTokens)
        )
    }

    @Test
    fun `id-less tool calls get a generated id`() {
        val result = classifyStreamResult(
            response(assistant(requests = listOf(request(id = null))), FinishReason.TOOL_EXECUTION),
            contextLength,
            maxOutputTokens,
        )
        val completed = assertIs<StreamingExecutionResult.Completed>(result)
        val call = completed.assistant.parts
            .filterIsInstance<ChatMessagePart.ToolCall>()
            .single()
        assertTrue(call.id.startsWith("call_"), "Expected a generated id, got ${call.id}")
        // the returned request list agrees with the stored part's id
        assertEquals(call.id, completed.toolCallRequests.single().id())
    }

    @Test
    fun `tool calls with ids are left untouched`() {
        val result = classifyStreamResult(
            response(assistant(requests = listOf(request(id = "call_keep"))), FinishReason.TOOL_EXECUTION),
            contextLength,
            maxOutputTokens,
        )
        val completed = assertIs<StreamingExecutionResult.Completed>(result)
        assertEquals("call_keep", completed.toolCallRequests.single().id())
        val call = completed.assistant.parts
            .filterIsInstance<ChatMessagePart.ToolCall>()
            .single()
        assertEquals("call_keep", call.id)
    }

    private fun errorChunkResponse(chunkData: String): ChatResponse =
        ChatResponse.builder()
            .aiMessage(assistant(text = "partial"))
            .metadata(
                OpenAiChatResponseMetadata.builder()
                    .finishReason(FinishReason.STOP)
                    .rawServerSentEvents(listOf(ServerSentEvent("", chunkData)))
                    .build()
            )
            .build()
}
