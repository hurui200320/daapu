package info.skyblond.daapu.agent.chat

import kotlinx.serialization.json.Json

/**
 * JSON codec for the neutral chat format ([ChatMessage]).
 *
 * Configuration mirrors the old koog-format codec: unknown keys are tolerated
 * on decode (a newer format may add fields), nulls are omitted on encode, and
 * defaults (e.g. `isError = false`) are written explicitly — the stored JSON
 * carries the full value, never an implicit default.
 *
 * Decode also validates the payload: [decodeChat] enforces the invariants
 * that keep a stored chat re-sendable to providers ([validateChat]), while
 * [decodeSnapshot] enforces the looser snapshot set ([validateSnapshot]).
 * A violating stored chat fails fast with the chat named, on every load
 * path (run loads and `GET /chat`), instead of surfacing later as an
 * opaque gateway 400 or an unbounded retry.
 */
object ChatCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        // defaults are part of the stored format (see the class KDoc):
        // writing them explicitly keeps encode independent of the code's
        // current default values
        encodeDefaults = true
    }

    fun encodeChat(chat: List<ChatMessage>): String = json.encodeToString(chat)

    /**
     * Decode a stored chat payload, failing fast on corruption or an
     * incompatible format instead of silently resetting the chat to empty.
     */
    fun decodeChat(chatId: String, chatJson: String): List<ChatMessage> =
        decode(chatId, chatJson, ::validateChat)

    /**
     * Decode an EXTRACTION SNAPSHOT payload (the `pending_extractions`
     * queue jobs, see `memory/eltm/ExtractionQueue.kt`): the same
     * corruption/format failing fast as [decodeChat], validated with
     * [validateSnapshot] instead of [validateChat] — a snapshot is a
     * history fragment, not necessarily a complete chat.
     */
    fun decodeSnapshot(chatId: String, chatJson: String): List<ChatMessage> =
        decode(chatId, chatJson, ::validateSnapshot)

    private fun decode(
        chatId: String,
        chatJson: String,
        validate: (List<ChatMessage>) -> Unit,
    ): List<ChatMessage> =
        try {
            json.decodeFromString<List<ChatMessage>>(chatJson).also(validate)
        } catch (e: Exception) {
            throw IllegalStateException(
                "Failed to decode chat '$chatId': ${e.message ?: e.toString()}",
                e,
            )
        }

    /**
     * Validate a chat: the invariants that keep a stored chat re-sendable to
     * providers (see the class KDoc). Internal so history-mutating operations
     * (e.g. truncation in [info.skyblond.daapu.agent.chat.ChatService]) can
     * check their result before storing.
     */
    internal fun validateChat(chat: List<ChatMessage>) {
        validateMessageAnchors(chat)
        // last message (if has one) must be assistant message with reason stop
        chat.lastOrNull()?.let {
            require(it.role == ChatMessageRole.Assistant) {
                "Last message is not assistant, this is not a complete chat: ${it.role}"
            }
            require(it.finishReason?.lowercase() == "stop") {
                "Last message is not naturally finished (reason should be 'stop'): ${it.finishReason}"
            }
        }
        validateToolPairs(chat)
    }

    /**
     * Validate an EXTRACTION SNAPSHOT (the `pending_extractions` payloads):
     * the per-message invariants of [validateChat] WITHOUT the
     * stored-chat completeness rule. The queue carries history FRAGMENTS —
     * a compaction drop region can legitimately end mid-turn (e.g. a fresh
     * chat's full-body reactive compaction drops the whole chat ending with
     * the run's user message, see
     * `agent/pipeline/compaction/ChatCompactionService.splitMessage`) — so
     * the trailing assistant-stop requirement does not hold for them. The
     * invariants that DO hold (and are enforced): user messages carry
     * `createdAt` (the extractor's `<meta>` anchoring is regenerated from
     * it), and tool calls/results stay paired (the compaction cuts at
     * user-turn boundaries, which never splits a pair).
     */
    internal fun validateSnapshot(chat: List<ChatMessage>) {
        validateMessageAnchors(chat)
        validateToolPairs(chat)
    }

    /**
     * The per-message invariant shared by [validateChat] and
     * [validateSnapshot]: every user message must carry its send time — the
     * per-request `<meta>` time anchors are regenerated from it, so a
     * message without one can never be time-anchored (old pre-feature rows
     * fail fast here on load).
     */
    private fun validateMessageAnchors(chat: List<ChatMessage>) {
        chat.forEach { message ->
            if (message.role == ChatMessageRole.User && message.createdAt == null) {
                throw IllegalArgumentException(
                    "A user message must carry a non-null createdAt"
                )
            }
        }
    }

    /**
     * The tool-pair invariants shared by [validateChat] and
     * [validateSnapshot]: unique tool-call ids and an exact 1:1
     * call/result pairing.
     */
    private fun validateToolPairs(chat: List<ChatMessage>) {
        val calls = chat.flatMap { it.parts }.filterIsInstance<ChatMessagePart.ToolCall>()
        val results = chat.flatMap { it.parts }.filterIsInstance<ChatMessagePart.ToolResult>()
        // call id must be unique, no duplicates
        val dedupToolCallIdCount = calls.map { it.id }.toSet().size
        require(dedupToolCallIdCount == calls.size) {
            "Tool call id has ${calls.size - dedupToolCallIdCount} duplicate ids"
        }
        // one call must have one result
        calls.forEach { call ->
            val resultCount = results.count { result -> call.id == result.id }
            if (resultCount == 0) {
                throw IllegalArgumentException("Tool call ${call.id} has no matching tool result")
            }
            if (resultCount > 1) {
                throw IllegalArgumentException("Tool call ${call.id} has $resultCount tool results")
            }
        }
        // no extra tool result: existing result must match a call
        results.forEach { result ->
            require(calls.any { call -> call.id == result.id }) {
                "Tool result ${result.id} has no matching tool call"
            }
        }
    }
}
