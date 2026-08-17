package info.skyblond.daapu.mcp

import info.skyblond.daapu.agent.chat.ChatMessagePart
import info.skyblond.daapu.agent.tool.ToolCallRequest
import info.skyblond.daapu.agent.tool.ToolProvider
import info.skyblond.daapu.agent.tool.ToolSpec
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
 *   drops the cached client and answers an *error tool-result* — no in-turn
 *   retry or reconnect: the hand re-queries `specifications()` (`GET
 *   /api/hand/tools`) before EVERY LLM request, so the next round's
 *   advertisement is the sole reconnection point. It reconnects (the connect
 *   itself retries [McpServerConfig.reconnectAttempts] times); if the server
 *   stays down, [McpTransportException] fails the run (surfaces as a clear
 *   SSE `error` event).
 *   An execution timeout (the advertised budget) never retries: the callback
 *   route ([HandCallbackService]) has already answered an *error tool-result*
 *   and cancelled the execution. The connection is KEPT: a timeout is a
 *   tool-level failure, not a transport failure — the server is usually fine
 *   and just slow, and a fresh connection would only pay a full reconnect on
 *   the next call. A genuinely broken connection surfaces a transport failure
 *   on the next call, which drops it and lets the next tool-list refresh
 *   reconnect.
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

    override fun executionTimeoutSeconds(toolName: String): Long {
        // the advertised name is `namespace__toolName`: neither part can
        // contain `__` (namespaces are validated, tool names are sanitized
        // in specifications), so the split is unambiguous
        val parts = toolName.split("__")
        return if (parts.size == 2) {
            entries[parts[0]]?.timeout ?: 0
        } else {
            0
        }
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
        // No in-turn retry or reconnect: a transport failure drops the cached
        // client and answers an error tool-result — the hand re-queries the
        // tool list (specifications) before EVERY LLM request, so the next
        // round's advertisement reconnects (the connect itself retries up to
        // `reconnectAttempts` times) or throws McpTransportException, which
        // fails the run. A timeout never reports as a transport failure: the
        // callback route's `withTimeout` (HandCallbackService) has already
        // answered the isError result and cancelled this coroutine, so its
        // catch below only logs — the connection is kept (a slow tool is not
        // a broken transport).
        return try {
            entry.executeRequestOnce(request.id, request.args, advertisedName)
        } catch (e: TimeoutCancellationException) {
            // the execution budget (enforced by the callback route's
            // `withTimeout`, see HandCallbackService) expired: the run
            // already got its isError timeout answer and this coroutine is
            // cancelled. The connection is kept: the server is usually fine
            // and just slow, and a genuinely broken one surfaces a transport
            // failure on the next call, which drops it and lets the next
            // tool-list refresh reconnect.
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
                reportTransportFailure(entry, request, e)
            } else {
                errorResult(
                    request.id, advertisedName,
                    e.message ?: "the tool call failed"
                )
            }
        } catch (e: Exception) {
            // anything else escaping the client (connect refused, stdio
            // process death, malformed response): transport failure — drop
            // the connection and report it to the model; the next tool-list
            // refresh (specifications) reconnects or fails the run
            reportTransportFailure(entry, request, e)
        }
    }

    /**
     * A transport failure mid-execution: drop the cached client (no in-turn
     * retry or reconnect — the next tool-list refresh, `specifications`, is
     * the sole reconnection point) and answer an error tool-result the model
     * can react to.
     */
    private suspend fun reportTransportFailure(
        entry: ClientEntry,
        request: ToolCallRequest,
        cause: Throwable,
    ): ChatMessagePart.ToolResult {
        logger.warn(cause) { "MCP server ${entry.namespace} has transport failure, dropping connection" }
        entry.dropConnection()
        return errorResult(
            request.id, request.name,
            TRANSPORT_FAILURE_MESSAGE
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
