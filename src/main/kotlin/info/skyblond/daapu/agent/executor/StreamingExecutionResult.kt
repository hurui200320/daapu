package info.skyblond.daapu.agent.executor

import dev.langchain4j.agent.tool.ToolExecutionRequest
import info.skyblond.daapu.chat.ChatMessage
import info.skyblond.daapu.chat.ChatMessageRole

/**
 * Outcome of one streaming execution round.
 */
sealed interface StreamingExecutionResult {
    /**
     * The stream produced a usable assistant message; the caller appends it
     * to the history and routes to tool execution or post-processing.
     */
    data class Completed(
        val assistant: ChatMessage,
        val toolCallRequests: List<ToolExecutionRequest>
    ) : StreamingExecutionResult {
        init {
            require(assistant.role == ChatMessageRole.Assistant) {
                "StreamingExecutionResult.Completed must carry an Assistant message"
            }
        }
    }

    /**
     * The stream ended with `finish_reason == "length"` and no usable output,
     * and the prompt is large enough (input > context - output) that it is
     * crowding the context window.
     *
     * Should shrink/compact the input frees output room then retry.
     */
    data object ContextExhausted : StreamingExecutionResult

    /**
     * The stream ended with `finish_reason == "length"` and no usable output,
     * but the prompt is at or below the threshold: the output cap bound on
     * its own (e.g. reasoning burned the whole output budget), so compaction
     * cannot help. Also used when the provider sent no usage data, and we
     * cannot tell which limit bound.
     */
    data object OutputBudgetExhausted : StreamingExecutionResult

    /**
     * The stream completed cleanly but produced no usable output and gave no
     * reason for it (`finish_reason` missing). Treated as a transient gateway
     * hiccup: retry with backoff.
     */
    data object EmptyTransient : StreamingExecutionResult

    /**
     * The stream completed cleanly but produced no usable output, and the
     * provider gave a named reason other than `length` (e.g. `content_filter`,
     * or a deterministic empty `stop`). The provider ended the response
     * deliberately, so retrying the identical prompt would spin forever.
     */
    data class EmptyPermanent(val finishReason: String) : StreamingExecutionResult
}
