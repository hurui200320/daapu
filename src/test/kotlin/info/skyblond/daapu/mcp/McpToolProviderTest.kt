package info.skyblond.daapu.mcp

import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.exception.ToolExecutionException
import info.skyblond.daapu.McpServerConfig
import info.skyblond.daapu.McpTransportType
import info.skyblond.daapu.chat.ChatMessagePart
import java.io.File
import java.io.IOException
import java.util.concurrent.CompletionException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the MCP tool provider's lifecycle and error policy (#8): lazy connect
 * + client caching (per-request turn construction must not reconnect per
 * run), per-server failure isolation, name namespacing, the error-result vs
 * transport-failure split, and the reconnect-on-drop behavior.
 */
class McpToolProviderTest {

    // ------------------------------------------------------------------
    // lazy connect + caching
    // ------------------------------------------------------------------

    @Test
    fun `clients connect lazily on first use and are cached across runs`() {
        val server = MockMcpServer(listOf(addTool()))
        val provider = McpToolProvider(listOf(httpConfig("calc", server)))
        try {
            assertEquals(0, server.initializeCount.get(), "no connect before first use")
            val specs = runBlocking { provider.specifications() }
            assertEquals(1, server.initializeCount.get(), "exactly one connect")
            assertEquals(listOf("calc_add"), specs.map { it.name() })

            // a second "run" must reuse the cached client, not reconnect
            val specs2 = runBlocking { provider.specifications() }
            assertEquals(1, server.initializeCount.get(), "the cached client must not reconnect")
            assertEquals(listOf("calc_add"), specs2.map { it.name() })
        } finally {
            provider.close()
            server.close()
        }
    }

