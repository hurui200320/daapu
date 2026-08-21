package info.skyblond.daapu.server

import info.skyblond.daapu.agent.chat.AttachmentContent
import info.skyblond.daapu.agent.chat.AttachmentKind
import info.skyblond.daapu.agent.chat.ChatMessagePart
import info.skyblond.daapu.agent.tool.ToolCallRequest
import info.skyblond.daapu.config.EltmConfig
import info.skyblond.daapu.config.McpServerConfig
import info.skyblond.daapu.config.McpTransportType
import info.skyblond.daapu.config.MemoryConfig
import info.skyblond.daapu.config.SstmConfig
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
    fun `a config without the bifrost provider fails fast at construction`() {
        // the catalog pins its models to the bifrost gateway (see
        // ModelCatalog.kt); a config without it is a wiring bug
        val e = assertIs<IllegalStateException>(
            assertFailsFast { chatRunService(testAppConfig().copy(providers = emptyMap())) }
        )
        assertEquals("Provider config 'bifrost' not found", e.message)
    }

    @Test
    fun `an unknown memory model id fails fast at construction`() {
        // the one-shot pipeline models are resolved once at startup: a typo
        // must fail here, not silently skip every compaction/extraction
        val valid = MemoryConfig(
            compactModel = "bifrost/cerebras/gemma-4-31b",
            sstm = SstmConfig(
                extractModel = "bifrost/cerebras/gemma-4-31b",
                mergeModel = "bifrost/cerebras/gemma-4-31b",
                maxCapacity = 100,
                purgeBatchSize = 10,
            ),
            eltm = EltmConfig(
                embeddingModel = "bifrost/zenmux sub/google/gemini-embedding-2",
                writerModel = "bifrost/cerebras/gemma-4-31b",
                recallModel = "bifrost/cerebras/gemma-4-31b",
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
                    testAppConfig().copy(memory = valid.copy(sstm = valid.sstm.copy(extractModel = "bifrost/nope")))
                )
            }
        )
        assertIs<IllegalArgumentException>(
            assertFailsFast {
                chatRunService(
                    testAppConfig().copy(memory = valid.copy(sstm = valid.sstm.copy(mergeModel = "bifrost/nope")))
                )
            }
        )
    }

    @Test
    fun `an unknown eltm model id fails fast at construction`() {
        // same as the memory pipeline models: the resolved ELTM models
        // (REQUIRED config) are checked once at startup. The recall model
        // is NOT resolved here: the recall sub-session is not wired into
        // the graph yet (the chat loop queries the ELTM through the
        // `eltm__*` tools instead), so its id is only validated at the
        // Phase 4 definition site.
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
    fun `the chat tool set is the read-only eltm tools without MCP servers`() = runBlocking {
        // the loop's combined set: the namespace-less empty MCP provider (the
        // default) is skipped — CombinedToolProvider fails fast on a
        // namespace-less child — so the set is exactly the namespaced
        // read-only ELTM tools
        val service = chatRunService(testAppConfig())
        assertEquals(setOf("eltm"), service.chatToolProvider.namespaces())
        assertEquals(
            listOf(
                "eltm__search_entities",
                "eltm__get_relationships",
                "eltm__get_entity_notes",
                "eltm__get_relationship_notes",
                "eltm__search_notes",
            ),
            service.chatToolProvider.specifications().map { it.name },
            "the loop sees only the five read tools, never a write tool"
        )
        // a bare (unprefixed) name is unroutable in a combined set
        val result = service.chatToolProvider.execute(
            ToolCallRequest("c1", "search_entities", buildJsonObject { put("query", "ali") }),
        )
        assertTrue(result.isError)
        assertTrue(
            (result.parts.single() as ChatMessagePart.Text).text.contains("not advertised"),
        )
    }

    @Test
    fun `the chat tool set combines MCP tools with the read-only eltm tools`() = runBlocking {
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
            assertEquals(setOf("calc", "eltm"), service.chatToolProvider.namespaces())
            val names = service.chatToolProvider.specifications().map { it.name }
            assertEquals("calc__add", names.first())
            assertTrue(
                names.containsAll(
                    listOf(
                        "eltm__search_entities",
                        "eltm__search_notes",
                    )
                ),
                "the combined set carries both children: $names"
            )
            // the MCP namespace routes to the server, not the ELTM provider
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

    private fun addTool(): MockTool = MockTool(
        name = "add",
        description = "Add two numbers a and b",
        handler = { args ->
            fun num(key: String) = args[key]?.jsonPrimitive?.let { it.content.toLongOrNull() } ?: 0L
            MockToolReply("${num("a")} + ${num("b")} = ${num("a") + num("b")}")
        },
    )
}
