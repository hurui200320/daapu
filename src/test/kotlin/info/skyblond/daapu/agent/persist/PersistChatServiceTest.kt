package info.skyblond.daapu.agent.persist

import info.skyblond.daapu.agent.ModelCatalog
import info.skyblond.daapu.agent.chat.*
import info.skyblond.daapu.agent.model.LLM
import info.skyblond.daapu.agent.model.ModelCapabilityException
import info.skyblond.daapu.agent.model.ModelProvider
import info.skyblond.daapu.agent.oneshot.compaction.ChatCompactionService
import info.skyblond.daapu.agent.oneshot.sstm.MergeMemoryToolProvider
import info.skyblond.daapu.agent.oneshot.sstm.SstmExtractionService
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
import info.skyblond.daapu.memory.sstm.MemoriesWithVersion
import info.skyblond.daapu.memory.sstm.ShortTermMemory
import info.skyblond.daapu.memory.sstm.SstmService
import info.skyblond.daapu.testutil.testHandService
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant
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
 * request, and the reactive compaction path (hand `context_exhausted` →
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
            mapOf("bifrost" to ModelProvider("bifrost", "http://127.0.0.1:9/v1", "test-key"))
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
     * dispatches on the out-of-band system prompt, standing in for the three
     * one-shot roles (compactor / extractor / merger) plus the chat loop.
     */
    private fun run(
        modelId: String = "bifrost/cerebras/gemma-4-31b",
        userParts: List<ChatMessagePart> = listOf(ChatMessagePart.Text("hello")),
        store: InMemoryChatStore = InMemoryChatStore(),
        sstmService: SstmService = InMemorySstmService(),
        toolProvider: ToolProvider = EmptyToolProvider,
        sstmExtractionService: SstmExtractionService? = null,
        compactionKeepRounds: Int = 3,
        chatScript: suspend (HandRunRequest) -> List<HandEvent> = { stopEvents() },
        compactionScript: suspend (HandRunRequest) -> List<HandEvent> =
            { textRunFlow("compacted summary") },
        extractionScript: suspend (HandRunRequest) -> List<HandEvent> =
            { textRunFlow("Nothing worth remember.") },
        mergeScript: (suspend (HandRunRequest) -> List<HandEvent>)? = null,
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
        val callback = RecordingCallback()
        val error = runBlocking {
            runCatching {
                PersistChatService(
                    chatStore = store,
                    sstmService = sstmService,
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
                        ),
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

        // the hand got one run request carrying the injection and the
        // system prompt as a separate field (never inside the messages)
        val request = outcome.hand.requests.single()
        // the runId is internal to the run/callback plumbing: generated by
        // HandService, never supplied by the chat loop
        assertFalse(request.runId.isNullOrBlank(), "HandService must generate a runId")
        assertEquals(
            "http://127.0.0.1:9/api/hand/tool",
            request.toolCallbackUrl,
            "the callback URL is attached even without tools (the hand only POSTs it on a tool call)",
        )
        assertTrue(request.tools.isNullOrEmpty(), "no tools advertised with the empty registry")
        assertEquals(systemPrompt, request.systemPrompt, "the system prompt travels out of band")
        assertTrue(injectionOf(request).contains("<sstm-updated>true</sstm-updated>"))

        // the model spec carries the reasoning effort from the model's
        // Reasoning capability (not a per-request field)
        assertTrue(request.model.reasoning, "the catalog model supports reasoning")
        assertEquals("high", request.model.reasoningEffort)

        // history stored: user (injection stripped), assistant
        val stored = assertNotNull(outcome.store.stored, "history must be stored on success")
        assertEquals(
            listOf(ChatMessageRole.User, ChatMessageRole.Assistant),
            stored.map { it.role },
        )
        assertEquals(listOf(ChatMessagePart.Text("hello")), stored[0].parts)
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
        val seed = listOf(ChatMessage(ChatMessageRole.User, listOf(ChatMessagePart.Text("old"))))
        val store = InMemoryChatStore(seed)
        val outcome = run(
            store = store,
            chatScript = { listOf(HandEvent.RunError("upstream", "500: boom")) },
        )

        val e = assertIs<HandRunException>(outcome.error)
        assertEquals("upstream", e.type)
        assertEquals(1, outcome.hand.requests.size, "a terminal error must not retry")
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
    fun `tool call round executes through the MCP provider with paired history and advertised specs`() {
        // end-to-end through the neutral tool seam: the loop advertises the
        // MCP server's tools (namespaced) in the hand request, and the
        // hand-side execution (simulated by calling the provider) stores the
        // result as a tool message whose id pairs with the call
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
                    // POST (the HTTP contract is pinned by HandCallbackTest)
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

            // the request advertised the namespaced tool with its schema
            val advertised = outcome.hand.requests.last().tools?.single()
            assertEquals("calc__add", advertised?.name)
            assertTrue(advertised!!.schema.isNotEmpty(), "the tool schema must be advertised")
            assertEquals(
                30L,
                advertised.timeoutSeconds,
                "the server's execution budget must be advertised"
            )
            assertEquals(
                "http://127.0.0.1:9/api/hand/tool",
                outcome.hand.requests.last().toolCallbackUrl,
                "the callback URL is sent when tools are advertised",
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
    fun `existing history is loaded and extended`() {
        val seed = listOf(
            ChatMessage(ChatMessageRole.User, listOf(ChatMessagePart.Text("earlier"))),
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
        assertEquals(listOf(ChatMessagePart.Text("earlier reply")), stored[1].parts)
        assertEquals(listOf(ChatMessagePart.Text("hello")), stored[2].parts)
        assertEquals(listOf(ChatMessagePart.Text("ok")), stored[3].parts)
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
        ChatMessage(ChatMessageRole.User, listOf(ChatMessagePart.Text(turnText(userPrefix, 1)))),
        answer(turnText(answerPrefix, 1)),
        ChatMessage(ChatMessageRole.User, listOf(ChatMessagePart.Text(turnText(userPrefix, 2)))),
        answer(turnText(answerPrefix, 2)),
        ChatMessage(ChatMessageRole.User, listOf(ChatMessagePart.Text(turnText(userPrefix, 3)))),
        answer(turnText(answerPrefix, 3)),
        ChatMessage(ChatMessageRole.User, listOf(ChatMessagePart.Text(turnText(userPrefix, 4)))),
        answer(turnText(answerPrefix, 4), lastInputTokens),
    )

    /**
     * A scripted merge-run flow: one `add_memory` tool round (executed
     * through the merge provider, standing in for the hand's tool callback)
     * followed by the final confirmation.
     */
    private suspend fun mergeRunFlow(sstm: SstmService, content: String): List<HandEvent> {
        val provider = MergeMemoryToolProvider(sstm)
        val round = assistantMessage(
            parts = listOf(
                ChatMessagePart.ToolCall(
                    id = "call_merge",
                    tool = "add_memory",
                    args = buildJsonObject { put("content", content) },
                )
            ),
            finishReason = "tool_calls",
        )
        return listOf(HandEvent.AssistantMessage(round)) +
                toolRoundEvents(round, provider) +
                listOf(
                    HandEvent.AssistantMessage(assistantMessage("done")),
                    HandEvent.Done("stop"),
                )
    }

    @Test
    fun `pre-round compaction fires when the estimated prompt exceeds the trigger`() {
        val outcome = run(
            store = InMemoryChatStore(crowdedSeed()),
        )
        assertNull(outcome.error)

        // the compactor ran (its one-shot /v1/run call) before the hand run,
        // followed by the sentinel extraction (a no-op)
        assertEquals(3, outcome.hand.requests.size, "compactor run + extractor run + hand run")
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
        val summaryText = (sent[0].parts.single() as ChatMessagePart.Text).text
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

        // reactive compaction happened once, mid-run: the first hand run
        // reported exhaustion, the compactor's one-shot run happened (plus
        // the sentinel extraction), and a fresh hand run received the
        // compacted history
        assertEquals(
            4,
            outcome.hand.requests.size,
            "exhausted run -> compactor -> extractor -> fresh run"
        )
        assertTrue(outcome.hand.requests[1].systemPrompt!!.startsWith("You're summarizing"))
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
            5,
            outcome.hand.requests.size,
            "exhausted -> compact(+extract) -> exhausted -> compact (fails)"
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
        val callback = RecordingCallback()
        val store = InMemoryChatStore(crowdedSeed())
        val error = runBlocking {
            runCatching {
                PersistChatService(
                    chatStore = store,
                    sstmService = sstm,
                    hand = handService,
                    compactionService = compactionService,
                    sstmExtractionService = SstmExtractionService(
                        extractModel = model,
                        hand = handService,
                        sstmService = sstm,
                        maxRetries = 0,
                        streamIdleTimeoutMs = 300_000,
                    ),
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

        // request order: compactor, extractor, merge, chat round
        assertEquals(4, hand.requests.size, "compactor + extractor + merge + chat round")

        // the raw dropped messages (not the summary) fed the extraction:
        // the extractor's run starts with the dropped complete turn,
        // followed by the extraction instruction
        val extractorMessages = hand.requests[1].messages
        assertEquals(
            listOf(ChatMessageRole.User, ChatMessageRole.Assistant, ChatMessageRole.User),
            extractorMessages.map { it.role },
        )
        assertTrue(
            (extractorMessages[0].parts.single() as ChatMessagePart.Text).text.startsWith("topic 1")
        )
        assertTrue(
            (extractorMessages[1].parts.single() as ChatMessagePart.Text).text.startsWith("answer 1")
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
        // input
        assertEquals(
            4,
            outcome.hand.requests.size,
            "exhausted run -> compactor -> extractor -> fresh run"
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
        )
        val chatStore = ConcurrentChatStore()
        val persistService = PersistChatService(
            chatStore = chatStore,
            sstmService = sstm,
            hand = handService,
            compactionService = compactionService,
            sstmExtractionService = extractionService,
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
            8,
            hand.requests.size,
            "2 chats x (compact + extract + merge + chat round)",
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

    override suspend fun load(chatId: String): ChatEntry? = stored?.let {
        ChatEntry(
            ChatInfo(chatId, DEFAULT_CHAT_TITLE),
            ChatContent(it, storedSstmVersion ?: "")
        )
    }

    override suspend fun store(chatId: String, chat: ChatContent) {
        storeCount++
        stored = chat.messages
        storedSstmVersion = chat.sstmVersion
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

    fun seed(chatId: String, chat: List<ChatMessage>, sstmVersion: String = "") {
        chats[chatId] = ChatContent(chat, sstmVersion)
    }

    override suspend fun load(chatId: String): ChatEntry? = chats[chatId]?.let {
        ChatEntry(
            ChatInfo(chatId, DEFAULT_CHAT_TITLE),
            ChatContent(it.messages, it.sstmVersion)
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
