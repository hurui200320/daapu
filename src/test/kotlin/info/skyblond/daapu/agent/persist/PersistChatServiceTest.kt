package info.skyblond.daapu.agent.persist

import info.skyblond.daapu.agent.chat.*
import info.skyblond.daapu.agent.context.ContextInjection
import info.skyblond.daapu.agent.model.LLM
import info.skyblond.daapu.agent.model.ModelCapabilityException
import info.skyblond.daapu.agent.pipeline.compaction.ChatCompactionService
import info.skyblond.daapu.memory.eltm.EltmToolProvider
import info.skyblond.daapu.agent.pipeline.rewrite.QueryRewriteService
import info.skyblond.daapu.agent.persona.DEFAULT_PERSONA_ID
import info.skyblond.daapu.agent.persona.Persona
import info.skyblond.daapu.agent.persona.defaultPersona
import info.skyblond.daapu.agent.tool.CombinedToolProvider
import info.skyblond.daapu.agent.tool.EmptyToolProvider
import info.skyblond.daapu.agent.tool.ToolCallRequest
import info.skyblond.daapu.agent.tool.ToolProvider
import info.skyblond.daapu.config.McpServerConfig
import info.skyblond.daapu.config.McpTransportType
import info.skyblond.daapu.hand.*
import info.skyblond.daapu.mcp.McpToolProvider
import info.skyblond.daapu.mcp.MockMcpServer
import info.skyblond.daapu.mcp.MockTool
import info.skyblond.daapu.mcp.MockToolReply
import info.skyblond.daapu.memory.eltm.ClaimedJob
import info.skyblond.daapu.memory.eltm.EltmEntity
import info.skyblond.daapu.memory.eltm.EltmNote
import info.skyblond.daapu.memory.eltm.EltmRelationship
import info.skyblond.daapu.memory.eltm.EltmService
import info.skyblond.daapu.memory.eltm.ExtractionQueue
import info.skyblond.daapu.memory.eltm.PostgresExtractionQueue
import info.skyblond.daapu.db.ELTM_VERSION_KEY
import info.skyblond.daapu.db.bumpMetaCounterTx
import info.skyblond.daapu.testutil.DbTestBase
import info.skyblond.daapu.testutil.TestDb
import info.skyblond.daapu.testutil.testPostgresEltmService
import info.skyblond.daapu.testutil.testHandService
import info.skyblond.daapu.testutil.testLlm
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.*

/**
 * Pins the persist service's behavior against a scripted fake hand (the hand
 * owns the round loop itself; hand-pi's vitest suite pins its semantics).
 * Covered here: the history load/store lifecycle (store only on success,
 * injection stripped; the system prompt travels separately and is never
 * stored), the run request shape
 * (messages, tool advertisement, callback URL — the runId is generated
 * internally by [HandService]), event mapping onto
 * the callback and the history, capability enforcement BEFORE any hand
 * request, the per-turn query rewrite one-shot, and the reactive compaction
 * path (hand `context_exhausted` →
 * compact → queue the extraction → refresh injection → fresh run, with no
 * attempt cap).
 */
class PersistChatServiceTest : DbTestBase() {

    private val mainAgentSystemPromptService = MainAgentSystemPromptService()

    /**
     * The production Postgres queue over the test database (the timeout
     * knobs are irrelevant here): wired into the service under test and
     * used to claim the enqueued jobs back, so the compaction tests assert
     * the queue's real round trip. Stateless — one instance serves the
     * concurrent-runs test too.
     */
    private val testExtractionQueue = PostgresExtractionQueue(jobTimeoutMinutes = 30, retryDelayMinutes = 5)

    /**
     * Catalog model, optionally with compaction values different from the
     * catalog entry (the compaction tuning lives on the model).
     */
    private fun catalogModel(
        id: String,
        compactionTriggerFraction: Double = 0.8,
        compactionKeepRounds: Int = 2,
    ): LLM {
        val base = testLlm(id)
        return LLM(
            provider = base.provider,
            modelId = base.modelId,
            contextLength = base.contextLength,
            maxOutputTokens = base.maxOutputTokens,
            capabilities = base.capabilities,
            compactionTriggerFraction = compactionTriggerFraction,
            compactionKeepRounds = compactionKeepRounds,
        )
    }

    /**
     * Runs one turn; returns the outcome for assertions. The fake hand
     * dispatches on the out-of-band system prompt, standing in for the
     * one-shot roles (compactor / query rewriter) plus
     * the chat loop. Within a [chatScript] it plays the model AND the
     * hand's callback POST (the script answers the brain-side callback).
     */
    private fun run(
        modelId: String = "bifrost/cerebras/gemma-4-31b",
        userParts: List<ChatMessagePart> = listOf(ChatMessagePart.Text("hello")),
        store: InMemoryChatStore = InMemoryChatStore(),
        eltmService: EltmService = testPostgresEltmService(FakeHand()),
        toolProvider: ToolProvider = EmptyToolProvider,
        // the extraction queue behind the compaction path; defaults to
        // testExtractionQueue, so compaction tests assert the enqueued job
        // through TestDb.allExtractionJobs()
        extractionQueue: ExtractionQueue? = null,
        compactionKeepRounds: Int = 3,
        rewriteRounds: Int = 5,
        relatedEntitiesLimit: Int = 5,
        relatedNotesLimit: Int = 5,
        chatScript: suspend (HandRunRequest) -> List<HandEvent> = { stopEvents() },
        compactionScript: suspend (HandRunRequest) -> List<HandEvent> =
            { textRunFlow("compacted summary") },
        rewriteScript: suspend (HandRunRequest) -> List<HandEvent> =
            { textRunFlow("rewritten query") },
        persona: Persona = defaultPersona(),
    ): TurnOutcome {
        val model = catalogModel(modelId, compactionKeepRounds = compactionKeepRounds)
        val hand = FakeHand(
            runScript = { request ->
                when {
                    request.systemPrompt?.startsWith("You're summarizing") == true ->
                        compactionScript(request)

                    request.systemPrompt?.startsWith("You're rewriting") == true ->
                        rewriteScript(request)

                    else -> chatScript(request)
                }
            },
        )
        // the run/callback plumbing (runId generation, the in-flight
        // registry, the callback URL) lives in HandService, so the persist
        // service under test is wired through it
        val handService = testHandService(hand)
        val compactionService = ChatCompactionService(
            model = model,
            hand = handService,
            policy = HandRunPolicy(0, 300_000),
        )
        val rewriteService = QueryRewriteService(
            model = model,
            hand = handService,
            policy = HandRunPolicy(0, 300_000),
        )
        val callback = RecordingCallback()
        val error = runBlocking {
            runCatching {
                PersistChatService(
                    chatStore = store,
                    eltmService = eltmService,
                    queryRewriteService = rewriteService,
                    hand = handService,
                    compactionService = compactionService,
                    systemPromptService = mainAgentSystemPromptService,
                    extractionQueue = extractionQueue ?: testExtractionQueue,
                    rewriteRounds = rewriteRounds,
                    relatedEntitiesLimit = relatedEntitiesLimit,
                    relatedNotesLimit = relatedNotesLimit,
                    maxRounds = 64,
                    policy = HandRunPolicy(0, 300_000),
                ).runChat(
                    chatId = "chat-1",
                    model = model,
                    userParts = userParts,
                    persona = persona,
                    toolProvider = toolProvider,
                    callback = callback,
                )
            }.exceptionOrNull()
        }
        return TurnOutcome(error, store, callback, hand)
    }

    private fun stopEvents(): List<HandEvent> = listOf(
        HandEvent.TextDelta("ok"),
        HandEvent.AssistantMessage(assistantMessage("ok")),
        HandEvent.Done("stop"),
    )

    private fun injectionOf(request: HandRunRequest): String =
        ChatCodec.encodeChat(request.messages)

    @Test
    fun `basic chat stores history with injection stripped and the system prompt out of band`() {
        val outcome = run()
        assertNull(outcome.error)

        // deltas streamed to the client
        assertEquals(listOf("ok"), outcome.callback.texts)
        assertTrue(outcome.callback.errors.isEmpty())

        // the rewrite ran first (its one-shot call), then the chat round:
        // the hand's last request carries the injection and the system
        // prompt as a separate field (never inside the messages)
        val request = outcome.hand.requests.last()
        // the runId is internal to the run/callback plumbing: generated by
        // HandService, never supplied by the chat loop
        assertFalse(request.runId.isNullOrBlank(), "HandService must generate a runId")
        // this harness runs the loop with no tools wired (EmptyToolProvider):
        // a tool-less run sends neither tool URL, so the hand makes no
        // brain-side HTTP call at all (the attachment contract for tool-ful
        // runs is pinned in HandServiceTest)
        assertNull(
            request.toolCallbackUrl,
            "a tool-less run sends no callback URL (no tool can ever execute)",
        )
        assertNull(
            request.toolListUrl,
            "a tool-less run sends no tool-list URL: the hand skips its " +
                    "per-round GET /api/hand/tools entirely",
        )
        assertEquals(
            mainAgentSystemPromptService.render(defaultPersona()),
            request.systemPrompt,
            "the system prompt travels out of band, rendered from the run's persona",
        )
        assertTrue(injectionOf(request).contains("<eltm-updated>true</eltm-updated>"))

        // the model spec carries the reasoning effort from the model's
        // Reasoning capability (not a per-request field)
        assertTrue(request.model.reasoning, "the catalog model supports reasoning")
        assertEquals("high", request.model.reasoningEffort)

        // history stored: user (injection stripped, createdAt stamped),
        // assistant
        val stored = assertNotNull(outcome.store.stored, "history must be stored on success")
        assertEquals(
            listOf(ChatMessageRole.User, ChatMessageRole.Assistant),
            stored.map { it.role },
        )
        assertEquals(listOf(ChatMessagePart.Text("hello")), stored[0].parts)
        assertNotNull(stored[0].createdAt, "the stored user message must carry its send time")
        assertEquals(listOf(ChatMessagePart.Text("ok")), stored[1].parts)
        assertEquals("stop", stored[1].finishReason)
    }

