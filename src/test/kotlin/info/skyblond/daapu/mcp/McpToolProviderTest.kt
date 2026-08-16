package info.skyblond.daapu.mcp

import info.skyblond.daapu.agent.tool.ToolCallRequest
import info.skyblond.daapu.agent.chat.ChatMessagePart
import info.skyblond.daapu.config.MCP_RESERVED_NAMESPACES
import info.skyblond.daapu.config.McpServerConfig
import info.skyblond.daapu.config.McpTransportType
import java.io.File
import java.io.IOException
import java.util.concurrent.CompletionException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the MCP tool provider's lifecycle and error policy (#8): eager connect
 * at construction (a server that cannot be reached aborts startup) + client
 * caching (per-request turn construction must not reconnect per run),
 * `{namespace}__{tool}` name namespacing, the error-result vs
 * transport-failure split, the in-turn drop-reconnect-retry, and the
 * reconnect-on-next-run behavior.
 */
class McpToolProviderTest {

    // ------------------------------------------------------------------
    // eager connect + caching
    // ------------------------------------------------------------------

    @Test
    fun `clients connect eagerly at construction and are cached across runs`() {
        val server = MockMcpServer(listOf(addTool()))
        val provider = McpToolProvider(listOf(httpConfig("calc", server)))
        try {
            assertEquals(1, server.initializeCount.get(), "the client connects at construction")
            val specs = runBlocking { provider.specifications() }
            assertEquals(1, server.initializeCount.get(), "no reconnect for a cached client")
            assertEquals(listOf("calc__add"), specs.map { it.name })

            // a second "run" must reuse the cached client, not reconnect
            val specs2 = runBlocking { provider.specifications() }
            assertEquals(1, server.initializeCount.get(), "the cached client must not reconnect")
            assertEquals(listOf("calc__add"), specs2.map { it.name })
        } finally {
            provider.close()
            server.close()
        }
    }

    @Test
    fun `an unreachable server fails startup`() {
        val good = MockMcpServer(listOf(echoTool()))
        // port 1: connection refused — the eager connect must abort construction
        try {
            assertFailsWith<McpTransportException> {
                McpToolProvider(
                    listOf(
                        httpConfig("good", good),
                        McpServerConfig(
                            namespace = "dead",
                            type = McpTransportType.Http,
                            url = "http://127.0.0.1:1/mcp",
                            reconnectAttempts = 2,
                            reconnectDelayMs = 50,
                            toolExecutionTimeoutSeconds = 0,
                        ),
                    )
                )
            }
        } finally {
            good.close()
        }
    }

    @Test
    fun `a server that fails initialize retries reconnectAttempts times then fails startup`() {
        // the server answers initialize with 500: the eager connect retries
        // `reconnectAttempts` times, then aborts construction
        val server = MockMcpServer(listOf(echoTool()), failInitialize = true)
        try {
            assertFailsWith<McpTransportException> {
                McpToolProvider(
                    listOf(httpConfig("calc", server, reconnectAttempts = 2, reconnectDelayMs = 50))
                )
            }
            assertEquals(2, server.initializeCount.get(), "exactly reconnectAttempts connect attempts")
        } finally {
            server.close()
        }
    }

    @Test
    fun `advertised tool names are namespaced by namespace`() {
        val server = MockMcpServer(listOf(addTool(), echoTool()))
        val provider = McpToolProvider(listOf(httpConfig("calc", server, toolExecutionTimeoutSeconds = 60)))
        try {
            val specs = runBlocking { provider.specifications() }
            assertEquals(listOf("calc__add", "calc__echo"), specs.map { it.name })
            assertTrue(specs.all { it.description.isNotBlank() })
            assertTrue(specs.all { it.timeoutSeconds == 60L }, "the server's execution timeout must reach every advertised tool")
        } finally {
            provider.close()
            server.close()
        }
    }

    @Test
    fun `a reserved namespace is rejected at construction`() {
        val server = MockMcpServer(listOf(addTool()))
        try {
            // namespaces the harness reserves for internal/harness tools must
            // not be claimed by an MCP server (validated in the config too,
            // see ConfigTest; here the provider fails fast at construction)
            for (reserved in MCP_RESERVED_NAMESPACES) {
                assertFailsWith<IllegalArgumentException> {
                    McpToolProvider(listOf(httpConfig(reserved, server)))
                }
            }
        } finally {
            server.close()
        }
    }

