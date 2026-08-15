package info.skyblond.daapu.server

import info.skyblond.daapu.agent.ModelCatalog
import info.skyblond.daapu.agent.chat.*
import info.skyblond.daapu.agent.model.LLM
import info.skyblond.daapu.agent.model.LLMCapability
import info.skyblond.daapu.agent.model.ModelProvider
import info.skyblond.daapu.agent.oneshot.TitleGenerator
import info.skyblond.daapu.agent.oneshot.compaction.ChatCompactionService
import info.skyblond.daapu.agent.oneshot.sstm.SstmExtractionService
import info.skyblond.daapu.agent.persist.PersistChatService
import info.skyblond.daapu.agent.persist.StreamingExecutionCallback
import info.skyblond.daapu.agent.persist.renderMainAgentSystemPrompt
import info.skyblond.daapu.config.AppConfig
import info.skyblond.daapu.hand.HandCallbackService
import info.skyblond.daapu.hand.HandClient
import info.skyblond.daapu.hand.HttpHandClient
import info.skyblond.daapu.mcp.McpToolProvider
import info.skyblond.daapu.memory.sstm.PostgresSstmService
import info.skyblond.daapu.memory.sstm.SstmService
import io.ktor.server.plugins.*
import kotlinx.coroutines.sync.Mutex
import java.util.*
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
 * provider (cached clients), the SSTM service (shared with the
 * memory CRUD routes), and the hand callback service (the in-flight run
 * registry behind the hand's tool callbacks, `hand/HandCallbackService.kt`)
 * are also reused. The one-shot pipeline services — compaction
 * (`ChatCompactionService`), SSTM extraction (`SstmExtractionService`),
 * session titles (`TitleGenerator`),
 * and the persist loop itself (`PersistChatService`) — are stateless
 * across runs and are constructed once here as well; their models come
 * from the REQUIRED `memory.compactModel/extractModel/mergeModel` +
 * `title.model` config,
 * resolved once at construction (never the run's model).
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
    // all chats-table access (list/create/rename/delete/title) goes through
    // this seam, so the service holds no raw DB calls (tests inject a fake)
    private val chatStore: ChatStore = PostgresChatStore(),
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
    private val systemPrompt = renderMainAgentSystemPrompt(true)
    private val memoryConfig = config.memory

    /** This brain's tool callback endpoint the hand POSTs to (loopback PoC). */
    internal val handCallbackUrl: String = "http://127.0.0.1:${config.server.port}/api/hand/tool"

    // the one-shot pipeline models: all REQUIRED config, resolved once at
    // construction (a chat run's own model is never used for these)
    private val compactModel = memoryConfig.compactModel.let { id ->
        modelCatalog.findModel(id)
            ?: throw IllegalArgumentException("memory.compactModel '$id' is not in the model catalog")
    }
    private val extractModel = memoryConfig.extractModel.let { id ->
        modelCatalog.findModel(id)
            ?: throw IllegalArgumentException("memory.extractModel '$id' is not in the model catalog")
    }
    private val mergeModel = memoryConfig.mergeModel.let { id ->
        val model = modelCatalog.findModel(id)
            ?: throw IllegalArgumentException("memory.mergeModel '$id' is not in the model catalog")
        require(model.supports(LLMCapability.Output.ToolCalls)) {
            "memory.mergeModel '${model.id}' must support tool calls (the memory merge agent runs a tool loop)"
        }
        model
    }
    private val titleModel = config.title.model.let { id ->
        modelCatalog.findModel(id)
            ?: throw IllegalArgumentException("title.model '$id' is not in the model catalog")
    }

    // one-shot pipeline services: stateless across runs, so a single
    // instance is shared by every concurrent chat run
    private val titleGenerator = TitleGenerator(titleModel, hand, config.title.lastNRound)
    private val compactionService = ChatCompactionService(compactModel, hand)
    private val sstmExtractionService = SstmExtractionService(
        extractModel = extractModel,
        mergeModel = mergeModel,
        hand = hand,
        sstmService = sstmService,
    )
    private val persistService = PersistChatService(
        chatStore = chatStore,
        sstmService = sstmService,
        hand = hand,
        compactionService = compactionService,
        sstmExtractionService = sstmExtractionService,
    )

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

    suspend fun listChats(): List<ChatInfo> = chatStore.listChats()

    /**
     * Create a chat: a row with the default title and empty history is
     * inserted right away, so the chat is visible in `GET /api/chats` and
     * renameable before the first run. The turn loop's store upsert only
     * touches `id` + `chat_json`, so the title survives every run untouched.
     */
    suspend fun newChat(): ChatIdResponse = ChatIdResponse(chatStore.newChat().id)

    /**
     * Rename a chat. Returns null when the chat doesn't exist.
     *
     * Takes no per-chat lock: the chat store's upsert writes only `id` and
     * `chat_json` ([PostgresChatStore.store]), never the title, so an
     * in-flight run cannot clobber a rename (unlike a delete, which the lock
     * guards against the upsert resurrecting the row).
     */
    suspend fun renameChat(chatId: String, title: String): ChatInfo? =
        chatStore.rename(chatId, title)

    /**
     * Generate a session title from the chat's stored history and persist it.
     * Returns null when the chat doesn't exist.
     *
     * The row is read exactly once ([ChatStore.load]): an empty history (a
     * fresh chat) short-circuits to a no-op — the current title is returned
     * unchanged and the LLM is never called
     * ([TitleGenerator.generateTitle]), so a custom title on a fresh chat is
     * never clobbered. A chat deleted after the read (before the rename)
     * returns null (the rename finds no row).
     */
    suspend fun generateTitle(chatId: String): ChatInfo? {
        val entry = chatStore.load(chatId) ?: return null
        if (entry.content.messages.isEmpty()) return entry.info
        return chatStore.rename(
            chatId, titleGenerator.generateTitle(entry.content.messages)
        )
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
            chatStore.delete(chatId)
        } finally {
            mutex?.unlock()
        }
    }

    suspend fun chat(chatId: String): List<ChatMessage> =
        chatStore.load(chatId)?.content?.messages ?: emptyList()

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
     *
     * The compaction/extraction services are shared, constructed once at
     * startup; the one-shot pipeline models are fixed at construction (the
     * run's model is only used for the chat round itself).
     */
    suspend fun runChat(
        setup: ChatRunSetup,
        callback: StreamingExecutionCallback,
    ) {
        val runId = UUID.randomUUID().toString()
        handCallback.register(runId, toolProvider, setup.model)
        try {
            persistService.runChat(
                chatId = setup.chatId,
                model = setup.model,
                userParts = setup.parts,
                systemPrompt = systemPrompt,
                toolProvider = toolProvider,
                callback = callback,
                runId = runId,
                toolCallbackUrl = handCallbackUrl,
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
