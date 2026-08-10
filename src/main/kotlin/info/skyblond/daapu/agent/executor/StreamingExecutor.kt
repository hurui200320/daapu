package info.skyblond.daapu.agent.executor

import dev.langchain4j.model.openai.OpenAiStreamingChatModel
import info.skyblond.daapu.agent.lc4j.tool.ToolProvider
import info.skyblond.daapu.chat.ChatMessage

interface StreamingExecutor {
    suspend fun executeOnce(
        model: OpenAiStreamingChatModel,
        modelContextLength: Long,
        modelMaxOutputTokens: Long,
        chat: List<ChatMessage>,
        toolProvider: ToolProvider,
        callback: StreamingExecutionCallback
    ): StreamingExecutionResult
}
