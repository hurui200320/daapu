package info.skyblond.daapu.agent

import dev.langchain4j.model.openai.OpenAiStreamingChatModel
import info.skyblond.daapu.agent.executor.StreamingExecutionCallback
import info.skyblond.daapu.agent.executor.StreamingExecutionResult
import info.skyblond.daapu.agent.executor.StreamingExecutor
import info.skyblond.daapu.agent.lc4j.llm.LLM
import info.skyblond.daapu.agent.lc4j.tool.ToolProvider
import info.skyblond.daapu.chat.ChatMessage
import info.skyblond.daapu.chat.ChatMessagePart
import info.skyblond.daapu.chat.ChatMessageRole
import info.skyblond.daapu.chat.ChatStore
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
 * The model cannot handle content present in the prompt. This is a
 * deterministic failure: the same prompt with the same model fails
 * identically forever.
 */
class ModelCapabilityException(message: String) : Exception(message)

fun checkPromptContentCapabilities(
    chat: List<ChatMessage>,
    model: LLM,
) {
    chat.flatMap { message ->
        // attachments can also arrive nested inside tool results (e.g. an MCP
        // tool returning an image), so descend into the result parts too
        message.parts.flatMap { part ->
            when (part) {
                is ChatMessagePart.ToolResult -> part.parts
                else -> listOf(part)
            }
        }
    }
        .filterIsInstance<ChatMessagePart.Attachment>()
        .map { it.kind }
        .toSet()
        .forEach { kind ->
            if (!model.supportAttachmentKind(kind)) {
                throw ModelCapabilityException(
                    "Model ${model.id} does not support ${kind.name.lowercase()} content."
                )
            }
        }
}

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
    loadMemories: suspend () -> List<String>,
    toolProvider: ToolProvider,
    callback: StreamingExecutionCallback,
    executor: StreamingExecutor
) {
    val contextInjection = ContextInjection()

    // the injection is prepended and stripped after the round, so an empty
    // user input would leave a lone injection message in chat
    require(userParts.isNotEmpty()) { "Empty user message is not allowed" }

    var chat = chatStore.load(chatId)
    chat = chat.refreshSystemPrompt(systemPrompt)

    // TODO: pre-round compaction (detect topic? or just compaction?)
    chat.lastOrNull { it.role == ChatMessageRole.Assistant }?.meta?.let { meta ->
        meta.totalTokens?.let { logger.info { "Usage so far: total:  $it" } }
        meta.inputTokens?.let { logger.info { "Usage so far: input:  $it" } }
        meta.outputTokens?.let { logger.info { "Usage so far: output: $it" } }
    }

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

            // the prompt crowds the context window: compact to free output room, then retry
            StreamingExecutionResult.ContextExhausted -> TODO("Compaction not implemented")

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
    chatStore.store(chatId, chat)
}

/**
 * Refresh the system prompt in place: only a system message at index 0 is
 * kept (never one buried in chat history), its text is updated to the latest
 * version before execution (identical text hits the provider cache), and a
 * missing system message is inserted at the front.
 */
private fun List<ChatMessage>.refreshSystemPrompt(systemPrompt: String): List<ChatMessage> {
    val parts = listOf(ChatMessagePart.Text(systemPrompt))
    val stripped = mapNotNull { message ->
        when { // remove all system message
            message.role == ChatMessageRole.System -> null
            else -> message
        }
    }
    // re-append
    return listOf(
        ChatMessage(role = ChatMessageRole.System, parts = parts)
    ) + stripped
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
