package info.skyblond.daapu.llm

import ai.koog.agents.chatMemory.feature.ChatHistoryProvider
import ai.koog.agents.chatMemory.feature.ChatMemory
import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterIsInstance

/**
 * Runs koog agents against a chat, with `ChatMemory` managing the conversation
 * history through a [ChatHistoryProvider] (Postgres-backed, see
 * [PostgresChatHistoryProvider]).
 *
 * koog owns the history: it loads the chat's messages before each run and stores
 * the updated conversation after the run completes. We only map the chat id to
 * koog's session id and relay streamed text deltas to the caller.
 *
 * The agent and its streaming strategy are built per request because the strategy
 * captures the per-request text sink. The shared [PromptExecutor] and provider
 * are stateless and reused.
 */
class ChatAgentService(
    private val promptExecutor: PromptExecutor,
    private val llmModel: LLModel,
    private val systemPrompt: String,
    private val historyProvider: ChatHistoryProvider,
) {

    /**
     * Stream a reply to [content] in [chatId]'s conversation.
     *
     * [onDelta] is called for each text delta as it arrives; the full assistant
     * text is returned. koog's ChatMemory loads the existing history for
     * [chatId] before the run and persists the updated history afterwards.
     */
    suspend fun streamReply(
        chatId: Long,
        content: String,
        onDelta: suspend (String) -> Unit,
    ): String {
        val strategy = streamingStrategy(onDelta)
        val agent = AIAgent(
            promptExecutor = promptExecutor,
            llmModel = llmModel,
            strategy = strategy,
            systemPrompt = systemPrompt,
        ) {
            install(ChatMemory) {
                chatHistoryProvider = historyProvider
                windowSize(50)
            }
        }
        val reply = agent.run(content, chatId.toString())
        return reply.textContent()
    }

    /**
     * A streaming strategy that appends the user message, requests a streaming
     * response, relays each text delta to [onDelta], then appends the assembled
     * `Message.Assistant` so ChatMemory persists it.
     */
    private fun streamingStrategy(onDelta: suspend (String) -> Unit): AIAgentGraphStrategy<String, Message.Assistant> =
        strategy<String, Message.Assistant>("daapu-chat-stream") {
            val llmNode by node<String, Message.Assistant>("llm") { message ->
                llm.writeSession {
                    appendPrompt {
                        user(message)
                    }
                    val text = StringBuilder()
                    requestLLMStreaming().filterIsInstance<StreamFrame.TextDelta>().collect { delta ->
                        onDelta(delta.text)
                        text.append(delta.text)
                    }
                    val assistant = Message.Assistant(
                        parts = listOf(MessagePart.Text(text.toString())),
                        metaInfo = ResponseMetaInfo.Empty,
                    )
                    appendPrompt {
                        message(assistant)
                    }
                    assistant
                }
            }
            edge(nodeStart forwardTo llmNode)
            edge(llmNode forwardTo nodeFinish)
        }
}
