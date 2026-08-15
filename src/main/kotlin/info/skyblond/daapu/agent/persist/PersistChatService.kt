package info.skyblond.daapu.agent.persist

import info.skyblond.daapu.agent.chat.*
import info.skyblond.daapu.agent.model.LLM
import info.skyblond.daapu.agent.oneshot.compaction.ChatCompactionService
import info.skyblond.daapu.agent.oneshot.currentPromptTokens
import info.skyblond.daapu.agent.oneshot.sstm.SstmExtractionService
import info.skyblond.daapu.agent.tool.ToolProvider
import info.skyblond.daapu.hand.*
import info.skyblond.daapu.memory.sstm.SstmService
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.ZonedDateTime


/**
 * Run one chat turn on the hand-pi execution service (`hand-pi/`), which
 * owns the LLM round loop: streaming, tool-call execution (via the tool
 * callback HTTP route back into this process), retries, and the
 * context-vs-output exhaustion classification. The brain keeps everything
 * content-related: the neutral history, the system prompt, injection,
 * compaction policy, and SSTM extraction.
 *
 * The neutral chat ([ChatMessage]s) is the canonical in-loop structure:
 * it is loaded from [chatStore], extended with the injected user message
 * and each run's accepted messages, and — only when the whole turn
 * succeeded — stripped of the per-turn XML injection and stored back. A
 * failed or aborted run never reaches [ChatStore.store], so the chat stays
 * at the last good state.
 *
 * Reactive compaction: the hand reports `context_exhausted` when the
 * prompt overflows the window (a `length` finish near the window or a
 * gateway-side 400/413 rejection). The loop then discards the failed
 * attempt's messages, compacts, extracts SSTM from the dropped messages,
 * refreshes the injection in place, and starts a fresh hand run — with no
 * attempt cap, exactly like the old in-process loop (a second exhaustion
 * compacts again).
 *
 * The injection is identified by XSD validation ([ContextInjection.isInjection])
 * and stripped only from the latest user message after the round (user input
 * may legitimately contain injection-shaped XML).
 */
