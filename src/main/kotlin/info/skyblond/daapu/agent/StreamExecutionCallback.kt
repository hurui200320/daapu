package info.skyblond.daapu.agent

/**
 * One locally-executed tool result, as reported to the client.
 */
data class ToolResultInfo(
    val id: String,
    val name: String,
    val content: String,
    val isError: Boolean,
)

/**
 * Receives stream events from one chat run's turn loop
 * (`agent/ChatTurnLoop.kt`), mapped from the langchain4j streaming callbacks.
 *
 * The server-side implementation (`server/ChatRunService.kt`'s
 * `streamEventCallback`) converts these to the SSE events the frontend parses
 * (`text` / `reasoning` / `tool_call` / `tool_result` / `retry`); the
 * protocol is pinned by `StreamEventMappingTest`.
 */
interface StreamExecutionCallback {
    /** A content delta of the assistant response. */
    suspend fun onTextDelta(text: String)

    /** A reasoning/thinking delta of the assistant response. */
    suspend fun onReasoningDelta(text: String)

    /** A tool call completed streaming; [args] is the raw JSON argument string. */
    suspend fun onToolCall(name: String, args: String)

    /**
     * The results of one tool-execution round, before they are appended to
     * the prompt and sent back to the LLM. Tool results are produced locally
     * by the harness (never by the LLM stream), so they do not arrive through
     * [onTextDelta]/[onToolCall].
     */
    suspend fun onToolResults(results: List<ToolResultInfo>)

    /**
     * The stream was aborted by an error before a complete assistant response
     * could be accepted (the round will be retried). Implementations should
     * reset any per-round state (e.g. reasoning/output markers) so the retry
     * starts from a clean slate.
     */
    suspend fun onStreamError(error: Throwable)
}
