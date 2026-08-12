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
import info.skyblond.daapu.chat.AttachmentContent
import info.skyblond.daapu.chat.AttachmentKind
import info.skyblond.daapu.chat.ChatMessagePart
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * One configured MCP server inside [McpToolProvider]: owns the single cached
 * [McpClient], the advertised tool-name mapping, and the connection lifecycle
 * (connect with retries, drop on transport failure).
 *
 * Connection lifecycle:
 * - [getConnectedClient] builds and connects a client on demand (the
 *   provider connects eagerly at construction; a transport failure later in a
 *   run drops the client so the next [getConnectedClient] call reconnects).
 *   The connect itself retries up to [McpServerConfig.reconnectAttempts]
 *   times, waiting [McpServerConfig.reconnectDelayMs] between attempts, then
 *   throws [McpTransportException].
 * - [dropConnection] discards and closes the current client (called on
 *   transport failure and on provider close). It busy-waits on the connect
 *   lock instead of blocking it, so a caller from a non-suspend context
 *   (`close()`) is safe while a concurrent connect is in progress.
 *
 * Advertised names are `{prefix}__{serverName}__{toolName}` (or
 * `{serverName}__{toolName}` without a provider prefix): `__` is the
 * separator, so server tool names containing it are sanitized to `_`
 * ([listTools], the raw name is preserved for [executeRequestOnce]). The
 * mapping is refreshed on every [listTools] pass; per-pass collisions are
 * rejected loudly rather than silently overwriting an earlier tool.
 */
class ClientEntry(
    private val config: McpServerConfig,
    // the caller should ensure this is valid
    private val toolNamePrefix: String,
) {
    private val clientRef: AtomicReference<McpClient?> = AtomicReference(null)
    private val connectLock: Mutex = Mutex()
    private val toolNameMapping: ConcurrentHashMap<String, String> = ConcurrentHashMap()

    init {
        config.validate()
    }

    val serverName: String = config.name

    private fun buildClient(): McpClient {
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
            .key(serverName)
            .transport(transport)
        config.initializationTimeoutSeconds?.let {
            builder.initializationTimeout(Duration.ofSeconds(it))
        }
        config.toolExecutionTimeoutSeconds?.let {
            builder.toolExecutionTimeout(Duration.ofSeconds(it))
        }
        return builder.build()
    }

    /**
     * Get the client if connected, otherwise construct a client and connect.
     */
    suspend fun getConnectedClient(): McpClient {
        return connectLock.withLock {
            clientRef.get()?.let { return@withLock it }
            var lastFailure: Throwable? = null
            for (attempt in 1..config.reconnectAttempts) {
                try {
                    val client = withContext(Dispatchers.IO) { buildClient() }
                    clientRef.set(client)
                    return@withLock client
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    lastFailure = t
                    if (attempt < config.reconnectAttempts)
                        delay(config.reconnectDelayMs)
                }
            }
            throw McpTransportException(
                "MCP server '${serverName}' is unavailable after ${config.reconnectAttempts} " +
                        "reconnect attempts: ${lastFailure?.message}",
                lastFailure!!,
            )
        }
    }

    /**
     * Drop the current connection.
     * */
    fun dropConnection() {
        while (!connectLock.tryLock()) {
            // nop
        }
        val client = clientRef.getAndSet(null)
        runCatching { client?.close() }
        connectLock.unlock()
    }

    private fun advertisedName(serverName: String, toolName: String): String =
        listOfNotNull(
            toolNamePrefix.takeIf { it.isNotEmpty() },
            serverName,
            toolName
        ).joinToString("__")

    suspend fun listTools(): List<ToolSpecification> {
        val specs = try {
            withContext(Dispatchers.IO) { getConnectedClient().listTools() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Error) {
            throw e
        } catch (_: Throwable) {
            // current client is broken, drop current connection and try again
            dropConnection()
            withContext(Dispatchers.IO) { getConnectedClient().listTools() }
        }
        // per-pass set: the persistent toolNameMapping must not decide
        // collisions, or re-advertising the same tool on a later run
        // would wrongly suffix its name
        val seen = mutableSetOf<String>()

        return specs.map { spec ->
            val rawName = spec.name()
            // `__` is the advertised-name separator: a server tool name
            // containing it is renamed so the concatenation stays
            // unambiguous (the raw name is preserved for execution)
            var sanitized = rawName
            while (sanitized.contains("__")) {
                sanitized = sanitized.replace("__", "_")
            }

            if (sanitized != rawName) {
                logger.warn {
                    "MCP server '${serverName}' tool '$rawName' advertising it as '$sanitized'"
                }
            }
            val name = advertisedName(serverName, sanitized)
            require(seen.add(name)) {
                "Tool name $name already exists in MCP server '${serverName}'"
            }
            toolNameMapping[name] = rawName
            spec.toBuilder().name(name).build()
        }
    }

    suspend fun executeRequestOnce(
        request: ToolExecutionRequest,
        advertisedName: String,
    ): ChatMessagePart.ToolResult {
        val rawName = toolNameMapping[advertisedName] ?: return errorResult(
            request.id(), advertisedName,
            "Tool name $advertisedName not found in MCP server '${serverName}'"
        )

        val result = withContext(Dispatchers.IO) {
            getConnectedClient().executeTool(
                request.toBuilder()
                    .name(rawName)
                    .build()
            )
        }

        return ChatMessagePart.ToolResult(
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
    }

    companion object {
        private val logger = KotlinLogging.logger { }
    }
}
