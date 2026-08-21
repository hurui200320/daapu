package info.skyblond.daapu.server

import info.skyblond.daapu.agent.ModelCatalog
import info.skyblond.daapu.agent.chat.*
import info.skyblond.daapu.agent.model.LLM
import info.skyblond.daapu.agent.model.LLMCapability
import info.skyblond.daapu.agent.oneshot.TitleGenerator
import info.skyblond.daapu.agent.oneshot.sstm.SstmExtractionService
import info.skyblond.daapu.agent.persist.PersistChatService
import info.skyblond.daapu.agent.persist.StreamingExecutionCallback
import info.skyblond.daapu.agent.persist.renderMainAgentSystemPrompt
import info.skyblond.daapu.agent.tool.CombinedToolProvider
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
 * Pure constructor injection: the whole object graph — the hand seam, the
 * stores, the one-shot pipeline services, the models — is assembled by the
 * Koin container (`di/DaapuModule.kt`) and injected here. Resolving this
 * root (eagerly, in `WebServer.startWebServer`) runs every definition, so
 * the fail-fast config validation (REQUIRED `memory.*`/`title.model` ids,
 * tool-call capability, the eager MCP connect) fires at startup, never
 * mid-run. Tests assemble the same module with fake seams overridden (see
 * `testutil/TestDi.kt`).
 *
 * The class only holds what it uses: the chat store, the model catalog,
 * the session-title generator, the chat loop's tool set, the SSTM
 * extraction pipeline (deletion) and the persist loop. The stores and the
 * hand callback service live in the container and are consumed directly by
 * the web server module; cleanup is Koin's job too (`onClose` on the
 * `HandService`/`CombinedToolProvider` definitions, triggered by
 * `koinApp.close()` in the shutdown hook) — this service owns no resources
 * itself. The one-shot pipeline services are stateless across runs and are
 * constructed once as well; their models come from the REQUIRED
 * `memory.compactModel` + `memory.sstm.extractModel/mergeModel` +
 * `title.model` config, resolved once at startup (never the run's model).
 */
class ChatRunService(
    private val chatStore: ChatStore,
    private val modelCatalog: ModelCatalog,
    private val titleGenerator: TitleGenerator,
    /**
     * The chat loop's tool set: the MCP servers plus the read-only ELTM
     * tools (`eltm__*`, see `agent/oneshot/eltm/EltmToolProvider.kt`), so
     * the main agent can query the external long-term memory directly (the
     * `recall` sub-session tool that offloads this is deferred). The MCP
     * child is only included when it serves namespaces. Exposed for tests.
     */
    internal val chatToolProvider: CombinedToolProvider,
    private val sstmExtractionService: SstmExtractionService,
    private val persistService: PersistChatService,
) {

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
     * The stored `eltm_version` is reset to `""` the same way: the `eltm_*`
     * tables are untouched, but the kept history may no longer cover what
     * was purged from the dropped tail, so the next run must re-flag
     * `eltm-updated`.
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
        chatStore.store(chatId, ChatContent(kept, "", ""))
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
     * The fork's `sstm_version` and `eltm_version` start as `""` (like a
     * fresh chat): the fork has never seen a memory list or the ELTM, so its
     * first run must flag `sstm-updated`/`eltm-updated`.
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
        chatStore.store(forked.id, ChatContent(kept, "", ""))
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
            systemPrompt = renderMainAgentSystemPrompt(true),
            toolProvider = chatToolProvider,
            callback = callback,
        )
    }

    companion object {
        // `.+` with DOT_MATCHES_ALL: data URLs may fold base64 across lines
        private val dataUrlRegex = Regex(
            """^data:(image/[a-zA-Z0-9.+-]+);base64,(.+)$""",
            RegexOption.DOT_MATCHES_ALL,
        )
    }
}
