package info.skyblond.daapu.agent

import dev.langchain4j.exception.HttpException
import dev.langchain4j.model.chat.response.ChatResponse
import dev.langchain4j.model.openai.OpenAiStreamingChatModel
import info.skyblond.daapu.history.AttachmentKind
import info.skyblond.daapu.history.HistoryMessage
import info.skyblond.daapu.history.HistoryPart
import info.skyblond.daapu.history.HistoryRole
import info.skyblond.daapu.history.HistoryStore
import info.skyblond.daapu.langchain4j.ModelMetadata
import info.skyblond.daapu.langchain4j.StreamSignal
import info.skyblond.daapu.langchain4j.checkPromptContentCapabilities
import info.skyblond.daapu.langchain4j.findErrorChunk
import info.skyblond.daapu.langchain4j.streamSignals
import info.skyblond.daapu.langchain4j.toLangchain4jMessages
import info.skyblond.daapu.langchain4j.toNeutralAssistantMessage
import info.skyblond.daapu.langchain4j.withGeneratedToolCallIds
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.ZonedDateTime
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect

private val logger = KotlinLogging.logger("ChatTurnLoop")

// stream retry backoff: 100ms, 200ms, 400ms, 800ms, 1.6s, 3.2s, 6.4s
private const val BACKOFF_BASE_MS = 100L
private const val BACKOFF_MAX_EXPONENT = 6

/**
 * Run one chat turn on langchain4j, replacing the old koog strategy graph.
 *
 * The neutral history ([HistoryMessage]s) is the canonical in-loop structure:
 * it is loaded from [historyStore], extended with the injected user message
 * and each accepted round's messages, and — only when the whole turn
 * succeeded — stripped of the per-turn XML injection and stored back. A
 * failed or aborted run never reaches [HistoryStore.store], so history stays
 * at the last good state.
 *
 * Round loop (invariants ported from the koog strategy, see AGENTS.md):
 * 1. Pre-send: refresh the system prompt in place, prepend the XML injection
 *    to the new user message, and check the FULL prompt (loaded history + new
 *    input — images can re-enter from history after a model switch) against
 *    the model's capabilities before any LLM request.
 * 2. Stream the round (deltas forwarded to [callback] live). Before
 *    accepting anything: scan the raw SSE events for a mid-stream
 *    `{"error": ...}` chunk (spike #2 — a numeric code becomes an
 *    [HttpException] so the retry policy classifies it, a code-less chunk is
 *    transient), then treat a missing `finish_reason` as a truncated stream
 *    (spike #1 — langchain4j silently accepts clean EOF without one).
 * 3. Classify ([classifyStreamResult]): accept only non-blank text or tool
 *    calls; `length` routes by the usage math; named empty reasons fail the
 *    run; reason-less empties are transient.
 * 4. Transient failures ([isRetryableStreamError]) retry forever with
 *    exponential backoff; permanent ones fail the run.
 * 5. Accepted tool calls execute via [toolProvider] (results appended as
 *    `tool` messages), then the next round starts with the extended history.
 *
 * The injection is identified by XSD validation ([ContextInjection.isInjection])
 * and stripped only from the latest user message after the round (user input
 * may legitimately contain injection-shaped XML).
 */