    // ------------------------------------------------------------------
    // execution
    // ------------------------------------------------------------------

    @Test
    fun `execute routes the advertised name to the raw tool`() {
        val server = MockMcpServer(listOf(addTool()))
        val provider = McpToolProvider(listOf(httpConfig("calc", server)))
        try {
            runBlocking { provider.specifications() }
            val result = runBlocking {
                provider.execute(request("call-1", "calc__add", buildJsonObject { put("a", 1); put("b", 2) }))
            }
            assertEquals("call-1", result.id)
            assertEquals("calc__add", result.tool)
            assertEquals("1 + 2 = 3", result.text())
            assertFalse(result.isError)
            // the server saw the RAW tool name and the parsed arguments
            val (rawName, args) = server.toolCalls.single()
            assertEquals("add", rawName)
            assertEquals(1, args["a"]?.jsonPrimitive?.let { it.content.toInt() })
            assertEquals(2, args["b"]?.jsonPrimitive?.let { it.content.toInt() })
        } finally {
            provider.close()
            server.close()
        }
    }

    @Test
    fun `server-side isError result becomes an error tool result without dropping the connection`() {
        val server = MockMcpServer(listOf(boomTool()))
        val provider = McpToolProvider(listOf(httpConfig("calc", server)))
        try {
            runBlocking { provider.specifications() }
            val result = runBlocking { provider.execute(request("call-1", "calc__boom", JsonObject(emptyMap()))) }
            assertTrue(result.isError, "the error must be surfaced as an error tool result")
            assertTrue(result.text().contains("exploded"))
            assertEquals("call-1", result.id, "the result must stay paired with its call id")

            // a server-side error is NOT a transport failure: the connection
            // must survive it (no drop, no reconnect)
            runBlocking { provider.execute(request("call-2", "calc__boom", JsonObject(emptyMap()))) }
            assertEquals(1, server.initializeCount.get(), "an isError result must not drop the connection")
        } finally {
            provider.close()
            server.close()
        }
    }

    @Test
    fun `executing a name no server advertises returns an error result`() {
        val server = MockMcpServer(listOf(addTool()))
        val provider = McpToolProvider(listOf(httpConfig("calc", server)))
        try {
            runBlocking { provider.specifications() }
            // a model hallucinating a tool name that is not advertised
            val result = runBlocking { provider.execute(request("call-1", "calc__nonexistent", JsonObject(emptyMap()))) }
            assertTrue(result.isError)
            assertTrue(result.text().contains("not found in MCP server"))
            assertTrue(server.toolCalls.isEmpty(), "no server call must be made")
        } finally {
            provider.close()
            server.close()
        }
    }

    @Test
    fun `execute rejects malformed advertised names`() {
        val server = MockMcpServer(listOf(addTool()))
        val provider = McpToolProvider(listOf(httpConfig("calc", server)))
        try {
            runBlocking { provider.specifications() }
            // a valid split with an unknown namespace: no entry owns it
            val unknown = runBlocking { provider.execute(request("call-1", "nope__add", JsonObject(emptyMap()))) }
            assertTrue(unknown.isError)
            assertTrue(unknown.text().contains("not advertised"))

            // wrong part count (3 parts: only `namespace__tool` is valid)
            val wrongParts = runBlocking { provider.execute(request("call-2", "a__b__c", JsonObject(emptyMap()))) }
            assertTrue(wrongParts.isError)
            assertTrue(wrongParts.text().contains("invalid tool name"))

            assertTrue(server.toolCalls.isEmpty(), "no server call must be made")
        } finally {
            provider.close()
            server.close()
        }
    }

    @Test
    fun `blank text result becomes the no-content placeholder`() {
        // a stored tool message with empty content is a risk with strict
        // providers: blank text is dropped and the placeholder stands in
        val server = MockMcpServer(
            listOf(MockTool(name = "blank", description = "returns nothing", handler = { MockToolReply("") }))
        )
        val provider = McpToolProvider(listOf(httpConfig("calc", server)))
        try {
            runBlocking { provider.specifications() }
            val result = runBlocking { provider.execute(request("call-1", "calc__blank", JsonObject(emptyMap()))) }
            assertFalse(result.isError)
            assertEquals(
                listOf(ChatMessagePart.Text("(the tool returned no text content)")),
                result.parts,
            )
        } finally {
            provider.close()
            server.close()
        }
    }

