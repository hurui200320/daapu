package info.skyblond.daapu.server

import info.skyblond.daapu.agent.chat.AttachmentContent
import info.skyblond.daapu.agent.chat.AttachmentKind
import info.skyblond.daapu.agent.chat.ChatMessagePart
import info.skyblond.daapu.agent.oneshot.investigate.InvestigatorService
import info.skyblond.daapu.agent.tool.ToolCallRequest
import info.skyblond.daapu.config.AgentConfig
import info.skyblond.daapu.config.EltmConfig
import info.skyblond.daapu.config.InvestigatorConfig
import info.skyblond.daapu.config.McpServerConfig
import info.skyblond.daapu.config.McpTransportType
import info.skyblond.daapu.config.MemoryConfig
import info.skyblond.daapu.config.TitleConfig
import info.skyblond.daapu.config.testAppConfig
import info.skyblond.daapu.mcp.McpToolProvider
import info.skyblond.daapu.mcp.MockMcpServer
import info.skyblond.daapu.mcp.MockTool
import info.skyblond.daapu.mcp.MockToolReply
import info.skyblond.daapu.testutil.assertFailsFast
import info.skyblond.daapu.testutil.chatRunService
import info.skyblond.daapu.testutil.testKoinApp
import io.ktor.server.plugins.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.*

/**
 * Pins the request → neutral parts mapping done by [ChatRunService.prepareRun].
 */
class ChatRunServiceTest {

    private val service = chatRunService(testAppConfig())

    private fun request(
        text: String? = null,
        images: List<String> = emptyList(),
        model: String = "bifrost/cerebras/gpt-oss-120b",
    ) = SendMessageRequest(text = text, images = images.map { ImagePart(it) }, model = model)

    @Test
    fun `text only maps to a text part`() {
        val setup = service.prepareRun("chat-1", request(text = "hello"))
        assertEquals(listOf(ChatMessagePart.Text("hello")), setup.parts)
        assertEquals("bifrost/cerebras/gpt-oss-120b", setup.model.id)
    }

    @Test
    fun `text and image map to text plus attachment`() {
        val dataUrl = "data:image/png;base64,AAAA"
        val setup = service.prepareRun("chat-1", request(text = "look", images = listOf(dataUrl)))
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
            service.prepareRun("chat-1", request(images = listOf("data:image/jpeg;base64,BBBB")))
        assertEquals(1, setup.parts.size)
        assertIs<ChatMessagePart.Attachment>(setup.parts[0])
    }

    @Test
    fun `image with a text-only model is NOT rejected at the API`() {
        // deliberate: capability enforcement lives in the turn loop's pre-send
        // step, so history-sourced images are covered too. Pinned here so the
        // API layer doesn't grow a partial validation that misses history.
        val setup = service.prepareRun(
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
        assertFailsWith<BadRequestException> { service.prepareRun("chat-1", request(text = "   ")) }
        assertFailsWith<BadRequestException> { service.prepareRun("chat-1", request(text = "")) }
        assertFailsWith<BadRequestException> { service.prepareRun("chat-1", request()) }
    }

    @Test
    fun `missing model is rejected`() {
        // the server has no default model; the web UI always sends one
        assertFailsWith<BadRequestException> {
            service.prepareRun("chat-1", SendMessageRequest(text = "hi"))
        }
        assertFailsWith<BadRequestException> {
            service.prepareRun("chat-1", SendMessageRequest(text = "hi", model = "  "))
        }
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
            val e = assertFailsWith<BadRequestException>("url: $url") {
                service.prepareRun("chat-1", request(images = listOf(url)))
            }
            assertNotNull(e.message)
        }
    }

    @Test
    fun `invalid base64 payload is rejected`() {
        // decodes to garbage, not valid base64
        val e = assertFailsWith<BadRequestException> {
            service.prepareRun(
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
            service.prepareRun("chat-1", request(images = listOf("data:image/png;base64,AAA\nA")))
        val attachment = assertIs<ChatMessagePart.Attachment>(setup.parts[0])
        assertEquals(AttachmentContent.Base64("AAAA"), attachment.content)
    }

    @Test
    fun `unknown model is rejected`() {
        val e = assertFailsWith<BadRequestException> {
            service.prepareRun("chat-1", request(text = "hi", model = "no/such-model"))
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
                val setup = service.prepareRun("chat-1", request(text = "hi", model = id))
                assertEquals(id, setup.model.id)
            }
    }

    @Test
    fun `a config without the providers fails fast at construction`() {
        // the catalog pins its models to the bifrost gateway and the
        // deepinfra embedding provider (see ModelCatalog.kt); a config
        // without them is a wiring bug
        val e = assertIs<IllegalArgumentException>(
            assertFailsFast { chatRunService(testAppConfig().copy(providers = emptyMap())) }
        )
        assertEquals("ModelCatalog requires a provider with id 'bifrost'", e.message)
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
            ),
        )
        val e = assertIs<IllegalArgumentException>(
            assertFailsFast {
                chatRunService(testAppConfig().copy(memory = valid.copy(compactModel = "bifrost/nope")))
            }
        )
        assertTrue(
            e.message!!.contains("memory.compactModel"),
            "the error should name the config key: ${e.message}"
        )
        assertIs<IllegalArgumentException>(
            assertFailsFast {
                chatRunService(
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
                chatRunService(
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
                chatRunService(
                    testAppConfig().copy(
                        memory = testAppConfig().memory.copy(eltm = base.copy(writerModel = "bifrost/nope"))
                    )
                )
            }
        )
        assertIs<IllegalArgumentException>(
            assertFailsFast {
                chatRunService(
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
            assertFailsFast { chatRunService(testAppConfig().copy(title = TitleConfig(model = "bifrost/nope"))) }
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
        val service = chatRunService(testAppConfig())
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
            listOf(
                McpServerConfig(
                    namespace = "calc",
                    type = McpTransportType.Http,
                    url = server.baseUrl,
                    toolExecutionTimeoutSeconds = 30,
                )
            )
        )
        val koinApp = testKoinApp(testAppConfig(), mcpToolProvider = mcp)
        val service = koinApp.koin.get<ChatRunService>()
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
}