suspend fun runChatTurn(
    chatId: String,
    model: ModelMetadata,
    streamingChatModel: OpenAiStreamingChatModel,
    userParts: List<HistoryPart>,
    systemPrompt: String,
    historyStore: HistoryStore,
    loadMemories: suspend () -> List<String>,
    toolProvider: ToolProvider,
    callback: StreamExecutionCallback,
) {
    val contextInjection = ContextInjection()

    // the injection is prepended and stripped after the round, so an empty
    // user input would leave a lone injection message in history
    require(userParts.isNotEmpty()) { "Empty user message is not allowed" }

    var history = historyStore.load(chatId)
    history = history.refreshSystemPrompt(systemPrompt)
    logAssistantTokenCount(history)

    // TODO: add a class for memories, use lock to block all readers when memory
    //       is too long and triggers compaction. Hold the lock until done.
    // TODO: history compaction when context is exhausted (see ContextExhausted
    //       handling below; currently unrecoverable).
    val memories = loadMemories()
    val injection = contextInjection.generateInjection(
        time = ZonedDateTime.now(),
        // TODO: hook these up (SSTM/ELTM update tracking)
        sstmUpdated = false,
        eltmUpdated = false,
        memoryList = memories,
    )
    history = history + HistoryMessage(
        role = HistoryRole.User,
        parts = listOf(injection) + userParts,
    )

    // the prompt is complete (history + system + new input): check the model
    // can actually process its content. Images can come from history (stored
    // when a vision model was used), not just from the request, so this must
    // be checked on the full prompt rather than on the request alone.
    checkPromptContentCapabilities(history.attachmentKinds(), model)

    var attempts = 0L
    while (true) {
        val completed: StreamExecutionResult.Completed = try {
            streamRoundOnce(streamingChatModel, history, toolProvider, callback, model)
        } catch (t: Throwable) {
            // the retry policy lives in isRetryableStreamError (unit-tested)
            if (isRetryableStreamError(t)) {
                callback.onStreamError(t)
                logger.error(t) { "Error during execution, retrying..." }
                val exponent = attempts.coerceAtMost(BACKOFF_MAX_EXPONENT.toLong())
                delay(BACKOFF_BASE_MS shl exponent.toInt())
                attempts++
                if (attempts % 10L == 0L) {
                    logger.warn { "Execution still failing after $attempts attempts (latest: ${t.message})" }
                }
                continue
            } else throw t
        }

        // accepted: the assistant message keeps its reasoning part in stored
        // history (reasoning stays visible for debugging); the gateway's
        // reasoning field is re-sent on later requests via sendThinking.
        val assistant = completed.assistant.withGeneratedToolCallIds()
        history = history + completed.response.toNeutralAssistantMessage(assistant)

        if (completed.hasToolCall()) {
            // tool loop skeleton: execute each call in parallel (the MCP
            // feature #8 only adds a real provider), stream the results, and
            // run the next round with them appended
            val results = coroutineScope {
                assistant.toolExecutionRequests().map { request ->
                    async { toolProvider.execute(request) }
                }.awaitAll()
            }
            callback.onToolResults(results)
            history = history + results.toNeutralToolMessages()
        } else {
            break
        }
    }

    // only the success path stores: a failed run never reaches here
    history = history.stripInjection(contextInjection)
    historyStore.store(chatId, history)
}

/**
 * Stream one round and classify the result, throwing for every non-accepted
 * outcome. The retry policy in the caller decides which throws are retried.
 */
private suspend fun streamRoundOnce(
    streamingChatModel: OpenAiStreamingChatModel,
    history: List<HistoryMessage>,
    toolProvider: ToolProvider,
    callback: StreamExecutionCallback,
    model: ModelMetadata,
): StreamExecutionResult.Completed {
    val response = streamingChatModel
        .streamSignals(history.toLangchain4jMessages(), toolProvider.specifications())
        .collectSignals(callback)

    // spike #2: some gateways deliver errors as a mid-stream SSE
    // {"error": ...} chunk after a 2xx response; langchain4j completes the
    // stream normally and keeps the chunk in rawServerSentEvents(). Without
    // this scan the response would look usable (non-blank text, no finish
    // reason) and would be accepted. A numeric code becomes an HttpException
    // (the retry policy walks the cause chain for the status); a code-less
    // chunk is transient, matching the old koog client.
    response.findErrorChunk()?.let { (code, data) ->
        throw if (code != null) {
            HttpException(code, data)
        } else {
            MidStreamErrorChunkException("Gateway sent a mid-stream error chunk: $data")
        }
    }

    // spike #1: langchain4j has no requireEndFrame equivalent — a stream that
    // ends without a finish_reason (clean EOF, or an unknown finish reason
    // mapped to null) completes silently, so the truncation detection is ours.
    if (response.finishReason() == null) {
        throw EmptyStreamResponseException(
            "Stream ended without a finish_reason (truncated stream or unknown finish reason)"
        )
    }

    return when (val result = classifyStreamResult(response, model.contextLength, model.maxOutputTokens)) {
        is StreamExecutionResult.Completed -> result

        // the prompt crowds the context window: compact to free output room,
        // then retry (compaction is a TODO, so this is unrecoverable for now)
        StreamExecutionResult.ContextExhausted -> throw IllegalStateException(
            "The prompt is crowding the context window (prompt tokens > " +
                    "contextLength - maxOutputTokens), so the model ran out of output " +
                    "room. History compaction is not implemented yet, making context " +
                    "exhaustion unrecoverable: start a new chat or shorten the prompt."
        )

        // the output cap bound on its own: compaction cannot help, fail the run
        StreamExecutionResult.OutputBudgetExhausted -> throw OutputExhaustionException(
            "The model exhausted its output budget without producing usable content " +
                    "while context is not exhausted. This suggest the model cannot " +
                    "fulfill the request with the given output limit. Either give " +
                    "a bigger output limit, or turn down the reasoning effort " +
                    "(or thinking budget, whatever it calls), or change a model"
        )

        // the provider ended the response deliberately (e.g. content_filter):
        // retrying the identical prompt would spin forever, fail the run
        is StreamExecutionResult.EmptyPermanent -> throw EmptyPermanentResponseException(
            "Stream completed with finish_reason=${result.finishReason} " +
                    "but no usable content. The provider ended the response " +
                    "deliberately, so retrying the identical prompt would spin " +
                    "forever. Rephrase the message, or change the model/provider."
        )

        // only reachable if the truncation check above is ever relaxed
        StreamExecutionResult.EmptyTransient -> throw EmptyStreamResponseException(
            "Stream completed with no usable content (finishReason=${response.finishReason()})"
        )
    }
}

