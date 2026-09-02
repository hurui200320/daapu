package info.skyblond.daapu.server

import info.skyblond.daapu.agent.chat.AttachmentContent
import info.skyblond.daapu.agent.chat.AttachmentKind
import info.skyblond.daapu.agent.chat.ChatMessage
import info.skyblond.daapu.agent.chat.ChatMessagePart
import info.skyblond.daapu.agent.chat.ChatMessageRole
import info.skyblond.daapu.agent.chat.ChatRunSetup
import info.skyblond.daapu.agent.chat.PostgresChatStore
import info.skyblond.daapu.agent.chat.ChatService
import info.skyblond.daapu.agent.chat.ChatValidationException
import info.skyblond.daapu.agent.pipeline.investigate.InvestigatorService
import info.skyblond.daapu.agent.persona.DEFAULT_PERSONA_ID
import info.skyblond.daapu.agent.persona.DEFAULT_PERSONA_SYSTEM_PROMPT
import info.skyblond.daapu.agent.persona.Persona
import info.skyblond.daapu.agent.persist.StreamingExecutionCallback
import info.skyblond.daapu.agent.tool.ToolCallRequest
import info.skyblond.daapu.config.AgentConfig
import info.skyblond.daapu.config.EltmConfig
import info.skyblond.daapu.config.InvestigatorConfig
import info.skyblond.daapu.config.McpServerConfig
import info.skyblond.daapu.config.McpTransportType
import info.skyblond.daapu.config.MemoryConfig
import info.skyblond.daapu.config.TitleConfig
import info.skyblond.daapu.config.testAppConfig
import info.skyblond.daapu.config.testLlmEntries
import info.skyblond.daapu.hand.FakeHand
import info.skyblond.daapu.hand.assistantMessage
import info.skyblond.daapu.hand.textRunFlow
import info.skyblond.daapu.mcp.McpToolProvider
import info.skyblond.daapu.mcp.MockMcpServer
import info.skyblond.daapu.mcp.MockTool
import info.skyblond.daapu.mcp.MockToolReply
import info.skyblond.daapu.testutil.DbTestBase
import info.skyblond.daapu.testutil.TestDb
import info.skyblond.daapu.testutil.assertFailsFast
import info.skyblond.daapu.testutil.chatService
import info.skyblond.daapu.testutil.testKoinApp
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.*

/**
 * Pins the request → neutral parts mapping done by [ChatService.prepareRun].
 */
class ChatServiceTest : DbTestBase() {

    private val service = chatService(testAppConfig())

    /**
     * The unpacked `POST /api/chats/{id}/messages` body: mirrors
     * [SendMessageRequest] the way [ChatService.prepareRun] consumes it.
     */
    private data class MsgRequest(
        val text: String?,
        val imageDataUrls: List<String>,
        val model: String?,
        val personaId: Long?,
    )

    private fun request(
        text: String? = null,
        images: List<String> = emptyList(),
        model: String? = "bifrost/cerebras/gpt-oss-120b",
        personaId: Long? = DEFAULT_PERSONA_ID,
    ) = MsgRequest(
        text = text,
        imageDataUrls = images,
        model = model,
        personaId = personaId,
    )

    /** The suspend validation call, runBlocking so the sync tests stay sync. */
    private fun prepare(chatId: String, request: MsgRequest): ChatRunSetup =
        runBlocking {
            service.prepareRun(
                chatId, request.text, request.imageDataUrls, request.model, request.personaId,
            )
        }

    @Test
    fun `text only maps to a text part`() {
        val setup = prepare("chat-1", request(text = "hello"))
        assertEquals(listOf(ChatMessagePart.Text("hello")), setup.parts)
        assertEquals("bifrost/cerebras/gpt-oss-120b", setup.model.id)
    }

    @Test
    fun `text and image map to text plus attachment`() {
        val dataUrl = "data:image/png;base64,AAAA"
        val setup = prepare("chat-1", request(text = "look", images = listOf(dataUrl)))
        assertEquals(2, setup.parts.size)
        val attachment = assertIs<ChatMessagePart.Attachment>(setup.parts[1])
        assertEquals(AttachmentKind.Image, attachment.kind)
        assertEquals("image/png", attachment.mimeType)
        assertEquals(AttachmentContent.Base64("AAAA"), attachment.content)
    }

