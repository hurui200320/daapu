package info.skyblond.daapu.agent.persist

import info.skyblond.daapu.agent.chat.*
import info.skyblond.daapu.agent.model.LLM
import info.skyblond.daapu.agent.oneshot.compaction.ChatCompactionService
import info.skyblond.daapu.agent.oneshot.currentPromptTokens
import info.skyblond.daapu.agent.oneshot.eltm.MemoryExtractionService
import info.skyblond.daapu.agent.oneshot.rewrite.QueryRewriteService
import info.skyblond.daapu.agent.persona.Persona
import info.skyblond.daapu.agent.tool.ToolProvider
import info.skyblond.daapu.db.DEFAULT_CHAT_TITLE
import info.skyblond.daapu.hand.*
import info.skyblond.daapu.memory.eltm.EltmEntity
import info.skyblond.daapu.memory.eltm.EltmNote
import info.skyblond.daapu.memory.eltm.EltmService
import info.skyblond.daapu.memory.eltm.EntityWithScore
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.ZonedDateTime


/**
 * Run one chat turn on the hand-pi execution service (`hand-pi/`), which
 * owns the LLM round loop: streaming, tool-call execution (via the tool
 * callback HTTP route back into this process), retries, and the
 * context-vs-output exhaustion classification. The brain keeps everything
 * content-related: the neutral history, the system prompt, injection,
 * compaction policy, and memory extraction.
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
 * attempt's messages, compacts, extracts memories from the dropped
 * messages, refreshes the injection in place, and starts a fresh hand run —
 * with no attempt cap, exactly like the old in-process loop (a second
 * exhaustion compacts again).
 *
 * The harness context ([ContextInjection.injectContext]) is applied to the
 * in-loop chat once before the round — `<meta>` time anchors on the
 * historical user messages, the `<injection>` on the run's user message
 * (the full ELTM shape when the persona's whitelist serves `gsg`, the
 * time-only simple shape otherwise) — and removed again before storing
 * ([ContextInjection.removeInjection]): the stored chat carries no harness
 * XML, only the per-message `createdAt` stamps the anchors are regenerated
 * from. Harness parts are identified by XSD validation plus an exact-match
 * guard (a user message whose text merely resembles the injection is kept).
 */
