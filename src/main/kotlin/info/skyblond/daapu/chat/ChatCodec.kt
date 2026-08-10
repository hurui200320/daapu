package info.skyblond.daapu.chat

import info.skyblond.daapu.chat.ChatCodec.validateChat
import kotlinx.serialization.json.Json

/**
 * JSON codec for the neutral chat history format ([ChatMessage]).
 *
 * Configuration mirrors the old koog-format codec: unknown keys are tolerated
 * on decode (a newer format may add fields), nulls are omitted on encode, and
 * defaults (e.g. `isError = false`) are written explicitly — the stored JSON
 * carries the full value, never an implicit default.
 *
 * Decode also validates the invariants that keep stored history re-sendable to
 * providers (see [validateChat]): a violating row fails fast with the chat
 * named, on every load path (run loads and `GET /history`), instead of
 * surfacing later as an opaque gateway 400 or an unbounded retry.
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
     * Decode a stored chat history payload, failing fast on corruption or an
     * incompatible format instead of silently resetting the chat to empty.
     */
    fun decodeChat(chatId: String, chatJson: String): List<ChatMessage> =
        try {
            json.decodeFromString<List<ChatMessage>>(chatJson).also {
                validateChat(it)
            }
        } catch (e: Exception) {
            throw IllegalStateException(
                "Failed to decode chat '$chatId': ${e.message ?: e.toString()}",
                e,
            )
        }

    /**
     * validate a chat.
     */
    private fun validateChat(history: List<ChatMessage>) {
        // last message (if has one) must be assistant message with reason stop
        history.lastOrNull()?.let {
            require(it.role == ChatMessageRole.Assistant) {
                "Last message is not assistant, this is not a complete chat: ${it.role}"
            }
            require(it.finishReason?.lowercase() == "stop") {
                "Last message is not naturally finished (reason should be 'stop'): ${it.finishReason}"
            }
        }
        val calls = history.flatMap { it.parts }.filterIsInstance<ChatMessagePart.ToolCall>()
        val results = history.flatMap { it.parts }.filterIsInstance<ChatMessagePart.ToolResult>()
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
