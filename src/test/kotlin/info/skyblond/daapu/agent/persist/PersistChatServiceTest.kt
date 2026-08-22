package info.skyblond.daapu.agent.persist

import info.skyblond.daapu.agent.ModelCatalog
import info.skyblond.daapu.agent.chat.*
import info.skyblond.daapu.agent.model.LLM
import info.skyblond.daapu.agent.model.ModelCapabilityException
import info.skyblond.daapu.agent.model.ModelProvider
import info.skyblond.daapu.agent.oneshot.compaction.ChatCompactionService
import info.skyblond.daapu.agent.oneshot.eltm.EltmToolProvider
import info.skyblond.daapu.agent.oneshot.eltm.EltmWriterService
import info.skyblond.daapu.agent.oneshot.rewrite.QueryRewriteService
import info.skyblond.daapu.agent.oneshot.sstm.SstmExtractionService
import info.skyblond.daapu.agent.tool.CombinedToolProvider
import info.skyblond.daapu.agent.tool.EmptyToolProvider
import info.skyblond.daapu.agent.tool.ToolCallRequest
import info.skyblond.daapu.agent.tool.ToolProvider
import info.skyblond.daapu.config.McpServerConfig
import info.skyblond.daapu.config.McpTransportType
import info.skyblond.daapu.db.DEFAULT_CHAT_TITLE
import info.skyblond.daapu.hand.*
import info.skyblond.daapu.mcp.McpToolProvider
import info.skyblond.daapu.mcp.MockMcpServer
import info.skyblond.daapu.mcp.MockTool
import info.skyblond.daapu.mcp.MockToolReply
import info.skyblond.daapu.memory.eltm.EltmEntity
import info.skyblond.daapu.memory.eltm.EltmNote
import info.skyblond.daapu.memory.eltm.EltmRelationship
import info.skyblond.daapu.memory.eltm.EltmService
import info.skyblond.daapu.memory.sstm.MemoriesWithVersion
import info.skyblond.daapu.memory.sstm.ShortTermMemory
import info.skyblond.daapu.memory.sstm.SstmService
import info.skyblond.daapu.testutil.FakeEltmService
import info.skyblond.daapu.testutil.MutableSstmService
import info.skyblond.daapu.testutil.mergeRunFlow
import info.skyblond.daapu.testutil.testHandService
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
 * compact → extract → refresh injection → fresh run, with no attempt cap).
 */
class PersistChatServiceTest {

    private val systemPrompt = "You are Raven."

    /**
     * Catalog model, optionally with compaction values different from the
     * catalog entry (the compaction tuning lives on the model).
     */
    private fun catalogModel(
        id: String,
        compactionTriggerFraction: Double = 0.8,
        compactionKeepRounds: Int = 2,
    ): LLM {
        val catalog = ModelCatalog(
            mapOf(
                "bifrost" to ModelProvider("bifrost", "http://127.0.0.1:9/v1", "test-key"),
                "deepinfra" to ModelProvider("deepinfra", "http://127.0.0.1:9/v1", "test-key"),
            )
        ).findModel(id)!!
        return LLM(
            provider = catalog.provider,
            modelId = catalog.modelId,
            contextLength = catalog.contextLength,
            maxOutputTokens = catalog.maxOutputTokens,
            capabilities = catalog.capabilities,
            compactionTriggerFraction = compactionTriggerFraction,
            compactionKeepRounds = compactionKeepRounds,
        )
    }

