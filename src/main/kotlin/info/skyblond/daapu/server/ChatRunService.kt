package info.skyblond.daapu.server

import info.skyblond.daapu.AppConfig
import info.skyblond.daapu.agent.executor.StreamingExecutionCallback
import info.skyblond.daapu.agent.lc4j.executor.Lc4jStreamingExecutor
import info.skyblond.daapu.agent.lc4j.llm.LLM
import info.skyblond.daapu.agent.lc4j.llm.LLMCapability
import info.skyblond.daapu.agent.lc4j.llm.ModelCatalog
import info.skyblond.daapu.agent.lc4j.provider.BifrostProvider
import info.skyblond.daapu.agent.renderSystemPrompt
import info.skyblond.daapu.agent.runChatTurn
import info.skyblond.daapu.chat.*
import info.skyblond.daapu.db.Chats
import info.skyblond.daapu.db.SSTMs
import info.skyblond.daapu.db.newChatId
import info.skyblond.daapu.db.withTransaction
import info.skyblond.daapu.mcp.McpToolProvider
import io.ktor.server.plugins.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.encoding.Base64

/**
 * One prepared chat run: everything validated and mapped, ready to execute.
 */
class ChatRunSetup(
    val chatId: String,
    val model: LLM,
    val parts: List<ChatMessagePart>,
    val reasoningEffort: String
)

/**
 * Service for executing an agent request.
 *
 * One streaming chat model is built per request (cheap: the model holds
 * configuration only, no connections), so per-request model selection comes
 * for free; only the expensive pieces — the model catalog, the chat store,
 * the system prompt, and the MCP tool provider (cached clients, #8) — are
 * shared.
 */
