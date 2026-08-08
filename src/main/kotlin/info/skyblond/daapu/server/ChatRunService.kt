package info.skyblond.daapu.server

import ai.koog.agents.chatMemory.feature.ChatHistoryProvider
import ai.koog.prompt.executor.clients.openai.OpenAIChatParams
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.streaming.StreamFrame
import info.skyblond.daapu.AppConfig
import info.skyblond.daapu.agent.StreamExecutionCallback
import info.skyblond.daapu.agent.buildChatAgent
import info.skyblond.daapu.agent.renderSystemPrompt
import info.skyblond.daapu.db.Chats
import info.skyblond.daapu.db.newChatId
import info.skyblond.daapu.db.withTransaction
import info.skyblond.daapu.history.HistoryCodec
import info.skyblond.daapu.history.HistoryMessage
import info.skyblond.daapu.koog.PostgresChatHistoryProvider
import info.skyblond.daapu.koog.client.CustomOpenAILLMClient
import info.skyblond.daapu.koog.client.findModel
import info.skyblond.daapu.koog.client.modelCatalog
import io.ktor.server.plugins.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonPrimitive
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
    val model: LLModel,
    val parts: List<MessagePart.RequestPart>,
)

/**
 * Service for executing an agent request.
 *
 * One agent is built per request (cheap: koog's `AIAgent.close()` is a no-op
 * and each `run()` gets a fresh session context), so per-request model and
 * callback selection comes for free; only the expensive pieces — the LLM
 * executor, the history provider, the system prompt — are shared.
 */
class ChatRunService(config: AppConfig) {

    private val promptExecutor = MultiLLMPromptExecutor(
        CustomOpenAILLMClient(
            config.llmApiKey,
            OpenAIClientSettings(baseUrl = config.llmBaseUrl)
        )
    )

    private val historyProvider: ChatHistoryProvider = PostgresChatHistoryProvider()
    private val systemPrompt = renderSystemPrompt("Raven", true)
    private val llmParameter = OpenAIChatParams(
        additionalProperties = mapOf(
            "reasoning_effort" to JsonPrimitive("high")
        )
    )

    // one run per chat at a time: a chat's history is loaded and stored as a
    // whole, so concurrent runs would corrupt each other.
    // Entries exist only while a run is active or a delete is in progress:
    // [acquireChatLock] creates them atomically, [releaseChatLock] and
    // [deleteChat] evict them, so arbitrary/deleted chat ids don't accumulate.
    // TODO: distributed lock in production
    private val chatLocks = ConcurrentHashMap<String, Mutex>()

    fun models(): List<ModelInfo> = modelCatalog.map {
        ModelInfo(
            id = it.id,
            vision = it.supports(LLMCapability.Vision.Image),
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
     * run holds the chat lock: [PostgresChatHistoryProvider.store] is an
     * upsert, so an in-flight run's final store would resurrect the deleted
     * row. Returns false when the chat doesn't exist.
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

    /**
     * The stored history for a chat as the neutral format
     * ([HistoryMessage]s). The stored JSON is decoded with [HistoryCodec]
     * before serving, so a corrupt row fails fast with a clear error instead
     * of leaking invalid JSON to the API. `[]` when the chat doesn't exist yet.
     */
    suspend fun history(chatId: String): List<HistoryMessage> = withTransaction {
        Chats.selectAll()
            .where { Chats.id eq chatId }
            .singleOrNull()
            ?.get(Chats.historyJson)
            ?: "[]"
    }.let { HistoryCodec.decodeHistory(chatId, it) }

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
            findModel(id) ?: throw BadRequestException("Unknown model '$id'")
        } ?: throw BadRequestException("model is required")
        val parts = mutableListOf<MessagePart.RequestPart>()
        if (text.isNotBlank()) parts += MessagePart.Text(text)
        request.images.forEach { parts += parseImagePart(it) }
        return ChatRunSetup(chatId, model, parts)
    }

    /**
     * Serialize a data URL (`data:image/png;base64,...`) into a koog image
     * attachment part.
     */
    private fun parseImagePart(image: ImagePart): MessagePart.Attachment {
        val match = dataUrlRegex.matchEntire(image.dataUrl.trim())
            ?: throw BadRequestException("Invalid image data URL")
        val mimeType = match.groupValues[1]
        val base64 = match.groupValues[2].filterNot { it.isWhitespace() }
        // validate early so a malformed payload fails with a clear 400 instead
        // of an opaque gateway error mid-stream
        runCatching { Base64.decode(base64) }
            .getOrElse { throw BadRequestException("Invalid base64 in image data URL") }
        return MessagePart.Attachment(
            source = AttachmentSource.Image(
                content = AttachmentContent.Binary.Base64(base64),
                format = mimeType.substringAfter("image/"),
                mimeType = mimeType,
            )
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
     * Run one agent turn for [setup], forwarding stream events to [sendEvent]
     * (an SSE writer). History is only stored by ChatMemory when the run
     * completes, so a failed or aborted run leaves the chat untouched.
     */
    suspend fun runChat(
        setup: ChatRunSetup,
        sendEvent: suspend (event: String, data: String) -> Unit
    ) {
        buildChatAgent(
            promptExecutor, historyProvider, systemPrompt, setup.model, llmParameter,
            streamEventCallback(sendEvent),
        ).run(setup.parts, sessionId = setup.chatId)
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
 * The [StreamExecutionCallback] that maps stream frames and tool results to
 * SSE events — the contract the frontend (`frontend/src/lib/api.ts`) parses.
 * Extracted from [ChatRunService.runChat] so the exact event payloads can be
 * unit-tested.
 */
internal fun streamEventCallback(
    sendEvent: suspend (event: String, data: String) -> Unit,
): StreamExecutionCallback = object : StreamExecutionCallback {
    override suspend fun onFrame(frame: StreamFrame) {
        // send delta frames to frontend so it provides realtime view of response
        when (frame) {
            is StreamFrame.ReasoningDelta -> sendEvent(
                "reasoning",
                sseData("delta" to frame.text.orEmpty())
            )

            is StreamFrame.TextDelta -> sendEvent(
                "text",
                sseData("delta" to frame.text)
            )

            is StreamFrame.ToolCallComplete ->
                sendEvent(
                    "tool_call",
                    sseData(
                        "name" to frame.name,
                        "args" to frame.content
                    )
                )
            // the remaining frames (completion markers, tool-call
            // deltas, End) carry no user-visible content of their own
            else -> Unit
        }
    }

    override suspend fun onToolResults(results: List<MessagePart.Tool.Result>) {
        // stream tool results as they are produced; the frontend shows
        // them live (the `done` history reload re-renders them anyway)
        results.forEach { result ->
            sendEvent(
                "tool_result",
                buildJsonObject {
                    put("id", result.id ?: "")
                    put("name", result.tool)
                    put("content", result.output)
                    put("isError", result.isError)
                }.toString()
            )
        }
    }

    override suspend fun onAssistantMessage(message: Message.Assistant) {
        // no-op: the frontend syncs via the `done` event's history reload
        // (a full-message `assistant` event had no consumer)
    }

    override suspend fun onStreamError(error: Throwable) {
        // the stream hit a transient error and will be retried
        // frontend should clear the current round (after previous tool call)
        sendEvent("retry", sseData("message" to (error.message ?: error.toString())))
    }
}

private fun sseData(vararg pairs: Pair<String, String>): String =
    buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }.toString()