    @Test
    fun `the run's persona id is stamped on the stored chat record`() {
        // the chats.persona_id column is a RECORD of the last successful
        // run's persona (the UI pre-fills its picker from it) — the prompt
        // and the tool set came from the resolved persona, not the column
        val outcome = run(persona = Persona(1L, "Persona 1", "You are persona-1.", emptyList()))
        assertNull(outcome.error)
        assertEquals(1L, outcome.store.storedPersonaId)
    }

    @Test
    fun `a persona without gsg access skips the rewrite and gets the time-only injection`() {
        // the memories injection is hidden for such a persona, so the query
        // rewrite (which exists only to feed the memories search) must not
        // run: no LLM call, no embedding calls — only the chat round
        val outcome = run(
            persona = Persona(1L, "Plain", "You are a plain assistant.", listOf("calc")),
            rewriteScript = { error("the rewrite must not run without gsg access: it only feeds the hidden memories") },
        )
        assertNull(outcome.error)
        assertEquals(1, outcome.hand.requests.size, "no rewrite: only the chat round")
        val request = outcome.hand.requests.single()
        // the reduced system prompt documents only the time basics
        val prompt = request.systemPrompt!!
        assertTrue(prompt.startsWith("You are a plain assistant.\n\n# Context"))
        assertFalse(prompt.contains("gsg__investigate"))
        // the injection carries localtime and the meta anchors, but neither
        // eltm-updated nor memories
        val injection = injectionOf(request)
        assertTrue(injection.contains("<localtime>"))
        assertFalse(injection.contains("eltm-updated"))
        assertFalse(injection.contains("memories"))
        // the ELTM version is still persisted (harness behavior, persona-
        // independent: the flag stays correct if the chat switches persona)
        assertEquals("0", outcome.store.storedEltmVersion)
    }

    @Test
    fun `a reactive compaction keeps the time-only injection for a persona without gsg access`() {
        val outcome = run(
            store = InMemoryChatStore(crowdedSeed(lastInputTokens = 10)),
            persona = Persona(1L, "Plain", "You are a plain assistant.", listOf("calc")),
            rewriteScript = { error("the rewrite must not run without gsg access: it only feeds the hidden memories") },
            chatScript = { request ->
                if (request.messages.any { message ->
                        message.parts.any { part ->
                            part is ChatMessagePart.Text &&
                                    part.text.startsWith("CONTEXT COMPACTION")
                        }
                    }) {
                    stopEvents()
                } else {
                    listOf(
                        HandEvent.RunError("context_exhausted", "input too big")
                    )
                }
            },
        )
        assertNull(outcome.error)
        assertEquals(
            3,
            outcome.hand.requests.size,
            "exhausted run -> compactor -> fresh run (no rewrite)",
        )
        // both the exhausted run and the compacted retry carry the same
        // time-only injection: localtime, never eltm-updated or memories
        for (request in listOf(outcome.hand.requests[0], outcome.hand.requests[2])) {
            val injection = injectionOf(request)
            assertTrue(injection.contains("<localtime>"))
            assertFalse(injection.contains("eltm-updated"))
            assertFalse(injection.contains("memories"))
        }
    }

    @Test
    fun `eltm version is persisted on the chat and the eltm-updated flag tracks changes`() {
        val store = InMemoryChatStore()
        val eltm = testPostgresEltmService(FakeHand())

        // a fresh chat has no stored version, so the first run must flag
        // the ELTM as updated and persist the version it saw
        var outcome = run(store = store, eltmService = eltm)
        assertNull(outcome.error)
        assertTrue(
            injectionOf(outcome.hand.requests.last()).contains("<eltm-updated>true</eltm-updated>"),
            "fresh chat must flag the ELTM as updated",
        )
        assertEquals("0", store.storedEltmVersion, "run must persist the eltm version")

        // same version as the last run: nothing changed, no flag
        outcome = run(store = store, eltmService = eltm)
        assertNull(outcome.error)
        assertTrue(
            injectionOf(outcome.hand.requests.last()).contains("<eltm-updated>false</eltm-updated>"),
            "an unchanged version must not flag",
        )
        assertEquals("0", store.storedEltmVersion)

        // an ELTM write bumps the version: the next run must flag again
        runBlocking { bumpMetaCounterTx(ELTM_VERSION_KEY) }
        outcome = run(store = store, eltmService = eltm)
        assertNull(outcome.error)
        assertTrue(
            injectionOf(outcome.hand.requests.last()).contains("<eltm-updated>true</eltm-updated>"),
            "a changed version must flag",
        )
        assertEquals("1", store.storedEltmVersion)
    }

    @Test
    fun `eltm version is not persisted on a failed run`() {
        val store = InMemoryChatStore()
        val eltm = testPostgresEltmService(FakeHand())
        val outcome = run(store = store, eltmService = eltm)
        assertNull(outcome.error)
        assertEquals("0", store.storedEltmVersion)

        // a failed run must not touch the stored version: history stays
        // at the last good state
        runBlocking { bumpMetaCounterTx(ELTM_VERSION_KEY) }
        val failed = run(
            store = store,
            eltmService = eltm,
            chatScript = { listOf(HandEvent.RunError("upstream", "boom")) },
        )
        assertIs<HandRunException>(failed.error)
        assertEquals("0", store.storedEltmVersion, "failed run must not update the version")

        // ... so the next successful run still flags the pending change
        val retry = run(store = store, eltmService = eltm)
        assertNull(retry.error)
        assertTrue(
            injectionOf(retry.hand.requests.last()).contains("<eltm-updated>true</eltm-updated>"),
            "a change missed by a failed run must flag on the next success",
        )
        assertEquals("1", store.storedEltmVersion)
    }

    @Test
    fun `a reactive compaction queues the dropped messages instead of extracting inline`() = runBlocking {
        // the reactive compaction enqueues the dropped messages into the
        // background extraction queue instead of running the two-stage
        // pipeline on the run path: no extractor/writer hand calls mid-run,
        // one job carrying the dropped messages. The queued extraction does
        // not bump the ELTM version, so the re-injected flag stays false —
        // the writes flag on the NEXT run once the worker has applied them.
        val store = InMemoryChatStore()

        // run 1: a normal turn stores the ELTM version it saw and one round
        // of history (the reactive compaction of run 2 drops it)
        val first = run(store = store)
        assertNull(first.error)
        assertEquals("0", store.storedEltmVersion)

        // run 2: the first chat attempt is exhausted, the reactive
        // compaction queues the dropped messages, the retried round is
        // re-injected with the (unchanged) version flag
        var chatRounds = 0
        val outcome = run(
            store = store,
            chatScript = { _ ->
                if (++chatRounds == 1) {
                    listOf(HandEvent.RunError("context_exhausted", "input too big"))
                } else {
                    stopEvents()
                }
            },
        )
        assertNull(outcome.error)

        // request order: rewrite, exhausted chat, compactor, fresh run —
        // NO extractor or writer call on the run path
        assertEquals(
            4, outcome.hand.requests.size,
            "rewrite -> exhausted -> compact -> fresh run",
        )
        // exactly one queue job, carrying the dropped first round
        val jobs = TestDb.allExtractionJobs()
        assertEquals(1, jobs.size, "the reactive compaction must enqueue the dropped messages")
        val claimed = assertNotNull(testExtractionQueue.claim())
        assertEquals(
            listOf(ChatMessageRole.User, ChatMessageRole.Assistant),
            claimed.messages.map { it.role },
            "the snapshot is the dropped round, not the summary",
        )
        assertEquals(listOf(ChatMessagePart.Text("hello")), claimed.messages[0].parts)

        // the queued extraction has not written anything yet: no ELTM
        // entities exist and the version never moved mid-run
        assertTrue(TestDb.allEltmEntities().isEmpty(), "nothing is written on the run path")
        // the exhausted attempt was injected BEFORE the compaction: its flag
        // must say the stored version matches
        assertTrue(
            injectionOf(outcome.hand.requests[1]).contains("<eltm-updated>false</eltm-updated>"),
            "the pre-compaction injection must not flag",
        )
        // the retried round was re-injected with a freshly read version:
        // the queued extraction cannot have bumped it, so it still says false
        assertTrue(
            injectionOf(outcome.hand.requests.last()).contains("<eltm-updated>false</eltm-updated>"),
            "the re-injected flag must not count the queued (not yet applied) extraction",
        )
        assertEquals("0", store.storedEltmVersion, "the run stores the version it saw")
    }