    @Test
    fun `image only is allowed`() {
        // an image-only message is valid; the turn loop's capability check
        // (not the API) decides whether the model can handle it
        val setup =
            prepare("chat-1", request(images = listOf("data:image/jpeg;base64,BBBB")))
        assertEquals(1, setup.parts.size)
        assertIs<ChatMessagePart.Attachment>(setup.parts[0])
    }

    @Test
    fun `image with a text-only model is NOT rejected at the API`() {
        // deliberate: capability enforcement lives in the turn loop's pre-send
        // step, so history-sourced images are covered too. Pinned here so the
        // API layer doesn't grow a partial validation that misses history.
        val setup = prepare(
            "chat-1",
            request(
                images = listOf("data:image/png;base64,AAAA"),
                model = "bifrost/cerebras/gpt-oss-120b"
            ),
        )
        assertEquals("bifrost/cerebras/gpt-oss-120b", setup.model.id)
        assertEquals(1, setup.parts.size)
    }

    @Test
    fun `blank message is rejected`() {
        assertFailsWith<ChatValidationException> { prepare("chat-1", request(text = "   ")) }
        assertFailsWith<ChatValidationException> { prepare("chat-1", request(text = "")) }
        assertFailsWith<ChatValidationException> { prepare("chat-1", request()) }
    }

    @Test
    fun `missing model is rejected`() {
        // the server has no default model; the web UI always sends one
        assertFailsWith<ChatValidationException> {
            prepare("chat-1", request(text = "hi", model = null, personaId = null))
        }
        assertFailsWith<ChatValidationException> {
            prepare("chat-1", request(text = "hi", model = "  ", personaId = null))
        }
    }

    @Test
    fun `missing persona is rejected`() {
        // the server has no default persona for the REQUEST either: the
        // frontend always sends the persona id along with the model (the
        // chat's persona_id column is only a record, never the run's source)
        assertFailsWith<ChatValidationException> {
            prepare("chat-1", request(text = "hi", personaId = null))
        }
        // the persona check fires regardless of how the message is formed
        val e = assertFailsWith<ChatValidationException> {
            prepare(
                "chat-1",
                request(images = listOf("data:image/png;base64,AAAA"), personaId = null),
            )
        }
        assertEquals("persona is required", e.message)
    }

    @Test
    fun `unknown persona is rejected`() {
        // fail fast like the unknown model: a stale id is a client error,
        // not a silent fallback to the default persona
        val e = assertFailsWith<ChatValidationException> {
            prepare("chat-1", request(text = "hi", personaId = 999L))
        }
        assertTrue(e.message!!.contains("Unknown persona"))
    }

    @Test
    fun `the default persona resolves from code`() {
        // the default persona lives ONLY in code (never a personas row); the
        // request carries its reserved id
        val setup = prepare("chat-1", request(text = "hi"))
        assertEquals(DEFAULT_PERSONA_ID, setup.persona.id)
        assertEquals(
            DEFAULT_PERSONA_SYSTEM_PROMPT,
            setup.persona.systemPrompt,
            "the default persona text is the code constant",
        )
        assertTrue(setup.persona.allowedNamespaces.isEmpty(), "empty whitelist = all namespaces")
    }

    @Test
    fun `a stored persona resolves from the store`() = runBlocking {
        val writer = TestDb.seedPersonaRow("Writer", "You are a writer.", listOf("gsg"))
        val service = chatService(testAppConfig())
        val req = request(text = "hi", personaId = writer.id)
        val setup = service.prepareRun("chat-1", req.text, req.imageDataUrls, req.model, req.personaId)
        assertEquals(writer.id, setup.persona.id)
        assertEquals("You are a writer.", setup.persona.systemPrompt)
    }