class ChatRunService(
    config: AppConfig,
    // the MCP clients are cached in the provider (connected eagerly at
    // construction, see mcp/McpToolProvider.kt): per-request runs must not
    // reconnect per turn. The default builds no clients, so a service
    // constructed without MCP servers (tests) behaves like the old
    // EmptyToolProvider path.
    private val toolProvider: McpToolProvider = McpToolProvider(emptyList()),
) : AutoCloseable {

    private val bifrostProvider = BifrostProvider(
        id = "bifrost",
        baseUrl = config.llmBaseUrl.openAiApiRoot(),
        apiKey = config.llmApiKey
    )
    private val modelCatalog = ModelCatalog(bifrostProvider)
    private val chatStore: ChatStore = PostgresChatStore()
    private val systemPrompt = renderSystemPrompt("Raven", true)

    // one run per chat at a time: a chat's history is loaded and stored as a
    // whole, so concurrent runs would corrupt each other.
    // Entries exist only while a run is active or a delete is in progress:
    // [acquireChatLock] creates them atomically, [releaseChatLock] and
    // [deleteChat] evict them, so arbitrary/deleted chat ids don't accumulate.
    // TODO: distributed lock in production
    private val chatLocks = ConcurrentHashMap<String, Mutex>()

    fun models(): List<ModelInfo> = modelCatalog.models.map {
        ModelInfo(
            id = it.id,
            vision = it.supports(LLMCapability.Input.Vision.Image),
            contextLength = it.contextLength,
            maxOutputTokens = it.maxOutputTokens,
        )
    }

    suspend fun listChats(): List<String> = withTransaction {
        Chats.selectAll()
            // TODO: should add time to Chats, 1) createdAt, 2) lastUpdatedAt
            .orderBy(Chats.id to SortOrder.DESC)
            // TODO: pagination?
            .limit(200)
            .map { it[Chats.id] }
    }

    fun newChat(): ChatIdResponse = ChatIdResponse(newChatId())

    /**
     * Delete a chat row. Refuses (throws [ChatRunConflictException]) while a
     * run holds the chat lock: the chat store's upsert would otherwise let
     * an in-flight run's final store resurrect the deleted row. Returns false
     * when the chat doesn't exist.
     *
     * The lock entry is taken and evicted atomically ([ConcurrentHashMap.compute]
     * serializes both map ops and the `tryLock`), so a delete and a run can never
     * end up holding two different mutexes for the same chat. A delete that lands
     * right before a run's lock acquisition can still be resurrected by that run's
     * final store (accepted for the PoC, same as the mid-run variant).
     */
    suspend fun deleteChat(chatId: String): Boolean {
        var lock: Mutex? = null
        chatLocks.compute(chatId) { _, existing ->
            if (existing == null) {
                // no run ever touched this chat: nothing to lock against
                null
            } else if (existing.tryLock()) {
                lock = existing
                null // evict while still held; the finally below unlocks it
            } else {
                throw ChatRunConflictException("Chat '$chatId' has an active run")
            }
        }
        val mutex = lock
        return try {
            withTransaction { Chats.deleteWhere { Chats.id eq chatId } > 0 }
        } finally {
            mutex?.unlock()
        }
    }

    suspend fun chat(chatId: String): List<ChatMessage> = chatStore.load(chatId)

    /**
     * Validate and map an incoming message. Throws ktor's
     * [BadRequestException] on malformed input, before any stream has started.
     */
    fun prepareRun(chatId: String, request: SendMessageRequest): ChatRunSetup {
        val text = request.text?.trim().orEmpty()
        if (text.isBlank() && request.images.isEmpty()) {
            throw BadRequestException("Message must have text and/or images")
        }
        val model = request.model?.takeIf { it.isNotBlank() }?.let { id ->
            modelCatalog.findModel(id) ?: throw BadRequestException("Unknown model '$id'")
        } ?: throw BadRequestException("model is required")
        val parts = mutableListOf<ChatMessagePart>()
        if (text.isNotBlank()) parts += ChatMessagePart.Text(text)
        request.images.forEach { parts += parseImagePart(it) }
        // TODO: hard coded reasoning effort
        return ChatRunSetup(chatId, model, parts, "high")
    }

    /**
     * Serialize a data URL (`data:image/png;base64,...`) into a neutral image
     * attachment part.
     */
    private fun parseImagePart(image: ImagePart): ChatMessagePart.Attachment {
        val match = dataUrlRegex.matchEntire(image.dataUrl.trim())
            ?: throw BadRequestException("Invalid image data URL")
        val mimeType = match.groupValues[1]
        val base64 = match.groupValues[2].filterNot { it.isWhitespace() }
        // validate early so a malformed payload fails with a clear 400 instead
        // of an opaque gateway error mid-stream
        runCatching { Base64.decode(base64) }
            .getOrElse { throw BadRequestException("Invalid base64 in image data URL") }
        return ChatMessagePart.Attachment(
            kind = AttachmentKind.Image,
            content = AttachmentContent.Base64(base64),
            mimeType = mimeType,
        )
    }

    /**
     * Take the per-chat run lock, or throw [ChatRunConflictException] when the
     * chat already has an active run. The caller must unlock the result via
     * [releaseChatLock].
     */
    // TODO: instead of acquired by webserver, should be acquired by runChat
    //       or check lock in runChat, throw IllegalStateException if lock not acquired
    fun acquireChatLock(chatId: String): Mutex {
        var acquired: Mutex? = null
        // create+tryLock atomically with the map op, so a concurrent
        // deleteChat can never pair a run with a lock that is not in the map
        chatLocks.compute(chatId) { _, existing ->
            val mutex = existing ?: Mutex()
            if (!mutex.tryLock()) {
                throw ChatRunConflictException("Chat '$chatId' already has an active run")
            }
            acquired = mutex
            mutex
        }
        return acquired!!
    }

    /**
     * Release a run lock obtained from [acquireChatLock] and evict its map
     * entry. The entry is removed BEFORE the unlock, so a run that acquired
     * the mutex earlier keeps working on it while the next acquirer gets a
     * fresh mutex — never two concurrent runs on the same chat.
     */
    fun releaseChatLock(chatId: String, mutex: Mutex) {
        chatLocks.compute(chatId) { _, existing ->
            if (existing === mutex) null else existing
        }
        mutex.unlock()
    }

    /**
     * Run one chat turn for [setup], forwarding stream events to [sendEvent]
     * (an SSE writer). The chat is only stored by the turn loop when the run
     * completes, so a failed or aborted run leaves the chat untouched.
     */
    suspend fun runChat(
        setup: ChatRunSetup,
        sendEvent: suspend (event: String, data: String) -> Unit
    ) {
        runChatTurn(
            chatId = setup.chatId,
            model = setup.model,
            streamingChatModel = setup.model.toStreamingChatModel(setup.reasoningEffort),
            userParts = setup.parts,
            systemPrompt = systemPrompt,
            chatStore = chatStore,
            loadMemories = {
                withTransaction {
                    SSTMs.selectAll()
                        .orderBy(SSTMs.lastUpdate to SortOrder.ASC)
                        .map { it[SSTMs.content] }
                }
            },
            toolProvider = toolProvider,
            callback = streamEventCallback(sendEvent),
            executor = Lc4jStreamingExecutor()
        )
    }

    /**
     * Close the shared MCP clients (called from the JVM shutdown hook
     * registered in `WebServer.startWebServer`).
     */
    override fun close() {
        toolProvider.close()
    }

    companion object {
        // `.+` with DOT_MATCHES_ALL: data URLs may fold base64 across lines
        private val dataUrlRegex = Regex(
            """^data:(image/[a-zA-Z0-9.+-]+);base64,(.+)$""",
            RegexOption.DOT_MATCHES_ALL,
        )
    }
}

