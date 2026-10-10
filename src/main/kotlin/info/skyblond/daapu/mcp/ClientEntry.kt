package info.skyblond.daapu.mcp

import info.skyblond.daapu.agent.chat.AttachmentContent
import info.skyblond.daapu.agent.chat.AttachmentKind
import info.skyblond.daapu.agent.chat.ChatMessagePart
import info.skyblond.daapu.agent.tool.ToolSpec
import info.skyblond.daapu.agent.tool.errorResult
import info.skyblond.daapu.agent.tool.nsToolName
import info.skyblond.daapu.config.McpProxyConfig
import info.skyblond.daapu.config.McpServerConfig
import info.skyblond.daapu.config.McpTransportType
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.engine.http
import io.ktor.client.engine.java.*
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
 *   provider connects eagerly at startup via McpToolProvider.connectAll; a
 *   transport failure later in a run drops the client so the next
 *   [getConnectedClient] call reconnects).
 *   The connect itself retries up to [McpServerConfig.reconnectAttempts]
 *   times, waiting [McpServerConfig.reconnectDelayMs] between attempts, then
 *   throws [McpTransportException].
 * - [dropConnection] discards the current connection — for stdio it
 *   destroys the subprocess BEFORE closing the client (why: the read-loop
 *   freeze in the handshake bullet below) — called on transport failure
 *   and on provider close. It waits
 *   for the connect lock (an in-flight connect is never interrupted, it
 *   runs to completion first), so a caller from a non-suspend context
 *   (`close()`, which blocks its own thread on the same wait) is safe
 *   while a concurrent connect is in progress.
 * - stdio subprocesses are owned from spawn on: the current attempt's
 *   process sits in an in-flight reference until it is published into
 *   `clientRef` or destroyed by its (failed/cancelled/timed-out) attempt;
 *   [close] first raises the closed gate ([closed]) — a retrying connect
 *   loop must not spawn further attempts after its one-time sweep, and a
 *   post-close connect must fail fast instead of hanging on the cancelled
 *   handshakeScope — then sweeps whatever a stuck attempt could not clean
 *   up itself, so no attempt ever leaks a live subprocess.
 * - the stdio handshake runs as an ABANDONABLE job ([handshakeScope]) that
 *   the connect coroutine merely awaits: on failure the SDK's close joins
 *   the transport's read loop (non-cancellable), which is blocked in a
 *   native pipe read that only EOF — the subprocess dying — unblocks, so
 *   the handshake coroutine itself can freeze long past any deadline. The
 *   await is cancellable: timeout, failure, and cancellation all reach the
 *   connect's own catch promptly, which destroys the process — the very
 *   thing that lets the abandoned job finish. With NO
 *   [McpServerConfig.initializationTimeoutSeconds] a silent stdio server
 *   can hold the await indefinitely — the documented meaning of "no
 *   timeout" (README's config reference): configure it for stdio servers.
 *
 * Advertised names are `{namespace}__{toolName}`: `__` is the separator, so
 * server tool names containing it are sanitized to `_` ([listTools], the raw
 * name is preserved for [executeRequestOnce]). The mapping is refreshed on
 * every [listTools] pass; per-pass collisions are rejected loudly rather
 * than silently overwriting an earlier tool.
 */