    /**
     * Runs one turn; returns the outcome for assertions. The fake hand
     * dispatches on the out-of-band system prompt, standing in for the four
     * one-shot roles (compactor / extractor / merger / query rewriter) plus
     * the chat loop.
     */
    private fun run(
        modelId: String = "bifrost/cerebras/gemma-4-31b",
        userParts: List<ChatMessagePart> = listOf(ChatMessagePart.Text("hello")),
        store: InMemoryChatStore = InMemoryChatStore(),
        sstmService: SstmService = InMemorySstmService(),
        eltmService: EltmService = FakeEltmService(),
        toolProvider: ToolProvider = EmptyToolProvider,
        sstmExtractionService: SstmExtractionService? = null,
        compactionKeepRounds: Int = 3,
        sstmCapacity: Int = 1_000_000,
        purgeBatchSize: Int = 10,
        rewriteRounds: Int = 5,
        relatedEntitiesLimit: Int = 5,
        relatedNotesLimit: Int = 5,
        chatScript: suspend (HandRunRequest) -> List<HandEvent> = { stopEvents() },
        compactionScript: suspend (HandRunRequest) -> List<HandEvent> =
            { textRunFlow("compacted summary") },
        extractionScript: suspend (HandRunRequest) -> List<HandEvent> =
            { textRunFlow("Nothing worth remember.") },
        mergeScript: (suspend (HandRunRequest) -> List<HandEvent>)? = null,
        writerScript: suspend (HandRunRequest) -> List<HandEvent> =
            { textRunFlow("done") },
        rewriteScript: suspend (HandRunRequest) -> List<HandEvent> =
            { textRunFlow("rewritten query") },
    ): TurnOutcome {
        val model = catalogModel(modelId, compactionKeepRounds = compactionKeepRounds)
        val hand = FakeHand(
            runScript = { request ->
                when {
                    request.systemPrompt?.startsWith("You're summarizing") == true ->
                        compactionScript(request)

                    request.systemPrompt?.startsWith("You're extracting") == true ->
                        extractionScript(request)

                    request.systemPrompt?.startsWith("You're merging") == true ->
                        mergeScript?.invoke(request) ?: error("unexpected merge run")

                    request.systemPrompt?.startsWith("You're maintaining an external long-term memory") == true ->
                        writerScript(request)

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
            maxRetries = 0,
            streamIdleTimeoutMs = 300_000,
        )
        val rewriteService = QueryRewriteService(
            model = model,
            hand = handService,
            maxRetries = 0,
            streamIdleTimeoutMs = 300_000,
        )
        val callback = RecordingCallback()
        val error = runBlocking {
            runCatching {
                PersistChatService(
                    chatStore = store,
                    sstmService = sstmService,
                    eltmService = eltmService,
                    queryRewriteService = rewriteService,
                    hand = handService,
                    compactionService = compactionService,
                    // the default answers the extractor with the sentinel, so
                    // extraction is a no-op: compaction-focused tests neither
                    // accumulate calls nor run the merge
                    sstmExtractionService = sstmExtractionService
                        ?: SstmExtractionService(
                            extractModel = model,
                            hand = handService,
                            sstmService = sstmService,
                            maxRetries = 0,
                            streamIdleTimeoutMs = 300_000,
                            // the purge must never fire here: the SSTM stays
                            // far under an effectively unbounded capacity
                            eltmWriterService = EltmWriterService(
                                writerModel = model,
                                hand = handService,
                                eltmService = eltmService,
                                maxWriterRounds = 150,
                                maxRetries = 0,
                                streamIdleTimeoutMs = 300_000,
                            ),
                            sstmCapacity = sstmCapacity,
                            purgeBatchSize = purgeBatchSize,
                        ),
                    rewriteRounds = rewriteRounds,
                    relatedEntitiesLimit = relatedEntitiesLimit,
                    relatedNotesLimit = relatedNotesLimit,
                    maxRounds = 64,
                    maxRetries = 0,
                    streamIdleTimeoutMs = 300_000,
                ).runChat(
                    chatId = "chat-1",
                    model = model,
                    userParts = userParts,
                    systemPrompt = systemPrompt,
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
        assertEquals(
            "http://127.0.0.1:9/api/hand/tool",
            request.toolCallbackUrl,
            "the callback URL is attached even without tools (the hand only POSTs it on a tool call)",
        )
        assertEquals(
            "http://127.0.0.1:9/api/hand/tools",
            request.toolListUrl,
            "the tool-list URL is attached on every run: the hand re-queries " +
                    "GET /api/hand/tools before every LLM request, so no static " +
                    "tool list travels in the request",
        )
        assertEquals(systemPrompt, request.systemPrompt, "the system prompt travels out of band")
        assertTrue(injectionOf(request).contains("<sstm-updated>true</sstm-updated>"))

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
    fun `sstm version is persisted on the chat and the sstm-updated flag tracks changes`() {
        val store = InMemoryChatStore()
        val sstm = InMemorySstmService(version = "v1")

        // a fresh chat has no stored version, so the first run must flag
        // the memory list as updated and persist the version it saw
        var outcome = run(store = store, sstmService = sstm)
        assertNull(outcome.error)
        assertTrue(
            injectionOf(outcome.hand.requests.last()).contains("<sstm-updated>true</sstm-updated>"),
            "fresh chat must flag memories as updated",
        )
        assertEquals("v1", store.storedSstmVersion, "run must persist the memory version")

        // same version as the last run: nothing changed, no flag
        outcome = run(store = store, sstmService = sstm)
        assertNull(outcome.error)
        assertTrue(
            injectionOf(outcome.hand.requests.last()).contains("<sstm-updated>false</sstm-updated>"),
            "an unchanged version must not flag",
        )
        assertEquals("v1", store.storedSstmVersion)

        // a memory edit bumps the version: the next run must flag again
        sstm.version = "v2"
        outcome = run(store = store, sstmService = sstm)
        assertNull(outcome.error)
        assertTrue(
            injectionOf(outcome.hand.requests.last()).contains("<sstm-updated>true</sstm-updated>"),
            "a changed version must flag",
        )
        assertEquals("v2", store.storedSstmVersion)
    }

    @Test
    fun `sstm version is not persisted on a failed run`() {
        val store = InMemoryChatStore()
        val sstm = InMemorySstmService(version = "v1")
        val outcome = run(store = store, sstmService = sstm)
        assertNull(outcome.error)
        assertEquals("v1", store.storedSstmVersion)

        // a failed run must not touch the stored version: history stays
        // at the last good state
        sstm.version = "v2"
        val failed = run(
            store = store,
            sstmService = sstm,
            chatScript = { listOf(HandEvent.RunError("upstream", "boom")) },
        )
        assertIs<HandRunException>(failed.error)
        assertEquals("v1", store.storedSstmVersion, "failed run must not update the version")

        // ... so the next successful run still flags the pending change
        val retry = run(store = store, sstmService = sstm)
        assertNull(retry.error)
        assertTrue(
            injectionOf(retry.hand.requests.last()).contains("<sstm-updated>true</sstm-updated>"),
            "a change missed by a failed run must flag on the next success",
        )
        assertEquals("v2", store.storedSstmVersion)
    }

    @Test
    fun `eltm version is persisted on the chat and the eltm-updated flag tracks changes`() {
        val store = InMemoryChatStore()
        val eltm = FakeEltmService()

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
        eltm.writeVersion = 1
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
        val eltm = FakeEltmService()
        val outcome = run(store = store, eltmService = eltm)
        assertNull(outcome.error)
        assertEquals("0", store.storedEltmVersion)

        // a failed run must not touch the stored version: history stays
        // at the last good state
        eltm.writeVersion = 1
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
    fun `a purge during reactive compaction bumps the ELTM version the re-injected flag sees`() {
        // the SSTM is over capacity, so the reactive compaction's extraction
        // pipeline purges the oldest batch into the ELTM, bumping the ELTM
        // version BETWEEN the initial injection and the re-injection: the
        // loop must read the version fresh at the re-injection — the
        // exhausted attempt's injection (pre-purge) says false, the retried
        // round's must count the purge write, and the run stores the
        // bumped version
        val store = InMemoryChatStore()
        val sstm = MutableSstmService(
            listOf(
                ShortTermMemory(
                    id = 1L,
                    lastUpdate = Instant.parse("2026-08-01T00:00:00Z"),
                    content = "a".repeat(100),
                )
            )
        )
        val eltm = FakeEltmService()
        val writerProvider = EltmToolProvider(eltm)

        // run 1: a normal turn stores the ELTM version it saw
        val first = run(store = store, sstmService = sstm, eltmService = eltm)
        assertNull(first.error)
        assertEquals("0", store.storedEltmVersion)

        // run 2: the first chat attempt is exhausted, the reactive
        // compaction's pipeline purges the over-capacity SSTM (writer run
        // creates an entity), and the retried round is re-injected
        var chatRounds = 0
        val outcome = run(
            store = store,
            sstmService = sstm,
            eltmService = eltm,
            sstmCapacity = 50,
            purgeBatchSize = 10,
            chatScript = { _ ->
                if (++chatRounds == 1) {
                    listOf(HandEvent.RunError("context_exhausted", "input too big"))
                } else {
                    stopEvents()
                }
            },
            writerScript = { _ ->
                val call = ChatMessagePart.ToolCall(
                    id = "call_1",
                    tool = "create_entity",
                    args = buildJsonObject {
                        put("name", "Alice")
                        put("category", "person")
                    },
                )
                val round = assistantMessage(parts = listOf(call), finishReason = "tool_calls")
                listOf(HandEvent.AssistantMessage(round)) +
                        toolRoundEvents(round, writerProvider) +
                        listOf(
                            HandEvent.AssistantMessage(assistantMessage("done")),
                            HandEvent.Done("stop"),
                        )
            },
        )
        assertNull(outcome.error)

        // the purge really wrote into the ELTM, bumping its version
        assertTrue(
            eltm.entities.values.any { it.canonicalName == "alice" },
            "the purge must write the victim into the ELTM",
        )
        // request order: rewrite, exhausted chat, compactor, extractor, writer, fresh run
        assertEquals(6, outcome.hand.requests.size, "rewrite -> exhausted -> compact -> extract -> purge -> fresh run")
        // the exhausted attempt was injected BEFORE the purge: its flag must
        // still say the stored version matches
        assertTrue(
            injectionOf(outcome.hand.requests[1]).contains("<eltm-updated>false</eltm-updated>"),
            "the pre-purge injection must not flag",
        )
        // the retried round was re-injected AFTER the purge: the fresh read
        // must flag the bumped version
        assertTrue(
            injectionOf(outcome.hand.requests.last()).contains("<eltm-updated>true</eltm-updated>"),
            "the re-injected flag must count the purge write",
        )
        assertEquals("1", store.storedEltmVersion, "the run must store the version it last saw")
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
                // the fake hand plays the model AND the hand's callback
                // POST: the hand would HTTP-POST the call to the brain,
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
            listOf(
                McpServerConfig(
                    namespace = "calc",
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
                    // the fake hand plays the model AND the hand's callback
                    // POST (the HTTP contract is pinned by HandCallbackTest);
                    // like the real hand it lists the tools first (the
                    // brain's GET /api/hand/tools) before executing calls,
                    // which populates the provider's name mapping
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
            // the chat loop's real shape (ChatRunService composes it): the MCP
            // servers plus the read-only ELTM tools, one namespaced set; a round
            // where the model queries the ELTM routes to the local provider and
            // stores the paired call/result like any other tool round
            val eltm = FakeEltmService()
            eltm.createEntity("Alice", "person")
            val eltmProvider = EltmToolProvider(eltm, readOnly = true, namespace = "eltm")
            val mcpServer = MockMcpServer(listOf(mcpAddTool()))
            val mcpProvider = McpToolProvider(
                listOf(
                    McpServerConfig(
                        namespace = "calc",
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
        // the anchor renders the message's own instant in the system zone,
        // so the expected date is computed from it (never hard-coded)
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
        // re-injects its own (empty) spec — the loop's memory list and
        // updated flags must not leak into the rewrite prompt
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
        // re-injected with its own spec: the loop's flags are not in it
        assertTrue(
            injectionOf(rewrite).contains("<sstm-updated>false</sstm-updated>"),
            "the rewrite sees its own injection, not the loop's",
        )

        // the chat round still carried the loop's real injection
        assertTrue(
            injectionOf(outcome.hand.requests.last()).contains("<sstm-updated>true</sstm-updated>"),
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
            maxRetries = 0,
            streamIdleTimeoutMs = 300_000,
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
            maxRetries = 0,
            streamIdleTimeoutMs = 300_000,
        ).rewriteQuery(chat, 1)
        assertEquals("When did Alice visit Paris?", result)
    }

    @Test
    fun `rewriteQuery returns null on a chat with no user message without calling the LLM`() = runBlocking {
        val hand = FakeHand(runScript = { error("the rewrite must not call the LLM") })
        val service = QueryRewriteService(
            model = catalogModel("bifrost/cerebras/gemma-4-31b"),
            hand = testHandService(hand),
            maxRetries = 0,
            streamIdleTimeoutMs = 300_000,
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
            maxRetries = 0,
            streamIdleTimeoutMs = 300_000,
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
     * A fake ELTM whose substring search matches "alice": one person entity
     * with an attribute, one company entity, one relationship
     * (alice→works_at→acme), and two diary notes (one per subject kind)
     * whose texts mention alice.
     */
    private fun eltmSeededForAliceSearch() = FakeEltmService().apply {
        val alice = EltmEntity(id = 1, canonicalName = "alice", category = "person")
        entities[1] = alice
        attributes[1] = mutableMapOf("real_name" to "Alice Smith")
        entities[2] = EltmEntity(id = 2, canonicalName = "bob", category = "person")
        entities[3] = EltmEntity(id = 3, canonicalName = "acme", category = "company")
        relationships[1] = EltmRelationship(
            id = 1, srcId = 1, dstId = 3, verb = "works_at", valid = true,
        )
        notes[1] = EltmNote(
            id = 1, entityId = 1, relationshipId = null,
            eventDate = LocalDate.of(2026, 8, 1),
            note = "alice met bob at the conference",
            createdAt = OffsetDateTime.parse("2026-08-01T12:00:00Z"),
        )
        notes[2] = EltmNote(
            id = 2, entityId = null, relationshipId = 1,
            eventDate = LocalDate.of(2026, 7, 15),
            note = "alice joined acme as an engineer",
            createdAt = OffsetDateTime.parse("2026-07-15T12:00:00Z"),
        )
    }

    @Test
    fun `the eltm context injection carries the searched entities and notes`() {
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
    fun `the nothing-to-query sentinel leaves the related sections empty`() {
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
    fun `a zero related-notes limit skips the note search but keeps the entity search`() {
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
    fun `both related limits zero skip the rewrite and the searches entirely`() {
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
    fun `a reactive compaction keeps the pre-round related context in the refreshed injection`() {
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
            5,
            outcome.hand.requests.size,
            "rewrite -> exhausted run -> compactor -> extractor -> fresh run",
        )
        // both the exhausted run and the compacted retry carry the same
        // pre-round search results: the compaction refreshes the injection
        // in place, it never re-searches
        for (request in listOf(outcome.hand.requests[1], outcome.hand.requests[4])) {
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
        // followed by the sentinel extraction (a no-op) and the rewrite
        assertEquals(4, outcome.hand.requests.size, "compactor run + extractor run + rewrite run + hand run")
        assertTrue(outcome.hand.requests[0].systemPrompt!!.startsWith("You're summarizing"))

        // the hand received the compacted history: the summary user message,
        // the last 3 turns verbatim, the injected user message
        val sent = outcome.hand.requests[3].messages
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
        // run happened (plus the sentinel extraction), and a fresh hand run
        // received the compacted history
        assertEquals(
            5,
            outcome.hand.requests.size,
            "rewrite -> exhausted run -> compactor -> extractor -> fresh run"
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
        // the outer message names the wrapper only; the detail lives on the cause
        assertEquals("Compaction summarization failed", e.message)
        val cause = assertIs<HandRunException>(e.cause)
        assertEquals("output_budget_exhausted", cause.type)
        assertEquals(
            6,
            outcome.hand.requests.size,
            "rewrite -> exhausted -> compact(+extract) -> exhausted -> compact (fails)"
        )
        assertEquals(0, outcome.store.storeCount, "a failed run must never store")
    }

    @Test
    fun `pre-round compaction extracts SSTM from the dropped messages`() {
        val sstm = InMemorySstmService()
        val model = catalogModel("bifrost/cerebras/gemma-4-31b", compactionKeepRounds = 3)
        val hand = FakeHand(
            runScript = { request ->
                when {
                    request.systemPrompt?.startsWith("You're summarizing") == true ->
                        textRunFlow("compacted summary")

                    request.systemPrompt?.startsWith("You're extracting") == true ->
                        textRunFlow("likes coffee")

                    request.systemPrompt?.startsWith("You're merging") == true ->
                        mergeRunFlow(sstm, "likes coffee")

                    else -> stopEvents()
                }
            },
        )
        val handService = testHandService(hand)
        val compactionService = ChatCompactionService(
            model = model,
            hand = handService,
            maxRetries = 0,
            streamIdleTimeoutMs = 300_000,
        )
        val rewriteService = QueryRewriteService(
            model = model,
            hand = handService,
            maxRetries = 0,
            streamIdleTimeoutMs = 300_000,
        )
        val callback = RecordingCallback()
        val store = InMemoryChatStore(crowdedSeed())
        val error = runBlocking {
            runCatching {
                PersistChatService(
                    chatStore = store,
                    sstmService = sstm,
                    eltmService = FakeEltmService(),
                    queryRewriteService = rewriteService,
                    hand = handService,
                    compactionService = compactionService,
                    sstmExtractionService = SstmExtractionService(
                        extractModel = model,
                        hand = handService,
                        sstmService = sstm,
                        maxRetries = 0,
                        streamIdleTimeoutMs = 300_000,
                        // the purge must never fire in these tests: the SSTM
                        // stays far under an effectively unbounded capacity
                        eltmWriterService = EltmWriterService(
                            writerModel = model,
                            hand = handService,
                            eltmService = FakeEltmService(),
                            maxWriterRounds = 150,
                            maxRetries = 0,
                            streamIdleTimeoutMs = 300_000,
                        ),
                        sstmCapacity = 1_000_000,
                        purgeBatchSize = 10,
                    ),
                    rewriteRounds = 5,
                    relatedEntitiesLimit = 5,
                    relatedNotesLimit = 5,
                    maxRounds = 64,
                    maxRetries = 0,
                    streamIdleTimeoutMs = 300_000,
                ).runChat(
                    chatId = "chat-1",
                    model = model,
                    userParts = listOf(ChatMessagePart.Text("hello")),
                    systemPrompt = systemPrompt,
                    toolProvider = EmptyToolProvider,
                    callback = callback,
                )
            }.exceptionOrNull()
        }
        assertNull(error)

        // request order: compactor, extractor, merge, rewrite, chat round
        assertEquals(5, hand.requests.size, "compactor + extractor + merge + rewrite + chat round")

        // the raw dropped messages (not the summary) fed the extraction:
        // the extractor's run starts with the dropped complete turn,
        // followed by the extraction instruction. The dropped user message
        // carries its send-time <meta> anchor (anchors-only: a one-shot
        // must not get a full context injection), so the extractor resolves
        // relative dates per message instead of against extraction time.
        val contextInjection = ContextInjection()
        val extractorMessages = hand.requests[1].messages
        assertEquals(
            listOf(ChatMessageRole.User, ChatMessageRole.Assistant, ChatMessageRole.User),
            extractorMessages.map { it.role },
        )
        assertTrue(contextInjection.hasMetaPart(extractorMessages[0]))
        assertTrue(
            (extractorMessages[0].parts[1] as ChatMessagePart.Text).text.startsWith("topic 1")
        )
        assertTrue(
            (extractorMessages[1].parts.single() as ChatMessagePart.Text).text.startsWith("answer 1")
        )
        assertFalse(
            extractorMessages.any { message ->
                message.parts.firstOrNull() is ChatMessagePart.Text &&
                        contextInjection.isInjection(message.parts.first() as ChatMessagePart.Text)
            },
            "the extractor must not receive a full context injection",
        )

        // the merge agent's add_memory call hit the SSTM
        assertEquals(listOf("likes coffee"), sstm.created)

        // the run completed and stored the compacted history
        val stored = assertNotNull(store.stored)
        assertTrue((stored[0].parts.single() as ChatMessagePart.Text).text.startsWith("CONTEXT COMPACTION: "))
    }

    @Test
    fun `a full-body reactive compaction re-appends the injection with the user input`() {
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
        // input (the rewrite's one-shot run precedes the first attempt)
        assertEquals(
            5,
            outcome.hand.requests.size,
            "rewrite -> exhausted run -> compactor -> extractor -> fresh run"
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
        assertTrue(
            injectionOf(retried).contains("<sstm-updated>true</sstm-updated>"),
            "the re-appended injection must carry the fresh flag",
        )
        // the ELTM version read fresh at the re-injection: a fresh chat's
        // stored version is "" vs the fake's "0", so the re-appended
        // injection must flag eltm-updated too
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
        // compacts and extracts through the SAME shared service instances
        // (ChatCompactionService / SstmExtractionService / PersistChatService):
        // this pins the statelessness claim of the shared services under
        // concurrent calls. Distinct chat content makes any cross-talk
        // visible in the stores.
        val sstm = ConcurrentSstmService()
        val hand = FakeHand(
            runScript = { request ->
                when {
                    request.systemPrompt?.startsWith("You're summarizing") == true ->
                        textRunFlow("compacted summary")

                    request.systemPrompt?.startsWith("You're extracting") == true ->
                        textRunFlow("likes coffee")

                    request.systemPrompt?.startsWith("You're merging") == true ->
                        mergeRunFlow(sstm, "likes coffee")

                    else -> stopEvents()
                }
            },
        )
        val model = catalogModel("bifrost/cerebras/gemma-4-31b", compactionKeepRounds = 3)
        val handService = testHandService(hand)
        val compactionService = ChatCompactionService(
            model = model,
            hand = handService,
            maxRetries = 0,
            streamIdleTimeoutMs = 300_000,
        )
        val extractionService = SstmExtractionService(
            extractModel = model,
            hand = handService,
            sstmService = sstm,
            maxRetries = 0,
            streamIdleTimeoutMs = 300_000,
            // the purge must never fire in these tests: the SSTM stays far
            // under an effectively unbounded capacity
            eltmWriterService = EltmWriterService(
                writerModel = model,
                hand = handService,
                eltmService = FakeEltmService(),
                maxWriterRounds = 150,
                maxRetries = 0,
                streamIdleTimeoutMs = 300_000,
            ),
            sstmCapacity = 1_000_000,
            purgeBatchSize = 10,
        )
        val chatStore = ConcurrentChatStore()
        val persistService = PersistChatService(
            chatStore = chatStore,
            sstmService = sstm,
            eltmService = FakeEltmService(),
            queryRewriteService = QueryRewriteService(
                model = model,
                hand = handService,
                maxRetries = 0,
                streamIdleTimeoutMs = 300_000,
            ),
            hand = handService,
            compactionService = compactionService,
            sstmExtractionService = extractionService,
            rewriteRounds = 5,
            relatedEntitiesLimit = 5,
            relatedNotesLimit = 5,
            maxRounds = 64,
            maxRetries = 0,
            streamIdleTimeoutMs = 300_000,
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
                            systemPrompt = systemPrompt,
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

        // both merges applied their fact to the shared SSTM; per chat the
        // shared hand served compactor + extractor + merge + chat round
        assertEquals(listOf("likes coffee", "likes coffee"), sstm.created, "both merges applied")
        assertEquals(
            10,
            hand.requests.size,
            "2 chats x (compact + extract + merge + rewrite + chat round)",
        )
    }
}

private class TurnOutcome(
    val error: Throwable?,
    val store: InMemoryChatStore,
    val callback: RecordingCallback,
    val hand: FakeHand,
)

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
    var storedSstmVersion: String? = null
        private set
    var storedEltmVersion: String? = null
        private set

    override suspend fun load(chatId: String): ChatEntry? = stored?.let {
        ChatEntry(
            ChatInfo(chatId, DEFAULT_CHAT_TITLE),
            ChatContent(it, storedSstmVersion ?: "", storedEltmVersion ?: "")
        )
    }

    override suspend fun store(chatId: String, chat: ChatContent) {
        storeCount++
        stored = chat.messages
        storedSstmVersion = chat.sstmVersion
        storedEltmVersion = chat.eltmVersion
    }

    // the chat-row CRUD methods are not part of this fake's contract (the
    // persist loop tests only exercise load/store)
    override suspend fun listChats(): List<ChatInfo> =
        error("not exercised by the persist loop tests")

    override suspend fun newChat(): ChatInfo = error("not exercised by the persist loop tests")
    override suspend fun rename(chatId: String, title: String): ChatInfo =
        error("not exercised by the persist loop tests")

    override suspend fun delete(chatId: String): Boolean =
        error("not exercised by the persist loop tests")
}

/**
 * A fake [SstmService] whose version is settable, so tests can verify the
 * loop persists the version it saw and flips the `sstm-updated` flag when
 * the version changes between runs. Supports writes so the extraction merge
 * tests can record what the merge agent created.
 */
private class InMemorySstmService(
    private val memories: List<ShortTermMemory> = emptyList(),
    var version: String = "test-version",
) : SstmService {
    val created = mutableListOf<String>()

    override suspend fun listMemories(): MemoriesWithVersion =
        MemoriesWithVersion(memories, version)

    override suspend fun createMemory(content: String): ShortTermMemory {
        created += content
        version = "version-${created.size}"
        return ShortTermMemory(created.size.toLong(), Instant.EPOCH, content)
    }

    override suspend fun updateMemory(id: Long, content: String): ShortTermMemory? =
        memories.firstOrNull { it.id == id }?.copy(content = content)

    override suspend fun deleteMemory(id: Long): Boolean =
        memories.any { it.id == id }
}

/**
 * A thread-safe in-memory [ChatStore] keyed by chat id: the shared-service
 * concurrency test runs several chats through one service (and one store)
 * at the same time.
 */
private class ConcurrentChatStore : ChatStore {
    private val chats = ConcurrentHashMap<String, ChatContent>()

    fun seed(chatId: String, chat: List<ChatMessage>, sstmVersion: String = "", eltmVersion: String = "") {
        chats[chatId] = ChatContent(chat, sstmVersion, eltmVersion)
    }

    override suspend fun load(chatId: String): ChatEntry? = chats[chatId]?.let {
        ChatEntry(
            ChatInfo(chatId, DEFAULT_CHAT_TITLE),
            ChatContent(it.messages, it.sstmVersion, it.eltmVersion)
        )
    }

    override suspend fun store(chatId: String, chat: ChatContent) {
        chats[chatId] = chat
    }

    // the chat-row CRUD methods are not part of this fake's contract (the
    // persist loop concurrency test only exercises load/store)
    override suspend fun listChats(): List<ChatInfo> =
        error("not exercised by the persist loop tests")

    override suspend fun newChat(): ChatInfo = error("not exercised by the persist loop tests")
    override suspend fun rename(chatId: String, title: String): ChatInfo =
        error("not exercised by the persist loop tests")

    override suspend fun delete(chatId: String): Boolean =
        error("not exercised by the persist loop tests")

    fun stored(chatId: String): List<ChatMessage>? = chats[chatId]?.messages
}

/**
 * A thread-safe [SstmService] fake whose version bumps on every write: the
 * shared-service concurrency test runs two extraction merges against one
 * instance at the same time.
 */
private class ConcurrentSstmService : SstmService {
    private val lock = Any()
    private val memories = mutableListOf<ShortTermMemory>()
    private var version = "v0"
    val created = mutableListOf<String>()

    override suspend fun listMemories(): MemoriesWithVersion = synchronized(lock) {
        MemoriesWithVersion(memories.toList(), version)
    }

    override suspend fun createMemory(content: String): ShortTermMemory = synchronized(lock) {
        created += content
        val memory = ShortTermMemory(memories.size.toLong(), Instant.EPOCH, content)
        memories += memory
        version = "v${memories.size}"
        memory
    }

    override suspend fun updateMemory(id: Long, content: String): ShortTermMemory? = null

    override suspend fun deleteMemory(id: Long): Boolean = false
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