    @Test
    fun `a chat run renders the persona prompt and stamps the chat record`() = runBlocking {
        val store = PostgresChatStore()
        TestDb.seedChatRow("chat-1", messages = listOf(user("u1"), assistantMessage("a1")))
        val writer = TestDb.seedPersonaRow("Writer", "You are a writer.", listOf("gsg"))
        val hand = FakeHand(
            runScript = { request ->
                when {
                    // the query-rewrite one-shot runs before the chat round
                    request.systemPrompt?.startsWith("You're rewriting") == true ->
                        textRunFlow("rewritten query")

                    else -> textRunFlow("written")
                }
            },
        )
        val service = chatService(
            testAppConfig(),
            hand = hand,
            chatStore = store,
        )
        val req = request(text = "write something", personaId = writer.id)
        val setup = service.prepareRun("chat-1", req.text, req.imageDataUrls, req.model, req.personaId)
        service.runChat(setup, NoopStreamingCallback)
        val chatRequest = hand.requests.last()
        assertTrue(
            chatRequest.systemPrompt!!.startsWith("You are a writer."),
            "the system prompt is the persona text plus the gsg introduction",
        )
        assertTrue(
            chatRequest.systemPrompt.contains("# Harness"),
            "the gsg harness introduction is appended to the persona text",
        )
        // the persona's whitelist serves `gsg`, so the full introduction
        // (incl. the gsg__investigate documentation) is rendered
        assertTrue(chatRequest.systemPrompt.contains("gsg__investigate"))
        // the successful run stamps the chat's persona record
        assertEquals(writer.id, store.load("chat-1")?.info?.personaId)
    }

    @Test
    fun `a persona whitelist restricts the tools and gates the introduction`() = runBlocking {
        // the whitelist filters the loop's TOOL set; a persona WITHOUT the
        // `gsg` namespace also gets the reduced introduction — no
        // gsg__investigate documentation, only the time basics
        val server = MockMcpServer(listOf(addTool()))
        val mcp = McpToolProvider(
            mapOf(
                "calc" to McpServerConfig(
                    type = McpTransportType.Http,
                    url = server.baseUrl,
                    toolExecutionTimeoutSeconds = 30,
                )
            )
        )
        val store = PostgresChatStore()
        TestDb.seedChatRow("chat-1", messages = listOf(user("u1"), assistantMessage("a1")))
        val plain = TestDb.seedPersonaRow("Plain", "You are a plain assistant.", listOf("calc"))
        val hand = FakeHand(
            runScript = { request ->
                when {
                    request.systemPrompt?.startsWith("You're rewriting") == true ->
                        textRunFlow("rewritten query")

                    else -> textRunFlow("plain")
                }
            },
        )
        val koinApp = testKoinApp(
            testAppConfig(),
            hand = hand,
            chatStore = store,
            mcpToolProvider = mcp,
        )
        val service = koinApp.koin.get<ChatService>()
        try {
            val req = request(text = "hi", personaId = plain.id)
            val setup = service.prepareRun("chat-1", req.text, req.imageDataUrls, req.model, req.personaId)
            service.runChat(setup, NoopStreamingCallback)
            val prompt = hand.requests.last().systemPrompt!!
            assertTrue(prompt.startsWith("You are a plain assistant."))
            assertTrue(
                prompt.contains("# Context"),
                "the reduced introduction is appended for a persona without gsg access",
            )
            assertFalse(
                prompt.contains("gsg__investigate"),
                "the gsg documentation is gated on the persona's whitelist",
            )
            assertFalse(prompt.contains("# Harness"))
            assertFalse(
                prompt.contains("# Policy"),
                "the policy is part of the DEFAULT persona's text only",
            )
            assertEquals(plain.id, store.load("chat-1")?.info?.personaId)
        } finally {
            koinApp.close()
            server.close()
        }
    }

    @Test
    fun `a stale persona whitelist fails prepareRun with a clear error`() = runBlocking {
        // seeded directly (the service would reject the unknown namespace at
        // save time): an MCP server dropped from config after the persona was
        // saved leaves a whitelist entry the loop's set does not serve — the
        // WhitelistedToolProvider construction invariant fails the REQUEST in
        // prepareRun (before the lock, before any stream) with a clear error
        // instead of silently dropping the namespace
        val store = PostgresChatStore()
        TestDb.seedChatRow("chat-1", messages = listOf(user("u1"), assistantMessage("a1")))
        val stale = TestDb.seedPersonaRow("Stale", "You are stale.", listOf("gsg", "web"))
        val hand = FakeHand(runScript = { error("the request must fail before the hand is called") })
        val service = chatService(
            testAppConfig(),
            hand = hand,
            chatStore = store,
        )
        val e = assertFailsWith<ChatValidationException> {
            runBlocking {
                val req = request(text = "hi", personaId = stale.id)
                service.prepareRun("chat-1", req.text, req.imageDataUrls, req.model, req.personaId)
            }
        }
        assertTrue(
            e.message!!.contains("not served by the delegate"),
            "the error names the unserved namespace: ${e.message}",
        )
    }

