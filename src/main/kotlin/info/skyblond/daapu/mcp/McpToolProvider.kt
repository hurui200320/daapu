package info.skyblond.daapu.mcp

import info.skyblond.daapu.agent.tool.ToolCallRequest
import info.skyblond.daapu.agent.tool.ToolProvider
import info.skyblond.daapu.agent.tool.ToolSpec
import info.skyblond.daapu.agent.chat.ChatMessagePart
import info.skyblond.daapu.config.McpServerConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import io.modelcontextprotocol.kotlin.sdk.types.McpException
import io.modelcontextprotocol.kotlin.sdk.types.RPCError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking

/**
 * The MCP-backed [ToolProvider] (#8): one [ClientEntry] per configured server
 * (`config.jsonc` → `mcp.servers`, see [McpServerConfig]), all tools
 * advertised to every chat run ("one global tool set" for the PoC).
 *
 * Lifecycle:
 * - Clients are cached long-lived per server, and connected EAGERLY at
 *   construction: a server that cannot be reached aborts startup with
 *   [McpTransportException] (fail fast — a broken entry blocks the app rather
 *   than silently degrading every chat run). The initialize handshake
 *   (~0.5–11s) is paid once per server, never per run. `listTools` is cached
 *   client-side (default), so per-round advertisement is a local lookup.
 * - A transport failure mid-execution (connect refused, stdio process died)
 *   drops the cached client and retries the call once (the reconnect itself
 *   retries [McpServerConfig.reconnectAttempts] times).
 *   If the reconnect cannot restore the connection, [McpTransportException]
 *   fails the run (surfaces as a clear SSE `error` event); if the call fails
 *   twice but the server stays up, a generic *error tool-result* is returned
 *   so the model sees the failure and can react in the next round.
 *   An execution timeout (the advertised budget) never retries: the callback
 *   route ([HandCallbackService]) has already answered an *error tool-result*
 *   and cancelled the execution. The connection is KEPT: a timeout is a
 *   tool-level failure, not a transport failure — the server is usually fine
 *   and just slow, and a fresh connection would only pay a full reconnect on
 *   the next call. A genuinely broken connection surfaces a transport failure
 *   on the next call, which drops and reconnects then.
 *   Tool-level failures (server-side `isError`, bad arguments) return an
 *   *error tool-result* without touching the connection.
 * - [close] closes every client (called on JVM shutdown).
 *
 * Tool names are advertised as `"{namespace}__{toolName}"`:
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
) : ToolProvider, AutoCloseable {

    // built once, never mutated afterwards: safe for the concurrent reads
    // from chat runs, and keeps the config-list advertisement order
    private val entries: Map<String, ClientEntry> = buildMap {
        for (config in configs) {
            val entry = ClientEntry(config)
            require(!containsKey(entry.namespace)) {
                "MCP tool provider namespace '${entry.namespace}' is duplicated"
            }
            put(config.namespace, entry)
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
            runBlocking { connected.forEach { it.dropConnection() } }
            throw t
        }
    }

    override suspend fun specifications(): List<ToolSpec> {
        val advertised = mutableListOf<ToolSpec>()
        val nameSet = mutableSetOf<String>()
        for (entry in entries.values) {
            entry.listTools().forEach {
                require(!nameSet.contains(it.name)) {
                    "MCP tool provider tool name '${it.name}' is duplicated"
                }
                nameSet.add(it.name)
                advertised.add(it)
            }
        }
        return advertised
    }

    override suspend fun execute(request: ToolCallRequest): ChatMessagePart.ToolResult {
        val advertisedName = request.name
        // the advertised name is `namespace__toolName`: neither part can
        // contain `__` (namespaces are validated, tool names are sanitized
        // in specifications), so the split is unambiguous
        val parts = advertisedName.split("__")
        if (parts.size != 2)
            return errorResult(request.id, advertisedName, "invalid tool name")
        val namespace = parts[0]
        val entry = entries[namespace] ?: return errorResult(
            request.id, advertisedName,
            "tool '$advertisedName' is not advertised by any configured MCP server."
        )
        // one retry: a transport failure drops the connection (the reconnection
        // itself retries up to `reconnectAttempts` times) and re-executes the
        // call once on the fresh connection. If the server stays down, the
        // reconnection throws McpTransportException, failing the run. A timeout
        // never retries: the callback route's `withTimeout` (HandCallbackService)
        // has already answered the isError result and cancelled this coroutine,
        // so its catch below only logs — the connection is kept (a slow tool is
        // not a broken transport).
        repeat(2) {
            return try {
                entry.executeRequestOnce(request.id, request.args, advertisedName)
            } catch (e: TimeoutCancellationException) {
                // the execution budget (enforced by the callback route's
                // `withTimeout`, see HandCallbackService) expired: the run
                // already got its isError timeout answer and this coroutine is
                // cancelled, so a retry could never complete. The connection is
                // kept: the server is usually fine and just slow, and a
                // genuinely broken one surfaces a transport failure on the next
                // call, which drops and reconnects then.
                logger.warn { "MCP server ${entry.namespace} timed out" }
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Error) {
                throw e
            } catch (e: McpException) {
                // the SDK wraps transport failures as McpExceptions too:
                // "Error while sending message: ..." carries the real cause,
                // and CONNECTION_CLOSED / REQUEST_TIMEOUT mark a dead or
                // unready transport. Anything else is a server-answered
                // protocol/tool-level error (bad arguments, server-side
                // failure): model-visible, the connection survives.
                if (e.cause != null ||
                    e.code == RPCError.ErrorCode.CONNECTION_CLOSED ||
                    e.code == RPCError.ErrorCode.REQUEST_TIMEOUT
                ) {
                    logger.warn(e) { "MCP server ${entry.namespace} has transport failure, retry..." }
                    entry.dropConnection()
                    return@repeat // retry
                }
                errorResult(
                    request.id, advertisedName,
                    e.message ?: "the tool call failed"
                )
            } catch (e: McpTransportException) {
                // the reconnect itself failed: the server stays down, fail
                // the run instead of answering an error result forever
                throw e
            } catch (e: Exception) {
                // anything else escaping the client (connect refused, stdio
                // process death, malformed response): transport failure —
                // drop the connection and retry the call once
                logger.warn(e) { "MCP server ${entry.namespace} has transport failure, retry..." }
                entry.dropConnection()
                return@repeat // retry
            }
        }
        return errorResult(
            request.id, advertisedName,
            "tool call failed with transport failure, will reconnect on next call"
        )
    }


    override fun close() {
        entries.values.forEach { entry ->
            entry.close()
        }
    }

    companion object {
        private val logger = KotlinLogging.logger("McpToolProvider")
    }
}