/**
 * Collect the streaming signals, forwarding deltas to [callback], returning
 * the final response or rethrowing the failure.
 */
private suspend fun Flow<StreamSignal>.collectSignals(callback: StreamExecutionCallback): ChatResponse {
    var result: ChatResponse? = null
    collect { signal ->
        when (signal) {
            is StreamSignal.TextDelta -> callback.onTextDelta(signal.text)
            is StreamSignal.ThinkingDelta -> callback.onReasoningDelta(signal.text)
            is StreamSignal.ToolCallDone -> callback.onToolCall(signal.name, signal.args)
            is StreamSignal.Completed -> result = signal.response
            is StreamSignal.Failed -> throw signal.error
        }
    }
    return result ?: error("Streaming round ended without a terminal signal")
}

/**
 * Refresh the system prompt in place: only a system message at index 0 is
 * kept (never one buried in chat history), its text is updated to the latest
 * version before execution (identical text hits the provider cache), and a
 * missing system message is inserted at the front.
 */
private fun List<HistoryMessage>.refreshSystemPrompt(systemPrompt: String): List<HistoryMessage> {
    val refreshed = mapIndexedNotNull { index, message ->
        when {
            message.role == HistoryRole.System && index == 0 ->
                message.copy(parts = listOf(HistoryPart.Text(systemPrompt)))
            message.role == HistoryRole.System -> null
            else -> message
        }
    }
    return if (refreshed.firstOrNull()?.role == HistoryRole.System) {
        refreshed
    } else {
        listOf(HistoryMessage(role = HistoryRole.System, parts = listOf(HistoryPart.Text(systemPrompt)))) + refreshed
    }
}

// TODO: pre-round compaction (detect topic? or just compaction?) — currently
//       only logs the last assistant message's token count.
private fun logAssistantTokenCount(history: List<HistoryMessage>) {
    val totalTokens = history.lastOrNull { it.role == HistoryRole.Assistant }?.meta?.totalTokens ?: return
    if (totalTokens > 0) logger.info { "Last assistant message token total: $totalTokens" }
}

/**
 * The attachment kinds present in the top-level parts of the full prompt
 * (loaded history + new input). Mirrors the old koog check, which also
 * scanned only top-level message parts.
 */
private fun List<HistoryMessage>.attachmentKinds(): Set<AttachmentKind> =
    flatMap { it.parts }
        .filterIsInstance<HistoryPart.Attachment>()
        .map { it.kind }
        .toSet()

/**
 * Remove the latest user message's injection part. Only the latest matching
 * message is touched: previous messages were already stripped, and a user
 * message may legitimately contain injection-shaped XML (validated against
 * the XSD, so user text that merely resembles the injection is kept).
 */
private fun List<HistoryMessage>.stripInjection(contextInjection: ContextInjection): List<HistoryMessage> {
    val index = indexOfLast { message ->
        message.role == HistoryRole.User
                && message.parts.size > 1
                && message.parts.first() is HistoryPart.Text
                && contextInjection.isInjection(message.parts.first() as HistoryPart.Text)
    }
    if (index < 0) return this
    val message = this[index]
    return toMutableList().also { it[index] = message.copy(parts = message.parts.drop(1)) }
}

private fun List<ToolResultInfo>.toNeutralToolMessages(): List<HistoryMessage> = map { result ->
    HistoryMessage(
        role = HistoryRole.Tool,
        parts = listOf(
            HistoryPart.ToolResult(
                id = result.id,
                tool = result.name,
                parts = listOf(HistoryPart.Text(result.content)),
                isError = result.isError,
            )
        ),
    )
}
