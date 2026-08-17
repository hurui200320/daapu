package info.skyblond.daapu.mcp

import info.skyblond.daapu.agent.chat.AttachmentContent
import info.skyblond.daapu.agent.chat.AttachmentKind
import info.skyblond.daapu.agent.chat.ChatMessagePart
import info.skyblond.daapu.agent.tool.ToolSpec
import info.skyblond.daapu.config.McpServerConfig
import info.skyblond.daapu.config.McpTransportType
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.modelcontextprotocol.kotlin.sdk.LIB_VERSION
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/** The connected client plus the stdio subprocess it reads from. */
internal class ConnectedClient(val client: Client, val process: Process?)

/**
 * One configured MCP server inside [McpToolProvider]: owns the single cached
 * [Client] (official MCP Kotlin SDK, `io.modelcontextprotocol`), the
 * advertised tool-name mapping, and the connection lifecycle (connect with
 * retries, drop on transport failure).
 *
 * Connection lifecycle:
 * - [getConnectedClient] builds and connects a client on demand (the
 *   provider connects eagerly at construction; a transport failure later in a
 *   run drops the client so the next [getConnectedClient] call reconnects).
 *   The connect itself retries up to [McpServerConfig.reconnectAttempts]
 *   times, waiting [McpServerConfig.reconnectDelayMs] between attempts, then
 *   throws [McpTransportException].
 * - [dropConnection] discards and closes the current client (and the stdio
 *   subprocess it owns) — called on transport failure and on provider close.
 *   It busy-waits on the connect lock instead of blocking it, so a caller
 *   from a non-suspend context (`close()`) is safe while a concurrent
 *   connect is in progress.
 *
 * Advertised names are `{namespace}__{toolName}`: `__` is the separator, so
 * server tool names containing it are sanitized to `_` ([listTools], the raw
 * name is preserved for [executeRequestOnce]). The mapping is refreshed on
 * every [listTools] pass; per-pass collisions are rejected loudly rather
 * than silently overwriting an earlier tool.
 */
