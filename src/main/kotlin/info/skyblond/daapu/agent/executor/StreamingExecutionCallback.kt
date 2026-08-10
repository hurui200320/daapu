package info.skyblond.daapu.agent.executor

import info.skyblond.daapu.chat.ChatMessagePart

/**
 * Receives stream events from the run.
 */
interface StreamingExecutionCallback {
    /** A content delta of the assistant response. */
    suspend fun onTextDelta(text: String)

    /** A reasoning/thinking delta of the assistant response. */
    suspend fun onReasoningDelta(text: String)

    /**
     * A tool call completed streaming.
     *
     * Note: at this point, some tool calls may not have an id.
     * The id is only generated when the request is fully generated.
     * */
    suspend fun onToolCall(name: String, args: String)

    /**
     * The results of one tool-execution round, before they are appended to
     * the prompt and sent back to the LLM. Tool results are produced locally
     * by the harness (never by the LLM stream), so they do not arrive through
     * [onTextDelta]/[onToolCall].
     */
    suspend fun onToolResults(results: List<ChatMessagePart.ToolResult>)

    /**
     * The stream was aborted by an error before a complete assistant response
     * could be accepted (the round will be retried). Implementations should
     * reset any per-round state (e.g. reasoning/output markers) so the retry
     * starts from a clean slate.
     */
    suspend fun onStreamError(error: String)
}
