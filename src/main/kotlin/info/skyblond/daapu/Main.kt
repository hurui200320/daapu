package info.skyblond.daapu

import ai.koog.agents.chatMemory.feature.ChatMemory
import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.session.AIAgentLLMWriteSession
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.ReceivedToolResults
import ai.koog.agents.core.dsl.extension.nodeExecuteTools
import ai.koog.agents.core.dsl.extension.onToolCalls
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.openai.OpenAIChatParams
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.streaming.requireEndFrame
import ai.koog.prompt.streaming.toMessageResponse
import info.skyblond.daapu.db.initDatabase
import info.skyblond.daapu.llm.FlagTool
import info.skyblond.daapu.llm.PostgresChatHistoryProvider
import info.skyblond.daapu.llm.client.CustomOpenAILLMClient
import info.skyblond.daapu.llm.createModel
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive

private val logger = KotlinLogging.logger("Application")

/**
 * Runs a single streaming LLM turn inside the session: collects all frames,
 * prints the stream (reasoning / text / tool calls), appends the assembled
 * assistant message to the prompt and returns it.
 *
 * History is updated by hand because koog only auto-updates the prompt with the
 * response on non-streaming calls.
 */
private suspend fun AIAgentLLMWriteSession.writeStreamingTurn(): Message.Assistant {
    // retry loop
    while (true) {
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
        try {
            val assistant = frames.toMessageResponse()
            appendPrompt {
                message(assistant)
            }
            return assistant
        } catch (e: Exception) {
            logger.error(e) { "Error during writeStreamingTurn, retrying..." }
        }
    }
}

/**
 * PoC entry point: connect the PostgreSQL database and build the koog agent
 * stack. The actual PoC loop comes later.
 */
fun main() {
    val config = appConfigFromEnv()
    initDatabase(config.databaseUrl, config.databaseUser, config.databasePassword)

    val historyProvider = PostgresChatHistoryProvider()

    val model = createModel(
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
                writeStreamingTurn()
            }
        }

        val nodeExecuteTools by nodeExecuteTools(parallel = true)
        val nodeSendToolResult by node<ReceivedToolResults, Message.Assistant>("send-tool-result") { toolResults ->
            llm.writeSession {
                appendPrompt {
                    user {
                        toolResults.toolResults.forEach { toolResult -> toolResult(toolResult.toMessagePart()) }
                    }
                }
                writeStreamingTurn()
            }
        }

        // first doing LLM request
        edge(nodeStart forwardTo llmNode)
        // execute tools if it has tool call
        edge(llmNode forwardTo nodeExecuteTools onToolCalls { true })
        // end if no tool call
        edge(
            llmNode forwardTo nodeFinish
                    onCondition { assistant -> assistant.parts.none { it is MessagePart.Tool.Call } })

        // TODO: before sending tool to LLM, we should first add result to context,
        //       then test the length. if too long, triggering compaction and extraction

        // after execution, send tool result back to LLM
        edge(nodeExecuteTools forwardTo nodeSendToolResult)
        // if still have tool calls, loop til finish
        edge(nodeSendToolResult forwardTo nodeExecuteTools onToolCalls { true })
        edge(
            nodeSendToolResult forwardTo nodeFinish
                    onCondition { assistant -> assistant.parts.none { it is MessagePart.Tool.Call } })
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
            "Hello! What tools are available to you? Can you call multiple tools in parallel? " +
                    "If you call the tools, please also tell me the result",
            sessionId = "2268100b-6544-4654-9d7a-87f55580fc51"
        )
    }
    logger.info { "Finish reason ${resp.finishReason}" }
    logger.info { "Input token ${resp.metaInfo.inputTokensCount}" }
    logger.info { "Output token ${resp.metaInfo.outputTokensCount}" }
    logger.info { "Total token ${resp.metaInfo.totalTokensCount}" }
}