    @Test
    fun `reasoning deltas are forwarded and kept in stored history`() {
        val outcome = run(
            chatScript = {
                listOf(
                    HandEvent.ReasoningDelta("Let me think"),
                    HandEvent.ReasoningDelta(" step by step"),
                    HandEvent.TextDelta("17 * 23 = 391"),
                    HandEvent.AssistantMessage(
                        assistantMessage(
                            parts = listOf(
                                ChatMessagePart.Reasoning("Let me think step by step"),
                                ChatMessagePart.Text("17 * 23 = 391"),
                            ),
                        )
                    ),
                    HandEvent.Done("stop"),
                )
            },
        )
        assertNull(outcome.error)
        assertEquals(listOf("Let me think", " step by step"), outcome.callback.thinkings)
        val assistant = outcome.store.stored!![1]
        // the reasoning part is kept in stored history on purpose (the
        // hand replays it as reasoning on later runs)
        assertEquals(
            listOf(
                ChatMessagePart.Reasoning("Let me think step by step"),
                ChatMessagePart.Text("17 * 23 = 391"),
            ),
            assistant.parts,
        )
    }

    @Test
    fun `a terminal hand error fails the run without storing`() {
        // a chat with existing history: a failed run must leave it untouched
        // (stored user messages carry their send time, like the decode path requires)
        val seed = listOf(
            ChatMessage(
                ChatMessageRole.User,
                listOf(ChatMessagePart.Text("old")),
                createdAt = Instant.parse("2026-08-17T09:00:00Z"),
            )
        )
        val store = InMemoryChatStore(seed)
        val outcome = run(
            store = store,
            chatScript = { listOf(HandEvent.RunError("upstream", "500: boom")) },
        )

        val e = assertIs<HandRunException>(outcome.error)
        assertEquals("upstream", e.type)
        assertEquals(2, outcome.hand.requests.size, "the rewrite runs once, the failing round does not retry")
        assertEquals(0, outcome.store.storeCount, "a failed run must never store")
        assertEquals(seed, outcome.store.stored, "history stays at the last good state")
    }

    @Test
    fun `retry events are relayed to the client`() {
        val outcome = run(
            chatScript = {
                listOf(
                    HandEvent.Retry(attempt = 2, delayMs = 200, message = "transient hiccup"),
                    HandEvent.TextDelta("ok"),
                    HandEvent.AssistantMessage(assistantMessage("ok")),
                    HandEvent.Done("stop"),
                )
            },
        )
        assertNull(outcome.error)
        assertEquals(listOf("transient hiccup"), outcome.callback.errors)
        assertNotNull(outcome.store.stored)
    }

    @Test
    fun `content filter fails fast without storing`() {
        val outcome = run(
            chatScript = {
                listOf(
                    HandEvent.RunError(
                        "content_filter",
                        "Provider finish_reason: content_filter"
                    )
                )
            },
        )
        val e = assertIs<HandRunException>(outcome.error)
        assertEquals("content_filter", e.type)
        assertTrue(
            e.message!!.contains("finish_reason"),
            "error should name the finish reason: ${e.message}"
        )
        assertEquals(0, outcome.store.storeCount, "a failed run must never store")
    }

    @Test
    fun `empty stop fails fast without storing`() {
        val outcome = run(
            chatScript = {
                listOf(
                    HandEvent.RunError(
                        "empty_response",
                        "assistant finished with neither text nor tool calls"
                    )
                )
            },
        )
        val e = assertIs<HandRunException>(outcome.error)
        assertEquals("empty_response", e.type)
        assertTrue(
            e.message!!.contains("finish_reason"),
            "error should name the finish reason: ${e.message}"
        )
        assertEquals(0, outcome.store.storeCount, "a failed run must never store")
    }

    @Test
    fun `output budget exhaustion fails the run`() {
        val outcome = run(
            chatScript = {
                listOf(
                    HandEvent.TextDelta("partial"),
                    HandEvent.AssistantMessage(
                        assistantMessage(
                            "partial",
                            finishReason = "length"
                        )
                    ),
                    HandEvent.RunError("output_budget_exhausted", "output hit the token budget"),
                )
            },
        )
        val e = assertIs<HandRunException>(outcome.error)
        assertEquals("output_budget_exhausted", e.type)
        assertTrue(
            e.message!!.contains("output budget"),
            "error should explain the failure: ${e.message}"
        )
        assertEquals(0, outcome.store.storeCount, "a failed run must never store")
    }

    @Test
    fun `capability violation fails before any hand request`() {
        // an image with a text-only model: the check must fail up front
        val outcome = run(
            modelId = "bifrost/cerebras/gpt-oss-120b",
            userParts = listOf(
                ChatMessagePart.Text("look"),
                ChatMessagePart.Attachment(
                    kind = AttachmentKind.Image,
                    content = AttachmentContent.Base64("AAAA"),
                    mimeType = "image/png",
                ),
            ),
        )
        assertIs<ModelCapabilityException>(outcome.error)
        assertTrue(outcome.hand.requests.isEmpty(), "no hand request must be made")
        assertEquals(0, outcome.store.storeCount, "a failed run must never store")
    }

    @Test
    fun `image with a vision model passes the capability check`() {
        val outcome = run(
            modelId = "bifrost/cerebras/gemma-4-31b",
            userParts = listOf(
                ChatMessagePart.Attachment(
                    kind = AttachmentKind.Image,
                    content = AttachmentContent.Base64("AAAA"),
                    mimeType = "image/png",
                ),
            ),
        )
        assertNull(outcome.error)
        assertNotNull(outcome.store.stored)
    }

    @Test
    fun `tool call round executes the empty registry and completes the next round`() {
        val outcome = run(
            chatScript = {
                // the hand would HTTP-POST the call to the brain,
                // which answers through the same empty registry
                val call = ChatMessagePart.ToolCall(
                    id = "call_1",
                    tool = "flag",
                    args = JsonObject(emptyMap())
                )
                val result =
                    EmptyToolProvider.execute(ToolCallRequest(call.id, call.tool, call.args))
                listOf(
                    HandEvent.AssistantMessage(
                        assistantMessage(
                            parts = listOf(call),
                            finishReason = "tool_calls"
                        )
                    ),
                    HandEvent.ToolCall(call.id, call.tool, call.args),
                    HandEvent.ToolResult(call.id, call.tool, result.parts, result.isError),
                    HandEvent.TextDelta("ok"),
                    HandEvent.AssistantMessage(assistantMessage("ok")),
                    HandEvent.Done("stop"),
                )
            },
        )
        assertNull(outcome.error)

        // the tool call was streamed to the client and the empty registry
        // answered it with an explicit error result
        assertEquals(listOf("flag" to JsonObject(emptyMap())), outcome.callback.toolCalls)
        val toolResult = outcome.callback.toolResults.single()
        assertEquals("call_1", toolResult.id)
        assertEquals("flag", toolResult.tool)
        assertTrue(toolResult.isError, "empty registry must answer with an error result")

        // stored history: user, assistant(tool_call), tool(result), assistant(answer)
        val stored = assertNotNull(outcome.store.stored)
        assertEquals(
            listOf(
                ChatMessageRole.User,
                ChatMessageRole.Assistant,
                ChatMessageRole.ToolResult,
                ChatMessageRole.Assistant
            ),
            stored.map { it.role },
        )
        assertEquals(
            listOf(
                ChatMessagePart.ToolCall(
                    id = "call_1",
                    tool = "flag",
                    args = JsonObject(emptyMap())
                )
            ),
            stored[1].parts,
        )
        assertEquals("tool_calls", stored[1].finishReason)
        val storedResult = assertIs<ChatMessagePart.ToolResult>(stored[2].parts.single())
        assertEquals("call_1", storedResult.id)
        assertEquals(true, storedResult.isError)
        assertEquals(listOf(ChatMessagePart.Text("ok")), stored[3].parts)
    }

