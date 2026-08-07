package info.skyblond.daapu.agent

import ai.koog.agents.chatMemory.feature.ChatHistoryProvider
import ai.koog.agents.chatMemory.feature.ChatMemory
import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.context.AIAgentGraphContextBase
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.ReceivedToolResults
import ai.koog.agents.core.dsl.extension.nodeExecuteTools
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.openai.OpenAIChatParams
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.streaming.requireEndFrame
import ai.koog.prompt.streaming.toMessageResponse
import info.skyblond.daapu.db.SSTMs
import info.skyblond.daapu.db.withTransaction
import info.skyblond.daapu.koog.withGeneratedToolCallIds
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.ZonedDateTime

private val logger = KotlinLogging.logger("ChatAgent")

// stream retry backoff: 100ms, 200ms, 400ms, 800ms, 1.6s, 3.2s, 6.4s
private const val BACKOFF_BASE_MS = 100L
private const val BACKOFF_MAX_EXPONENT = 6

// Called before and during a round; only logs the token count for now.
private suspend fun AIAgentGraphContextBase.logAssistantTokenCount() {
    llm.readSession {
        val message = prompt.messages.lastOrNull { it is Message.Assistant }
            ?.let { it as Message.Assistant } ?: return@readSession
        val totalToken = message.metaInfo.totalTokensCount ?: 0
        if (totalToken <= 0) return@readSession
        logger.info { "Last assistant message token total: $totalToken" }
    }
}

/**
 * Build a daapu chat agent for one run.
 *
 * The agent is cheap to construct (koog's `AIAgent.close()` is a no-op and
 * each `run()` creates a fresh session context), so the server builds one per
 * chat request: this gives every request its own [model] and [callback]
 * without any shared mutable state. The expensive pieces ([promptExecutor],
 * [historyProvider], [systemPrompt]) are created once and shared.
 */
