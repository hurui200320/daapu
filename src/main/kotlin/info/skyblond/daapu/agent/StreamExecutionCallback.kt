package info.skyblond.daapu.agent

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.streaming.StreamFrame

interface StreamExecutionCallback {
    /**
     * Called when we got a stream frame.
     * */
    suspend fun onFrame(frame: StreamFrame)

    /**
     * Called when we assembled a complete assistant response message.
     * */
    suspend fun onAssistantMessage(message: Message.Assistant)

    /**
     * Called with the tool results of one tool-execution round, before they
     * are appended to the prompt and sent back to the LLM. Tool results are
     * produced locally by the agent (never by the LLM stream), so they do not
     * arrive through [onFrame].
     * */
    suspend fun onToolResults(results: List<MessagePart.Tool.Result>)

    /**
     * Called when the stream was aborted by an error before a complete
     * assistant response could be assembled (the stream will be retried).
     * Implementations should reset any per-stream state (e.g. reasoning/output
     * markers) so the retry starts from a clean slate.
     * */
    suspend fun onStreamError(error: Throwable)
}