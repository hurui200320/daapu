package info.skyblond.daapu.server

import info.skyblond.daapu.agent.model.LLM
import info.skyblond.daapu.agent.model.LLMCapability
import info.skyblond.daapu.agent.ModelCatalog
import info.skyblond.daapu.agent.model.ModelProvider
import info.skyblond.daapu.agent.oneshot.ChatCompactor
import info.skyblond.daapu.agent.oneshot.SstmExtractor
import info.skyblond.daapu.agent.persist.renderSystemPrompt
import info.skyblond.daapu.agent.persist.runChatTurn
import info.skyblond.daapu.agent.chat.AttachmentContent
import info.skyblond.daapu.agent.chat.AttachmentKind
import info.skyblond.daapu.agent.chat.ChatMessage
import info.skyblond.daapu.agent.chat.ChatMessagePart
import info.skyblond.daapu.agent.chat.ChatStore
import info.skyblond.daapu.agent.chat.PostgresChatStore
import info.skyblond.daapu.config.AppConfig
import info.skyblond.daapu.db.Chats
import info.skyblond.daapu.db.DEFAULT_CHAT_TITLE
import info.skyblond.daapu.db.newChatId
import info.skyblond.daapu.db.withTransaction
import info.skyblond.daapu.hand.HandCallbackService
import info.skyblond.daapu.hand.HandClient
import info.skyblond.daapu.hand.HttpHandClient
import info.skyblond.daapu.agent.persist.StreamingExecutionCallback
import info.skyblond.daapu.mcp.McpToolProvider
import info.skyblond.daapu.memory.sstm.PostgresSstmService
import info.skyblond.daapu.memory.sstm.SstmService
import io.ktor.server.plugins.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.encoding.Base64

/**
 * One prepared chat run: everything validated and mapped, ready to execute.
 */
class ChatRunSetup(
    val chatId: String,
    val model: LLM,
    val parts: List<ChatMessagePart>,
)

/**
 * Service for executing an agent request.
 *
 * The hand client is built once (one HTTP client, shared across runs — a
 * run's state lives entirely inside the hand call, so sharing is safe);
 * the model catalog, the chat store, the system prompt, the MCP tool
 * provider (cached clients, #8), the SSTM service (shared with the
 * memory CRUD routes), and the hand callback service (the in-flight run
 * registry behind the hand's tool callbacks, `hand/HandCallbackService.kt`)
 * are also reused.
 */