class ClientEntry(
    val namespace: String,
    private val config: McpServerConfig,
    private val proxy: McpProxyConfig? = null,
) {
    private val clientRef: AtomicReference<ConnectedClient?> = AtomicReference(null)
    private val connectLock: Mutex = Mutex()
    private val toolNameMapping: ConcurrentHashMap<String, String> = ConcurrentHashMap()

    // stdio handshakes run here as ABANDONABLE jobs: on failure the SDK's
    // close can freeze the handshake coroutine until the subprocess dies,
    // so the awaiting connect coroutine must not be its parent — a parent
    // waits for its children (see the class KDoc); only [close] cancels
    // this scope
    private val handshakeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // the stdio subprocess owned by the current connect attempt, from
    // spawn until publication (or destruction): the owner-of-last-resort
    // reference (see the class KDoc) — the connect lock serializes
    // attempts, so one slot is enough
    private val inFlightStdioProcess = AtomicReference<Process?>(null)

    // the closed gate, raised by close() before any teardown: stops the
    // connect retry loop and fails post-close connects fast. The
    // post-registration re-check in buildConnectedClient pairs with
    // close()'s gate-then-sweep order so no subprocess can slip between
    // the two — a process registered after the sweep implies the gate was
    // already up at that check, so the attempt destroys the process itself
    private val closed = AtomicBoolean(false)

    // one HTTP engine per entry: transports in the official SDK take a
    // ktor HttpClient (auth headers go through the per-request builder);
    // the optional mcp.proxy applies engine-wide, so POST, SSE GET and
    // DELETE all tunnel through it (CONNECT). The Java engine (JDK
    // HttpClient) speaks TLS 1.3 and imposes no read timeout, so long
    // idle SSE tool streams survive.
    private val httpClient = HttpClient(Java) {
        proxy?.let {
            engine {
                this.proxy = ProxyBuilder.http("http://${it.host}:${it.port}")
            }
        }
    }

    val timeoutSeconds: Long = config.toolExecutionTimeoutSeconds

    init {
        config.validate(namespace)
    }

    private suspend fun buildConnectedClient(): ConnectedClient = when (config.type) {
        McpTransportType.Http -> ConnectedClient(
            client = withInitializationTimeout {
                httpClient.mcpStreamableHttp(
                    config.url!!,
                    requestBuilder = {
                        config.headers.forEach { (name, value) -> header(name, value) }
                    },
                )
            },
            process = null,
        )

        McpTransportType.Stdio -> {
            val process = withContext(Dispatchers.IO) {
                ProcessBuilder(config.command)
                    .apply { environment().putAll(config.environment) }
                    .start()
            }
            // owned from spawn on: until publication the in-flight
            // reference is the owner of last resort (swept by close())
            inFlightStdioProcess.set(process)
            try {
                // the closed gate, re-checked AFTER registration: this is
                // the airtight half (see [closed]) — a process registered
                // after close()'s one-time sweep implies the gate was
                // already up here, so this attempt destroys the process
                // itself through the catch below
                if (closed.get()) throw McpTransportException("MCP server '$namespace' is closed")
                val transport = StdioClientTransport(
                    input = process.inputStream.asSource().buffered(),
                    output = process.outputStream.asSink().buffered(),
                    error = process.errorStream.asSource().buffered(),
                )
                val client = Client(
                    clientInfo = Implementation(name = "daapu", version = LIB_VERSION),
                )
                // the handshake runs in a job we can ABANDON (see the class
                // KDoc): the await below is cancellable, so OUR catch runs
                // promptly on timeout/failure/cancellation and destroys the
                // process — which is what unblocks the frozen job
                val done = CompletableDeferred<Unit>()
                handshakeScope.launch {
                    try {
                        client.connect(transport)
                        done.complete(Unit)
                    } catch (t: Throwable) {
                        // nobody may be awaiting anymore (timeout or
                        // cancellation already returned): still record how
                        // the abandoned handshake ended
                        logger.debug(t) { "MCP server '$namespace': stdio handshake job ended" }
                        done.completeExceptionally(t)
                    }
                }
                withInitializationTimeout { done.await() }
                // success: ownership transfers with the return value — the
                // in-flight reference is cleared at publication time
                ConnectedClient(client, process)
            } catch (t: Throwable) {
                // the attempt failed (timeout, handshake error, cancellation):
                // destroy the process and give up ownership. NonCancellable:
                // outer cancellation must not abort the cleanup itself
                withContext(NonCancellable) {
                    runCatching { destroyWithEscalation(process) }
                }
                inFlightStdioProcess.compareAndSet(process, null)
                throw t
            }
        }
    }

    /**
     * Runs [block] under [McpServerConfig.initializationTimeoutSeconds] —
     * at BOTH call sites (the http connect and the stdio handshake await):
     * OUR timeout is translated into [McpTransportException] so it flows
     * through the reconnect policy — a bare [TimeoutCancellationException]
     * would otherwise surface as cancellation, which [getConnectedClient]
     * rethrows without retrying (an http initialization timeout therefore
     * RETRIES too, instead of failing the connect on its first miss).
     *
     * An OUTER timeout or cancellation keeps propagating untouched: an
     * outer [withTimeout]'s exception is delivered through this
     * coroutine's job cancellation, so [ensureActive] re-surfaces it —
     * and the same check catches a cancellation that raced OUR timer,
     * which the translation must never swallow. (The abandonable-handshake
     * watchdog that makes the timeout trustworthy for a silent stdio
     * server is stdio-only — see the class KDoc; the http connect is
     * cancelled in place and relies on the HTTP stack's own
     * interruptibility.)
     */
    private suspend fun <T> withInitializationTimeout(block: suspend () -> T): T {
        val seconds = config.initializationTimeoutSeconds ?: return block()
        return try {
            withTimeout(seconds * 1_000L) { block() }
        } catch (e: TimeoutCancellationException) {
            // cancellation wins over the translation: this covers both an
            // outer timeout (re-surfaced here as the original exception,
            // because it cancelled this job on its way in) and an outer
            // cancellation that raced OUR timer. The unfixable remainder —
            // a cancel landing between this check and the throw below —
            // is microscopic and surfaces at the caller's next suspension
            // point
            currentCoroutineContext().ensureActive()
            throw McpTransportException(
                "MCP server '$namespace' initialization timed out after ${seconds}s", e
            )
        }
    }

    /**
     * Destroys a stdio subprocess with bounded escalation: a graceful
     * [Process.destroy], then [Process.destroyForcibly] if the process
     * ignores it within [STDIO_KILL_GRACE_MS] — never wait forever on an
     * unkillable process.
     */
    private suspend fun destroyWithEscalation(process: Process) = withContext(Dispatchers.IO) {
        process.destroy()
        if (!process.waitFor(STDIO_KILL_GRACE_MS, TimeUnit.MILLISECONDS)) {
            logger.warn {
                "MCP server '$namespace': stdio process ${process.pid()} ignored termination, force-killing"
            }
            process.destroyForcibly()
            if (!process.waitFor(STDIO_KILL_GRACE_MS, TimeUnit.MILLISECONDS)) {
                logger.error {
                    "MCP server '$namespace': stdio process ${process.pid()} survived a force kill"
                }
            }
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
                // the closed gate, checked per attempt (see [closed]): a
                // post-close connect fails fast — the cancelled
                // handshakeScope could never complete a handshake, so
                // without the gate it would only spawn doomed subprocesses
                // until the retry budget is spent (or hang forever with no
                // initialization timeout) — and a retry loop interrupted
                // by close() stops instead of respawning processes its
                // one-time sweep can no longer see
                if (closed.get()) throw McpTransportException("MCP server '$namespace' is closed")
                try {
                    val connected = withContext(Dispatchers.IO) { buildConnectedClient() }
                    // publish even when cancellation lands on the
                    // withContext boundary: a built client (with its
                    // subprocess) must never be orphaned, and the in-flight
                    // reference is cleared exactly at publication
                    withContext(NonCancellable) {
                        clientRef.set(connected)
                        connected.process?.let { inFlightStdioProcess.compareAndSet(it, null) }
                    }
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
     *
     * Ordering for stdio: the subprocess is destroyed (bounded escalation)
     * BEFORE the client is closed — closing first can hang the drop for as
     * long as the process lives (why: the class KDoc's read-loop freeze).
     */
    suspend fun dropConnection() {
        connectLock.withLock {
            val connected = clientRef.getAndSet(null)
            runCatching { connected?.process?.let { destroyWithEscalation(it) } }
            runCatching { connected?.client?.close() }
        }
    }

    /**
     * Closes the connection and the HTTP engine (provider shutdown). Also
     * the owner of last resort: the closed gate goes up FIRST — a retrying
     * connect loop must not spawn further attempts after the one-time
     * sweep below, and a post-close connect must fail fast instead of
     * hanging on the cancelled handshakeScope (see [closed]) — then the
     * subprocess an in-flight connect still owns is destroyed FIRST, so
     * the stuck connect unwinds on its own (why killing the process
     * unblocks it: the class KDoc) and releases the connect lock
     * [dropConnection] below needs.
     */
    fun close() {
        closed.set(true)
        kotlinx.coroutines.runBlocking {
            inFlightStdioProcess.getAndSet(null)?.let { process ->
                runCatching { destroyWithEscalation(process) }
            }
            dropConnection()
        }
        // no new handshake jobs; abandoned ones finish on their own once
        // their process is dead
        handshakeScope.cancel()
        runCatching { httpClient.close() }
    }

    private fun advertisedName(toolName: String): String =
        nsToolName(namespace, toolName)

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

        // no in-turn reconnect: a dropped connection is reported to the model
        // and rebuilt by the next tool-list refresh (listTools), which is the
        // sole reconnection point. No execution timeout HERE either: the
        // budget is enforced once, on the hand callback route
        // (HandCallbackService.executeToolCall wraps execute in withTimeout
        // from this entry's toolExecutionTimeoutSeconds) — duplicating it
        // would mean two timers racing on the same budget.
        val client = clientRef.get()?.client ?: return errorResult(
            id, advertisedName,
            TRANSPORT_FAILURE_MESSAGE
        )
        val request = CallToolRequest(CallToolRequestParams(name = rawName, arguments = arguments))
        val result = client.callTool(request)

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

        /**
         * Grace period before a stdio subprocess that ignores
         * [Process.destroy] is force-killed ([destroyWithEscalation]):
         * generous for a well-behaved server to exit on its SIGTERM, far
         * below any sane initialization budget.
         */
        private const val STDIO_KILL_GRACE_MS: Long = 2_000L
    }
}
