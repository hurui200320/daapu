package info.skyblond.daapu.mcp

import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.data.message.*
import dev.langchain4j.exception.ToolArgumentsException
import dev.langchain4j.exception.ToolExecutionException
import dev.langchain4j.mcp.client.DefaultMcpClient
import dev.langchain4j.mcp.client.McpClient
import dev.langchain4j.mcp.client.McpException
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport
import info.skyblond.daapu.McpServerConfig
import info.skyblond.daapu.McpTransportType
import info.skyblond.daapu.agent.lc4j.tool.ToolProvider
import info.skyblond.daapu.chat.AttachmentContent
import info.skyblond.daapu.chat.AttachmentKind
import info.skyblond.daapu.chat.ChatMessagePart
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Duration
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger("McpToolProvider")

/**
 * The MCP-backed [ToolProvider] (#8): one [McpClient] per configured server
 * (hardcoded in `Main.kt`, see [McpServerConfig]), all tools advertised to
 * every chat run ("one global tool set" for the PoC).
 *
 * Lifecycle (per the #3 spike conclusions):
 * - Clients are cached long-lived, keyed by server name, and connected
 *   LAZILY on the first [specifications] call — per-request chat runs share
 *   them exactly like they share the LLM executor; the initialize handshake
 *   (~0.5–11s) is paid once per server, never per run. `listTools` is cached
 *   client-side (default), so per-round advertisement is a local lookup.
 * - A server that fails to connect is skipped with a warning: its tools are
 *   not advertised, and other servers (and chats that don't need them) are
 *   unaffected. Failed connects are retried on later runs, at most once per
 *   [connectRetryIntervalMs], so a dead server can't slow every run with a
 *   connect timeout forever.
 * - A transport failure mid-execution (connect refused, stdio process died)
 *   drops the cached client — the next run reconnects — and fails the run
 *   with [McpTransportException] (surfaces as a clear SSE `error` event).
 *   Tool-level failures (server-side `isError`, bad arguments) return an
 *   *error tool-result* instead, so the model sees the error text and can
 *   react in the next round.
 * - [close] closes every client (called on JVM shutdown).
 *
 * Tool names are advertised as `"{serverName}_{toolName}"`: OpenAI-style
 * gateways require unique tool names in the `tools` array, so two servers
 * exporting e.g. `search` would otherwise collide and the gateway would
 * reject EVERY request. [execute] maps the advertised name back to the raw
 * MCP tool name. (MCP tool names are spec-constrained to `[a-zA-Z0-9_-]`,
 * as are validated config names, so the concatenation is always gateway-safe.)
 */
