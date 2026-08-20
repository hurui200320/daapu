package info.skyblond.daapu.server

import info.skyblond.daapu.agent.ModelCatalog
import info.skyblond.daapu.agent.chat.*
import info.skyblond.daapu.agent.model.LLM
import info.skyblond.daapu.agent.model.LLMCapability
import info.skyblond.daapu.agent.model.ModelProvider
import info.skyblond.daapu.agent.oneshot.TitleGenerator
import info.skyblond.daapu.agent.oneshot.compaction.ChatCompactionService
import info.skyblond.daapu.agent.oneshot.eltm.EltmWriterService
import info.skyblond.daapu.agent.oneshot.sstm.SstmExtractionService
import info.skyblond.daapu.agent.persist.PersistChatService
import info.skyblond.daapu.agent.persist.StreamingExecutionCallback
import info.skyblond.daapu.agent.persist.renderMainAgentSystemPrompt
import info.skyblond.daapu.config.AppConfig
import info.skyblond.daapu.hand.HandCallbackService
import info.skyblond.daapu.hand.HandClient
import info.skyblond.daapu.hand.HandService
import info.skyblond.daapu.hand.HttpHandClient
import info.skyblond.daapu.mcp.McpToolProvider
import info.skyblond.daapu.memory.eltm.EltmService
import info.skyblond.daapu.memory.eltm.PostgresEltmService
import info.skyblond.daapu.memory.sstm.PostgresSstmService
import info.skyblond.daapu.memory.sstm.SstmService
import io.ktor.server.plugins.*
import kotlinx.coroutines.sync.Mutex
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
 * memory CRUD routes), the hand callback service (the in-flight run
 * registry behind the hand's tool callbacks, `hand/HandCallbackService.kt`),
 * and the hand service wrapping them (`hand/HandService.kt`: the agent
 * layer's hand seam, which wires the runId + in-flight registration around
 * every `/v1/run` call) are also reused. The one-shot pipeline services — compaction
 * (`ChatCompactionService`), SSTM extraction (`SstmExtractionService`),
 * session titles (`TitleGenerator`),
 * and the persist loop itself (`PersistChatService`) — are stateless
 * across runs and are constructed once here as well; their models come
 * from the REQUIRED `memory.compactModel` + `memory.sstm.extractModel/
 * mergeModel` + `title.model` config, resolved once at construction
 * (never the run's model).
 */
class ChatRunService(
    config: AppConfig,
    // the MCP clients are cached in the provider (connected eagerly at
    // construction, see mcp/McpToolProvider.kt): per-request runs must not
    // reconnect per turn. The default builds no clients, so a service
    // constructed without MCP servers (tests) behaves like the old
    // EmptyToolProvider path.
    private val toolProvider: McpToolProvider = McpToolProvider(emptyList()),
    private val hand: HandClient = HttpHandClient(config.hand.baseUrl, config.hand.token),
    internal val handCallback: HandCallbackService = HandCallbackService(config.hand.token),
    // all chats-table access (list/create/rename/delete/title) goes through
    // this seam, so the service holds no raw DB calls (tests inject a fake)
    private val chatStore: ChatStore = PostgresChatStore(),
    // the SSTM store: shared with the memory CRUD routes and the turn-loop
    // injection. Public: the web server module reads it for `/api/memories`.
    val sstmService: SstmService = PostgresSstmService(),
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
    // TODO: configurable. Currently fine but will break in docker when move to production.
    private val handCallbackUrl: String = "http://127.0.0.1:${config.server.port}/api/hand/tool"

    /**
     * This brain's tool-listing endpoint the hand queries before EVERY LLM
     * request (`GET {url}?runId=...`): the run's tool set is resolved per
     * round from the registered provider, not captured statically in the
     * run request.
     */
    private val handToolListUrl: String = "http://127.0.0.1:${config.server.port}/api/hand/tools"

    /**
     * The agent layer's hand seam: the HTTP client plus the tool-callback
     * wiring (the in-flight run registry behind the hand's tool callbacks,
     * `hand/HandService.kt`). The runId and the register/unregister
     * lifecycle live here — the chat loop never sees them.
     */
    private val handService = HandService(hand, handCallback, handCallbackUrl, handToolListUrl)

    // the one-shot pipeline models: all REQUIRED config, resolved once at
    // construction (a chat run's own model is never used for these)
    private val compactModel = memoryConfig.compactModel.let { id ->
        modelCatalog.findModel(id)
            ?: throw IllegalArgumentException("memory.compactModel '$id' is not in the model catalog")
    }
    private val extractModel = memoryConfig.sstm.extractModel.let { id ->
        modelCatalog.findModel(id)
            ?: throw IllegalArgumentException("memory.sstm.extractModel '$id' is not in the model catalog")
    }
    private val mergeModel = memoryConfig.sstm.mergeModel.let { id ->
        val model = modelCatalog.findModel(id)
            ?: throw IllegalArgumentException("memory.sstm.mergeModel '$id' is not in the model catalog")
        require(model.supports(LLMCapability.Output.ToolCalls)) {
            "memory.sstm.mergeModel '${model.id}' must support tool calls (the memory merge agent runs a tool loop)"
        }
        model
    }
    private val titleModel = config.title.model.let { id ->
        modelCatalog.findModel(id)
            ?: throw IllegalArgumentException("title.model '$id' is not in the model catalog")
    }

    // the ELTM models: REQUIRED config (`memory.eltm`), resolved once at
    // construction like the memory pipeline models. The writer/recall agents
    // run tool loops; the embedding model's output dimensions must not
    // exceed the fixed ELTM column width (validated at catalog construction).
    private val embeddingModel = config.memory.eltm.embeddingModel.let { id ->
        modelCatalog.findEmbeddingModel(id)
            ?: throw IllegalArgumentException("memory.eltm.embeddingModel '$id' is not in the model catalog")
    }
    private val writerModel = config.memory.eltm.writerModel.let { id ->
        val model = modelCatalog.findModel(id)
            ?: throw IllegalArgumentException("memory.eltm.writerModel '$id' is not in the model catalog")
        require(model.supports(LLMCapability.Output.ToolCalls)) {
            "memory.eltm.writerModel '${model.id}' must support tool calls (the ELTM writer runs a tool loop)"
        }
        model
    }
    private val recallModel = config.memory.eltm.recallModel.let { id ->
        val model = modelCatalog.findModel(id)
            ?: throw IllegalArgumentException("memory.eltm.recallModel '$id' is not in the model catalog")
        require(model.supports(LLMCapability.Output.ToolCalls)) {
            "memory.eltm.recallModel '${model.id}' must support tool calls (the recall sub-session runs a tool loop)"
        }
        model
    }

    // one-shot pipeline services: stateless across runs, so a single
    // instance is shared by every concurrent chat run. They talk to the
    // hand through the same `/v1/run` seam as the chat loop, carrying the
    // same `hand.*` policy knobs (transient retry budget, idle timeout);
    // the merge's round cap lives in SstmExtractionService.
    private val titleGenerator = TitleGenerator(
        model = titleModel,
        hand = handService,
        lastNRound = config.title.lastNRound,
        maxRetries = config.hand.maxRetries,
        streamIdleTimeoutMs = config.hand.streamIdleTimeoutMs,
    )
    private val compactionService = ChatCompactionService(
        model = compactModel,
        hand = handService,
        maxRetries = config.hand.maxRetries,
        streamIdleTimeoutMs = config.hand.streamIdleTimeoutMs,
    )

    private val eltmConfig = config.memory.eltm

    // the ELTM store: shared by the writer and (Phase 4) the recall
    // sub-session; embeddings go through the hand with the same retry
    // budget and the stream idle timeout as the embed timeout. Exposed for
    // the browse-only `/api/eltm` routes (WebServer.kt).
    val eltmService: EltmService = PostgresEltmService(
        embeddingModel = embeddingModel,
        hand = handService,
        entityMatchThreshold = eltmConfig.entityMatchThreshold,
        noteSearchThreshold = eltmConfig.noteSearchThreshold,
        maxRetries = config.hand.maxRetries,
        timeoutMs = config.hand.streamIdleTimeoutMs,
    )
    private val eltmWriterService = EltmWriterService(
        writerModel = writerModel,
        hand = handService,
        eltmService = eltmService,
        maxWriterRounds = eltmConfig.maxWriterRounds,
        maxRetries = config.hand.maxRetries,
        streamIdleTimeoutMs = config.hand.streamIdleTimeoutMs,
    )
    private val sstmExtractionService = SstmExtractionService(
        extractModel = extractModel,
        mergeModel = mergeModel,
        hand = handService,
        sstmService = sstmService,
        maxMergeRounds = config.memory.sstm.maxMergeRounds,
        maxRetries = config.hand.maxRetries,
        streamIdleTimeoutMs = config.hand.streamIdleTimeoutMs,
        eltmWriterService = eltmWriterService,
        sstmCapacity = config.memory.sstm.maxCapacity,
        purgeBatchSize = config.memory.sstm.purgeBatchSize,
    )
    private val persistService = PersistChatService(
        chatStore = chatStore,
        sstmService = sstmService,
        hand = handService,
        compactionService = compactionService,
        sstmExtractionService = sstmExtractionService,
        maxRounds = config.hand.maxRounds,
        maxRetries = config.hand.maxRetries,
        streamIdleTimeoutMs = config.hand.streamIdleTimeoutMs,
    )

    // one run per chat at a time: a chat's history is loaded and stored as a
    // whole, so concurrent runs would corrupt each other.
    // Entries exist only while a run is active or a history-mutating operation
    // (delete/truncate) is in progress: [acquireChatLock] and [withChatLock]
    // create them atomically, and [releaseChatLock] — the one eviction path
    // both go through — removes them, so arbitrary/deleted chat ids don't
    // accumulate.
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
     * Delete a chat row, but first run the SSTM extraction pipeline over its
     * full history so the chat's memories survive the deletion. Refuses
     * (throws [ChatRunConflictException]) while a run holds the chat lock:
     * the chat store's upsert would otherwise let an in-flight run's final
     * store resurrect the deleted row. Returns false when the chat doesn't
     * exist (nothing to extract, no LLM call; an empty chat extracts nothing).
     *
     * The lock is held for the whole operation — load, extraction (potentially
     * minutes of LLM calls) and the row delete — via [withChatLock], so no new
     * run can start (409) while the deletion is in progress.
     *
     * A failed extraction (a classified hand error, a truncated extractor
     * round, a model that cannot see the history) throws and FAILS the
     * delete: the row survives untouched, and the next delete attempt
     * re-extracts the same history, which the merge agent deduplicates.
     */
    suspend fun deleteChat(chatId: String): Boolean = withChatLock(chatId) {
        val entry = chatStore.load(chatId) ?: return@withChatLock false
        sstmExtractionService.processDiscardedMessages(entry.content.messages)
        chatStore.delete(chatId)
    }

    /**
     * Truncate a chat: drop every message from [index] (a user message) to
     * the end, keeping `messages[0..index-1]`. The dropped tail is discarded
     * WITHOUT SSTM extraction — deliberately: a typo'd turn must not leak
     * into memories. Returns false when the chat doesn't exist; throws
     * [BadRequestException] when [index] is out of bounds, does not point at
     * a user message, or would leave the kept prefix ending mid-turn (a user
     * message whose predecessor is another user message — consecutive user
     * turns occur after a compaction, whose summary user message sits
     * directly before the preserved tail).
     *
     * Runs under the per-chat lock: an in-flight run's final store upsert
     * would otherwise resurrect the truncated tail (the same reason
     * [deleteChat] takes the lock). The stored `sstm_version` is reset to
     * `""`: the `sstms` table is untouched, but the kept history may no
     * longer cover the memories merged from the dropped tail, so the next
     * run must re-flag `sstm-updated` and re-inject the current memory list.
     */
    suspend fun truncateChat(chatId: String, index: Int): Boolean = withChatLock(chatId) {
        val entry = chatStore.load(chatId) ?: return@withChatLock false
        val messages = entry.content.messages
        if (index < 0 || index >= messages.size) {
            throw BadRequestException("Message index $index is out of bounds")
        }
        if (messages[index].role != ChatMessageRole.User) {
            throw BadRequestException("Message $index is not a user message, refusing to truncate")
        }
        val kept = messages.subList(0, index).toList()
        // the kept prefix must end with a completed assistant turn (or be
        // empty): a user message is always preceded by an assistant stop
        // message in a stored chat — EXCEPT after a compaction, where the
        // summary user message can directly precede the preserved tail's
        // first user message. Storing a prefix that ends mid-turn would
        // brick the chat on load (decodeChat validates), so refuse it with
        // a clear 400 instead of a defensive 500 from validateChat below.
        if (kept.isNotEmpty() && kept.last().role != ChatMessageRole.Assistant) {
            throw BadRequestException(
                "Refusing to truncate at message $index: the kept prefix would end " +
                        "mid-turn (a user message follows a user message, e.g. after a " +
                        "compaction), which the stored chat format cannot represent"
            )
        }
        // still validate defensively: a violating truncation would brick the
        // chat on load
        ChatCodec.validateChat(kept)
        chatStore.store(chatId, ChatContent(kept, ""))
        true
    }

    /**
     * Fork a chat: create a new chat whose history is the source chat's
     * `messages[0..index]` (inclusive), where [index] must point at an
     * assistant message that ended naturally (`finishReason == "stop"`) —
     * the fork's history is then a complete, valid chat by construction.
     * The source row is untouched. Returns null when the source chat doesn't
     * exist; throws [BadRequestException] when [index] is out of bounds or
     * does not point at a naturally finished assistant message.
     *
     * Takes no per-chat lock: it is a pure read + insert into a NEW row, so
     * a concurrent run can only make the fork reflect the committed state
     * without the in-flight turn (snapshot semantics) — nothing corrupts.
     * The fork's `sstm_version` starts as `""` (like a fresh chat): the fork
     * has never seen a memory list, so its first run must flag
     * `sstm-updated`.
     */
    suspend fun forkChat(chatId: String, index: Int): ChatInfo? {
        val entry = chatStore.load(chatId) ?: return null
        val messages = entry.content.messages
        if (index < 0 || index >= messages.size) {
            throw BadRequestException("Message index $index is out of bounds")
        }
        val message = messages[index]
        if (message.role != ChatMessageRole.Assistant ||
            message.finishReason?.lowercase() != "stop"
        ) {
            throw BadRequestException(
                "Message $index is not a naturally finished assistant message, refusing to fork"
            )
        }
        val kept = messages.subList(0, index + 1).toList()
        ChatCodec.validateChat(kept)
        val forked = chatStore.newChat()
        chatStore.store(forked.id, ChatContent(kept, ""))
        return forked
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
     * chat is locked by a run or a deletion in progress. The caller must
     * unlock the result via [releaseChatLock].
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
                throw ChatRunConflictException("Chat '$chatId' is currently locked")
            }
            acquired = mutex
            mutex
        }
        return acquired!!
    }

    /**
     * Run [block] while holding the per-chat lock, or throw
     * [ChatRunConflictException] when the chat is locked by a run or another
     * history-mutating operation in progress. The lock entry is taken
     * atomically with the `tryLock` ([ConcurrentHashMap.compute] serializes
     * both map ops); [releaseChatLock] removes the entry BEFORE the unlock,
     * so the block's holder keeps working on its mutex while the next
     * acquirer gets a fresh one — never two concurrent holders.
     */
    private suspend fun <T> withChatLock(chatId: String, block: suspend () -> T): T {
        var lock: Mutex? = null
        chatLocks.compute(chatId) { _, existing ->
            val mutex = existing ?: Mutex()
            if (!mutex.tryLock()) {
                throw ChatRunConflictException("Chat '$chatId' is currently locked")
            }
            lock = mutex
            mutex
        }
        val mutex = lock!!
        try {
            return block()
        } finally {
            releaseChatLock(chatId, mutex)
        }
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
     * The tool callback wiring is handled by [HandService]: a fresh runId is
     * generated per hand `/v1/run` call, the in-flight run is registered
     * before the request goes out and evicted when the stream ends — the
     * chat loop never sees a runId.
     *
     * The compaction/extraction services are shared, constructed once at
     * startup; the one-shot pipeline models are fixed at construction (the
     * run's model is only used for the chat round itself).
     */
    suspend fun runChat(
        setup: ChatRunSetup,
        callback: StreamingExecutionCallback,
    ) {
        persistService.runChat(
            chatId = setup.chatId,
            model = setup.model,
            userParts = setup.parts,
            systemPrompt = systemPrompt,
            toolProvider = toolProvider,
            callback = callback,
        )
    }

    /**
     * Close the shared MCP clients and the hand HTTP client (called from
     * the JVM shutdown hook registered in `WebServer.startWebServer`).
     */
    override fun close() {
        toolProvider.close()
        handService.close()
    }

    companion object {
        // `.+` with DOT_MATCHES_ALL: data URLs may fold base64 across lines
        private val dataUrlRegex = Regex(
            """^data:(image/[a-zA-Z0-9.+-]+);base64,(.+)$""",
            RegexOption.DOT_MATCHES_ALL,
        )
    }
}