    @Test
    fun `tool call round executes through the MCP provider with paired history`() {
        // end-to-end through the neutral tool seam: the loop no longer
        // carries a static tool list (the hand re-queries the brain's
        // GET /api/hand/tools before every LLM request — pinned by
        // HandCallbackTest), and the hand-side execution (simulated by
        // calling the provider) stores the result as a tool message whose
        // id pairs with the call
        val mcpServer = MockMcpServer(listOf(mcpAddTool()))
        val mcpProvider = McpToolProvider(
            mapOf(
                "calc" to McpServerConfig(
                    type = McpTransportType.Http,
                    url = mcpServer.baseUrl,
                    toolExecutionTimeoutSeconds = 30,
                )
            )
        )
        try {
            val outcome = run(
                toolProvider = mcpProvider,
                chatScript = {
                    // the HTTP contract is pinned by HandCallbackTest; like
                    // the real hand it lists the tools first (the brain's
                    // GET /api/hand/tools) before executing calls, which
                    // populates the provider's name mapping
                    mcpProvider.specifications()
                    val call = ChatMessagePart.ToolCall(
                        id = "call_1",
                        tool = "calc__add",
                        args = buildJsonObject { put("a", 1); put("b", 2) },
                    )
                    val result = mcpProvider.execute(ToolCallRequest(call.id, call.tool, call.args))
                    listOf(
                        HandEvent.AssistantMessage(
                            assistantMessage(
                                parts = listOf(call),
                                finishReason = "tool_calls"
                            )
                        ),
                        HandEvent.ToolCall(call.id, call.tool, call.args),
                        HandEvent.ToolResult(call.id, call.tool, result.parts, result.isError),
                        HandEvent.TextDelta("ok"),
                        HandEvent.AssistantMessage(assistantMessage("ok")),
                        HandEvent.Done("stop"),
                    )
                },
            )
            assertNull(outcome.error)

            // no static tools in the request; the provider serves the
            // namespaced tool with its schema through the per-round listing
            // path instead (pinned by HandCallbackTest)
            val request = outcome.hand.requests.last()
            assertEquals(
                "http://127.0.0.1:9/api/hand/tools",
                request.toolListUrl,
                "the tool-list URL is attached on every run",
            )
            val advertised = runBlocking { mcpProvider.specifications().single() }
            assertEquals("calc__add", advertised.name)
            assertTrue(advertised.schema.isNotEmpty(), "the tool schema must be advertised")
            assertEquals(
                30L,
                mcpProvider.executionTimeoutSeconds("calc__add"),
                "the server's execution budget is enforced brain-side from config"
            )
            assertEquals(
                "http://127.0.0.1:9/api/hand/tool",
                request.toolCallbackUrl,
                "the callback URL is sent on every run",
            )

            // the call streamed with the advertised name, the server executed
            // the raw tool with the parsed arguments
            assertEquals(
                listOf("calc__add" to buildJsonObject { put("a", 1); put("b", 2) }),
                outcome.callback.toolCalls
            )
            val (rawName, args) = mcpServer.toolCalls.single()
            assertEquals("add", rawName)
            assertEquals("1", args["a"]?.jsonPrimitive?.let { it.content })

            // the result streamed and stored, paired with the call id
            val toolResult = outcome.callback.toolResults.single()
            assertEquals("call_1", toolResult.id)
            assertEquals("calc__add", toolResult.tool)
            assertEquals("1 + 2 = 3", toolResult.textContent())
            assertTrue(!toolResult.isError)

            val stored = assertNotNull(outcome.store.stored)
            val call = assertIs<ChatMessagePart.ToolCall>(stored[1].parts.single())
            assertEquals("call_1", call.id)
            assertEquals("calc__add", call.tool)
            val result = assertIs<ChatMessagePart.ToolResult>(stored[2].parts.single())
            assertEquals("call_1", result.id, "the stored result must pair with the stored call id")
            assertEquals(listOf(ChatMessagePart.Text("1 + 2 = 3")), result.parts)
            assertEquals(listOf(ChatMessagePart.Text("ok")), stored[3].parts)
        } finally {
            mcpProvider.close()
            mcpServer.close()
        }
    }

    @Test
    fun `tool round executes through the combined provider with the namespaced read-only eltm tools`() =
        runBlocking {
            // the investigate sub-agent's real shape (the DI module builds
            // it): the MCP servers plus the read-only ELTM tools, one
            // namespaced set; a round where the model queries the ELTM
            // routes to the local provider and stores the paired call/result
            // like any other tool round
            val eltm = testPostgresEltmService(FakeHand())
            eltm.createEntity("Alice", "person")
            val eltmProvider = EltmToolProvider(eltm, readOnly = true, namespace = "eltm")
            val mcpServer = MockMcpServer(listOf(mcpAddTool()))
            val mcpProvider = McpToolProvider(
                mapOf(
                    "calc" to McpServerConfig(
                        type = McpTransportType.Http,
                        url = mcpServer.baseUrl,
                        toolExecutionTimeoutSeconds = 30,
                    )
                )
            )
            val combined = CombinedToolProvider(listOf(eltmProvider, mcpProvider))
            try {
                val outcome = run(
                    toolProvider = combined,
                    chatScript = {
                        combined.specifications()
                        val call = ChatMessagePart.ToolCall(
                            id = "call_1",
                            tool = "eltm__search_entities",
                            args = buildJsonObject { put("query", "ali") },
                        )
                        val result = combined.execute(ToolCallRequest(call.id, call.tool, call.args))
                        listOf(
                            HandEvent.AssistantMessage(
                                assistantMessage(
                                    parts = listOf(call),
                                    finishReason = "tool_calls"
                                )
                            ),
                            HandEvent.ToolCall(call.id, call.tool, call.args),
                            HandEvent.ToolResult(call.id, call.tool, result.parts, result.isError),
                            HandEvent.TextDelta("ok"),
                            HandEvent.AssistantMessage(assistantMessage("ok")),
                            HandEvent.Done("stop"),
                        )
                    },
                )
                assertNull(outcome.error)

                val stored = assertNotNull(outcome.store.stored)
                val call = assertIs<ChatMessagePart.ToolCall>(stored[1].parts.single())
                assertEquals("call_1", call.id)
                assertEquals("eltm__search_entities", call.tool)
                val result = assertIs<ChatMessagePart.ToolResult>(stored[2].parts.single())
                assertEquals("call_1", result.id, "the stored result must pair with the stored call id")
                assertTrue(
                    result.parts.filterIsInstance<ChatMessagePart.Text>()
                        .joinToString("") { it.text }.contains("alice"),
                    "the read-only provider answered from the ELTM store"
                )
                assertTrue(mcpServer.toolCalls.isEmpty(), "the query never reached the MCP server")

                // the read-only guard holds inside the combined set: a write tool
                // the model could never see is still rejected if called
                val write = combined.execute(
                    ToolCallRequest("call_2", "eltm__create_entity", buildJsonObject { put("name", "Bob") })
                )
                assertTrue(write.isError)
            } finally {
                mcpProvider.close()
                mcpServer.close()
            }
        }

    @Test
    fun `existing history is loaded and extended`() {
        val seed = listOf(
            ChatMessage(
                ChatMessageRole.User,
                listOf(ChatMessagePart.Text("earlier")),
                createdAt = Instant.parse("2026-08-17T09:00:00Z"),
            ),
            ChatMessage(
                ChatMessageRole.Assistant,
                listOf(ChatMessagePart.Text("earlier reply")),
                meta = ChatMessageMeta(inputTokens = 10, outputTokens = 5, totalTokens = 15),
                finishReason = "stop",
            ),
        )
        val store = InMemoryChatStore(seed)
        val outcome = run(store = store)
        assertNull(outcome.error)
        val stored = assertNotNull(outcome.store.stored)
        assertEquals(4, stored.size, "seed + new user + new assistant")
        assertEquals(listOf(ChatMessagePart.Text("earlier")), stored[0].parts)
        assertEquals(seed[0].createdAt, stored[0].createdAt, "historical send times survive storage")
        assertEquals(listOf(ChatMessagePart.Text("earlier reply")), stored[1].parts)
        assertEquals(listOf(ChatMessagePart.Text("hello")), stored[2].parts)
        assertNotNull(stored[2].createdAt, "the run's user message must be stamped")
        assertEquals(listOf(ChatMessagePart.Text("ok")), stored[3].parts)
    }

    @Test
    fun `historical user messages are time-anchored in the hand request and clean in storage`() {
        val contextInjection = ContextInjection()
        val seed = listOf(
            ChatMessage(
                ChatMessageRole.User,
                listOf(ChatMessagePart.Text("earlier")),
                createdAt = Instant.parse("2026-08-17T09:00:00Z"),
            ),
            ChatMessage(
                ChatMessageRole.Assistant,
                listOf(ChatMessagePart.Text("earlier reply")),
                meta = ChatMessageMeta(inputTokens = 10, outputTokens = 5, totalTokens = 15),
                finishReason = "stop",
            ),
        )
        val outcome = run(store = InMemoryChatStore(seed))
        assertNull(outcome.error)

        // the hand request: the historical user message carries its send-time
        // <meta> anchor, the latest carries the full injection, assistant
        // messages are never touched (the rewrite's one-shot run precedes
        // the chat round, so the chat request is the last one)
        val sent = outcome.hand.requests.last().messages
        assertEquals(
            listOf(ChatMessageRole.User, ChatMessageRole.Assistant, ChatMessageRole.User),
            sent.map { it.role },
        )
        val historicalMeta = sent[0].parts.first() as ChatMessagePart.Text
        assertTrue(contextInjection.hasMetaPart(sent[0]))
        // expected date computed from the anchor's own instant (never hard-coded)
        val anchorDate = java.time.ZonedDateTime.ofInstant(
            Instant.parse("2026-08-17T09:00:00Z"),
            java.time.ZoneId.systemDefault(),
        ).toLocalDate().toString()
        assertTrue(
            historicalMeta.text.contains(anchorDate),
            "the anchor must render the message's own send time, got: ${historicalMeta.text}",
        )
        assertEquals(
            listOf(ChatMessagePart.Text("earlier")),
            sent[0].parts.drop(1),
            "the anchor is prepended, the original content untouched",
        )
        assertTrue(contextInjection.isInjection(sent[2].parts.first() as ChatMessagePart.Text))
        assertEquals(listOf(ChatMessagePart.Text("hello")), sent[2].parts.drop(1))
        assertEquals(sent[1], seed[1], "assistant messages are not touched")

        // storage: anchors and injection stripped, createdAt preserved
        val stored = assertNotNull(outcome.store.stored)
        assertTrue(
            stored.none { message ->
                contextInjection.hasMetaPart(message) ||
                        (message.parts.firstOrNull() is ChatMessagePart.Text &&
                                contextInjection.isInjection(message.parts.first() as ChatMessagePart.Text))
            },
            "stored chat must contain no harness parts",
        )
        assertEquals(seed[0].createdAt, stored[0].createdAt)
        assertNotNull(stored[2].createdAt)
    }

