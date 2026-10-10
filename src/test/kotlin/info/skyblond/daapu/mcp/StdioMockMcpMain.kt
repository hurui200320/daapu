package info.skyblond.daapu.mcp

import kotlinx.serialization.json.*
import java.io.File

/**
 * Minimal MCP stdio server for tests, run as a subprocess by
 * `StdioMcpTransport`. Speaks newline-delimited JSON-RPC on stdin/stdout.
 *
 * Observability and modes (via the transport's environment):
 * - `MCP_STDIO_PID_FILE` — the process appends its own PID as one line at
 *   startup (any mode), so tests can assert ACTUAL process termination
 *   through [ProcessHandle].
 * - `MCP_STDIO_SILENT` — never speaks: no handshake, no stderr, just a
 *   bounded "forever" (~2 minutes by default, in 1s sleeps, so a
 *   regression fails timing assertions instead of hanging a test forever;
 *   shorter via `MCP_STDIO_SILENT_SECONDS` for tests whose regression
 *   cost scales per retry attempt). A client stuck on this server sees
 *   an alive-but-silent pipe — exactly the case where the SDK's close
 *   blocks until the process dies.
 * - `MCP_STDIO_IGNORE_TERM` — a shutdown hook blocks the JVM's exit
 *   (~2 minutes by default, shorter via `MCP_STDIO_IGNORE_TERM_SECONDS`),
 *   so a graceful SIGTERM does NOT terminate the process: only a force
 *   kill reaps it. Composable with `MCP_STDIO_SILENT` to exercise the
 *   destroy-with-escalation path ([ClientEntry.destroyWithEscalation]).
 *
 * Tools:
 * - `echo(text)` — returns the text back.
 * - `die` — exits the process WITHOUT responding (mid-session process death).
 *
 * Each `initialize` appends a line to the file in `MCP_STDIO_COUNT_FILE` (a
 * test passes it via the transport's environment), so tests can observe
 * connection/reconnection attempts of the subprocess.
 */
fun main() {
    // self-report first: even the silent mode must be observable
    System.getenv("MCP_STDIO_PID_FILE")?.let { File(it).appendText("${ProcessHandle.current().pid()}\n") }
    if (System.getenv("MCP_STDIO_IGNORE_TERM") != null) {
        // SIGTERM starts the JVM shutdown sequence, which runs this hook
        // before exiting: sleeping here keeps the process alive — a force
        // kill is the only way out (why: the mode's KDoc above)
        val seconds = System.getenv("MCP_STDIO_IGNORE_TERM_SECONDS")?.toLongOrNull() ?: 120L
        Runtime.getRuntime().addShutdownHook(Thread {
            try {
                Thread.sleep(seconds * 1_000L)
            } catch (_: InterruptedException) {
                // interrupted: exit at once
            }
        })
    }
    if (System.getenv("MCP_STDIO_SILENT") != null) {
        val seconds = System.getenv("MCP_STDIO_SILENT_SECONDS")?.toLongOrNull() ?: 120L
        repeat(seconds.toInt()) { Thread.sleep(1_000) }
        return
    }
    val json = Json
    val countFile = System.getenv("MCP_STDIO_COUNT_FILE")?.let { File(it) }
    val reader = System.`in`.bufferedReader()
    val out = System.out
    while (true) {
        val line = reader.readLine() ?: break
        val request = try {
            json.parseToJsonElement(line).jsonObject
        } catch (_: Exception) {
            continue
        }
        val id = request["id"]
        val method = request["method"]?.jsonPrimitive?.content
        val params = request["params"] as? JsonObject

        // notifications (e.g. notifications/initialized) need no response
        if (id == null || id is JsonPrimitive && id.content == "null") continue

        val response = when (method) {
            "initialize" -> {
                countFile?.appendText("initialize\n")
                buildJsonObject {
                    put("jsonrpc", JsonPrimitive("2.0"))
                    put("id", id)
                    put("result", buildJsonObject {
                        put("protocolVersion", JsonPrimitive("2025-11-25"))
                        put("capabilities", buildJsonObject {
                            put(
                                "tools",
                                buildJsonObject { put("listChanged", JsonPrimitive(false)) })
                        })
                        put("serverInfo", buildJsonObject {
                            put("name", JsonPrimitive("kotlin-stdio-mock-mcp"))
                            put("version", JsonPrimitive("0.1.0"))
                        })
                    })
                }
            }

            "ping" -> buildJsonObject {
                put("jsonrpc", JsonPrimitive("2.0"))
                put("id", id)
                put("result", buildJsonObject {})
            }

            "tools/list" -> buildJsonObject {
                put("jsonrpc", JsonPrimitive("2.0"))
                put("id", id)
                put("result", buildJsonObject {
                    put("tools", buildJsonArray {
                        add(simpleTool("echo", "Echo the given text back"))
                        add(simpleTool("die", "Exits the server process without responding"))
                    })
                })
            }

            "tools/call" -> {
                val name = params?.get("name")?.jsonPrimitive?.content ?: ""
                val args = params?.get("arguments") as? JsonObject ?: buildJsonObject {}
                when (name) {
                    "echo" -> toolResult(id, args["text"]?.jsonPrimitive?.content ?: "")

                    "die" -> {
                        // exit without responding: the client should observe
                        // the process dying and cancel the pending operation
                        out.flush()
                        System.exit(1)
                    }

                    else -> buildJsonObject {
                        put("jsonrpc", JsonPrimitive("2.0"))
                        put("id", id)
                        put("error", buildJsonObject {
                            put("code", JsonPrimitive(-32602))
                            put("message", JsonPrimitive("Unknown tool: $name"))
                        })
                    }
                }
            }

            else -> buildJsonObject {
                put("jsonrpc", JsonPrimitive("2.0"))
                put("id", id)
                put("error", buildJsonObject {
                    put("code", JsonPrimitive(-32601))
                    put("message", JsonPrimitive("Method not found: $method"))
                })
            }
        }
        out.println(response)
        out.flush()
    }
}

private fun simpleTool(name: String, description: String) = buildJsonObject {
    put("name", JsonPrimitive(name))
    put("description", JsonPrimitive(description))
    put("inputSchema", buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("properties", buildJsonObject {
            put("text", buildJsonObject { put("type", JsonPrimitive("string")) })
        })
    })
}

private fun toolResult(id: JsonElement, text: String) = buildJsonObject {
    put("jsonrpc", JsonPrimitive("2.0"))
    put("id", id)
    put("result", buildJsonObject {
        put("content", buildJsonArray {
            add(buildJsonObject {
                put("type", JsonPrimitive("text"))
                put("text", JsonPrimitive(text))
            })
        })
        put("isError", JsonPrimitive(false))
    })
}