    @Test
    fun `malformed data url is rejected`() {
        listOf(
            "http://example.com/image.png",              // not a data URL
            "data:text/plain;base64,AAAA",               // not an image
            "data:image/png;base64,",                    // no payload
            "data:image/png,AAAA",                       // not base64
            "not even a url",
        ).forEach { url ->
            val e = assertFailsWith<ChatValidationException>("url: $url") {
                prepare("chat-1", request(images = listOf(url)))
            }
            assertNotNull(e.message)
        }
    }

    @Test
    fun `invalid base64 payload is rejected`() {
        // decodes to garbage, not valid base64
        val e = assertFailsWith<ChatValidationException> {
            prepare(
                "chat-1",
                request(images = listOf("data:image/png;base64,@@@not-base64@@@"))
            )
        }
        assertNotNull(e.message)
    }

    @Test
    fun `base64 with line breaks is accepted`() {
        // data URLs produced by FileReader are single-line, but folded base64
        // (whitespace-separated) is legal; whitespace must be stripped
        val setup =
            prepare("chat-1", request(images = listOf("data:image/png;base64,AAA\nA")))
        val attachment = assertIs<ChatMessagePart.Attachment>(setup.parts[0])
        assertEquals(AttachmentContent.Base64("AAAA"), attachment.content)
    }

    @Test
    fun `unknown model is rejected`() {
        val e = assertFailsWith<ChatValidationException> {
            prepare("chat-1", request(text = "hi", model = "no/such-model"))
        }
        assertNotNull(e.message)
    }

    @Test
    fun `known models are accepted`() {
        listOf(
            "bifrost/cerebras/gpt-oss-120b",
            "bifrost/cerebras/gemma-4-31b",
            "bifrost/novita/google/gemma-4-31b-it"
        )
            .forEach { id ->
                val setup = prepare("chat-1", request(text = "hi", model = id))
                assertEquals(id, setup.model.id)
            }
    }

    @Test
    fun `a catalog without any llm model fails fast at construction`() {
        // the catalog is built from the config's provider entries: a config
        // with no chat model at all cannot serve any request, so the
        // catalog construction fails fast instead of degrading every run
        val e = assertIs<IllegalArgumentException>(
            assertFailsFast { chatService(testAppConfig().copy(providers = emptyMap())) }
        )
        assertTrue(
            e.message!!.contains("providers.<id>.llm"),
            "the error should name the missing config surface: ${e.message}"
        )
    }

    @Test
    fun `a duplicated model id fails fast at construction`() {
        // model ids are the lookup keys (per kind and across the whole
        // catalog): a duplicate would make findModel ambiguous
        val duplicated = testAppConfig().providers.getValue("bifrost")
            .copy(llm = testLlmEntries().take(1) + testLlmEntries().take(1))
        val e = assertIs<IllegalArgumentException>(
            assertFailsFast {
                chatService(testAppConfig().copy(providers = mapOf("bifrost" to duplicated)))
            }
        )
        assertTrue(
            e.message!!.contains("Duplicate model id"),
            "the error should name the collision: ${e.message}"
        )
    }

    @Test
    fun `an unknown memory model id fails fast at construction`() {
        // the one-shot pipeline models are resolved once at startup: a typo
        // must fail here, not silently skip every compaction/extraction
        val valid = MemoryConfig(
            compactModel = "bifrost/cerebras/gemma-4-31b",
            eltm = EltmConfig(
                extractionModel = "bifrost/cerebras/gemma-4-31b",
                embeddingModel = "bifrost/zenmux sub/google/gemini-embedding-2",
                writerModel = "bifrost/cerebras/gemma-4-31b",
                rewriteModel = "bifrost/cerebras/gemma-4-31b",
                rewriteRounds = 5,
                relatedEntitiesLimit = 5,
                relatedNotesLimit = 5,
                queueWorkers = 1,
                jobTimeoutMinutes = 30,
                retryDelayMinutes = 5,
            ),
        )
        val e = assertIs<IllegalArgumentException>(
            assertFailsFast {
                chatService(testAppConfig().copy(memory = valid.copy(compactModel = "bifrost/nope")))
            }
        )
        assertTrue(
            e.message!!.contains("memory.compactModel"),
            "the error should name the config key: ${e.message}"
        )
        assertIs<IllegalArgumentException>(
            assertFailsFast {
                chatService(
                    testAppConfig().copy(
                        memory = valid.copy(
                            eltm = valid.eltm.copy(extractionModel = "bifrost/nope")
                        )
                    )
                )
            }
        )
    }

