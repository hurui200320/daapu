package info.skyblond.daapu

import ai.koog.agents.chatMemory.feature.ChatMemory
import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import info.skyblond.daapu.db.initDatabase
import info.skyblond.daapu.llm.PostgresChatHistoryProvider
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.runBlocking

private val logger = KotlinLogging.logger("Application")

/**
 * PoC entry point: connect the PostgreSQL database and build the koog agent
 * stack. The actual PoC loop comes later.
 */
fun main() {
    val config = appConfigFromEnv()
    initDatabase(config.databaseUrl, config.databaseUser, config.databasePassword)

    val historyProvider = PostgresChatHistoryProvider()

    val model = LLModel(
        provider = LLMProvider.OpenAI,
        id = "novita/google/gemma-4-31b-it",
        capabilities = listOf(
            LLMCapability.ToolChoice,
            LLMCapability.Schema.JSON.Basic,
            LLMCapability.Schema.JSON.Standard,
            LLMCapability.Tools,
            LLMCapability.Vision.Image,
            LLMCapability.Completion,
            LLMCapability.MultipleChoices,
            LLMCapability.OpenAIEndpoint.Completions,
            LLMCapability.Thinking,
        ),
        contextLength = 400_000,
        maxOutputTokens = 128_000,
    )

    val strategy = strategy<String, Message.Assistant>("daapu-chat-stream") {
        val llmNode by node<String, Message.Assistant>("llm") { message ->
            llm.writeSession {
                appendPrompt {
                    user(message)
                }
                val text = StringBuilder()
                // TODO: The docs from koog is misleading.
                //       Automatic history update on response ONLY works with non-streaming calls
                //       So with streaming, we might need to manually construct Message.Assistant
                requestLLMStreaming().filterIsInstance<StreamFrame.TextDelta>().collect { delta ->
                    // for now we just print them
                    print(delta.text)
                    text.append(delta.text)
                }
                requestLLMBlocking()
                // print a new line after finished streaming
                println()
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

    val agent = AIAgent(
        promptExecutor = MultiLLMPromptExecutor(
            OpenAILLMClient(
                // API key of the locally deployed bifrost (LLM gateway); safe to keep here.
                "sk-bf-cc5e85a8-72c7-4ba5-8903-600cc276e8a0",
                OpenAIClientSettings(
                    baseUrl = "http://10.233.1.8:8002"
                )
            )
        ),
        llmModel = model,
        strategy = strategy,
        systemPrompt = "You're Gemma 4.",
    ) {
        install(ChatMemory) {
            chatHistoryProvider = historyProvider
            windowSize(50)
        }
    }

    // Run the agent
    runBlocking {
        agent.run(
            "What can you do?",
            sessionId = "2268100b-6544-4654-9d7a-87f55580fc51"
        )
    }
}
