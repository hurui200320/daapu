package info.skyblond.daapu.agent.persist

import info.skyblond.daapu.agent.chat.*
import info.skyblond.daapu.agent.model.LLM
import info.skyblond.daapu.agent.oneshot.compaction.ChatCompactionService
import info.skyblond.daapu.agent.oneshot.currentPromptTokens
import info.skyblond.daapu.agent.oneshot.sstm.SstmExtractionService
import info.skyblond.daapu.agent.tool.ToolProvider
import info.skyblond.daapu.db.DEFAULT_CHAT_TITLE
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
 * The harness context ([ContextInjection.injectContext]) is applied to the
 * in-loop chat once before the round — `<meta>` time anchors on the
 * historical user messages, the full `<injection>` on the run's user
 * message — and removed again before storing
 * ([ContextInjection.removeInjection]): the stored chat carries no harness
 * XML, only the per-message `createdAt` stamps the anchors are regenerated
 * from. Harness parts are identified by XSD validation plus an exact-match
 * guard (a user message whose text merely resembles the injection is kept).
 */
class PersistChatService(
    private val chatStore: ChatStore,
    private val sstmService: SstmService,
    private val hand: HandService,
    private val compactionService: ChatCompactionService,
    /**
     * SSTM extraction over the raw messages a compaction is about to discard
     * (see `agent/oneshot/sstm/SstmExtractionService.kt`). No lock is held:
     * a concurrent run's injection read may observe a half-merged SSTM, which
     * is fine — the version digest comparison flags it and the next round
     * reads the final state.
     */
    private val sstmExtractionService: SstmExtractionService,
    // the hand's /v1/run policy knobs (config `hand.*`): the hand holds no
    // defaults, every parameter is REQUIRED per request, so the brain
    // sources them here
    private val maxRounds: Int,
    private val maxRetries: Int,
    private val streamIdleTimeoutMs: Long,
) {
    suspend fun runChat(
        chatId: String,
        model: LLM,
        userParts: List<ChatMessagePart>,
        systemPrompt: String,
        toolProvider: ToolProvider,
        callback: StreamingExecutionCallback,
    ) {
        val contextInjection = ContextInjection()

        // empty user input would leave a part-less user message: injectContext
        // skips empty-parts messages (no injection, no createdAt stamp), so
        // the stored chat would fail the user-message-must-carry-createdAt
        // validation
        require(userParts.isNotEmpty()) { "Empty user message is not allowed" }

        val loaded = chatStore.load(chatId) ?: ChatEntry(
            ChatInfo(chatId, DEFAULT_CHAT_TITLE),
            ChatContent(emptyList(), "")
        )
        val chatSstmVersion = loaded.content.sstmVersion
        var chat = loaded.content.messages

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
        // the run's user message: stamped and injected by injectContext below
        chat = chat + ChatMessage(
            role = ChatMessageRole.User,
            parts = userParts,
        )
        chat = contextInjection.injectContext(
            chat,
            InjectionSpec(
                time = ZonedDateTime.now(),
                sstmUpdated = chatSstmVersion != sstm.version,
                // TODO: hook these up (ELTM update tracking)
                eltmUpdated = false,
                memoryList = sstm.memories.map { it.content },
            )
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
                model = model,
                chat = chat,
                systemPrompt = systemPrompt,
                toolProvider = toolProvider,
                callback = callback,
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
                        // the compacted chat history does not contain injection
                        chat = result.newChat
                        // The compaction replaces the whole chat with the
                        // summary when the keep count collapses to zero, so the
                        // run's own user message may be gone. The latest user
                        // message after a compaction is either that run message
                        // (preserved verbatim, parts equal to the input) or the
                        // summary message; anything else means the input must
                        // be re-appended so the retried round still carries it.
                        val runMessageSurvived = chat.lastOrNull { it.role == ChatMessageRole.User }
                            ?.let { it.parts == userParts } ?: false
                        if (!runMessageSurvived) {
                            chat = chat + ChatMessage(
                                role = ChatMessageRole.User,
                                parts = userParts,
                            )
                        }
                        // the extraction may have changed the SSTM, so refresh
                        // the injection with the fresh memory list and the
                        // updated flag (injectContext replaces the stale
                        // injection on the run's message in place)
                        sstm = sstmService.listMemories()
                        chat = contextInjection.injectContext(
                            chat,
                            InjectionSpec(
                                time = ZonedDateTime.now(),
                                sstmUpdated = chatSstmVersion != sstm.version,
                                eltmUpdated = false,
                                memoryList = sstm.memories.map { it.content },
                            )
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
        chat = contextInjection.removeInjection(chat)
        chatStore.store(chatId, ChatContent(chat, sstm.version))
    }

    private sealed interface HandTerminal {
        data object Done : HandTerminal

        data class RunError(val type: String, val message: String) : HandTerminal
    }

    /**
     * One hand `/v1/run` call: streams the events into the callback and the
     * history, and returns the new history with the terminal event. No tool
     * list travels in the request: the hand queries the brain's
     * `GET /api/hand/tools` endpoint before EVERY LLM request and gets the
     * provider's latest advertisements (`HandService` attaches the URL, the
     * in-flight run registry resolves the provider). The tool callback
     * wiring — runId generation, the in-flight registry, the callback
     * URL — is handled by [HandService.run].
     */
    private suspend fun runHandRun(
        model: LLM,
        chat: List<ChatMessage>,
        systemPrompt: String,
        toolProvider: ToolProvider,
        callback: StreamingExecutionCallback,
    ): Pair<List<ChatMessage>, HandTerminal> {
        val request = HandRunRequest(
            model = model.toHandModelSpec(),
            messages = chat,
            systemPrompt = systemPrompt,
            maxTokens = model.maxOutputTokens,
            maxRounds = maxRounds,
            maxRetries = maxRetries,
            streamIdleTimeoutMs = streamIdleTimeoutMs,
        )
        var newChat = chat
        var terminal: HandTerminal? = null
        hand.run(request, toolProvider, model).collect { event ->
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

                is HandEvent.Done -> terminal = HandTerminal.Done
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

    companion object {
        private val logger = KotlinLogging.logger { }
    }
}