    @Test
    fun `an unknown eltm model id fails fast at construction`() {
        // same as the memory pipeline models: the resolved ELTM models
        // (REQUIRED config) are checked once at startup.
        val base = testAppConfig().memory.eltm
        val e = assertIs<IllegalArgumentException>(
            assertFailsFast {
                chatService(
                    testAppConfig().copy(
                        memory = testAppConfig().memory.copy(
                            eltm = base.copy(embeddingModel = "bifrost/nope")
                        )
                    )
                )
            }
        )
        assertTrue(
            e.message!!.contains("memory.eltm.embeddingModel"),
            "the error should name the config key: ${e.message}"
        )
        assertIs<IllegalArgumentException>(
            assertFailsFast {
                chatService(
                    testAppConfig().copy(
                        memory = testAppConfig().memory.copy(eltm = base.copy(writerModel = "bifrost/nope"))
                    )
                )
            }
        )
        assertIs<IllegalArgumentException>(
            assertFailsFast {
                chatService(
                    testAppConfig().copy(
                        memory = testAppConfig().memory.copy(eltm = base.copy(rewriteModel = "bifrost/nope"))
                    )
                )
            }
        )
    }

    @Test
    fun `an unknown investigate model id fails fast at construction`() {
        // the investigate sub-agent's model is REQUIRED config, resolved
        // once at startup like the memory pipeline models: a typo must fail
        // at boot, not mid-run on the first gsg__investigate call. The
        // service is wired into the graph root via the loop's
        // GsgToolProvider, so resolving the root (the same eager `get`
        // startWebServer runs) fires the fail-fast.
        val e = assertIs<IllegalArgumentException>(
            assertFailsFast {
                testKoinApp(
                    testAppConfig().copy(
                        agent = AgentConfig(
                            investigator = InvestigatorConfig(
                                model = "bifrost/nope",
                                allowedNamespaces = listOf("eltm"),
                            )
                        )
                    )
                ).koin.get<InvestigatorService>()
            }
        )
        assertTrue(
            e.message!!.contains("agent.investigator.model"),
            "the error should name the config key: ${e.message}"
        )
    }

    @Test
    fun `an investigator whitelist its own set does not serve fails fast at construction`() {
        // the whitelist restricts the investigator's OWN tool set (the
        // read-only eltm tools plus the MCP servers — a separate combined
        // provider, not the loop's set): a listed namespace the set does
        // not serve is a config typo, so the WhitelistedToolProvider
        // construction must fail at boot, not surface as an error tool
        // result mid-run
        val e = assertIs<IllegalArgumentException>(
            assertFailsFast {
                testKoinApp(
                    testAppConfig().copy(
                        agent = AgentConfig(
                            investigator = InvestigatorConfig(
                                model = "bifrost/cerebras/gemma-4-31b",
                                allowedNamespaces = listOf("eltm", "nope"),
                            )
                        )
                    )
                ).koin.get<InvestigatorService>()
            }
        )
        assertTrue(
            e.message!!.contains("not served by the delegate"),
            "the error should name the unserved namespace: ${e.message}"
        )
    }

    @Test
    fun `an unknown title model id fails fast at construction`() {
        // same as the memory pipeline models: the title generator's model is
        // resolved once at startup, so a typo fails here, not on the button
        val e = assertIs<IllegalArgumentException>(
            assertFailsFast { chatService(testAppConfig().copy(title = TitleConfig(model = "bifrost/nope"))) }
        )
        assertTrue(
            e.message!!.contains("title.model"),
            "the error should name the config key: ${e.message}"
        )
    }