class PersistChatService(
    private val chatStore: ChatStore,
    private val sstmService: SstmService,
    private val hand: HandClient,
    private val compactionService: ChatCompactionService,
    /**
     * SSTM extraction over the raw messages a compaction is about to discard
     * (see `agent/oneshot/sstm/SstmExtractionService.kt`). No lock is held:
     * a concurrent run's injection read may observe a half-merged SSTM, which
     * is fine — the version digest comparison flags it and the next round
     * reads the final state.
     */
    private val sstmExtractionService: SstmExtractionService,
) {
    suspend fun runChat(
        chatId: String,
        model: LLM,
        userParts: List<ChatMessagePart>,
        systemPrompt: String,
        toolProvider: ToolProvider,
        callback: StreamingExecutionCallback,
        /** The in-flight run's id; the hand's tool callbacks carry it. */
        runId: String,
        /** This brain's tool callback endpoint the hand POSTs to. */
        toolCallbackUrl: String,
    ) {
        val contextInjection = ContextInjection()

        // the injection is prepended and stripped after the round, so an empty
        // user input would leave a lone injection message in chat
        require(userParts.isNotEmpty()) { "Empty user message is not allowed" }

        val loaded = chatStore.load(chatId)
        val chatSstmVersion = loaded.sstmVersion
        var chat = loaded.chat

        // history compaction: fire before the round when the measured prompt
        // size (the last round's provider-reported input tokens) crosses the
        // trigger, so the prompt never crowds the context window (if it still
        // does, the hand reports context_exhausted, which compacts reactively
        // below). The not-yet-appended input is not counted; the trigger
        // headroom absorbs the difference.
        // The raw dropped messages feed the SSTM extraction BEFORE they are
        // discarded (see agent/persist/SystemPrompt.kt's memory architecture).
        if (model.compactionTriggerFraction > 0 &&
            currentPromptTokens(chat) > model.contextLength * model.compactionTriggerFraction
        ) {
            logger.info { "Compacting chat $chatId" }
            val result = compactionService.compactChat(chat, model.compactionKeepRounds)
            sstmExtractionService.processDiscardedMessages(result.droppedMessages)
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

        while (true) {
            // the prompt is complete (history + the out-of-band system prompt +
            // new input + any tool results from earlier rounds of this run):
            // check the model can process its content before every hand run.
            // Images can come from
            // the request, from stored history (sent to a vision model earlier,
            // then the chat switches to a text-only model), or from tool
            // results — the callback route answers `fatal` for tool-result
            // attachments, and this check covers everything else.
            model.checkPromptContentCapabilities(chat)
            val attemptStartSize = chat.size
            val (newChat, terminal) = runHandRun(
                chatId = chatId,
                model = model,
                chat = chat,
                systemPrompt = systemPrompt,
                toolProvider = toolProvider,
                callback = callback,
                runId = runId,
                toolCallbackUrl = toolCallbackUrl,
            )
            chat = newChat
            when (terminal) {
                is HandTerminal.Done -> break
                is HandTerminal.RunError -> {
                    // the failed attempt's messages must not leak into the
                    // compaction or the stored history
                    chat = chat.take(attemptStartSize)
                    if (terminal.type == "context_exhausted") {
                        logger.info { "Hand reports context exhaustion, compacting chat $chatId" }
                        val result = compactionService.compactChat(chat, model.compactionKeepRounds)
                        sstmExtractionService.processDiscardedMessages(result.droppedMessages)
                        // the compacted history reaches the client via the post-run
                        // resync; no dedicated event is emitted
                        chat = result.newChat
                        // the injection was generated before the compaction; the
                        // extraction may have changed the SSTM, so refresh the
                        // latest user message's injection with the fresh memory list
                        // and the updated flag. A full-body compaction (keep=0)
                        // replaces the whole chat — injected message included — so
                        // the injection is re-appended with the user's parts.
                        sstm = sstmService.listMemories()
                        chat = chat.refreshLatestUserInjection(
                            contextInjection = contextInjection,
                            time = ZonedDateTime.now(),
                            sstmUpdated = chatSstmVersion != sstm.version,
                            memories = sstm.memories.map { it.content },
                            userParts = userParts,
                        )
                        // fall through: the next loop iteration starts a fresh
                        // hand run with the compacted prompt
                    } else {
                        runError(terminal.type, terminal.message)
                    }
                }
            }
        }

        // only the success path stores: a failed run never reaches here
        chat = chat.stripInjection(contextInjection)
        chatStore.store(chatId, ChatStoreEntry(chat, sstm.version))
    }

    private sealed interface HandTerminal {
        data class Done(val finishReason: String) : HandTerminal

        data class RunError(val type: String, val message: String) : HandTerminal
    }

    /**
     * One hand `/v1/run` call: advertises the provider's tools, streams the
     * events into the callback and the history, and returns the new history
     * with the terminal event.
     */
    private suspend fun runHandRun(
        chatId: String,
        model: LLM,
        chat: List<ChatMessage>,
        systemPrompt: String,
        toolProvider: ToolProvider,
        callback: StreamingExecutionCallback,
        runId: String,
        toolCallbackUrl: String,
    ): Pair<List<ChatMessage>, HandTerminal> {
        val specs = toolProvider.specifications()
        val request = HandRunRequest(
            model = model.toHandModelSpec(),
            messages = chat,
            systemPrompt = systemPrompt,
            // tools are attached only when non-empty (some gateways reject
            // `tools: []`), and the callback URL is required iff tools exist
            tools = specs.takeIf { it.isNotEmpty() }
                ?.map { HandToolSpec(it.name, it.description, it.schema) },
            toolCallbackUrl = specs.takeIf { it.isNotEmpty() }?.let { toolCallbackUrl },
            runId = runId,
            chatId = chatId,
        )
        var newChat = chat
        var terminal: HandTerminal? = null
        hand.run(request).collect { event ->
            when (event) {
                is HandEvent.TextDelta -> callback.onTextDelta(event.text)
                is HandEvent.ReasoningDelta -> callback.onReasoningDelta(event.text)

                // the hand retries transient failures itself; the retry event
                // keeps the frontend's "clearing the round" behavior
                is HandEvent.Retry -> callback.onStreamError(event.message)

                // per-round authoritative message: persist it (the frontend
                // resyncs after the run; the event is not forwarded)
                is HandEvent.AssistantMessage -> newChat = newChat + event.message

                is HandEvent.ToolCall -> callback.onToolCall(event.name, event.args)
                is HandEvent.ToolResult -> {
                    val result = ChatMessagePart.ToolResult(
                        id = event.id,
                        tool = event.name,
                        parts = event.parts,
                        isError = event.isError,
                    )
                    callback.onToolResults(listOf(result))
                    newChat = newChat + ChatMessage(ChatMessageRole.ToolResult, listOf(result))
                }

                is HandEvent.Done -> terminal = HandTerminal.Done(event.finishReason)
                is HandEvent.RunError -> terminal = HandTerminal.RunError(event.type, event.message)
            }
        }
        return newChat to (terminal
            ?: throw HandUpstreamException("hand run ended without a terminal event"))
    }

    /**
     * Maps a hand run error type onto the run failure. `context_exhausted` is
     * handled by the caller (reactive compaction); the rest fail the run with
     * the same explanatory wording the old in-process loop used.
     */
    private fun runError(type: String, message: String): Nothing = when (type) {
        "output_budget_exhausted" -> throw HandRunException(
            type,
            "The model exhausted its output budget without producing usable content " +
                    "while context is not exhausted. This suggest the model cannot " +
                    "fulfill the request with the given output limit. Either give " +
                    "a bigger output limit, or turn down the reasoning effort " +
                    "(or thinking budget, whatever it calls), or change a model"
        )

        "content_filter" -> throw HandRunException(
            type,
            "Stream completed with finish_reason=content_filter but no usable content. " +
                    "The provider ended the response deliberately, so retrying the identical " +
                    "prompt would spin forever. Rephrase the message, or change the model/provider."
        )

        "empty_response" -> throw HandRunException(
            type,
            "Stream completed with finish_reason=stop but no usable content. " +
                    "The provider ended the response deliberately, so retrying the identical " +
                    "prompt would spin forever. Rephrase the message, or change the model/provider."
        )

        "tool_transport" -> throw HandRunException(type, "Tool transport failure: $message")
        "round_limit" -> throw HandRunException(type, "Round limit reached: $message")
        else -> throw HandRunException(type, "Hand run failed ($type): $message")
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
     *
     * When the compaction dropped the injected message entirely (a full-body
     * compaction: the keep count collapses to zero, which replaces the whole
     * chat — the injected user message included — with the summary), a fresh
     * injection is appended together with the run's [userParts], so the
     * retried round still carries the user input.
     */
    private fun List<ChatMessage>.refreshLatestUserInjection(
        contextInjection: ContextInjection,
        time: ZonedDateTime,
        sstmUpdated: Boolean,
        memories: List<String>,
        userParts: List<ChatMessagePart>,
    ): List<ChatMessage> {
        val matchedIndex = indexOfLast { message ->
            message.role == ChatMessageRole.User
                    && message.parts.size > 1
                    && message.parts.first() is ChatMessagePart.Text
                    && contextInjection.isInjection(message.parts.first() as ChatMessagePart.Text)
        }
        val fresh = contextInjection.generateInjection(
            time = time,
            sstmUpdated = sstmUpdated,
            eltmUpdated = false,
            memoryList = memories,
        )
        if (matchedIndex < 0) {
            return this + ChatMessage(
                role = ChatMessageRole.User,
                parts = listOf(fresh) + userParts,
            )
        }
        return mapIndexed { index, message ->
            if (matchedIndex != index) message
            else message.copy(parts = listOf(fresh) + message.parts.drop(1))
        }
    }

    companion object {
        private val logger = KotlinLogging.logger { }
    }
}