fun buildChatAgent(
    promptExecutor: MultiLLMPromptExecutor,
    historyProvider: ChatHistoryProvider,
    systemPrompt: String,
    model: LLModel,
    llmParameter: OpenAIChatParams,
    callback: StreamExecutionCallback,
): AIAgent<List<MessagePart.RequestPart>, Unit> {
    val contextInjection = ContextInjection()

    val strategy = strategy<List<MessagePart.RequestPart>, Unit>("daapu-chat-stream") {
        // TODO: add a internal context object? Contains some info like last assistant message, etc.
        val userInputPreprocess by node<List<MessagePart.RequestPart>, Unit> { userInput ->
            // the injection is prepended and stripped after the round, so an
            // empty user input would leave a lone injection message in history
            require(userInput.isNotEmpty()) { "Empty user message is not allowed" }
            // TODO: add a class for memories, use lock to block all readers when memory is too long
            //       and triggers the compaction. Hold the lock before compaction finished.
            val memories = withTransaction {
                SSTMs.selectAll()
                    .orderBy(SSTMs.lastUpdate to SortOrder.ASC)
                    .map { it[SSTMs.content] }
            }
            llm.writeSession {
                // first clean up system prompt
                prompt = prompt.withMessages { messages ->
                    // + We ONLY allow system message at index = 0, not in chat history (after user message)
                    // + We update the system message to latest before execution
                    //   + If it's the same, it should hit the cache, otherwise recompute.
                    val refreshed = messages.mapIndexedNotNull { index, m ->
                        when (m) {
                            is Message.System ->
                                if (index == 0) {
                                    m.copy(parts = listOf(MessagePart.Text(systemPrompt)))
                                } else {
                                    null
                                }
                            else -> m
                        }
                    }
                    // if missing system prompt, insert one
                    if (refreshed.firstOrNull() is Message.System) {
                        refreshed
                    } else {
                        listOf(Message.System(systemPrompt, RequestMetaInfo.Empty)) + refreshed
                    }
                }
                // then we inject context into latest user input as first part
                appendPrompt {
                    user(
                        listOf(
                            contextInjection.generateInjection(
                                time = ZonedDateTime.now(),
                                // TODO: hook up this
                                sstmUpdated = false,
                                eltmUpdated = false,
                                memoryList = memories
                            )
                        ) + userInput
                    )
                }
                // the prompt is complete (history + system + new input): check
                // the model can actually process its content. Images can come
                // from history (stored when a vision model was used), not just
                // from the request, so this must be checked on the full prompt
                // rather than on the request alone.
                checkPromptContentCapabilities(prompt.messages, model)
            }
        }

        val preRoundCompact by node<Unit, Unit> {
            // TODO: detect topic? or just compaction?
            //       launch two executions:
            //         1) extract info and inject to STM
            //         2) compact history and rewrite prompt
            // TODO: how to notify callback that history has been changed?
            logAssistantTokenCount()
        }

        val executionStreaming by node<Unit, StreamExecutionResult> {
            llm.writeSession {
                var attempts = 0L
                // unbounded retry: an upstream hiccup (5xx, connection drop,
                // truncated stream, empty response for no reason) is expected;
                // backoff so we don't spam it.
                var executionResult: StreamExecutionResult? = null
                while (executionResult == null) {
                    val frames = mutableListOf<StreamFrame>()
                    try {
                        requestLLMStreaming().requireEndFrame().collect { frame ->
                            frames.add(frame)
                            callback.onFrame(frame)
                        }
                        val assistant = frames.toMessageResponse()
                        // Test result to see if it should be accepted
                        when (
                            val result = classifyStreamResult(
                                assistant,
                                contextLength = model.contextLength
                                    ?: error("Model has unknown context length"),
                                maxOutputTokens = model.maxOutputTokens
                                    ?: error("Model has unknown max output"),
                            )
                        ) {
                            is StreamExecutionResult.Completed -> {
                                val normalized = result.assistant.withGeneratedToolCallIds()
                                callback.onAssistantMessage(normalized)
                                appendPrompt {
                                    message(normalized)
                                }
                                executionResult = StreamExecutionResult.Completed(normalized)
                            }

                            // the prompt crowds the context window: compact
                            // to free output room, then retry
                            StreamExecutionResult.ContextExhausted ->
                                executionResult = StreamExecutionResult.ContextExhausted

                            // the output cap bound on its own: compaction
                            // cannot help, fail the run
                            StreamExecutionResult.OutputBudgetExhausted ->
                                throw OutputExhaustionException(
                                    "The model exhausted its output budget without producing usable content " +
                                            "while context is not exhausted. This suggest the model cannot " +
                                            "fulfill the request with the given output limit. Either give " +
                                            "a bigger output limit, or turn down the reasoning effort " +
                                            "(or thinking budget, whatever it calls), or change a model"
                                )

                            // the provider ended the response deliberately
                            // (e.g. content_filter): retrying the identical
                            // prompt would spin forever, fail the run
                            is StreamExecutionResult.EmptyPermanent ->
                                throw EmptyPermanentResponseException(
                                    "Stream completed with finish_reason=${result.finishReason} " +
                                            "but no usable content. The provider ended the response " +
                                            "deliberately, so retrying the identical prompt would spin " +
                                            "forever. Rephrase the message, or change the model/provider."
                                )

                            StreamExecutionResult.EmptyTransient ->
                                throw EmptyStreamResponseException(
                                    "Stream completed with no usable content " +
                                            "(finishReason=${assistant.finishReason})"
                                )
                        }
                    } catch (t: Throwable) {
                        // the retry policy lives in isRetryableStreamError
                        // (and is unit-tested there)
                        if (isRetryableStreamError(t)) {
                            callback.onStreamError(t)
                            logger.error(t) { "Error during execution, retrying..." }
                            val exponent = (attempts).coerceAtMost(BACKOFF_MAX_EXPONENT.toLong())
                            delay(BACKOFF_BASE_MS shl exponent.toInt())
                            attempts++
                            if (attempts % 10L == 0L) {
                                logger.warn { "Execution still failing after $attempts attempts (latest: ${t.message})" }
                            }
                        } else throw t
                    }
                }
                executionResult
            }
        }

        val exhaustionCompact by node<StreamExecutionResult, Unit> {
            // TODO: detect topic? or just compaction?
            //       launch two executions:
            //         1) extract info and inject to STM
            //         2) compact history and rewrite prompt
            // TODO: how to notify callback that history has been changed?
            throw IllegalStateException(
                "The prompt is crowding the context window (prompt tokens > " +
                        "contextLength - maxOutputTokens), so the model ran out of output " +
                        "room. History compaction is not implemented yet, making context " +
                        "exhaustion unrecoverable: start a new chat or shorten the prompt."
            )
        }

        val toolExecution by nodeExecuteTools(parallel = true)

        val toolResultAppender by node<ReceivedToolResults, Unit> { toolResults ->
            val results = toolResults.toolResults.map { it.toMessagePart() }
            callback.onToolResults(results)
            llm.writeSession {
                appendPrompt {
                    user {
                        // TODO: how to limit tool result length so it won't blow up the context?
                        results.forEach { result ->
                            toolResult(result)
                        }
                    }
                }
            }
        }

        val postProcess by node<Message.Assistant, Unit> { _ ->
            // remove the latest user message injection.
            // We ONLY remove the latest injection because
            // user input may contain valid injection XML (rare but possible)
            llm.writeSession {
                prompt = prompt.withMessages { messages ->
                    // find last user message with injection
                    // previous messages should already be stripped
                    val matchedUserMessage = messages.lastOrNull { m ->
                        m is Message.User
                                && m.parts.size > 1
                                && m.parts[0] is MessagePart.Text
                                && contextInjection.isInjection(m.parts[0])
                    }?.let { it as Message.User }

                    messages.map { m ->
                        if (m === matchedUserMessage) {
                            // drop injection
                            m.copy(parts = m.parts.drop(1))
                        } else {
                            m
                        }
                    }
                }
            }
        }


        // context injection for user input
        edge(nodeStart forwardTo userInputPreprocess)
        // pre round compaction
        edge(userInputPreprocess forwardTo preRoundCompact)
        // run the prompt
        edge(preRoundCompact forwardTo executionStreaming)
        // execute tools if it has tool call
        edge(
            executionStreaming forwardTo toolExecution
                    onCondition { it is StreamExecutionResult.Completed && it.hasToolCall() }
                    transformed { (it as StreamExecutionResult.Completed).toToolCalls() })
        // end if no tool call, go to post process
        edge(
            executionStreaming forwardTo postProcess
                    onCondition { it is StreamExecutionResult.Completed && !it.hasToolCall() }
                    transformed { (it as StreamExecutionResult.Completed).assistant })
        // output budget exhausted with no usable response: compact, then retry
        edge(
            executionStreaming forwardTo exhaustionCompact
                    onCondition { it is StreamExecutionResult.ContextExhausted })
        // after compact, execute again
        edge(exhaustionCompact forwardTo executionStreaming)

        // after tool call, send tool result back to LLM
        edge(toolExecution forwardTo toolResultAppender)
        // before sending tool result to LLM, we might want to compact
        edge(toolResultAppender forwardTo executionStreaming)

        // after clean up injection, end execution
        edge(postProcess forwardTo nodeFinish)
    }

    return AIAgent(
        promptExecutor = promptExecutor,
        agentConfig = AIAgentConfig(
            prompt = prompt(
                id = "chat",
                params = llmParameter
            ) {
                system(systemPrompt)
            },
            model = model,
            maxAgentIterations = 262144, // 256K
            responseProcessor = null
        ),
        strategy = strategy,
        toolRegistry = ToolRegistry {
            // TODO: built-in tools, call sub-agent, mcp tools for exa search
        }
    ) {
        install(ChatMemory) {
            chatHistoryProvider = historyProvider
        }
    }
}
