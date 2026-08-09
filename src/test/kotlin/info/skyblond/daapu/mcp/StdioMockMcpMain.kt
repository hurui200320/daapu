package info.skyblond.daapu.mcp

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Minimal MCP stdio server for tests, run as a subprocess by
 * `StdioMcpTransport`. Speaks newline-delimited JSON-RPC on stdin/stdout.
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
                            put("tools", buildJsonObject { put("listChanged", JsonPrimitive(false)) })
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