class ClientEntry(
    private val config: McpServerConfig,
) {
    private val clientRef: AtomicReference<ConnectedClient?> = AtomicReference(null)
    private val connectLock: Mutex = Mutex()
    private val toolNameMapping: ConcurrentHashMap<String, String> = ConcurrentHashMap()

    // one HTTP engine per entry: transports in the official SDK take a
    // ktor HttpClient (auth headers go through the per-request builder)
    private val httpClient = HttpClient(CIO)

    val namespace: String = config.namespace
    val timeout: Long = config.toolExecutionTimeoutSeconds

    init {
        config.validate()
    }

    private suspend fun buildConnectedClient(): ConnectedClient = when (config.type) {
        McpTransportType.Http -> ConnectedClient(
            client = config.initializationTimeoutSeconds?.let { seconds ->
                withTimeout(seconds * 1_000L) {
                    httpClient.mcpStreamableHttp(
                        config.url!!,
                        requestBuilder = {
                            config.headers.forEach { (name, value) -> header(name, value) }
                        },
                    )
                }
            } ?: httpClient.mcpStreamableHttp(
                config.url!!,
                requestBuilder = {
                    config.headers.forEach { (name, value) -> header(name, value) }
                },
            ),
            process = null,
        )

        McpTransportType.Stdio -> {
            val process = withContext(Dispatchers.IO) {
                ProcessBuilder(config.command)
                    .apply { environment().putAll(config.environment) }
                    .start()
            }
            val transport = StdioClientTransport(
                input = process.inputStream.asSource().buffered(),
                output = process.outputStream.asSink().buffered(),
                error = process.errorStream.asSource().buffered(),
            )
            val client = Client(
                clientInfo = Implementation(name = "daapu", version = LIB_VERSION),
            )
            if (config.initializationTimeoutSeconds != null) {
                withTimeout(config.initializationTimeoutSeconds * 1_000L) {
                    client.connect(transport)
                }
            } else {
                client.connect(transport)
            }
            ConnectedClient(client, process)
        }
    }

    /**
     * Get the client if connected, otherwise construct a client and connect.
     */
    internal suspend fun getConnectedClient(): ConnectedClient {
        return connectLock.withLock {
            clientRef.get()?.let { return@withLock it }
            var lastFailure: Throwable? = null
            for (attempt in 1..config.reconnectAttempts) {
                try {
                    val connected = withContext(Dispatchers.IO) { buildConnectedClient() }
                    clientRef.set(connected)
                    return@withLock connected
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    lastFailure = t
                    if (attempt < config.reconnectAttempts)
                        delay(config.reconnectDelayMs)
                }
            }
            throw McpTransportException(
                "MCP server '${namespace}' is unavailable after ${config.reconnectAttempts} " +
                        "reconnect attempts: ${lastFailure?.message}",
                lastFailure!!,
            )
        }
    }

    /**
     * Drop the current connection (closes the client and any stdio
     * subprocess). No-op when the client is already gone. The HTTP engine
     * survives: a transport failure only needs a fresh MCP session, not a
     * fresh engine.
     */
    suspend fun dropConnection() {
        connectLock.withLock {
            val connected = clientRef.getAndSet(null)
            runCatching { connected?.client?.close() }
            runCatching { connected?.process?.destroy() }
        }
    }

    /** Closes the connection and the HTTP engine (provider shutdown). */
    fun close() {
        kotlinx.coroutines.runBlocking { dropConnection() }
        runCatching { httpClient.close() }
    }

    private fun advertisedName(toolName: String): String = "${namespace}__$toolName"

    suspend fun listTools(): List<ToolSpec> {
        val tools = try {
            getConnectedClient().client.listTools().tools
        } catch (e: CancellationException) {
            throw e
        } catch (e: Error) {
            throw e
        } catch (_: Throwable) {
            // current client is broken, drop current connection and try again
            dropConnection()
            getConnectedClient().client.listTools().tools
        }
        // per-pass set: the persistent toolNameMapping must not decide
        // collisions, or re-advertising the same tool on a later run
        // would wrongly suffix its name
        val seen = mutableSetOf<String>()

        return tools.map { tool ->
            val rawName = tool.name
            // `__` is the advertised-name separator: a server tool name
            // containing it is renamed so the concatenation stays
            // unambiguous (the raw name is preserved for execution)
            var sanitized = rawName
            while (sanitized.contains("__")) {
                sanitized = sanitized.replace("__", "_")
            }

            if (sanitized != rawName) {
                logger.warn {
                    "MCP server '${namespace}' tool '$rawName' advertising it as '$sanitized'"
                }
            }
            val name = advertisedName(sanitized)
            require(seen.add(name)) {
                "Tool name $name already exists in MCP server '${namespace}'"
            }
            toolNameMapping[name] = rawName
            ToolSpec(
                name = name,
                description = tool.description.orEmpty(),
                schema = tool.toSchemaJson(),
            )
        }
    }

    suspend fun executeRequestOnce(
        id: String,
        arguments: JsonObject,
        advertisedName: String,
    ): ChatMessagePart.ToolResult {
        val rawName = toolNameMapping[advertisedName] ?: return errorResult(
            id, advertisedName,
            "Tool name $advertisedName not found in MCP server '${namespace}'"
        )

        val client = getConnectedClient().client
        val request = CallToolRequest(CallToolRequestParams(name = rawName, arguments = arguments))
        val seconds = config.toolExecutionTimeoutSeconds
        val result = if (seconds > 0) {
            withTimeout(seconds * 1_000L) { client.callTool(request) }
        } else {
            client.callTool(request)
        }

        return ChatMessagePart.ToolResult(
            id = id,
            tool = advertisedName,
            // blank results become a placeholder: a stored tool message
            // with empty content is a risk with strict providers
            parts = result.content.mapNotNull { it.toChatMessageContentPart() }
                .takeIf { it.isNotEmpty() }
                ?: listOf(ChatMessagePart.Text("(the tool returned no text content)")),
            isError = result.isError == true,
        )
    }

    /** The advertised JSON schema: `{type: object, properties, required, $defs}`. */
    private fun Tool.toSchemaJson(): JsonObject = buildJsonObject {
        put("type", "object")
        description?.let { put("description", it) }
        inputSchema.properties?.takeIf { it.isNotEmpty() }?.let { put("properties", it) }
        inputSchema.required?.takeIf { it.isNotEmpty() }?.let {
            put("required", buildJsonArray { it.forEach { name -> add(name) } })
        }
        inputSchema.defs?.takeIf { it.isNotEmpty() }?.let { put("\$defs", it) }
    }

    /**
     * Maps one MCP content block to a daapu content part. Blank text is
     * dropped (an empty text part stores nothing useful and may trip strict
     * providers); unsupported content types (resource links, nested tool
     * results) throw — the provider turns that into an error tool result the
     * model can react to.
     */
    private fun ContentBlock.toChatMessageContentPart(): ChatMessagePart.ContentPart? =
        when (this) {
            is TextContent -> ChatMessagePart.Text(text)
                .takeIf { it.text.isNotBlank() }

            is ImageContent -> ChatMessagePart.Attachment(
                kind = AttachmentKind.Image,
                content = AttachmentContent.Base64(data),
                mimeType = mimeType,
            )

            is AudioContent -> ChatMessagePart.Attachment(
                kind = AttachmentKind.Audio,
                content = AttachmentContent.Base64(data),
                mimeType = mimeType,
            )

            is EmbeddedResource -> when (val resource = resource) {
                is TextResourceContents -> ChatMessagePart.Text(resource.text)
                    .takeIf { it.text.isNotBlank() }

                is BlobResourceContents -> {
                    val mimeType = resource.mimeType
                    when {
                        mimeType == null ->
                            error("Unsupported embedded resource without a mime type (uri ${resource.uri})")

                        mimeType.startsWith("image/") -> ChatMessagePart.Attachment(
                            kind = AttachmentKind.Image,
                            content = AttachmentContent.Base64(resource.blob),
                            mimeType = mimeType,
                        )

                        mimeType.startsWith("video/") -> ChatMessagePart.Attachment(
                            kind = AttachmentKind.Video,
                            content = AttachmentContent.Base64(resource.blob),
                            mimeType = mimeType,
                        )

                        mimeType.startsWith("audio/") -> ChatMessagePart.Attachment(
                            kind = AttachmentKind.Audio,
                            content = AttachmentContent.Base64(resource.blob),
                            mimeType = mimeType,
                        )

                        mimeType == "application/pdf" -> ChatMessagePart.Attachment(
                            kind = AttachmentKind.File,
                            content = AttachmentContent.Base64(resource.blob),
                            mimeType = mimeType,
                        )

                        else -> error("Unsupported blob content type '$mimeType' (uri ${resource.uri})")
                    }
                }

                is UnknownResourceContents ->
                    error("Unsupported embedded resource content (uri ${resource.uri})")
            }

            // a resource LINK is not content the model can consume directly, and
            // nested tool results/uses are out of scope for the PoC
            else -> error("Unsupported MCP content type ${this::class.simpleName}")
        }

    companion object {
        private val logger = KotlinLogging.logger { }
    }
}