    @Test
    fun `the chat tool set is the gsg investigate tool without MCP servers`() = runBlocking {
        // the loop's combined set: the namespace-less empty MCP provider (the
        // default) is skipped — CombinedToolProvider fails fast on a
        // namespace-less child — so the set is exactly the gsg tool; the
        // granular ELTM read tools are NOT in the loop's set anymore (they
        // live in the investigator's own set)
        val service = chatService(testAppConfig())
        assertEquals(setOf("gsg"), service.chatToolProvider.namespaces())
        assertEquals(
            listOf("gsg__investigate"),
            service.chatToolProvider.specifications().map { it.name },
            "the loop sees exactly the investigate tool"
        )
        // a bare (unprefixed) name is unroutable in a combined set
        val result = service.chatToolProvider.execute(
            ToolCallRequest("c1", "investigate", buildJsonObject { put("query", "ali") }),
        )
        assertTrue(result.isError)
        assertTrue(
            (result.parts.single() as ChatMessagePart.Text).text.contains("not advertised"),
        )
    }

    @Test
    fun `the chat tool set combines MCP tools with the gsg investigate tool`() = runBlocking {
        val server = MockMcpServer(listOf(addTool()))
        val mcp = McpToolProvider(
            mapOf(
                "calc" to McpServerConfig(
                    type = McpTransportType.Http,
                    url = server.baseUrl,
                    toolExecutionTimeoutSeconds = 30,
                )
            )
        )
        val koinApp = testKoinApp(testAppConfig(), mcpToolProvider = mcp)
        val service = koinApp.koin.get<ChatService>()
        try {
            assertEquals(setOf("calc", "gsg"), service.chatToolProvider.namespaces())
            val names = service.chatToolProvider.specifications().map { it.name }
            assertEquals("calc__add", names.first())
            assertEquals("gsg__investigate", names.last())
            // the MCP namespace routes to the server, not the gsg provider
            val add = service.chatToolProvider.execute(
                ToolCallRequest("c1", "calc__add", buildJsonObject { put("a", 1); put("b", 2) }),
            )
            assertFalse(add.isError)
            assertEquals("add", server.toolCalls.single().first)
        } finally {
            // closing the container fires the onClose callbacks (the
            // CombinedToolProvider closes the MCP client it cached)
            koinApp.close()
            server.close()
        }
    }

    @Test
    fun `the gsg namespace is not whitelistable for the investigator`() {
        // the investigator's tool set is its OWN combined provider (MCP +
        // read-only eltm), so `gsg` is not servable — a whitelist entry for
        // it fails fast at boot, ruling out sub-agent recursion via the
        // construction-time invariant
        val e = assertIs<IllegalArgumentException>(
            assertFailsFast {
                testKoinApp(
                    testAppConfig().copy(
                        agent = AgentConfig(
                            investigator = InvestigatorConfig(
                                model = "bifrost/cerebras/gemma-4-31b",
                                allowedNamespaces = listOf("gsg"),
                            )
                        )
                    )
                ).koin.get<InvestigatorService>()
            }
        )
        assertTrue(
            e.message!!.contains("not served by the delegate"),
            "the error should name the unserved namespace: ${e.message}"
        )
    }

    private fun addTool(): MockTool = MockTool(
        name = "add",
        description = "Add two numbers a and b",
        handler = { args ->
            fun num(key: String) = args[key]?.jsonPrimitive?.let { it.content.toLongOrNull() } ?: 0L
            MockToolReply("${num("a")} + ${num("b")} = ${num("a") + num("b")}")
        },
    )

    private fun user(text: String) = ChatMessage(
        ChatMessageRole.User,
        listOf(ChatMessagePart.Text(text)),
        createdAt = java.time.Instant.parse("2026-08-17T09:00:00Z"),
    )
}

/** A callback that drops everything: persona-run tests assert on the hand. */
private object NoopStreamingCallback : StreamingExecutionCallback {
    override suspend fun onTextDelta(text: String) {}
    override suspend fun onReasoningDelta(text: String) {}
    override suspend fun onToolCall(name: String, args: JsonObject) {}
    override suspend fun onToolResults(results: List<ChatMessagePart.ToolResult>) {}
    override suspend fun onStreamError(error: String) {}
}
