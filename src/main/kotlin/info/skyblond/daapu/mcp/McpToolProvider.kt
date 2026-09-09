package info.skyblond.daapu.mcp

import info.skyblond.daapu.agent.chat.ChatMessagePart
import info.skyblond.daapu.agent.tool.ToolCallRequest
import info.skyblond.daapu.agent.tool.ToolProvider
import info.skyblond.daapu.agent.tool.ToolSpec
import info.skyblond.daapu.agent.tool.errorResult
import info.skyblond.daapu.agent.tool.splitStrictNsToolName
import info.skyblond.daapu.config.McpConfig
import info.skyblond.daapu.config.McpProxyConfig
import info.skyblond.daapu.config.McpServerConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import io.modelcontextprotocol.kotlin.sdk.types.McpException
import io.modelcontextprotocol.kotlin.sdk.types.RPCError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext

/**
 * The model-visible answer for a transport failure: the connection is
 * dropped, reconnection happens at the next tool-list refresh.
 */
internal const val TRANSPORT_FAILURE_MESSAGE =
    "tool call failed with transport failure, will reconnect on next call"

/**
 * The MCP-backed [ToolProvider] (#8): one [ClientEntry] per configured server
 * (`config.jsonc` → `mcp.customs` keyed by namespace plus the dedicated
 * `mcp.exa`, merged by [McpConfig.allServers] — see [McpServerConfig]), all
 * tools advertised to every chat run ("one global tool set" for the PoC).
 *
 * Lifecycle:
 * - Clients are cached long-lived per server, and connected EAGERLY at
 *   startup via [connectAll] (NOT in the constructor: the constructor only
 *   builds the entries, so it never blocks a thread — the DI container
 *   calls [connectAll] once at boot, where a server that cannot be
 *   reached aborts startup with [McpTransportException], fail fast — a
 *   broken entry blocks the app rather than silently degrading every chat
 *   run). The initialize handshake (~0.5–11s) is paid once per server, in
 *   parallel across servers, never per run. `listTools` is cached
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
    configs: Map<String, McpServerConfig>,
    proxy: McpProxyConfig? = null,
) : ToolProvider, AutoCloseable {

    // built once, never mutated afterwards: safe for the concurrent reads
    // from chat runs, and keeps the config-map's advertisement order
    private val entries: Map<String, ClientEntry> = configs.mapValues { (namespace, config) ->
        ClientEntry(namespace, config, proxy)
    }

    /**
     * Eager-connect every server, in parallel: a server that cannot be
     * reached fails startup (see the class KDoc). Call once at boot, from
     * a coroutine — never from the constructor, which must not block. On
     * failure every entry is dropped so no client is leaked.
     *
     * A [supervisorScope] (not a plain `coroutineScope`): one server's
     * failure must not cancel a sibling's in-flight connect — a sibling
     * cancelled after spawning its stdio process (or its HTTP session) but
     * before publishing it into `clientRef` would orphan it (in neither
     * `clientRef` nor the failure's `connected` set, so neither the drop
     * nor a later `close()` destroys it). Every child runs to completion
     * here; the first failure is rethrown after all entries are dropped.
     * An outer cancellation also drops every entry (in [NonCancellable] —
     * the dropping itself must not be cancelled) before propagating, so a
     * cancelled boot never strands a half-connected client either.
     */
    suspend fun connectAll() {
        val failures = try {
            supervisorScope {
                entries.values.map { entry ->
                    async {
                        try {
                            logger.info { "Initializing MCP server ${entry.namespace}" }
                            entry.getConnectedClient()
                            logger.info { "MCP server ${entry.namespace} connected" }
                            null
                        } catch (e: CancellationException) {
                            throw e
                        } catch (t: Throwable) {
                            entry.namespace to t
                        }
                    }
                }.awaitAll().filterNotNull()
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                entries.values.forEach { runCatching { it.dropConnection() } }
            }
            throw e
        }
        val failure = failures.firstOrNull() ?: return
        failures.drop(1).forEach { (namespace, cause) ->
            logger.error(cause) { "MCP server $namespace also failed to connect" }
        }
        withContext(NonCancellable) {
            entries.values.forEach { it.dropConnection() }
        }
        failures.drop(1).forEach { failure.second.addSuppressed(it.second) }
        throw failure.second
    }

    override fun namespaces(): Set<String> = entries.keys

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

    override fun executionTimeoutSeconds(toolName: String): Long =
        splitStrictNsToolName(toolName)?.let { (namespace, _) ->
            entries[namespace]?.timeoutSeconds
        } ?: 0

    override suspend fun execute(request: ToolCallRequest): ChatMessagePart.ToolResult {
        val advertisedName = request.name
        val namespace = splitStrictNsToolName(advertisedName)?.first
            ?: return errorResult(request.id, advertisedName, "invalid tool name")
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
            // the execution budget expired — it is enforced ONCE, by the
            // callback route's `withTimeout` (HandCallbackService, budget
            // from this entry's toolExecutionTimeoutSeconds): the route
            // already answered the isError timeout result and cancelled this
            // coroutine, so this catch only logs before rethrowing into the
            // route's handler. The connection is kept: the server is usually
            // fine and just slow, and a genuinely broken one surfaces a
            // transport failure on the next call, which drops it and lets
            // the next tool-list refresh reconnect.
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