class McpToolProvider(
    private val configs: List<McpServerConfig>,
    private val connectRetryIntervalMs: Long = DEFAULT_CONNECT_RETRY_INTERVAL_MS,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : ToolProvider, AutoCloseable {

    /** Connected clients by server name. */
    private val clients = ConcurrentHashMap<String, McpClient>()

    /** Last failed-connect timestamp per server name (cooldown bookkeeping). */
    private val lastConnectFailureMs = ConcurrentHashMap<String, Long>()

    /** Serializes the lazy connect per server (a lost race would leak a process). */
    private val connectLocks = ConcurrentHashMap<String, Mutex>()

    /** Advertised tool name -> (server name, raw MCP tool name). */
    private val toolOwners = ConcurrentHashMap<String, Pair<String, String>>()

    override suspend fun specifications(): List<ToolSpecification> {
        val advertised = mutableListOf<ToolSpecification>()
        for (config in configs) {
            val client = connectedClient(config) ?: continue
            val specs = try {
                withContext(Dispatchers.IO) { client.listTools() }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // listTools failing between runs means the server died after
                // connect (e.g. stdio process): drop the client so the next
                // run reconnects, and skip this server's tools this round
                dropClient(config.name, t, "listing tools")
                continue
            }
            specs.forEach { spec ->
                val owner = config.name to spec.name()
                val advertisedName = "${config.name}_${spec.name()}"
                toolOwners[advertisedName] = owner
                advertised += spec.toBuilder().name(advertisedName).build()
            }
        }
        return advertised
    }

    override suspend fun execute(request: ToolExecutionRequest): ChatMessagePart.ToolResult {
        val advertisedName = request.name()
        val (serverName, rawName) = toolOwners[advertisedName] ?: return errorResult(
            request.id(), advertisedName,
            "tool '$advertisedName' is not advertised by any configured MCP server."
        )
        val client = clients[serverName] ?: return errorResult(
            request.id(), advertisedName,
            "MCP server '$serverName' is not connected " +
                    "(it may have failed earlier; the next chat run will try to reconnect)."

        )
        return try {
            val result = withContext(Dispatchers.IO) {
                client.executeTool(request.toBuilder().name(rawName).build())
            }
            ChatMessagePart.ToolResult(
                id = request.id(),
                tool = advertisedName,
                // blank results become a placeholder: a stored tool message
                // with empty content is a risk with strict providers
                parts = result.resultContents()?.mapNotNull {
                    when (it) {
                        // blank text contents are dropped: an empty text part
                        // stores nothing useful and may trip strict providers
                        is TextContent -> ChatMessagePart.Text(it.text())
                            .takeIf { text -> text.text.isNotBlank() }

                        is ImageContent -> ChatMessagePart.Attachment(
                            kind = AttachmentKind.Image,
                            content = AttachmentContent.Base64(it.image().base64Data()),
                            mimeType = it.image().mimeType()
                        )

                        is VideoContent -> ChatMessagePart.Attachment(
                            kind = AttachmentKind.Video,
                            content = AttachmentContent.Base64(it.video().base64Data()),
                            mimeType = it.video().mimeType()
                        )

                        is AudioContent -> ChatMessagePart.Attachment(
                            kind = AttachmentKind.Audio,
                            content = AttachmentContent.Base64(it.audio().base64Data()),
                            mimeType = it.audio().mimeType()
                        )

                        is PdfFileContent -> ChatMessagePart.Attachment(
                            kind = AttachmentKind.File,
                            content = AttachmentContent.Base64(it.pdfFile().base64Data()),
                            mimeType = it.pdfFile().mimeType()
                        )

                        else -> error("Unknown content (${it.javaClass}): $it")
                    }
                    // no contents at all, or nothing but blank text: placeholder
                }?.takeIf { it.isNotEmpty() } ?: listOf(
                    ChatMessagePart.Text("(the tool returned no text content)")
                ),
                isError = result.isError,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: ToolExecutionException) {
            if (e.isTransportFailure()) {
                // the transport itself failed (connection refused, stdio
                // process died): drop the client so the next run reconnects,
                // and fail the run — the model cannot react to a dead transport
                dropClient(serverName, e, "executing tool '$advertisedName'")
                throw McpTransportException(
                    "MCP server '$serverName' failed while executing tool '$advertisedName': ${e.message}",
                    e,
                )
            }
            // server-side failure (isError result, JSON-RPC error response):
            // the model gets the error text and can react in the next round
            errorResult(request.id(), advertisedName, e.message ?: "the tool failed")
        } catch (e: ToolArgumentsException) {
            // the model's arguments were rejected (parse failure or -32602):
            // the model can fix them on the next attempt
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

    private fun errorResult(id: String, name: String, errorMessage: String) =
        ChatMessagePart.ToolResult(
            id = id, tool = name,
            parts = listOf(
                ChatMessagePart.Text(
                    "Error: $errorMessage"
                )
            ),
            isError = true,
        )


    /**
     * Whether a [ToolExecutionException] means the MCP transport itself died
     * (fail the run) rather than a tool-level failure (error tool-result).
     *
     * The langchain4j-mcp client builds BOTH kinds as `ToolExecutionException`:
     * - a server-side `isError` result or JSON-RPC error response is thrown
     *   message-only — its cause is a `RuntimeException` wrapping the very
     *   same text;
     * - a transport failure (`ExecutionException` from the transport) keeps
     *   the underlying exception as the cause.
     * The stdio process-death messages are named transport failures too, but
     * arrive as `IllegalStateException`s whose message equals the wrapper's,
     * so they are matched explicitly (the #3 spike's transport-level list:
     * connect refused, "Process has exited" / "Process is not alive").
     *
     * Transport failures surfacing from the HTTP transport's
     * `CompletableFuture` can arrive as `CompletionException` (e.g. the body
     * subscriber throwing mid-stream on a malformed SSE payload). It too
     * carries the wrapper's message, so it must be matched explicitly BEFORE
     * the message-equality test below, which would otherwise misclassify it
     * as a server-side error: the run would not fail, the dead client would
     * not be dropped, and every later call would repeat as an error tool-result.
     * Server-side errors never pass through `CompletionException` — the client
     * throws them synchronously from the tool execution helper.
     */
    internal fun ToolExecutionException.isTransportFailure(): Boolean {
        val cause = cause ?: return false
        if (cause is CompletionException) return true
        if (cause is IllegalStateException && cause.message?.let {
                it.contains("Process has exited") || it.contains("Process is not alive")
            } == true) {
            return true
        }
        if (cause is RuntimeException && cause.message == message) return false
        return true
    }

    /**
     * The cached client for [config], connecting lazily. Returns null when the
     * server is unavailable (connect failure, or still inside the retry
     * cooldown after one) — its tools are then simply not advertised.
     */
    private suspend fun connectedClient(config: McpServerConfig): McpClient? {
        clients[config.name]?.let { return it }
        val lastFailure = lastConnectFailureMs[config.name]
        if (lastFailure != null && nowMillis() - lastFailure < connectRetryIntervalMs) return null
        val mutex = connectLocks.computeIfAbsent(config.name) { Mutex() }
        return mutex.withLock {
            clients[config.name]?.let { return@withLock it }
            try {
                val client = withContext(Dispatchers.IO) { buildClient(config) }
                clients[config.name] = client
                client
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                lastConnectFailureMs[config.name] = nowMillis()
                logger.warn(t) {
                    "MCP server '${config.name}' is unavailable; its tools are not advertised. " +
                            "A later chat run will retry."
                }
                null
            }
        }
    }

    private fun buildClient(config: McpServerConfig): McpClient {
        val transport = when (config.type) {
            McpTransportType.Http -> StreamableHttpMcpTransport.builder()
                .url(config.url!!)
                .apply { if (config.headers.isNotEmpty()) customHeaders(config.headers) }
                .build()

            McpTransportType.Stdio -> StdioMcpTransport.builder()
                .command(config.command)
                .apply { if (config.environment.isNotEmpty()) environment(config.environment) }
                .build()
        }
        val builder = DefaultMcpClient.builder()
            .key(config.name)
            .transport(transport)
        config.initializationTimeoutSeconds?.let {
            builder.initializationTimeout(
                Duration.ofSeconds(
                    it
                )
            )
        }
        config.toolExecutionTimeoutSeconds?.let { builder.toolExecutionTimeout(Duration.ofSeconds(it)) }
        return builder.build()
    }

    private fun dropClient(serverName: String, cause: Throwable, action: String) {
        val client = clients.remove(serverName)
        runCatching { client?.close() }
        logger.warn(cause) { "MCP server '$serverName' failed while $action; dropping the client. A later chat run will reconnect." }
    }

    override fun close() {
        clients.values.forEach { client ->
            runCatching { client.close() }
                .onFailure { logger.warn(it) { "Failed to close an MCP client" } }
        }
        clients.clear()
    }

    companion object {
        const val DEFAULT_CONNECT_RETRY_INTERVAL_MS = 30_000L
    }
}

/**
 * The MCP transport itself failed (connect refused, stdio process died, ...)
 * while executing a tool. Thrown by [McpToolProvider.execute] instead of
 * returning an error tool-result — the model cannot react to a dead
 * transport — so the chat run fails and surfaces a clear SSE `error` event.
 * The provider drops the cached client first, so a later run reconnects.
 */
class McpTransportException(message: String, cause: Throwable) : Exception(message, cause)