    @Test
    fun `advertised tool names are namespaced by server name`() {
        val server = MockMcpServer(listOf(addTool(), echoTool()))
        val provider = McpToolProvider(listOf(httpConfig("calc", server)))
        try {
            val specs = runBlocking { provider.specifications() }
            assertEquals(listOf("calc_add", "calc_echo"), specs.map { it.name() })
            assertTrue(specs.all { it.description() != null })
        } finally {
            provider.close()
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
                provider.execute(request("call-1", "calc_add", """{"a":1,"b":2}"""))
            }
            assertEquals("call-1", result.id)
            assertEquals("calc_add", result.tool)
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
    fun `server-side isError result becomes an error tool result`() {
        val server = MockMcpServer(listOf(boomTool()))
        val provider = McpToolProvider(listOf(httpConfig("calc", server)))
        try {
            runBlocking { provider.specifications() }
            val result = runBlocking { provider.execute(request("call-1", "calc_boom", "{}")) }
            assertTrue(result.isError, "the error must be surfaced as an error tool result")
            assertTrue(result.text().contains("exploded"))
            assertEquals("call-1", result.id, "the result must stay paired with its call id")
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
            val result = runBlocking { provider.execute(request("call-1", "calc_nonexistent", "{}")) }
            assertTrue(result.isError)
            assertTrue(result.text().contains("not advertised"))
            assertTrue(server.toolCalls.isEmpty(), "no server call must be made")
        } finally {
            provider.close()
            server.close()
        }
    }

    @Test
    fun `tool arguments rejected by the server become an error tool result`() {
        val server = MockMcpServer(listOf(addTool()))
        val provider = McpToolProvider(listOf(httpConfig("calc", server)))
        try {
            runBlocking { provider.specifications() }
            // malformed JSON arguments: the client throws ToolArgumentsException
            val result = runBlocking { provider.execute(request("call-1", "calc_add", "not json")) }
            assertTrue(result.isError)
            assertTrue(result.text().isNotBlank(), "the failure reason must reach the model")
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
            val result = runBlocking { provider.execute(request("call-1", "calc_blank", "{}")) }
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
        val provider = McpToolProvider(listOf(httpConfig("calc", server)))
        val port = server.port
        try {
            runBlocking { provider.specifications() }
            assertEquals(1, server.initializeCount.get())

            // kill the server mid-session: executing must fail the run
            server.close()
            val e = assertFailsWith<McpTransportException> {
                runBlocking { provider.execute(request("call-1", "calc_echo", """{"text":"hi"}""")) }
            }
            assertTrue(e.cause != null, "the transport failure must be preserved as the cause")

            // the client was dropped: the next run reconnects to a fresh server
            // bound to the same port (a restart from the client's perspective)
            val restarted = MockMcpServer(listOf(echoTool()), bindPort = port)
            try {
                val specs = runBlocking { provider.specifications() }
                assertEquals(listOf("calc_echo"), specs.map { it.name() })
                assertEquals(1, restarted.initializeCount.get(), "a fresh client must be built")
                val result = runBlocking {
                    provider.execute(request("call-2", "calc_echo", """{"text":"hi"}"""))
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
    fun `isTransportFailure classifies server-side and transport failures`() {
        val provider = McpToolProvider(emptyList())
        with(provider) {
            // server-side isError: a RuntimeException cause carrying the
            // wrapper's very message (ToolExecutionHelper builds this shape)
            assertFalse(ToolExecutionException(RuntimeException("boom")).isTransportFailure())
            // no cause at all: not a transport failure
            assertFalse(ToolExecutionException("just a message").isTransportFailure())
            // stdio process death: named IllegalStateException messages
            assertTrue(ToolExecutionException(IllegalStateException("Process has exited")).isTransportFailure())
            assertTrue(ToolExecutionException(IllegalStateException("Process is not alive")).isTransportFailure())
            // transport failures keep their underlying cause (IOException, ...)
            assertTrue(ToolExecutionException(IOException("connection refused")).isTransportFailure())
            // a CompletionException from the HTTP transport's CompletableFuture
            // (e.g. the body subscriber throwing mid-stream on a malformed SSE
            // payload) is a transport failure too, despite carrying the
            // wrapper's message
            assertTrue(ToolExecutionException(CompletionException("boom", RuntimeException("boom"))).isTransportFailure())
        }
    }

    @Test
    fun `one unreachable server does not break other servers`() {
        val good = MockMcpServer(listOf(echoTool()))
        // port 1: connection refused
        val provider = McpToolProvider(
            listOf(
                httpConfig("good", good),
                McpServerConfig(name = "dead", type = McpTransportType.Http, url = "http://127.0.0.1:1/mcp"),
            )
        )
        try {
            val specs = runBlocking { provider.specifications() }
            assertEquals(listOf("good_echo"), specs.map { it.name() }, "only the reachable server's tools are advertised")
            val result = runBlocking { provider.execute(request("call-1", "good_echo", """{"text":"hi"}""")) }
            assertEquals("hi", result.text())
            assertFalse(result.isError)
        } finally {
            provider.close()
            good.close()
        }
    }

    @Test
    fun `a failed connect is not retried within the cooldown interval`() {
        // a server that answers initialize with 500: the connect fails, but
        // the server stays up and counts the attempts
        val server = MockMcpServer(listOf(echoTool()), failInitialize = true)
        var now = 0L
        val provider = McpToolProvider(
            configs = listOf(httpConfig("calc", server)),
            connectRetryIntervalMs = 1_000,
            nowMillis = { now },
        )
        try {
            assertEquals(emptyList(), runBlocking { provider.specifications() })
            assertEquals(1, server.initializeCount.get())

            // inside the cooldown: no reconnect attempt at all
            now += 500
            assertEquals(emptyList(), runBlocking { provider.specifications() })
            assertEquals(1, server.initializeCount.get(), "the cooldown must suppress reconnects")

            // after the cooldown: a fresh attempt
            now += 1_000
            assertEquals(emptyList(), runBlocking { provider.specifications() })
            assertEquals(2, server.initializeCount.get())
        } finally {
            provider.close()
            server.close()
        }
    }

    // ------------------------------------------------------------------
    // stdio transport
    // ------------------------------------------------------------------

    @Test
    fun `stdio server executes tools and a dead process is reconnected on the next run`() {
        val countFile = File.createTempFile("mcp-stdio-count", ".txt").apply { deleteOnExit() }
        val provider = McpToolProvider(
            listOf(
                McpServerConfig(
                    name = "local",
                    type = McpTransportType.Stdio,
                    command = stdioMockCommand(),
                    environment = mapOf("MCP_STDIO_COUNT_FILE" to countFile.absolutePath),
                )
            )
        )
        try {
            assertEquals(listOf("local_echo", "local_die"), runBlocking { provider.specifications() }.map { it.name() })
            assertEquals(1, countFile.readLines().count { it == "initialize" })

            // the die tool kills the subprocess: executing it is a transport failure
            val e = assertFailsWith<McpTransportException> {
                runBlocking { provider.execute(request("call-1", "local_die", "{}")) }
            }
            assertTrue(e.cause != null)

            // the client was dropped: the next run spawns a fresh subprocess
            assertEquals(listOf("local_echo", "local_die"), runBlocking { provider.specifications() }.map { it.name() })
            assertEquals(2, countFile.readLines().count { it == "initialize" }, "a fresh subprocess must be spawned")
            val result = runBlocking { provider.execute(request("call-2", "local_echo", """{"text":"hello"}""")) }
            assertEquals("hello", result.text())
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
                    name = "local",
                    type = McpTransportType.Stdio,
                    command = stdioMockCommand(),
                    environment = mapOf("MCP_STDIO_COUNT_FILE" to countFile.absolutePath),
                )
            )
        )
        try {
            runBlocking { provider.specifications() }
            assertEquals(1, countFile.readLines().count { it == "initialize" })
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

    private fun httpConfig(name: String, server: MockMcpServer) =
        McpServerConfig(name = name, type = McpTransportType.Http, url = server.baseUrl)

    private fun request(id: String, name: String, argsJson: String): ToolExecutionRequest =
        ToolExecutionRequest.builder().id(id).name(name).arguments(argsJson).build()

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