    // ------------------------------------------------------------------
    // transport failures
    // ------------------------------------------------------------------

    @Test
    fun `transport failure mid-execution fails the run and the next run reconnects`() {
        val server = MockMcpServer(listOf(echoTool()))
        val provider = McpToolProvider(
            listOf(httpConfig("calc", server, reconnectAttempts = 2, reconnectDelayMs = 50))
        )
        val port = server.port
        try {
            runBlocking { provider.specifications() }
            assertEquals(1, server.initializeCount.get())

            // kill the server mid-session: executing must fail the run
            server.close()
            val e = assertFailsWith<McpTransportException> {
                runBlocking { provider.execute(request("call-1", "calc__echo", buildJsonObject { put("text", "hi") })) }
            }
            assertTrue(e.cause != null, "the transport failure must be preserved as the cause")

            // the client was dropped: the next run reconnects to a fresh server
            // bound to the same port (a restart from the client's perspective)
            val restarted = MockMcpServer(listOf(echoTool()), bindPort = port)
            try {
                val specs = runBlocking { provider.specifications() }
                assertEquals(listOf("calc__echo"), specs.map { it.name })
                assertEquals(1, restarted.initializeCount.get(), "a fresh client must be built")
                val result = runBlocking {
                    provider.execute(request("call-2", "calc__echo", buildJsonObject { put("text", "hi") }))
                }
                assertEquals("hi", result.text())
                assertFalse(result.isError)
            } finally {
                restarted.close()
            }
        } finally {
            provider.close()
        }
    }

    @Test
    fun `listTools on a dead server fails the run and the next run reconnects`() {
        val server = MockMcpServer(listOf(echoTool()))
        val provider = McpToolProvider(
            listOf(httpConfig("calc", server, reconnectAttempts = 2, reconnectDelayMs = 50))
        )
        val port = server.port
        try {
            // no specifications() before the kill: the client caches listTools
            // after the first success, so advertisement on the dead server
            // must fail on a cache-miss to reach the drop-reconnect path
            assertEquals(1, server.initializeCount.get(), "eager connect at construction")

            // kill the server: the first advertisement drops the dead client,
            // the reconnect cannot restore it (the server is still down), and
            // the run fails instead of silently advertising nothing
            server.close()
            val e = assertFailsWith<McpTransportException> {
                runBlocking { provider.specifications() }
            }
            assertTrue(e.cause != null, "the transport failure must be preserved as the cause")

            // a restarted server on the same port is picked up on the next run
            val restarted = MockMcpServer(listOf(echoTool()), bindPort = port)
            try {
                val specs = runBlocking { provider.specifications() }
                assertEquals(listOf("calc__echo"), specs.map { it.name })
                assertEquals(1, restarted.initializeCount.get(), "a fresh client must be built")
                val result = runBlocking {
                    provider.execute(request("call-2", "calc__echo", buildJsonObject { put("text", "hi") }))
                }
                assertEquals("hi", result.text())
                assertFalse(result.isError)
            } finally {
                restarted.close()
            }
        } finally {
            provider.close()
        }
    }


    // ------------------------------------------------------------------
    // stdio transport
    // ------------------------------------------------------------------