/**
 * The OpenAI-compatible API root langchain4j should hit for a configured
 * `LLM_BASE_URL`: the base URL itself, plus `/v1` when it is missing
 * (langchain4j appends `/chat/completions` to it).
 */
internal fun String.openAiApiRoot(): String {
    val trimmed = trimEnd('/')
    return if (trimmed.endsWith("/v1")) trimmed else "$trimmed/v1"
}

/**
 * The [StreamingExecutionCallback] that maps turn-loop stream events to SSE
 * events — the contract the frontend (`frontend/src/lib/api.ts`) parses.
 * Extracted from [ChatRunService.runChat] so the exact event payloads can be
 * unit-tested.
 */
internal fun streamEventCallback(
    sendEvent: suspend (event: String, data: String) -> Unit,
): StreamingExecutionCallback = object : StreamingExecutionCallback {
    override suspend fun onTextDelta(text: String) {
        sendEvent("text", sseData("delta" to text))
    }

    override suspend fun onReasoningDelta(text: String) {
        sendEvent("reasoning", sseData("delta" to text))
    }

    override suspend fun onToolCall(name: String, args: String) {
        sendEvent(
            "tool_call",
            sseData(
                "name" to name,
                "args" to args,
            )
        )
    }

    override suspend fun onToolResults(results: List<ChatMessagePart.ToolResult>) {
        // stream tool results as they are produced; the frontend shows
        // them live (the `done` history reload re-renders them anyway)
        results.forEach { result ->
            sendEvent(
                "tool_result",
                buildJsonObject {
                    put("id", result.id)
                    put("name", result.tool)
                    put("content", result.parts.joinToString("\n") {
                        when (it) {
                            is ChatMessagePart.Text -> it.text

                            // TODO: non-text content?
                            is ChatMessagePart.Attachment -> "Show attachment is not supported yet"
                        }
                    })
                    put("isError", result.isError)
                }.toString()
            )
        }
    }

    override suspend fun onStreamError(error: String) {
        // the stream hit a transient error and will be retried
        // frontend should clear the current round (after previous tool call)
        sendEvent("retry", sseData("message" to error))
    }
}

private fun sseData(vararg pairs: Pair<String, String>): String =
    buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }.toString()
