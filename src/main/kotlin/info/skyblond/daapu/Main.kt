package info.skyblond.daapu

import ai.koog.agents.chatMemory.feature.ChatMemory
import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.openai.OpenAIChatParams
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.streaming.requireEndFrame
import ai.koog.prompt.streaming.toMessageResponse
import info.skyblond.daapu.db.initDatabase
import info.skyblond.daapu.llm.FlagTool
import info.skyblond.daapu.llm.PostgresChatHistoryProvider
import info.skyblond.daapu.llm.client.CustomOpenAILLMClient
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive

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
        contextLength = 262144,
        maxOutputTokens = 131072,
    )

    val strategy = strategy<String, Message.Assistant>("daapu-chat-stream") {
        val llmNode by node<String, Message.Assistant>("llm") { message ->
            llm.writeSession {
                appendPrompt {
                    user(message)
                }
                // The docs from koog is misleading.
                // Automatic history update on response ONLY works with non-streaming calls.
                // So with streaming, we might need to manually construct Message.Assistant
                val frames = mutableListOf<StreamFrame>()
                var isReasoning = false
                var isOutput = false
                var hasToolCall = false
                requestLLMStreaming().requireEndFrame().collect { frame ->
                    frames.add(frame)
                    when (frame) {
                        is StreamFrame.ReasoningDelta -> {
                            if (!isReasoning) {
                                isReasoning = true
                                println("========== Reasoning START ==========")
                            }
                            print(frame.text)
                        }

                        is StreamFrame.ReasoningComplete -> {
                            isReasoning = false
                            println("\n========== Reasoning END ==========")
                        }

                        is StreamFrame.TextDelta -> {
                            if (!isOutput) {
                                isOutput = true
                                println("========== Output START ==========")
                            }
                            print(frame.text)
                        }

                        is StreamFrame.TextComplete -> {
                            isOutput = false
                            println("\n========== Output END ==========")
                        }

                        is StreamFrame.ToolCallDelta -> {
                            if (!hasToolCall) {
                                hasToolCall = true
                                println("========== Tool call START ==========")
                            }
                        }

                        is StreamFrame.ToolCallComplete -> {
                            println("Tool name: " + frame.name)
                            println("Args: " + frame.content)
                            println("========== Tool call END ==========")
                        }

                        is StreamFrame.End -> {
                            println("Streaming end")
                        }
                    }
                }
                val assistant = frames.toMessageResponse()
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
            CustomOpenAILLMClient(
                // API key of the locally deployed bifrost (LLM gateway); safe to keep here.
                "sk-bf-cc5e85a8-72c7-4ba5-8903-600cc276e8a0",
                OpenAIClientSettings(
                    baseUrl = "http://10.233.1.8:8002"
                )
            )
        ),
        agentConfig = AIAgentConfig(
            prompt = prompt(
                id = "chat",
                params = OpenAIChatParams(
                    additionalProperties = mapOf(
                        "reasoning_effort" to JsonPrimitive("high")
                    )
                )
            ) {
                system("You're Gemma 4, LLM trained by Google LLC.")
            },
            model = model,
            maxAgentIterations = 50,
            responseProcessor = null
        ),
        strategy = strategy,
        toolRegistry = ToolRegistry {
            tool(FlagTool())
        }
    ) {
        install(ChatMemory) {
            chatHistoryProvider = historyProvider
            windowSize(50)
        }
    }

    // Run the agent
    val resp = runBlocking {
        agent.run(
            "Hello! Who are you?",
            sessionId = "2268100b-6544-4654-9d7a-87f55580fc51"
        )
    }
    logger.info { "Finish reason ${resp.finishReason}" }
    logger.info { "Input token ${resp.metaInfo.inputTokensCount}" }
    logger.info { "Output token ${resp.metaInfo.outputTokensCount}" }
    logger.info { "Total token ${resp.metaInfo.totalTokensCount}" }
}
