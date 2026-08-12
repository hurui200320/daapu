package info.skyblond.daapu.mcp

import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.exception.ToolArgumentsException
import dev.langchain4j.exception.ToolExecutionException
import dev.langchain4j.mcp.client.McpException
import info.skyblond.daapu.McpServerConfig
import info.skyblond.daapu.agent.lc4j.tool.ToolProvider
import info.skyblond.daapu.chat.ChatMessagePart
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

/**
 * The MCP-backed [ToolProvider] (#8): one [ClientEntry] per configured server
 * (hardcoded in `Main.kt`, see [McpServerConfig]), all tools advertised to
 * every chat run ("one global tool set" for the PoC).
 *
 * Lifecycle:
 * - Clients are cached long-lived per server, and connected EAGERLY at
 *   construction: a server that cannot be reached aborts startup with
 *   [McpTransportException] (fail fast — a broken entry blocks the app rather
 *   than silently degrading every chat run). The initialize handshake
 *   (~0.5–11s) is paid once per server, never per run. `listTools` is cached
 *   client-side (default), so per-round advertisement is a local lookup.
 * - A transport failure mid-execution (connect refused, stdio process died)
 *   drops the cached client and retries the call once on a fresh connection
 *   (the reconnect itself retries [McpServerConfig.reconnectAttempts] times).
 *   If the reconnect cannot restore the connection, [McpTransportException]
 *   fails the run (surfaces as a clear SSE `error` event); if the call fails
 *   twice but the server stays up, a generic *error tool-result* is returned
 *   so the model sees the failure and can react in the next round.
 *   Tool-level failures (server-side `isError`, bad arguments) return an
 *   *error tool-result* without touching the connection.
 * - [close] closes every client (called on JVM shutdown).
 *
 * Tool names are advertised as `"{prefix}__{serverName}__{toolName}"`
 * (without the optional [namePrefix], `"{serverName}__{toolName}"`):
 * OpenAI-style gateways require unique tool names in the `tools` array, so
 * two servers exporting e.g. `search` would otherwise collide and the gateway
 * would reject EVERY request. [execute] splits the advertised name on `__` to
 * route back to the server; [ClientEntry.executeRequestOnce] maps it to the
 * raw MCP tool name. Server tool names that themselves contain `__` are
 * sanitized to `_` at advertisement time (the raw name is preserved for
 * execution), so the concatenation always stays unambiguous and gateway-safe.
 */
class McpToolProvider(
    configs: List<McpServerConfig>,
    private val namePrefix: String = "",
) : ToolProvider, AutoCloseable {

    // built once, never mutated afterwards: safe for the concurrent reads
    // from chat runs, and keeps the config-list advertisement order
    private val entries: Map<String, ClientEntry> = buildMap {
        require(
            namePrefix.isEmpty()
                    || (namePrefix.matches(TOOL_NAME_REGEX) && !namePrefix.contains("__"))
        ) {
            "MCP tool provider name prefix '$namePrefix' is invalid: tool names are prefixed with it, " +
                    "so only [a-zA-Z0-9_-] is allowed and it must not contain the '__' separator"
        }
        for (config in configs) {
            val entry = ClientEntry(config, namePrefix)
            require(!containsKey(entry.serverName)) {
                "MCP tool provider server name '${entry.serverName}' is duplicated"
            }
            put(config.name, entry)
        }
    }

    init {
        // eager connect: a server that cannot be reached fails startup. On
        // failure, close the already-connected entries so no client is leaked.
        val connected = mutableListOf<ClientEntry>()
        try {
            runBlocking {
                entries.values.forEach { entry ->
                    entry.getConnectedClient()
                    connected += entry
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            connected.forEach { it.dropConnection() }
            throw t
        }
    }

    override suspend fun specifications(): List<ToolSpecification> {
        val advertised = mutableListOf<ToolSpecification>()
        val nameSet = mutableSetOf<String>()
        for (entry in entries.values) {
            entry.listTools().forEach {
                require(!nameSet.contains(it.name())) {
                    "MCP tool provider tool name '${it.name()}' is duplicated"
                }
                nameSet.add(it.name())
                advertised.add(it)
            }
        }
        return advertised
    }

    override suspend fun execute(request: ToolExecutionRequest): ChatMessagePart.ToolResult {
        val advertisedName = request.name()
        // the advertised name is `prefix__serverName__toolName` (or
        // `serverName__toolName` without a prefix): none of the parts can
        // contain `__` (prefix and config names are validated, tool names are
        // sanitized in specifications), so the split is unambiguous
        val parts = advertisedName.split("__")
        if (namePrefix.isBlank()) {
            if (parts.size != 2)
                return errorResult(request.id(), advertisedName, "invalid tool name")
        } else {
            if (parts.size != 3)
                return errorResult(request.id(), advertisedName, "invalid tool name")
        }
        val serverName = parts[parts.size - 2]
        val entry = entries[serverName] ?: return errorResult(
            request.id(), advertisedName,
            "tool '$advertisedName' is not advertised by any configured MCP server."
        )
        // one retry: a transport failure drops the connection (the reconnect
        // itself retries up to `reconnectAttempts` times) and re-executes the
        // call once on the fresh connection. If the server stays down, the
        // reconnect throws McpTransportException, failing the run.
        repeat(2) {
            return try {
                entry.executeRequestOnce(request, advertisedName)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Error) {
                throw e
            } catch (e: ToolExecutionException) {
                if (e.isTransportFailure()) {
                    logger.warn { "MCP server ${entry.serverName} has transport failure, retry..." }
                    entry.dropConnection()
                    return@repeat // retry
                } else {
                    errorResult(
                        request.id(), advertisedName,
                        e.message ?: "the tool call failed"
                    )
                }
            } catch (e: ToolArgumentsException) {
                errorResult(
                    request.id(),
                    advertisedName,
                    e.message ?: "the tool rejected the arguments"
                )
            } catch (e: McpException) {
                errorResult(request.id(), advertisedName, e.message ?: "MCP protocol error")
            } catch (e: RuntimeException) {
                // anything else escaping the client, e.g. a malformed response
                // ("Result contains neither 'result' nor 'error' element"): a
                // server-side failure the model can be told about
                errorResult(request.id(), advertisedName, e.message ?: "the tool call failed")
            }
        }
        return errorResult(
            request.id(), advertisedName,
            "tool call failed with transport failure, will reconnect on next call"
        )
    }


    override fun close() {
        entries.values.forEach { entry ->
            entry.dropConnection()
        }
    }

    companion object {
        private val logger = KotlinLogging.logger("McpToolProvider")
        private val TOOL_NAME_REGEX = Regex("[a-zA-Z0-9_-]+")
    }
}
