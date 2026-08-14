package info.skyblond.daapu.agent.persist

import dev.langchain4j.model.openai.OpenAiStreamingChatModel
import info.skyblond.daapu.agent.checkPromptContentCapabilities
import info.skyblond.daapu.agent.executor.StreamingExecutionCallback
import info.skyblond.daapu.agent.executor.StreamingExecutionResult
import info.skyblond.daapu.agent.executor.StreamingExecutor
import info.skyblond.daapu.agent.lc4j.llm.LLM
import info.skyblond.daapu.agent.lc4j.tool.ToolProvider
import info.skyblond.daapu.agent.oneshot.ChatCompactor
import info.skyblond.daapu.agent.oneshot.estimateTokens
import info.skyblond.daapu.agent.refreshSystemPrompt
import info.skyblond.daapu.chat.*
import info.skyblond.daapu.memory.sstm.SstmService
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.time.ZonedDateTime

private val logger = KotlinLogging.logger("ChatTurnLoop")

// stream retry backoff: 100ms, 200ms, 400ms, 800ms, 1.6s, 3.2s, 6.4s
private const val BACKOFF_BASE_MS = 100L
private const val BACKOFF_MAX_EXPONENT = 6

/**
 * Run one chat turn on langchain4j, replacing the old koog strategy graph.
 *
 * The neutral chat ([ChatMessage]s) is the canonical in-loop structure:
 * it is loaded from [chatStore], extended with the injected user message
 * and each accepted round's messages, and — only when the whole turn
 * succeeded — stripped of the per-turn XML injection and stored back. A
 * failed or aborted run never reaches [ChatStore.store], so the chat stays
 * at the last good state.
 *
 *
 * The injection is identified by XSD validation ([ContextInjection.isInjection])
 * and stripped only from the latest user message after the round (user input
 * may legitimately contain injection-shaped XML).
 */
suspend fun runChatTurn(
    chatId: String,
    model: LLM,
    streamingChatModel: OpenAiStreamingChatModel,
    userParts: List<ChatMessagePart>,
    systemPrompt: String,
    chatStore: ChatStore,
    sstmService: SstmService,
    toolProvider: ToolProvider,
    callback: StreamingExecutionCallback,
    executor: StreamingExecutor,
    compactor: ChatCompactor,
    /**
     * SSTM extraction over the raw messages a compaction is about to discard
     * (see `agent/oneshot/ExtractSSTM.kt`). The caller (e.g.
     * `ChatRunService`) must hold the memory lock for the whole extraction.
     */
    extractSstm: suspend (droppedMessages: List<ChatMessage>) -> Unit,
    /**
     * Pre-round compaction trigger: compact when the estimated prompt size
     * exceeds this fraction of the model's context window. `0.0` disables
     * the proactive path (the reactive `ContextExhausted` path still
     * compacts).
     */
    compactionTriggerFraction: Double,
    /** Complete rounds kept verbatim at the tail of a compaction. */
    compactionKeepRounds: Int,
) {
    val contextInjection = ContextInjection()

    // the injection is prepended and stripped after the round, so an empty
    // user input would leave a lone injection message in chat
    require(userParts.isNotEmpty()) { "Empty user message is not allowed" }

    val loaded = chatStore.load(chatId)
    val chatSstmVersion = loaded.sstmVersion
    var chat = loaded.chat.refreshSystemPrompt(systemPrompt)

    // history compaction: fire before the round when the estimated prompt
    // size crosses the trigger, so the prompt never crowds the context
    // window (if it still does, the round fails with ContextExhausted,
    // which compacts reactively below).
    // The raw dropped messages feed the SSTM extraction BEFORE they are
    // discarded (see agent/persist/SystemPrompt.kt's memory architecture).
    if (compactionTriggerFraction > 0 &&
        estimateTokens(chat) > model.contextLength * compactionTriggerFraction
    ) {
        logger.info { "Compacting chat $chatId" }
        val result = compactor.compactChat(chat, excludeLastNRound = compactionKeepRounds)
        extractSstm(result.droppedMessages)
        // the compacted history reaches the client via the post-run
        // resync; no dedicated event is emitted
        chat = result.newChat
        logger.info { "Finished compacting chat $chatId" }
    }

    var sstm = sstmService.listMemories()
    val injection = contextInjection.generateInjection(
        time = ZonedDateTime.now(),
        sstmUpdated = chatSstmVersion != sstm.version,
        // TODO: hook these up (ELTM update tracking)
        eltmUpdated = false,
        memoryList = sstm.memories.map { it.content },
    )
    chat = chat + ChatMessage(
        role = ChatMessageRole.User,
        parts = listOf(injection) + userParts,
    )

    var attempts = 0L
    while (true) {
        // the prompt is complete (history + system + new input + any tool
        // results from earlier rounds of this run): check the model can
        // process its content before every request. Images can come from the
        // request, from stored history (sent to a vision model earlier, then
        // the chat switches to a text-only model), or from tool results
        // (e.g. an MCP tool returning an image) — so the check must run per
        // round against the current prompt, not once on the request alone.
        checkPromptContentCapabilities(chat, model)
        val result = executor.executeOnce(
            model = streamingChatModel,
            modelContextLength = model.contextLength,
            modelMaxOutputTokens = model.maxOutputTokens,
            chat = chat,
            toolProvider = toolProvider,
            callback = callback
        )
        when (result) { // TODO: http error like 429 rate limited? classified to EmptyTransient?
            is StreamingExecutionResult.Completed -> {
                // add to the chat
                chat = chat + result.assistant
                // handle tool calls
                if (result.toolCallRequests.isNotEmpty()) {
                    // tool loop skeleton: execute each call in parallel,
                    // stream the results, and run the next round with them appended
                    val results = coroutineScope {
                        result.toolCallRequests.map { request ->
                            async { toolProvider.execute(request) }
                        }.awaitAll()
                    }
                    callback.onToolResults(results)
                    // add tool results to the chat
                    chat = chat + results.map { result ->
                        ChatMessage(
                            role = ChatMessageRole.ToolResult,
                            parts = listOf(result),
                        )
                    }
                } else {
                    // no tool calls, end the turn
                    break
                }
            }

            // the prompt crowds the context window: compact the history to
            // free output room, then retry the round with the compacted
            // prompt. Only once per run — a second exhaustion means the
            // compacted prompt still crowds, which compaction cannot fix.
            StreamingExecutionResult.ContextExhausted -> {
                val result = compactor.compactChat(chat, excludeLastNRound = compactionKeepRounds)
                extractSstm(result.droppedMessages)
                // the compacted history reaches the client via the post-run
                // resync; no dedicated event is emitted
                chat = result.newChat
                // the injection was generated before the compaction; the
                // extraction may have changed the SSTM, so refresh the
                // latest user message's injection with the fresh memory list
                // and the updated flag
                sstm = sstmService.listMemories()
                chat = chat.refreshLatestUserInjection(
                    contextInjection = contextInjection,
                    time = ZonedDateTime.now(),
                    sstmUpdated = chatSstmVersion != sstm.version,
                    memories = sstm.memories.map { it.content },
                )
                // fall through: the next loop iteration retries the round
                // with the compacted prompt
            }

            // the output cap bound on its own: compaction cannot help, fail the run
            StreamingExecutionResult.OutputBudgetExhausted -> error(
                "The model exhausted its output budget without producing usable content " +
                        "while context is not exhausted. This suggest the model cannot " +
                        "fulfill the request with the given output limit. Either give " +
                        "a bigger output limit, or turn down the reasoning effort " +
                        "(or thinking budget, whatever it calls), or change a model"
            )

            // the provider ended the response deliberately (e.g. content_filter):
            // retrying the identical prompt would spin forever, fail the run
            is StreamingExecutionResult.EmptyPermanent -> error(
                "Stream completed with finish_reason=${result.finishReason} " +
                        "but no usable content. The provider ended the response " +
                        "deliberately, so retrying the identical prompt would spin " +
                        "forever. Rephrase the message, or change the model/provider."
            )

            // empty result without a finish reason, network blip, should retry
            StreamingExecutionResult.EmptyTransient -> {
                callback.onStreamError(
                    "Stream ended without a finish_reason, will retry (attempt ${attempts + 1})"
                )
                logger.warn { "Streaming completed with no clear finish reason, retrying..." }
                val exponent = attempts.coerceAtMost(BACKOFF_MAX_EXPONENT.toLong())
                delay(BACKOFF_BASE_MS shl exponent.toInt())
                attempts++
            }
        }
    }

    // only the success path stores: a failed run never reaches here
    chat = chat.stripInjection(contextInjection)
    chatStore.store(chatId, ChatEntry(chat, sstm.version))
}

