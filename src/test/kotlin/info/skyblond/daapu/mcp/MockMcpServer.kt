package info.skyblond.daapu.mcp

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.Closeable
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One advertised tool of the mock MCP server: [handler] produces the reply
 * for a `tools/call` request with the given arguments.
 */
internal data class MockTool(
    val name: String,
    val description: String,
    val inputSchema: JsonObject = buildJsonObject { put("type", JsonPrimitive("object")) },
    val handler: (JsonObject) -> MockToolReply,
)

internal data class MockToolReply(val text: String, val isError: Boolean = false)

/**
 * Minimal MCP (2025-11-25) streamable-HTTP server for tests, built on the JDK
 * HttpServer. Mirrors the wire protocol of the #3 spike's mock: a session id
 * is issued at `initialize` and REQUIRED on every later request (400 without
 * one, 404 for an unknown one — the client's re-initialize-and-retry trigger).
 *
 * A stopped instance cannot be restarted (JDK HttpServer is one-shot), so a
 * "server restart" is a NEW instance bound to the same [bindPort].
 */
internal class MockMcpServer(
    private val tools: List<MockTool>,
    private val bindPort: Int = 0,
    private val sessionRequired: Boolean = true,
    /** When true, `initialize` answers 500 (a server that cannot handshake). */
    private val failInitialize: Boolean = false,
) : Closeable {
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", bindPort), 0)
    private val sessionCounter = AtomicInteger(0)
    private val issuedSessions = CopyOnWriteArrayList<String>()
    private val json = Json

    val port: Int get() = server.address.port
    val baseUrl: String get() = "http://127.0.0.1:$port/mcp"

    /** Number of `initialize` requests served (connection attempts observed). */
    val initializeCount = AtomicInteger(0)

    /** Every tool call in order: (raw tool name, arguments). */
    val toolCalls = CopyOnWriteArrayList<Pair<String, JsonObject>>()

    init {
        // explicit executor: the JDK default can be a single dispatcher thread
        server.executor = Executors.newFixedThreadPool(4) { r ->
            Thread(r, "mock-mcp-server").apply { isDaemon = true }
        }
        server.createContext("/mcp") { exchange ->
            try {
                handle(exchange)
            } catch (_: Exception) {
                // the client aborting mid-response is expected
                runCatching { exchange.close() }
            }
        }
        server.start()
    }

    fun stop() = server.stop(0)

    override fun close() = stop()

    private fun handle(exchange: HttpExchange) {
        val sessionId = exchange.requestHeaders.getFirst("Mcp-Session-Id")
        val body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
        val request = try {
            json.parseToJsonElement(body).jsonObject
        } catch (_: Exception) {
            respond(exchange, 400, buildJsonObject { put("error", JsonPrimitive("Parse error")) })
            return
        }
        val method = request["method"]?.jsonPrimitive?.content

        if (sessionRequired && method != "initialize" && sessionId == null) {
            respond(exchange, 400, buildJsonObject { put("error", JsonPrimitive("Mcp-Session-Id header is required")) })
            return
        }
        if (sessionRequired && sessionId != null && sessionId !in issuedSessions) {
            respond(exchange, 404, buildJsonObject { put("error", JsonPrimitive("Unknown session: $sessionId")) })
            return
        }

        val id = request["id"]
        // notifications (e.g. notifications/initialized) carry no id
        if (id == null || id is JsonPrimitive && id.content == "null") {
            exchange.sendResponseHeaders(202, -1)
            exchange.close()
            return
        }

        when (method) {
            "initialize" -> {
                initializeCount.incrementAndGet()
                if (failInitialize) {
                    respond(exchange, 500, buildJsonObject { put("error", JsonPrimitive("initialization failed")) })
                    return
                }
                val newSession = "mock-session-${sessionCounter.incrementAndGet()}"
                issuedSessions += newSession
                exchange.responseHeaders.add("Mcp-Session-Id", newSession)
                respond(
                    exchange, 200,
                    jsonRpc(id) {
                        put("protocolVersion", JsonPrimitive("2025-11-25"))
                        put("capabilities", buildJsonObject {
                            put("tools", buildJsonObject { put("listChanged", JsonPrimitive(false)) })
                        })
                        put("serverInfo", buildJsonObject {
                            put("name", JsonPrimitive("kotlin-mock-mcp"))
                            put("version", JsonPrimitive("0.1.0"))
                        })
                    },
                )
            }

            "ping" -> respond(exchange, 200, jsonRpc(id) { })

            "tools/list" -> respond(exchange, 200, jsonRpc(id) {
                put("tools", buildJsonArray {
                    tools.forEach { tool ->
                        add(buildJsonObject {
                            put("name", JsonPrimitive(tool.name))
                            put("description", JsonPrimitive(tool.description))
                            put("inputSchema", tool.inputSchema)
                        })
                    }
                })
            })

            "tools/call" -> {
                val params = request["params"]?.jsonObject ?: buildJsonObject {}
                val name = params["name"]?.jsonPrimitive?.content ?: ""
                val args = params["arguments"]?.jsonObject ?: buildJsonObject {}
                toolCalls += name to args
                val tool = tools.find { it.name == name }
                if (tool == null) {
                    respond(exchange, 200, jsonRpcError(id, -32602, "Unknown tool: $name"))
                } else {
                    val reply = tool.handler(args)
                    respond(exchange, 200, jsonRpc(id) {
                        put("content", buildJsonArray {
                            add(buildJsonObject {
                                put("type", JsonPrimitive("text"))
                                put("text", JsonPrimitive(reply.text))
                            })
                        })
                        put("isError", JsonPrimitive(reply.isError))
                    })
                }
            }

            else -> respond(exchange, 200, jsonRpcError(id, -32601, "Method not found: $method"))
        }
    }

    private fun jsonRpc(id: JsonElement, body: JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject {
        put("jsonrpc", JsonPrimitive("2.0"))
        put("id", id)
        put("result", buildJsonObject(body))
    }

    private fun jsonRpcError(id: JsonElement, code: Int, message: String): JsonObject = buildJsonObject {
        put("jsonrpc", JsonPrimitive("2.0"))
        put("id", id)
        put("error", buildJsonObject {
            put("code", JsonPrimitive(code))
            put("message", JsonPrimitive(message))
        })
    }

    private fun respond(exchange: HttpExchange, status: Int, response: JsonObject) {
        val bytes = response.toString().toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