    // ------------------------------------------------------------------
    // query rewrite (the per-turn one-shot before the first hand round)
    // ------------------------------------------------------------------

    @Test
    fun `the query rewrite runs before the round with the last N user rounds and its own injection`() {
        // 4 stored turns + the run's user message = 5 user rounds; with
        // rewriteRounds = 2 the rewrite sees only the trailing 2 rounds and
        // re-injects its own (empty) spec — the loop's updated flags must
        // not leak into the rewrite prompt
        val outcome = run(
            store = InMemoryChatStore(crowdedSeed(lastInputTokens = 10)),
            rewriteRounds = 2,
        )
        assertNull(outcome.error)

        val rewrite = outcome.hand.requests[0]
        assertTrue(rewrite.systemPrompt!!.startsWith("You're rewriting"))
        val userTexts = rewrite.messages.filter { it.role == ChatMessageRole.User }
            .flatMap { it.parts.filterIsInstance<ChatMessagePart.Text>().map { part -> part.text } }
            .joinToString("\n")
        assertFalse(userTexts.contains("topic 1"), "the rewrite must not see the dropped rounds")
        assertFalse(userTexts.contains("topic 2"), "the rewrite must not see the dropped rounds")
        assertFalse(userTexts.contains("topic 3"), "the rewrite sees the tail only")
        assertTrue(userTexts.contains("topic 4"))
        assertTrue(userTexts.contains("hello"), "the run's own user message feeds the rewrite")
        // the rewrite received the loop's injected chat sanitized and
        // re-injected with its own time-only spec: the loop's flags and
        // memories are not in it
        val rewriteInjection = injectionOf(rewrite)
        assertTrue(
            rewriteInjection.contains("<localtime>"),
            "the rewrite sees its own time-only injection",
        )
        assertFalse(
            rewriteInjection.contains("eltm-updated"),
            "the rewrite's one-shot injection carries no ELTM update flag",
        )
        assertFalse(rewriteInjection.contains("memories"))

        // the chat round still carried the loop's real injection
        assertTrue(
            injectionOf(outcome.hand.requests.last()).contains("<eltm-updated>true</eltm-updated>"),
            "the loop's injection is untouched by the rewrite",
        )
        // the rewrite result feeds the <memories>' related-entities/notes
        // search of the chat round's injection (empty with an empty ELTM)
        assertEquals(2, outcome.hand.requests.size, "rewrite run + chat round")
    }

    @Test
    fun `rewriteQuery returns null on the sentinel and the rewritten text on anything else`() = runBlocking {
        val hand = FakeHand(
            runScript = { request ->
                when {
                    request.systemPrompt?.startsWith("You're rewriting") == true ->
                        textRunFlow("Nothing worth query.")
                    else -> error("unexpected run: ${request.systemPrompt}")
                }
            },
        )
        val service = QueryRewriteService(
            model = catalogModel("bifrost/cerebras/gemma-4-31b"),
            hand = testHandService(hand),
            policy = HandRunPolicy(0, 300_000),
        )
        val chat = listOf(
            ChatMessage(
                ChatMessageRole.User,
                listOf(ChatMessagePart.Text("hi")),
                createdAt = Instant.parse("2026-08-17T09:00:00Z"),
            )
        )
        assertNull(service.rewriteQuery(chat, 1), "the sentinel means nothing worth querying")

        val rewritten = FakeHand(
            runScript = { textRunFlow("When did Alice visit Paris?") }
        )
        val result = QueryRewriteService(
            model = catalogModel("bifrost/cerebras/gemma-4-31b"),
            hand = testHandService(rewritten),
            policy = HandRunPolicy(0, 300_000),
        ).rewriteQuery(chat, 1)
        assertEquals("When did Alice visit Paris?", result)
    }

    @Test
    fun `rewriteQuery returns null on a chat with no user message without calling the LLM`() = runBlocking {
        val hand = FakeHand(runScript = { error("the rewrite must not call the LLM") })
        val service = QueryRewriteService(
            model = catalogModel("bifrost/cerebras/gemma-4-31b"),
            hand = testHandService(hand),
            policy = HandRunPolicy(0, 300_000),
        )
        assertNull(service.rewriteQuery(emptyList(), 1), "nothing to rewrite, nothing to query")
        assertNull(
            service.rewriteQuery(
                listOf(
                    ChatMessage(
                        ChatMessageRole.Assistant,
                        listOf(ChatMessagePart.Text("hello")),
                        meta = ChatMessageMeta(inputTokens = 10, outputTokens = 5, totalTokens = 15),
                        finishReason = "stop",
                    )
                ),
                1,
            ),
            "an assistant-only chat cliips to an empty history",
        )
        assertTrue(hand.requests.isEmpty(), "no hand request must be made")
    }

    @Test
    fun `rewriteQuery fails fast on a non-positive round count`() = runBlocking {
        // config validates `memory.eltm.rewriteRounds >= 1` at boot; this
        // pins the direct-caller guard so a bad count cannot silently skip
        // the rewrite
        val hand = FakeHand(runScript = { error("the rewrite must not call the LLM") })
        val service = QueryRewriteService(
            model = catalogModel("bifrost/cerebras/gemma-4-31b"),
            hand = testHandService(hand),
            policy = HandRunPolicy(0, 300_000),
        )
        assertFailsWith<IllegalArgumentException> { service.rewriteQuery(emptyList(), 0) }
        assertFailsWith<IllegalArgumentException> { service.rewriteQuery(emptyList(), -1) }
        assertTrue(hand.requests.isEmpty(), "no hand request must be made")
    }

    @Test
    fun `a failed rewrite fails the run without storing`() {
        val outcome = run(
            rewriteScript = { errorRunFlow("upstream", "boom") },
        )
        val e = assertIs<IllegalStateException>(outcome.error)
        assertEquals("Query rewrite failed", e.message)
        assertIs<HandRunException>(e.cause)
        assertEquals(0, outcome.store.storeCount, "a failed run must never store")
    }

    @Test
    fun `a rewrite model that cannot see the chat fails fast with a capability error`() = runBlocking {
        val hand = FakeHand(runScript = { error("the rewrite must not call the LLM") })
        val service = QueryRewriteService(
            model = catalogModel("bifrost/cerebras/gpt-oss-120b"),
            hand = testHandService(hand),
            policy = HandRunPolicy(0, 300_000),
        )
        val chat = listOf(
            ChatMessage(
                ChatMessageRole.User,
                listOf(
                    ChatMessagePart.Text("look"),
                    ChatMessagePart.Attachment(
                        kind = AttachmentKind.Image,
                        content = AttachmentContent.Base64("AAAA"),
                        mimeType = "image/png",
                    ),
                ),
                createdAt = Instant.parse("2026-08-17T09:00:00Z"),
            )
        )
        assertFailsWith<ModelCapabilityException> { service.rewriteQuery(chat, 1) }
        assertTrue(hand.requests.isEmpty(), "no hand request must be made")
    }

    // ------------------------------------------------------------------
    // ELTM context injection (the rewritten query seeds entity/note search)
    // ------------------------------------------------------------------

    /**
     * A real ELTM store seeded for the "alice" search: one person entity
     * with an attribute, one company entity, one relationship
     * (alice→works_at→acme), and two diary notes (one per subject kind)
     * whose texts mention alice. The ids are deterministic (the per-test
     * reset restarts every sequence): alice=1, bob=2, acme=3,
     * relationship=1, notes 1 and 2 in insert order.
     */
    private suspend fun eltmSeededForAliceSearch(): EltmService {
        val eltm = testPostgresEltmService(FakeHand())
        val alice = eltm.createEntity("alice", "person").entity
        val bob = eltm.createEntity("bob", "person").entity
        val acme = eltm.createEntity("acme", "company").entity
        eltm.setEntityAttribute(alice.id, "real_name", "Alice Smith")
        val rel = eltm.createRelationship(alice.id, acme.id, "works_at")
        eltm.attachNoteToEntity(alice.id, LocalDate.of(2026, 8, 1), "alice met bob at the conference")
        eltm.attachNoteToRelationship(rel.id, LocalDate.of(2026, 7, 15), "alice joined acme as an engineer")
        return eltm
    }

    @Test
    fun `the eltm context injection carries the searched entities and notes`() = runBlocking {
        val outcome = run(
            eltmService = eltmSeededForAliceSearch(),
            rewriteScript = { textRunFlow("alice") },
        )
        assertNull(outcome.error)

        // the injection of the chat round carries the entity with its
        // current-state attribute facts and the notes with name-identified
        // subjects (entity: name+category, relationship: src-name+verb+dst-name).
        // The text is JSON-encoded (`"` is `\"`) and the DOM transformer
        // serializes attributes alphabetically.
        val injection = injectionOf(outcome.hand.requests.last())
        assertTrue(
            injection.contains("""<entity category=\"person\" id=\"1\" name=\"alice\"><attribute key=\"real_name\">Alice Smith</attribute></entity>"""),
            "the related entity and its attributes must be injected: $injection",
        )
        assertTrue(
            injection.contains(
                """<note category=\"person\" date=\"2026-08-01\" id=\"1\" name=\"alice\" subject-type=\"entity\">alice met bob at the conference</note>"""
            ),
            "the entity diary note must be injected with its subject names: $injection",
        )
        assertTrue(
            injection.contains(
                """<note date=\"2026-07-15\" dst-name=\"acme\" id=\"2\" src-name=\"alice\" subject-type=\"relationship\" verb=\"works_at\">alice joined acme as an engineer</note>"""
            ),
            "the relationship diary note must be injected with its subject names: $injection",
        )
        // the stored chat never carries the harness XML
        val stored = assertNotNull(outcome.store.stored)
        assertFalse(ChatCodec.encodeChat(stored).contains("related-entities"))
    }