class PersistChatService(
    private val chatStore: ChatStore,
    private val eltmService: EltmService,
    private val queryRewriteService: QueryRewriteService,
    private val hand: HandService,
    private val compactionService: ChatCompactionService,
    /**
     * Renders the run's system prompt from the run's persona
     * ([MainAgentSystemPromptService.render]): the
     * persona text plus the GSG harness introduction.
     */
    private val systemPromptService: MainAgentSystemPromptService,
    /**
     * Memory extraction over the raw messages a compaction is about to
     * discard (see `agent/oneshot/eltm/MemoryExtractionService.kt`): the
     * extractor summarizes them and the ELTM writer records the facts into
     * the diary directly. No lock is held: concurrent writes are safe
     * because the writer deduplicates against the store.
     */
    private val memoryExtractionService: MemoryExtractionService,
    /**
     * How many trailing user rounds of the chat feed the query rewrite
     * one-shot (config `memory.eltm.rewriteRounds`).
     */
    private val rewriteRounds: Int,
    /**
     * How many related entities the ELTM context injection puts into the
     * `<memories>`' `<related-entities>` (config
     * `memory.eltm.relatedEntitiesLimit`); `0` skips the entity search.
     */
    private val relatedEntitiesLimit: Int,
    /**
     * How many related diary notes the ELTM context injection puts into the
     * `<memories>`' `<related-notes>` (config `memory.eltm.relatedNotesLimit`);
     * `0` skips the note search, and with both limits `0` the query rewrite
     * one-shot is skipped too (it exists only to feed these searches).
     */
    private val relatedNotesLimit: Int,
    // the hand's /v1/run policy knobs (config `hand.*`): the hand holds no
    // defaults, every parameter is REQUIRED per request, so the brain
    // sources them here
    private val maxRounds: Int,
    private val policy: HandRunPolicy,
) {
    suspend fun runChat(
        chatId: String,
        model: LLM,
        userParts: List<ChatMessagePart>,
        /**
         * The run's persona: owns the system prompt (rendered once per run by
         * [systemPromptService], never stored) and is stamped on the
         * successful store's `chats.persona_id` record.
         */
        persona: Persona,
        toolProvider: ToolProvider,
        callback: StreamingExecutionCallback,
    ) {
        val systemPrompt = systemPromptService.render(persona)
        val contextInjection = ContextInjection()

        // empty user input would leave a part-less user message: injectContext
        // skips empty-parts messages (no injection, no createdAt stamp), so
        // the stored chat would fail the user-message-must-carry-createdAt
        // validation
        require(userParts.isNotEmpty()) { "Empty user message is not allowed" }

        val loaded = chatStore.load(chatId) ?: ChatEntry(
            ChatInfo(chatId, DEFAULT_CHAT_TITLE, persona.id),
            ChatContent(emptyList(), "", persona.id)
        )
        val chatEltmVersion = loaded.content.eltmVersion
        var chat = loaded.content.messages

        // history compaction: fire before the round when the measured prompt
        // size (the last round's provider-reported input tokens) crosses the
        // trigger, so the prompt never crowds the context window (if it still
        // does, the hand reports context_exhausted, which compacts reactively
        // below). The not-yet-appended input is not counted; the trigger
        // headroom absorbs the difference.
        // The raw dropped messages feed the memory extraction BEFORE they are
        // discarded (see agent/persist/MainAgentSystemPromptService.kt's
        // memory architecture).
        if (model.compactionTriggerFraction > 0 &&
            currentPromptTokens(chat) > model.contextLength * model.compactionTriggerFraction
        ) {
            logger.info { "Compacting chat $chatId" }
            chat = compactAndExtract(chat, model)
            logger.info { "Finished compacting chat $chatId" }
        }

        var eltmVersion = eltmService.version()
        // a persona without `gsg` access never sees the ELTM: its system
        // prompt documents only the time basics, and its injection carries
        // neither `eltm-updated` nor `<memories>` (the simple injection
        // shape — all ELTM spec fields null)
        val gsgAccess = persona.serves("gsg")
        // the run's user message: stamped and injected by injectContext below
        chat = chat + ChatMessage(
            role = ChatMessageRole.User,
            parts = userParts,
        )

        // the capability check runs BEFORE the rewrite and the first hand
        // round: a chat the run model cannot process must fail without
        // spending a rewrite call (and the rewrite one-shot checks its OWN
        // model against the same chat inside rewriteQuery)
        model.checkPromptContentCapabilities(chat)

        // the query rewrite one-shot: rewrites the run's latest input into
        // standalone retrieval queries from the last `rewriteRounds` user
        // rounds (config `memory.eltm.rewriteRounds`), then searches the ELTM
        // for related entities and diary notes to inject under `<memories>`
        // (config `memory.eltm.relatedEntitiesLimit`/`relatedNotesLimit`).
        // With both limits 0 the whole chain is skipped: no rewrite call, no
        // embedding calls, empty related sections. A persona WITHOUT `gsg`
        // access skips it too: the retrieved memories would never be injected
        // (its injection is the time-only simple shape), so the rewrite and
        // embedding calls would be wasted work.
        val skipEltmSearch = relatedEntitiesLimit == 0 && relatedNotesLimit == 0
        val (relatedEntities, relatedNotes) = if (!gsgAccess || skipEltmSearch) {
            emptyList<EntityWithScore>() to emptyList<RelatedNoteView>()
        } else {
            queryRewriteService.rewriteQuery(chat, rewriteRounds)?.let { query ->
                val entities = if (relatedEntitiesLimit > 0) {
                    eltmService.searchEntities(query, relatedEntitiesLimit)
                } else {
                    emptyList()
                }
                val notes = if (relatedNotesLimit > 0) {
                    resolveRelatedNotes(
                        notes = eltmService.searchNotes(
                            query, null, null, null, null, relatedNotesLimit
                        ),
                        knownEntities = entities,
                    )
                } else {
                    emptyList()
                }
                entities to notes
            } ?: Pair(emptyList(), emptyList())
        }

        // the injection spec for the CURRENT eltmVersion snapshot: the ELTM
        // fields are gated on gsgAccess (all-null = the time-only simple
        // injection, no ELTM content at all), and the related entities/notes
        // were retrieved for THIS run's input. A local function, not a val,
        // so the reactive `context_exhausted` recovery below re-reads the
        // refreshed eltmVersion.
        fun buildInjectionSpec(): InjectionSpec = InjectionSpec(
            time = ZonedDateTime.now(),
            eltmUpdated = if (gsgAccess) chatEltmVersion != eltmVersion else null,
            relatedEntities = if (gsgAccess) relatedEntities else null,
            relatedNotes = if (gsgAccess) relatedNotes else null,
        )

        chat = contextInjection.injectContext(chat, buildInjectionSpec())

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
                        // the compacted chat history does not contain injection
                        chat = compactAndExtract(chat, model)
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
                        // the extraction may have changed the ELTM, so refresh
                        // the injection with the fresh version flag
                        // (injectContext replaces the stale
                        // injection on the run's message in place). The
                        // related entities/notes were retrieved for THIS run's
                        // input, which the compaction never changes, so the
                        // pre-round search results stay valid — no re-search,
                        // no mid-loop rewrite/embed call.
                        eltmVersion = eltmService.version()
                        chat = contextInjection.injectContext(chat, buildInjectionSpec())
                        // fall through: the next loop iteration starts a fresh
                        // hand run with the compacted prompt
                    } else {
                        runError(terminal.type, terminal.message)
                    }
                }
            }
        }

        // only the success path stores: a failed run never reaches here.
        // The persona record is stamped from the run's persona, so
        // `chats.persona_id` always reflects the last successful run (the
        // column is a record for the UI's picker, never the run's source of
        // truth — the prompt and the tool whitelist came from the resolved
        // persona, not from this column).
        chat = contextInjection.removeInjection(chat)
        chatStore.store(chatId, ChatContent(chat, eltmVersion, persona.id))
    }

    private sealed interface HandTerminal {
        data object Done : HandTerminal

        data class RunError(val type: String, val message: String) : HandTerminal
    }

    /**
     * Compact the chat and run the memory extraction over the dropped
     * messages BEFORE they are discarded (see
     * `agent/persist/MainAgentSystemPromptService.kt`'s memory
     * architecture); returns the compacted history (no injection). The
     * compacted history reaches the client via the post-run resync; no
     * dedicated event is emitted. Both the proactive trigger and the
     * reactive `context_exhausted` recovery go through here — the two
     * paths differ only in their logging and what they re-inject after.
     */
    private suspend fun compactAndExtract(
        chat: List<ChatMessage>,
        model: LLM,
    ): List<ChatMessage> {
        val result = compactionService.compactChat(chat, model.compactionKeepRounds)
        memoryExtractionService.processDiscardedMessages(result.droppedMessages)
        return result.newChat
    }

    /**
     * Turn raw diary-note search hits into the injection's [RelatedNoteView]
     * list by resolving each note's subject to NAMES (the render carries no
     * ids-only references): an entity subject reuses the search's own hits
     * when the note's entity is among them, otherwise a [EltmService.getEntity]
     * fallback; a relationship subject resolves via
     * [EltmService.getRelationship] (which carries the endpoint names and the
     * verb). A note whose subject cannot be resolved (impossible under the
     * notes CHECK) is skipped rather than rendered with partial ids.
     */
    private suspend fun resolveRelatedNotes(
        notes: List<EltmNote>,
        knownEntities: List<EntityWithScore>,
    ): List<RelatedNoteView> = notes.mapNotNull { note ->
        when {
            note.entityId != null -> {
                val entity: EltmEntity = knownEntities.firstOrNull {
                    it.entity.id == note.entityId
                }?.entity
                    ?: eltmService.getEntity(note.entityId)?.entity
                    ?: return@mapNotNull null
                RelatedNoteView(
                    id = note.id,
                    eventDate = note.eventDate,
                    subjectType = "entity",
                    subjectAttributes = linkedMapOf(
                        "name" to entity.canonicalName,
                        "category" to entity.category,
                    ),
                    note = note.note,
                )
            }

            note.relationshipId != null -> {
                val relationship = eltmService.getRelationship(note.relationshipId)
                    ?: return@mapNotNull null
                RelatedNoteView(
                    id = note.id,
                    eventDate = note.eventDate,
                    subjectType = "relationship",
                    subjectAttributes = linkedMapOf(
                        "src-name" to relationship.srcName,
                        "verb" to relationship.relationship.verb,
                        "dst-name" to relationship.dstName,
                    ),
                    note = note.note,
                )
            }

            else -> null // impossible: the notes CHECK enforces exactly one subject
        }
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
        val request = handRunRequest(
            model = model,
            messages = chat,
            systemPrompt = systemPrompt,
            policy = policy,
            maxRounds = maxRounds,
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
