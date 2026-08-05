package info.skyblond.daapu.agent

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Verifies the routing classification of streamed assistant messages:
 * only messages with user-visible content or tool calls may be accepted;
 * everything else must be retried (transient, only when no finish reason was
 * given), compacted (the prompt crowds the context window) or failed (the
 * output cap bound on its own, or the provider ended the response with a
 * named reason but no usable output) instead of being persisted into the
 * chat history.
 */
class StreamExecutionResultTest {

    // cerebras-like limits: 128K context, 40K output
    private val contextLength = 131072L
    private val maxOutputTokens = 40000L

    // prompt tokens above this means the input is crowding the output room
    private val threshold = contextLength - maxOutputTokens // 91072

    private fun assistant(
        parts: List<MessagePart.ResponsePart>,
        finishReason: String? = null,
        promptTokens: Int? = null,
    ) = Message.Assistant(
        parts = parts,
        metaInfo = ResponseMetaInfo.Empty.copy(inputTokensCount = promptTokens),
        finishReason = finishReason,
    )

    private fun classify(message: Message.Assistant) =
        classifyStreamResult(message, contextLength, maxOutputTokens)

    @Test
    fun `text response is completed`() {
        val message = assistant(listOf(MessagePart.Text("Hello")), finishReason = "stop")
        assertEquals(StreamExecutionResult.Completed(message), classify(message))
    }

    @Test
    fun `tool call response is completed`() {
        val message = assistant(
            listOf(MessagePart.Tool.Call(id = "call_1", tool = "flag", args = "{}")),
            finishReason = "tool_calls",
        )
        assertEquals(StreamExecutionResult.Completed(message), classify(message))
    }

    @Test
    fun `reasoning plus text is completed`() {
        val message = assistant(
            listOf(
                MessagePart.Reasoning(content = listOf("thinking")),
                MessagePart.Text("Answer"),
            ),
            finishReason = "stop",
        )
        assertEquals(StreamExecutionResult.Completed(message), classify(message))
    }

    @Test
    fun `length with usable content is completed`() {
        // a truncated but usable response is accepted as-is: the text may
        // end mid-sentence, but it is real content worth keeping
        val message = assistant(
            listOf(MessagePart.Text("Partial answer")),
            finishReason = "length",
            promptTokens = 100_000,
        )
        assertEquals(StreamExecutionResult.Completed(message), classify(message))
    }

    @Test
    fun `length with a prompt above the threshold is context exhaustion`() {
        // the input crowds the output room: compaction frees room, retry helps
        val message = assistant(emptyList(), finishReason = "length", promptTokens = 100_000)
        assertEquals(StreamExecutionResult.ContextExhausted, classify(message))
    }

    @Test
    fun `length with a prompt below the threshold is output budget exhaustion`() {
        // the output cap bound on its own: compaction cannot free output room
        val message = assistant(emptyList(), finishReason = "length", promptTokens = 20_000)
        assertEquals(StreamExecutionResult.OutputBudgetExhausted, classify(message))
    }

    @Test
    fun `length with a prompt exactly at the threshold is output budget exhaustion`() {
        // at the threshold the output room equals the cap, so the cap binds
        val message = assistant(emptyList(), finishReason = "length", promptTokens = 91_072)
        assertEquals(StreamExecutionResult.OutputBudgetExhausted, classify(message))
    }

    @Test
    fun `length without usage data is output budget exhaustion`() {
        // we cannot tell which limit bound, so fail fast: output exhaustion
        // breaks the retry loop with a clear error instead of compacting
        // blindly
        val message = assistant(emptyList(), finishReason = "length", promptTokens = null)
        assertEquals(StreamExecutionResult.OutputBudgetExhausted, classify(message))
    }

    @Test
    fun `reasoning-only response with length below the threshold is output budget exhaustion`() {
        // the model burned the whole output budget on reasoning: nothing
        // usable was produced, and a small prompt means compaction won't help
        val message = assistant(
            listOf(MessagePart.Reasoning(content = listOf("thinking"))),
            finishReason = "length",
            promptTokens = 20_000,
        )
        assertEquals(StreamExecutionResult.OutputBudgetExhausted, classify(message))
    }

    @Test
    fun `empty response without a reason is a transient hiccup`() {
        val message = assistant(emptyList(), finishReason = null)
        assertEquals(StreamExecutionResult.EmptyTransient, classify(message))
    }

    @Test
    fun `empty response with a named reason fails fast`() {
        // the provider ended the response deliberately (e.g. the safety
        // filter rejected it, or a deterministic empty stop): retrying the
        // identical prompt would spin forever, so the run must fail
        assertEquals(
            StreamExecutionResult.EmptyPermanent("content_filter"),
            classify(assistant(emptyList(), finishReason = "content_filter")),
        )
        assertEquals(
            StreamExecutionResult.EmptyPermanent("stop"),
            classify(assistant(emptyList(), finishReason = "stop")),
        )
    }

    @Test
    fun `blank text response is not usable`() {
        // OpenAI-style streams open with an empty content delta; a response
        // of only blank text must not count as usable output. No reason:
        // transient hiccup, retry.
        assertEquals(
            StreamExecutionResult.EmptyTransient,
            classify(assistant(listOf(MessagePart.Text("")), finishReason = null)),
        )
        // A named reason makes the blank response definitive: fail fast.
        assertEquals(
            StreamExecutionResult.EmptyPermanent("stop"),
            classify(assistant(listOf(MessagePart.Text("  \n")), finishReason = "stop")),
        )
    }

    @Test
    fun `reasoning-only response without a reason is a transient hiccup`() {
        // reasoning-only would serialize back as an assistant message with
        // content=null, which strict providers reject: never accept it
        val message = assistant(
            listOf(MessagePart.Reasoning(content = listOf("thinking"))),
            finishReason = null,
        )
        assertEquals(StreamExecutionResult.EmptyTransient, classify(message))
    }

    @Test
    fun `reasoning-only response with a named reason fails fast`() {
        // same as above, but the named finish reason makes it definitive:
        // the run fails instead of retrying the identical prompt forever
        val message = assistant(
            listOf(MessagePart.Reasoning(content = listOf("thinking"))),
            finishReason = "stop",
        )
        assertEquals(StreamExecutionResult.EmptyPermanent("stop"), classify(message))
    }

    @Test
    fun `length with a complete tool call is completed`() {
        // a truncated stream that still produced a complete tool call is
        // accepted: the tool call is usable output worth executing
        val message = assistant(
            listOf(MessagePart.Tool.Call(id = "call_1", tool = "flag", args = "{}")),
            finishReason = "length",
            promptTokens = 100_000,
        )
        assertEquals(StreamExecutionResult.Completed(message), classify(message))
    }

    @Test
    fun `completed result wraps the assistant message`() {
        val message = assistant(listOf(MessagePart.Text("Hi")))
        val result = classify(message)
        assertIs<StreamExecutionResult.Completed>(result)
        assertEquals(message, result.assistant)
    }
}