    @Test
    fun `the nothing-to-query sentinel leaves the related sections empty`() = runBlocking {
        // a matching ELTM is seeded, but the rewrite answers with the
        // sentinel: no search runs, both sections stay empty
        val outcome = run(
            eltmService = eltmSeededForAliceSearch(),
            rewriteScript = { textRunFlow("Nothing worth query.") },
        )
        assertNull(outcome.error)
        val injection = injectionOf(outcome.hand.requests.last())
        assertFalse(injection.contains("<entity"), "no related entities must be injected")
        assertFalse(injection.contains("subject-type"), "no related notes must be injected")
    }

    @Test
    fun `a zero related-notes limit skips the note search but keeps the entity search`() = runBlocking {
        val outcome = run(
            eltmService = eltmSeededForAliceSearch(),
            relatedNotesLimit = 0,
            rewriteScript = { textRunFlow("alice") },
        )
        assertNull(outcome.error)
        val injection = injectionOf(outcome.hand.requests.last())
        assertTrue(
            injection.contains("""<entity category=\"person\" id=\"1\" name=\"alice\">"""),
            "the entity search still runs: $injection",
        )
        assertFalse(injection.contains("subject-type"), "the note search must be skipped")
    }

    @Test
    fun `both related limits zero skip the rewrite and the searches entirely`() = runBlocking {
        val outcome = run(
            eltmService = eltmSeededForAliceSearch(),
            relatedEntitiesLimit = 0,
            relatedNotesLimit = 0,
            rewriteScript = { error("the rewrite must not run when both limits are zero") },
        )
        assertNull(outcome.error)
        assertEquals(
            1, outcome.hand.requests.size,
            "only the chat round: no rewrite, no searches",
        )
        val injection = injectionOf(outcome.hand.requests.last())
        assertFalse(injection.contains("<entity"))
        assertFalse(injection.contains("subject-type"))
    }

    @Test
    fun `a reactive compaction keeps the pre-round related context in the refreshed injection`() = runBlocking {
        val outcome = run(
            store = InMemoryChatStore(crowdedSeed(lastInputTokens = 10)),
            eltmService = eltmSeededForAliceSearch(),
            rewriteScript = { textRunFlow("alice") },
            chatScript = { request ->
                if (request.messages.any { message ->
                        message.parts.any { part ->
                            part is ChatMessagePart.Text &&
                                    part.text.startsWith("CONTEXT COMPACTION")
                        }
                    }) {
                    stopEvents()
                } else {
                    listOf(
                        HandEvent.RunError("context_exhausted", "input too big")
                    )
                }
            },
        )
        assertNull(outcome.error)
        assertEquals(
            4,
            outcome.hand.requests.size,
            "rewrite -> exhausted run -> compactor -> fresh run",
        )
        // both the exhausted run and the compacted retry carry the same
        // pre-round search results: the compaction refreshes the injection
        // in place, it never re-searches
        for (request in listOf(outcome.hand.requests[1], outcome.hand.requests[3])) {
            val injection = injectionOf(request)
            assertTrue(
                injection.contains("""<entity category=\"person\" id=\"1\" name=\"alice\">"""),
                "the re-injected message must keep the pre-round related entity: $injection",
            )
            assertTrue(
                injection.contains("""id=\"2\" src-name=\"alice\" subject-type=\"relationship\" """),
                "the re-injected message must keep the pre-round related note: $injection",
            )
        }
    }

    // ------------------------------------------------------------------
    // history compaction (pre-round trigger + reactive context_exhausted)
    // ------------------------------------------------------------------

    private fun turnText(prefix: String, i: Int) = "$prefix $i".padEnd(200, 'x')

    private fun answer(text: String, inputTokens: Int = 100): ChatMessage = ChatMessage(
        ChatMessageRole.Assistant,
        listOf(ChatMessagePart.Text(text)),
        meta = ChatMessageMeta(
            inputTokens = inputTokens,
            outputTokens = 10,
            totalTokens = inputTokens + 10
        ),
        finishReason = "stop",
    )

    /**
     * 4 complete turns (realistic-length texts); the last assistant reports
     * a huge input. [userPrefix]/[answerPrefix] distinguish the turns, so
     * concurrent runs on different chats can detect cross-talk in the
     * stored history.
     */
    private fun crowdedSeed(
        userPrefix: String = "topic",
        answerPrefix: String = "answer",
        lastInputTokens: Int = 200_000,
    ): List<ChatMessage> = listOf(
        ChatMessage(
            ChatMessageRole.User,
            listOf(ChatMessagePart.Text(turnText(userPrefix, 1))),
            createdAt = Instant.parse("2026-08-17T09:00:00Z"),
        ),
        answer(turnText(answerPrefix, 1)),
        ChatMessage(
            ChatMessageRole.User,
            listOf(ChatMessagePart.Text(turnText(userPrefix, 2))),
            createdAt = Instant.parse("2026-08-17T10:00:00Z"),
        ),
        answer(turnText(answerPrefix, 2)),
        ChatMessage(
            ChatMessageRole.User,
            listOf(ChatMessagePart.Text(turnText(userPrefix, 3))),
            createdAt = Instant.parse("2026-08-17T11:00:00Z"),
        ),
        answer(turnText(answerPrefix, 3)),
        ChatMessage(
            ChatMessageRole.User,
            listOf(ChatMessagePart.Text(turnText(userPrefix, 4))),
            createdAt = Instant.parse("2026-08-17T12:00:00Z"),
        ),
        answer(turnText(answerPrefix, 4), lastInputTokens),
    )

    @Test
    fun `pre-round compaction fires when the estimated prompt exceeds the trigger`() {
        val outcome = run(
            store = InMemoryChatStore(crowdedSeed()),
        )
        assertNull(outcome.error)

        // the compactor ran (its one-shot /v1/run call) before the hand run,
        // followed by the rewrite (the extraction is queued, not called)
        assertEquals(3, outcome.hand.requests.size, "compactor run + rewrite run + hand run")
        assertTrue(outcome.hand.requests[0].systemPrompt!!.startsWith("You're summarizing"))

        // the hand received the compacted history: the summary user message,
        // the last 3 turns verbatim, the injected user message
        val sent = outcome.hand.requests[2].messages
        assertEquals(
            listOf(
                ChatMessageRole.User,
                ChatMessageRole.User, ChatMessageRole.Assistant,
                ChatMessageRole.User, ChatMessageRole.Assistant,
                ChatMessageRole.User, ChatMessageRole.Assistant,
                ChatMessageRole.User,
            ),
            sent.map { it.role },
        )
        // the summary user message leads with its send-time anchor in the
        // request (the compactor stamped it); the text follows
        assertTrue(
            ContextInjection().hasMetaPart(sent[0]),
            "the summary must carry a send-time anchor in the request",
        )
        val summaryText = (sent[0].parts[1] as ChatMessagePart.Text).text
        assertTrue(
            summaryText.startsWith("CONTEXT COMPACTION: "),
            "the summary carries the compaction marker"
        )
        assertTrue(summaryText.endsWith("compacted summary"))

        // stored: same shape, injection stripped, plus the new answer
        val stored = assertNotNull(outcome.store.stored)
        assertEquals(
            listOf(
                ChatMessageRole.User,
                ChatMessageRole.User, ChatMessageRole.Assistant,
                ChatMessageRole.User, ChatMessageRole.Assistant,
                ChatMessageRole.User, ChatMessageRole.Assistant,
                ChatMessageRole.User, ChatMessageRole.Assistant,
            ),
            stored.map { it.role },
        )
        assertTrue((stored[1].parts.single() as ChatMessagePart.Text).text.startsWith("topic 2"))
        assertTrue((stored[5].parts.single() as ChatMessagePart.Text).text.startsWith("topic 4"))
    }