    @Test
    fun `stdio server executes tools and a dead process gets one in-turn retry then an error result`() {
        val countFile = File.createTempFile("mcp-stdio-count", ".txt").apply { deleteOnExit() }
        val provider = McpToolProvider(
            listOf(
                McpServerConfig(
                    namespace = "local",
                    type = McpTransportType.Stdio,
                    command = stdioMockCommand(),
                    environment = mapOf("MCP_STDIO_COUNT_FILE" to countFile.absolutePath),
                    toolExecutionTimeoutSeconds = 0,
                )
            )
        )
        try {
            assertEquals(1, countFile.readLines().count { it == "initialize" }, "eager connect at construction")
            assertEquals(listOf("local__echo", "local__die"), runBlocking { provider.specifications() }.map { it.name })

            // the die tool kills the subprocess: the first attempt fails with
            // a transport failure, the provider drops the connection,
            // reconnects (a fresh subprocess), and re-executes the call once —
            // which kills the second subprocess too. The retry is exhausted,
            // so the model gets a generic error tool-result instead of a run failure.
            val result = runBlocking { provider.execute(request("call-1", "local__die", JsonObject(emptyMap()))) }
            assertTrue(result.isError, "the exhausted in-turn retry must surface as an error result")
            assertTrue(result.text().contains("transport failure"))
            assertEquals(2, countFile.readLines().count { it == "initialize" }, "the in-turn retry reconnects once")

            // the dead client was dropped: the next run spawns a fresh subprocess
            assertEquals(listOf("local__echo", "local__die"), runBlocking { provider.specifications() }.map { it.name })
            assertEquals(3, countFile.readLines().count { it == "initialize" }, "a fresh subprocess must be spawned")
            val echo = runBlocking { provider.execute(request("call-2", "local__echo", buildJsonObject { put("text", "hello") })) }
            assertEquals("hello", echo.text())
        } finally {
            provider.close()
            countFile.delete()
        }
    }

    @Test
    fun `close shuts down the stdio subprocess`() {
        val countFile = File.createTempFile("mcp-stdio-count", ".txt").apply { deleteOnExit() }
        val provider = McpToolProvider(
            listOf(
                McpServerConfig(
                    namespace = "local",
                    type = McpTransportType.Stdio,
                    command = stdioMockCommand(),
                    environment = mapOf("MCP_STDIO_COUNT_FILE" to countFile.absolutePath),
                    toolExecutionTimeoutSeconds = 0,
                )
            )
        )
        try {
            assertEquals(1, countFile.readLines().count { it == "initialize" }, "eager connect at construction")
            provider.close()
            // give the process a moment to be destroyed
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline && countFile.readLines().count { it == "initialize" } == 1) {
                Thread.sleep(50)
            }
            assertEquals(1, countFile.readLines().count { it == "initialize" }, "no further initialize after close")
        } finally {
            provider.close()
            countFile.delete()
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private fun httpConfig(
        namespace: String,
        server: MockMcpServer,
        reconnectAttempts: Int = 3,
        reconnectDelayMs: Long = 1000L,
        toolExecutionTimeoutSeconds: Long = 0,
    ) = McpServerConfig(
        namespace = namespace,
        type = McpTransportType.Http,
        url = server.baseUrl,
        reconnectAttempts = reconnectAttempts,
        reconnectDelayMs = reconnectDelayMs,
        toolExecutionTimeoutSeconds = toolExecutionTimeoutSeconds,
    )

    private fun request(id: String, name: String, argsJson: JsonObject): ToolCallRequest =
        ToolCallRequest(id = id, name = name, args = argsJson)

    /** The tool result's text content, joined like the SSE `tool_result` event. */
    private fun ChatMessagePart.ToolResult.text(): String =
        parts.filterIsInstance<ChatMessagePart.Text>().joinToString("") { it.text }

    private fun addTool(): MockTool = MockTool(
        name = "add",
        description = "Add two numbers a and b",
        handler = { args ->
            fun num(key: String) = args[key]?.jsonPrimitive?.let { it.content.toLongOrNull() } ?: 0L
            MockToolReply("${num("a")} + ${num("b")} = ${num("a") + num("b")}")
        },
    )

    private fun echoTool(): MockTool = MockTool(
        name = "echo",
        description = "Echo the given text back",
        handler = { args -> MockToolReply(args["text"]?.jsonPrimitive?.content ?: "") },
    )

    private fun boomTool(): MockTool = MockTool(
        name = "boom",
        description = "Always fails with an application-level error",
        handler = { MockToolReply("mock server exploded", isError = true) },
    )

    private fun stdioMockCommand(): List<String> = listOf(
        System.getProperty("java.home") + File.separator + "bin" + File.separator + "java",
        "-cp",
        System.getProperty("java.class.path"),
        "info.skyblond.daapu.mcp.StdioMockMcpMainKt",
    )
}