class ChatRunService(
    config: AppConfig,
    // the MCP clients are cached in the provider (connected eagerly at
    // construction, see mcp/McpToolProvider.kt): per-request runs must not
    // reconnect per turn. The default builds no clients, so a service
    // constructed without MCP servers (tests) behaves like the old
    // EmptyToolProvider path.
    private val toolProvider: McpToolProvider = McpToolProvider(emptyList()),
    private val sstmService: SstmService = PostgresSstmService(),
    private val hand: HandClient = HttpHandClient(config.hand.baseUrl, config.hand.token),
    internal val handCallback: HandCallbackService = HandCallbackService(config.hand.token),
) : AutoCloseable {

    // PoC: the catalog pins its models to the bifrost gateway (see
    // ModelCatalog.kt); a config without it is a wiring bug, so fail fast
    // at startup instead of at model resolution time.
    private val bifrostConfig = config.providers["bifrost"]
        ?: error("Provider config 'bifrost' not found")
    private val modelCatalog = ModelCatalog(
        mapOf(
            "bifrost" to ModelProvider(
                id = "bifrost",
                baseUrl = bifrostConfig.baseUrl,
                apiKey = bifrostConfig.apiKey,
            )
        )
    )
    private val chatStore: ChatStore = PostgresChatStore()
    private val systemPrompt = renderSystemPrompt(true)
    private val memoryConfig = config.memory

    /** This brain's tool callback endpoint the hand POSTs to (loopback PoC). */
    internal val handCallbackUrl: String = "http://127.0.0.1:${config.server.port}/api/hand/tool"

    // one-shot pipeline models: resolved once at startup (unchanged)
    private val configuredCompactModel = memoryConfig.compactModel?.let { id ->
        modelCatalog.findModel(id) ?: throw IllegalArgumentException("memory.compactModel '$id' is not in the model catalog")
    }
    private val configuredExtractModel = memoryConfig.extractModel?.let { id ->
        modelCatalog.findModel(id) ?: throw IllegalArgumentException("memory.extractModel '$id' is not in the model catalog")
    }
    private val configuredMergeModel = memoryConfig.mergeModel?.let { id ->
        val model = modelCatalog.findModel(id)
            ?: throw IllegalArgumentException("memory.mergeModel '$id' is not in the model catalog")
        require(model.supports(LLMCapability.Output.ToolCalls)) {
            "memory.mergeModel '${model.id}' must support tool calls (the memory merge agent runs a tool loop)"
        }
        model
    }

    // serializes the SSTM writes of concurrent runs' extraction merges (unchanged)
    private val sstmWriteLock = Mutex()

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

    suspend fun listChats(): List<ChatInfo> = withTransaction {
        Chats.selectAll()
            // TODO: should add time to Chats, lastUpdatedAt
            .orderBy(Chats.id to SortOrder.DESC)
            // TODO: pagination?
            .limit(200)
            .map { row -> ChatInfo(row[Chats.id], row[Chats.title]) }
    }

    /**
     * Create a chat: a row with the default title and empty history is
     * inserted right away, so the chat is visible in `GET /api/chats` and
     * renameable before the first run. The turn loop's store upsert only
     * touches `id` + `chat_json`, so the title survives every run untouched.
     */
    suspend fun newChat(): ChatIdResponse = withTransaction {
        val id = newChatId()
        Chats.insert {
            it[Chats.id] = id
            it[Chats.title] = DEFAULT_CHAT_TITLE
        }
        ChatIdResponse(id)
    }

    /**
     * Rename a chat. Returns null when the chat doesn't exist.
     *
     * Takes no per-chat lock: the chat store's upsert writes only `id` and
     * `chat_json` ([PostgresChatStore.store]), never the title, so an
     * in-flight run cannot clobber a rename (unlike a delete, which the lock
     * guards against the upsert resurrecting the row).
     */
    suspend fun renameChat(chatId: String, title: String): ChatInfo? = withTransaction {
        val updated = Chats.update({ Chats.id eq chatId }) {
            it[Chats.title] = title
        }
        if (updated == 0) null else ChatInfo(chatId, title)
    }

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

    suspend fun chat(chatId: String): List<ChatMessage> = chatStore.load(chatId).chat

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
        return ChatRunSetup(chatId, model, parts)
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
     * Run one chat turn for [setup], forwarding stream events to [callback]
     * (a [StreamingExecutionCallback] implementation). The chat is only
     * stored by the turn loop when the run completes, so a failed or aborted
     * run leaves the chat untouched.
     *
     * The run is registered under a fresh [runId] before the hand call: the
     * hand's tool callbacks (HTTP POSTs back into this process) resolve it
     * through [HandCallbackService.executeToolCall]. The entry is evicted when the
     * run ends.
     */
    suspend fun runChat(
        setup: ChatRunSetup,
        callback: StreamingExecutionCallback,
    ) {
        // one-shot models per run: the configured model wins, the run's
        // model is the fallback. Capability checks (attachments in the
        // history) happen inside the one-shots and skip with a warning
        // instead of failing the run. Each model carries its own reasoning
        // effort (its Reasoning capability), no override.
        val compactModel = configuredCompactModel ?: setup.model
        val extractModel = configuredExtractModel ?: setup.model
        val mergeModel = configuredMergeModel ?: setup.model
        val compactor = ChatCompactor(compactModel, hand)
        val extractor = SstmExtractor(
            extractModel = extractModel,
            mergeModel = mergeModel,
            hand = hand,
            sstmService = sstmService,
        )
        val runId = UUID.randomUUID().toString()
        handCallback.register(runId, toolProvider, setup.model)
        try {
            runChatTurn(
                chatId = setup.chatId,
                model = setup.model,
                userParts = setup.parts,
                systemPrompt = systemPrompt,
                chatStore = chatStore,
                sstmService = sstmService,
                toolProvider = toolProvider,
                callback = callback,
                hand = hand,
                runId = runId,
                toolCallbackUrl = handCallbackUrl,
                compactor = compactor,
                // the whole extraction merge holds the write lock, so the
                // injection read never observes a half-merged SSTM
                extractSstm = { dropped -> sstmWriteLock.withLock { extractor.processDiscardedMessages(dropped) } },
                compactionTriggerFraction = memoryConfig.compactionTriggerFraction,
                compactionKeepRounds = memoryConfig.compactionKeepRounds,
            )
        } finally {
            handCallback.unregister(runId)
        }
    }

    /**
     * Close the shared MCP clients and the hand HTTP client (called from
     * the JVM shutdown hook registered in `WebServer.startWebServer`).
     */
    override fun close() {
        toolProvider.close()
        hand.close()
    }

    companion object {
        // `.+` with DOT_MATCHES_ALL: data URLs may fold base64 across lines
        private val dataUrlRegex = Regex(
            """^data:(image/[a-zA-Z0-9.+-]+);base64,(.+)$""",
            RegexOption.DOT_MATCHES_ALL,
        )
    }
}