    @Test
    fun `context exhausted compacts once and retries with a fresh hand run`() {
        val outcome = run(
            store = InMemoryChatStore(crowdedSeed(lastInputTokens = 10)),
            chatScript = { request ->
                if (request.messages.any {
                        it.parts.any { part ->
                            part is ChatMessagePart.Text && part.text.startsWith(
                                "CONTEXT COMPACTION"
                            )
                        }
                    }) {
                    stopEvents()
                } else {
                    listOf(
                        HandEvent.RunError(
                            "context_exhausted",
                            "input 100000 tokens exceeds context window"
                        )
                    )
                }
            },
        )
        assertNull(outcome.error)

        // reactive compaction happened once, mid-run: the rewrite ran first, then
        // the first hand run reported exhaustion, the compactor's one-shot
        // run happened, and a fresh hand run received the compacted history
        // (the dropped messages were queued, not extracted on the run path)
        assertEquals(
            4,
            outcome.hand.requests.size,
            "rewrite -> exhausted run -> compactor -> fresh run"
        )
        assertTrue(outcome.hand.requests[2].systemPrompt!!.startsWith("You're summarizing"))
        assertTrue(outcome.callback.errors.isEmpty())

        val stored = assertNotNull(outcome.store.stored)
        // the first turn is summarized, the rest of the history is kept
        assertEquals(
            listOf(ChatMessageRole.User),
            stored.take(1).map { it.role },
        )
        assertTrue((stored[0].parts.single() as ChatMessagePart.Text).text.startsWith("CONTEXT COMPACTION: "))
        assertTrue((stored[1].parts.single() as ChatMessagePart.Text).text.startsWith("topic 3"))
    }

    @Test
    fun `a second exhaustion compacts again and a failed compaction fails the run`() {
        // the loop compacts on EVERY exhaustion: the second compactor call
        // answers with a truncated summary (a length-classified hand error),
        // the compactor rejects it, and the run fails without storing.
        var compactions = 0
        val outcome = run(
            store = InMemoryChatStore(crowdedSeed(lastInputTokens = 10)),
            chatScript = { listOf(HandEvent.RunError("context_exhausted", "still too big")) },
            compactionScript = {
                if (++compactions == 1) {
                    textRunFlow("compacted summary")
                } else {
                    errorRunFlow("output_budget_exhausted", "output hit the token budget")
                }
            },
        )
        val e = assertIs<IllegalStateException>(outcome.error)
        assertEquals("Compaction summarization failed", e.message)
        val cause = assertIs<HandRunException>(e.cause)
        assertEquals("output_budget_exhausted", cause.type)
        assertEquals(
            5,
            outcome.hand.requests.size,
            "rewrite -> exhausted -> compact -> exhausted -> compact (fails)"
        )
        assertEquals(0, outcome.store.storeCount, "a failed run must never store")
    }

    @Test
    fun `pre-round compaction queues the dropped messages for background extraction`() = runBlocking {
        val model = catalogModel("bifrost/cerebras/gemma-4-31b", compactionKeepRounds = 3)
        val hand = FakeHand(
            runScript = { request ->
                when {
                    request.systemPrompt?.startsWith("You're summarizing") == true ->
                        textRunFlow("compacted summary")

                    else -> stopEvents()
                }
            },
        )
        val handService = testHandService(hand)
        val compactionService = ChatCompactionService(
            model = model,
            hand = handService,
            policy = HandRunPolicy(0, 300_000),
        )
        val rewriteService = QueryRewriteService(
            model = model,
            hand = handService,
            policy = HandRunPolicy(0, 300_000),
        )
        val callback = RecordingCallback()
        val store = InMemoryChatStore(crowdedSeed())
        val error = runBlocking {
            runCatching {
                PersistChatService(
                    chatStore = store,
                    eltmService = testPostgresEltmService(FakeHand()),
                    queryRewriteService = rewriteService,
                    hand = handService,
                    compactionService = compactionService,
                    systemPromptService = mainAgentSystemPromptService,
                    extractionQueue = testExtractionQueue,
                    rewriteRounds = 5,
                    relatedEntitiesLimit = 5,
                    relatedNotesLimit = 5,
                    maxRounds = 64,
                    policy = HandRunPolicy(0, 300_000),
                ).runChat(
                    chatId = "chat-1",
                    model = model,
                    userParts = listOf(ChatMessagePart.Text("hello")),
                    persona = defaultPersona(),
                    toolProvider = EmptyToolProvider,
                    callback = callback,
                )
            }.exceptionOrNull()
        }
        assertNull(error)

        // request order: compactor, rewrite, chat round — the memory
        // pipeline (extractor + writer) never runs on the request path
        assertEquals(3, hand.requests.size, "compactor + rewrite + chat round")

        // the queue holds exactly one job: the raw dropped messages (turn 1),
        // NOT the summary and NOT an extraction input — the extractor's
        // instruction and the <meta> anchoring are added by the pipeline when
        // the worker runs
        assertEquals(1, TestDb.allExtractionJobs().size, "one queue job carries the dropped messages")
        val claimed = assertNotNull(testExtractionQueue.claim())
        val dropped = claimed.messages
        assertEquals(
            listOf(ChatMessageRole.User, ChatMessageRole.Assistant),
            dropped.map { it.role },
        )
        assertTrue(
            (dropped[0].parts.single() as ChatMessagePart.Text).text.startsWith("topic 1"),
            "the raw dropped turn feeds the extraction, not the summary",
        )
        assertTrue(
            (dropped[1].parts.single() as ChatMessagePart.Text).text.startsWith("answer 1")
        )
        val contextInjection = ContextInjection()
        assertFalse(
            contextInjection.hasMetaPart(dropped[0]),
            "the snapshot carries raw messages; the worker anchors them at extraction time",
        )

        // nothing was written into the ELTM on the run path
        assertTrue(
            TestDb.allEltmNotes().isEmpty(),
            "the ELTM is only written by the background worker",
        )

        // the run completed and stored the compacted history
        val stored = assertNotNull(store.stored)
        assertTrue((stored[0].parts.single() as ChatMessagePart.Text).text.startsWith("CONTEXT COMPACTION: "))
    }

    @Test
    fun `a failed enqueue fails the run without storing`() = runBlocking {
        // the enqueue is the only memory-extraction step left on the run
        // path: when it fails (a DB error), the run must fail BEFORE the
        // store, so the dropped messages stay in the stored chat and the
        // retry's compaction re-enqueues them — nothing is silently lost.
        val outcome = run(
            store = InMemoryChatStore(crowdedSeed()),
            extractionQueue = FailingEnqueueQueue(testExtractionQueue),
        )
        val e = assertIs<IllegalStateException>(outcome.error)
        assertEquals("extraction queue is down", e.message)
        // the compaction itself succeeded (the summarizer ran) — the
        // failure came from the enqueue after it
        assertTrue(outcome.hand.requests[0].systemPrompt!!.startsWith("You're summarizing"))
        assertTrue(TestDb.allExtractionJobs().isEmpty(), "no job was enqueued")
        assertEquals(0, outcome.store.storeCount, "a failed run must never store")
    }

    @Test
    fun `a full-body reactive compaction re-appends the injection with the user input`() = runBlocking {
        // a fresh chat whose single user message (injection + input) is the
        // only user message: the compaction's keep count collapses to zero,
        // replacing the whole chat — injected message included — with the
        // summary. The loop must re-append the injection with the user's
        // parts, so the retried round still carries the user input.
        val outcome = run(
            store = InMemoryChatStore(),
            chatScript = { request ->
                if (request.messages.any { message ->
                        message.parts.any { part ->
                            part is ChatMessagePart.Text && part.text.startsWith(
                                "CONTEXT COMPACTION"
                            )
                        }
                    }) {
                    stopEvents()
                } else {
                    listOf(HandEvent.RunError("context_exhausted", "input too big"))
                }
            },
        )
        assertNull(outcome.error)

        // exhausted attempt -> compaction -> fresh run with the re-appended
        // input (the rewrite's one-shot run precedes the first attempt; the
        // dropped messages are queued, not extracted on the run path)
        assertEquals(
            4,
            outcome.hand.requests.size,
            "rewrite -> exhausted run -> compactor -> fresh run"
        )
        // the queued snapshot is the WHOLE chat — ending with the run's user
        // message, i.e. a mid-turn fragment the stored-chat validation would
        // reject. The queue's snapshot decode must accept it (the worker's
        // claim below runs ChatCodec.validateSnapshot).
        assertEquals(1, TestDb.allExtractionJobs().size, "the drop region is queued")
        val claimed = assertNotNull(testExtractionQueue.claim())
        assertEquals(1, claimed.messages.size, "the fragment is the run's user message only")
        assertEquals(
            listOf(ChatMessagePart.Text("hello")),
            claimed.messages.single().parts,
        )
        assertNotNull(
            claimed.messages.single().createdAt,
            "the run's user message must carry its send time in the snapshot",
        )
        val retried = outcome.hand.requests.last()
        assertTrue(
            retried.messages.any { message ->
                message.parts.any { part ->
                    part is ChatMessagePart.Text && part.text.contains("hello")
                }
            },
            "the retried round must carry the user's input again",
        )
        // the ELTM version read fresh at the re-injection: a fresh chat's
        // stored version is "" vs the fake's "0", so the re-appended
        // injection must flag eltm-updated
        assertTrue(
            injectionOf(retried).contains("<eltm-updated>true</eltm-updated>"),
            "the re-appended injection must carry the fresh eltm flag",
        )

        // stored: the summary, the stripped user message, the final answer
        val stored = assertNotNull(outcome.store.stored)
        assertEquals(
            listOf(ChatMessageRole.User, ChatMessageRole.User, ChatMessageRole.Assistant),
            stored.map { it.role },
        )
        assertTrue(
            (stored[0].parts.single() as ChatMessagePart.Text).text.startsWith("CONTEXT COMPACTION: ")
        )
        assertEquals(listOf(ChatMessagePart.Text("hello")), stored[1].parts, "injection stripped")
        assertEquals(listOf(ChatMessagePart.Text("ok")), stored[2].parts)
    }