/**
 * Remove the latest user message's injection part. Only the latest matching
 * message is touched: previous messages were already stripped, and a user
 * message may legitimately contain injection-shaped XML (validated against
 * the XSD, so user text that merely resembles the injection is kept).
 */
private fun List<ChatMessage>.stripInjection(contextInjection: ContextInjection): List<ChatMessage> {
    val matchedIndex = indexOfLast { message ->
        message.role == ChatMessageRole.User
                && message.parts.size > 1
                && message.parts.first() is ChatMessagePart.Text
                && contextInjection.isInjection(message.parts.first() as ChatMessagePart.Text)
    }
    if (matchedIndex < 0) return this
    return mapIndexed { index, message ->
        if (matchedIndex != index) message
        else message.copy(parts = message.parts.drop(1))
    }
}

/**
 * Regenerate the latest user message's injection part in place: after a
 * mid-run compaction the SSTM may have changed (extraction) and the flag
 * must be set, so the injection is rebuilt with the fresh memory list
 * instead of leaving the stale pre-run one.
 */
private fun List<ChatMessage>.refreshLatestUserInjection(
    contextInjection: ContextInjection,
    time: ZonedDateTime,
    sstmUpdated: Boolean,
    memories: List<String>,
): List<ChatMessage> {
    val matchedIndex = indexOfLast { message ->
        message.role == ChatMessageRole.User
                && message.parts.size > 1
                && message.parts.first() is ChatMessagePart.Text
                && contextInjection.isInjection(message.parts.first() as ChatMessagePart.Text)
    }
    if (matchedIndex < 0) return this
    val fresh = contextInjection.generateInjection(
        time = time,
        sstmUpdated = sstmUpdated,
        eltmUpdated = false,
        memoryList = memories,
    )
    return mapIndexed { index, message ->
        if (matchedIndex != index) message
        else message.copy(parts = listOf(fresh) + message.parts.drop(1))
    }
}