    @Test
    fun `one shared service instance serves concurrent runs without cross-talk`() = runBlocking {
        // both chats sit above the proactive compaction trigger, so each run
        // compacts and enqueues through the SAME shared service instances
        // (ChatCompactionService / ExtractionQueue / PersistChatService):
        // this pins the statelessness claim of the shared services under
        // concurrent calls. Distinct chat content makes any cross-talk
        // visible in the stores.
        val eltm = testPostgresEltmService(FakeHand())
        val hand = FakeHand(
            runScript = { request ->
                when {
                    request.systemPrompt?.startsWith("You're summarizing") == true ->
                        textRunFlow("compacted summary")

                    else -> stopEvents()
                }
            },
        )
        val model = catalogModel("bifrost/cerebras/gemma-4-31b", compactionKeepRounds = 3)
        val handService = testHandService(hand)
        val compactionService = ChatCompactionService(
            model = model,
            hand = handService,
            policy = HandRunPolicy(0, 300_000),
        )
        val chatStore = ConcurrentChatStore()
        val persistService = PersistChatService(
            chatStore = chatStore,
            eltmService = eltm,
            queryRewriteService = QueryRewriteService(
                model = model,
                hand = handService,
                policy = HandRunPolicy(0, 300_000),
            ),
            hand = handService,
            compactionService = compactionService,
            systemPromptService = mainAgentSystemPromptService,
            extractionQueue = testExtractionQueue,
            rewriteRounds = 5,
            relatedEntitiesLimit = 5,
            relatedNotesLimit = 5,
            maxRounds = 64,
            policy = HandRunPolicy(0, 300_000),
        )

        val chats = listOf(
            "chat-a" to "alpha",
            "chat-b" to "beta",
        )
        chats.forEach { (chatId, prefix) ->
            chatStore.seed(chatId, crowdedSeed(userPrefix = prefix, answerPrefix = prefix))
        }
        val errors = coroutineScope {
            chats.map { (chatId, prefix) ->
                async(Dispatchers.Default) {
                    runCatching {
                        persistService.runChat(
                            chatId = chatId,
                            model = model,
                            userParts = listOf(ChatMessagePart.Text("$prefix question")),
                            persona = defaultPersona(),
                            toolProvider = EmptyToolProvider,
                            callback = RecordingCallback(),
                        )
                    }.exceptionOrNull()
                }
            }.awaitAll()
        }
        assertTrue(errors.all { it == null }, "both concurrent runs must succeed: $errors")

        chats.forEach { (chatId, prefix) ->
            val stored = assertNotNull(chatStore.stored(chatId), "$chatId must be stored")
            // summary + turns 2-4 verbatim + the user message (injection
            // stripped) + the final answer
            assertEquals(
                listOf(
                    ChatMessageRole.User,
                    ChatMessageRole.User, ChatMessageRole.Assistant,
                    ChatMessageRole.User, ChatMessageRole.Assistant,
                    ChatMessageRole.User, ChatMessageRole.Assistant,
                    ChatMessageRole.User, ChatMessageRole.Assistant,
                ),
                stored.map { it.role },
                "$chatId history shape",
            )
            assertTrue(
                (stored[0].parts.single() as ChatMessagePart.Text).text.startsWith("CONTEXT COMPACTION: ")
            )
            assertTrue(
                (stored[1].parts.single() as ChatMessagePart.Text).text.startsWith("$prefix 2"),
                "$chatId must keep its own history (no cross-talk)",
            )
            assertFalse(
                stored.any { message ->
                    message.parts.any { part ->
                        part is ChatMessagePart.Text && part.text.contains(
                            if (prefix == "alpha") "beta" else "alpha"
                        )
                    }
                },
                "$chatId must not contain the other chat's content",
            )
            assertEquals(
                listOf(ChatMessagePart.Text("$prefix question")),
                stored[7].parts,
                "the user message keeps its own input with the injection stripped",
            )
        }

        // each run queued its own dropped messages into the shared queue;
        // per chat the shared hand served compactor + rewrite + chat round
        assertEquals(
            2,
            TestDb.allExtractionJobs().size,
            "both compactions enqueued their dropped messages",
        )
        assertEquals(
            6,
            hand.requests.size,
            "2 chats x (compact + rewrite + chat round)",
        )
    }
}

private class TurnOutcome(
    val error: Throwable?,
    val store: InMemoryChatStore,
    val callback: RecordingCallback,
    val hand: FakeHand,
)

/**
 * An [ExtractionQueue] that fails the enqueue (a DB outage stand-in) while
 * delegating everything else: pins the compaction path's enqueue-failure
 * contract (the run fails before the store).
 */
private class FailingEnqueueQueue(private val delegate: ExtractionQueue) : ExtractionQueue {
    override suspend fun enqueue(messages: List<ChatMessage>): Long =
        throw IllegalStateException("extraction queue is down")

    override suspend fun claim(): ClaimedJob? = delegate.claim()

    override suspend fun complete(id: Long) = delegate.complete(id)

    override suspend fun reschedule(id: Long) = delegate.reschedule(id)
}

private fun mcpAddTool(): MockTool = MockTool(
    name = "add",
    description = "Add two numbers a and b",
    handler = { args ->
        fun num(key: String) = args[key]?.jsonPrimitive?.let { it.content.toLongOrNull() } ?: 0L
        MockToolReply("${num("a")} + ${num("b")} = ${num("a") + num("b")}")
    },
)

private class InMemoryChatStore(seed: List<ChatMessage>? = null) : ChatStore {
    var stored: List<ChatMessage>? = seed
        private set
    var storeCount = 0
        private set
    var storedEltmVersion: String? = null
        private set
    var storedPersonaId: Long? = null
        private set

    override suspend fun load(chatId: String): ChatEntry? = stored?.let {
        ChatEntry(
            ChatInfo(chatId, DEFAULT_CHAT_TITLE, storedPersonaId ?: DEFAULT_PERSONA_ID),
            ChatContent(it, storedEltmVersion ?: "", storedPersonaId ?: DEFAULT_PERSONA_ID)
        )
    }

    override suspend fun store(chatId: String, chat: ChatContent) {
        storeCount++
        stored = chat.messages
        storedEltmVersion = chat.eltmVersion
        storedPersonaId = chat.personaId
    }

    // the chat-row CRUD methods are not part of this fake's contract (the
    // persist loop tests only exercise load/store)
    override suspend fun listChats(): List<ChatInfo> =
        error("not exercised by the persist loop tests")

    override suspend fun newChat(personaId: Long, title: String): ChatInfo =
        error("not exercised by the persist loop tests")

    override suspend fun rename(chatId: String, title: String): ChatInfo =
        error("not exercised by the persist loop tests")

    override suspend fun delete(chatId: String): Boolean =
        error("not exercised by the persist loop tests")
}

/**
 * A thread-safe in-memory [ChatStore] keyed by chat id: the shared-service
 * concurrency test runs several chats through one service (and one store)
 * at the same time.
 */
private class ConcurrentChatStore : ChatStore {
    private val chats = ConcurrentHashMap<String, ChatContent>()

    fun seed(chatId: String, chat: List<ChatMessage>, eltmVersion: String = "") {
        chats[chatId] = ChatContent(chat, eltmVersion, DEFAULT_PERSONA_ID)
    }

    override suspend fun load(chatId: String): ChatEntry? = chats[chatId]?.let {
        ChatEntry(
            ChatInfo(chatId, DEFAULT_CHAT_TITLE, it.personaId),
            ChatContent(it.messages, it.eltmVersion, it.personaId)
        )
    }

    override suspend fun store(chatId: String, chat: ChatContent) {
        chats[chatId] = chat
    }

    // the chat-row CRUD methods are not part of this fake's contract (the
    // persist loop concurrency test only exercises load/store)
    override suspend fun listChats(): List<ChatInfo> =
        error("not exercised by the persist loop tests")

    override suspend fun newChat(personaId: Long, title: String): ChatInfo =
        error("not exercised by the persist loop tests")

    override suspend fun rename(chatId: String, title: String): ChatInfo =
        error("not exercised by the persist loop tests")

    override suspend fun delete(chatId: String): Boolean =
        error("not exercised by the persist loop tests")

    fun stored(chatId: String): List<ChatMessage>? = chats[chatId]?.messages
}

private class RecordingCallback : StreamingExecutionCallback {
    val texts = mutableListOf<String>()
    val thinkings = mutableListOf<String>()
    val toolCalls = mutableListOf<Pair<String, JsonObject>>()
    val toolResults = mutableListOf<ChatMessagePart.ToolResult>()
    val errors = mutableListOf<String>()

    override suspend fun onTextDelta(text: String) {
        texts += text
    }

    override suspend fun onReasoningDelta(text: String) {
        thinkings += text
    }

    override suspend fun onToolCall(name: String, args: JsonObject) {
        toolCalls += name to args
    }

    override suspend fun onToolResults(results: List<ChatMessagePart.ToolResult>) {
        toolResults += results
    }

    override suspend fun onStreamError(error: String) {
        errors += error
    }
}

private fun ChatMessagePart.ToolResult.textContent(): String =
    parts.filterIsInstance<ChatMessagePart.Text>().joinToString("") { it.text }
