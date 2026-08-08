package info.skyblond.daapu.history

import kotlinx.serialization.json.Json
import kotlin.time.Instant

/**
 * JSON codec for the neutral chat history format ([HistoryMessage]).
 *
 * Configuration mirrors the old koog-format codec: unknown keys are tolerated
 * on decode (a newer format may add fields), nulls are omitted on encode, and
 * defaults (e.g. `isError = false`) are not written — the format stays minimal.
 *
 * Decode also validates the invariants that keep stored history re-sendable to
 * providers (see [validateHistory]): a violating row fails fast with the chat
 * named, on every load path (run loads and `GET /history`), instead of
 * surfacing later as an opaque gateway 400 or an unbounded retry.
 */
object HistoryCodec {

    internal val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun encodeHistory(history: List<HistoryMessage>): String = json.encodeToString(history)

    /**
     * Decode a stored `history_json` payload, failing fast on corruption or an
     * incompatible format instead of silently resetting the chat to empty.
     */
    fun decodeHistory(conversationId: String, historyJson: String): List<HistoryMessage> =
        try {
            json.decodeFromString<List<HistoryMessage>>(historyJson).also { validateHistory(conversationId, it) }
        } catch (e: IllegalStateException) {
            // already chat-named by validateHistory
            throw e
        } catch (e: Exception) {
            throw IllegalStateException(
                "Failed to decode history_json for chat '$conversationId': the stored format " +
                        "is corrupt or incompatible with the current history format. " +
                        "Migrate or fix the chats row manually.",
                e
            )
        }

    /**
     * Fail fast on stored rows that would brick the chat when re-sent to a
     * provider (a chat whose history cannot load can never run again anyway):
     *
     * - an assistant message without `finishReason`: the streaming path only
     *   emits the terminal frame when the gateway sent a `finish_reason`
     *   (see `CustomOpenAILLMClient`), so its absence means the row was
     *   written by something that violated that invariant — e.g. a future
     *   non-streaming node appending an assistant message without one.
     * - a non-assistant message with a `finishReason`: `finishReason` is
     *   assistant-only, and the koog converter silently drops it on re-send,
     *   so a row carrying one is a broken invariant.
     * - a non-parseable `meta.timestamp`: the koog converter would fail on
     *   load with an opaque `Instant.parse` error instead of a clear one.
     * - a part its role cannot carry (e.g. a `reasoning` part in a `user`
     *   message): the koog converter would fail with a generic
     *   `error(...)` message not naming the chat.
     * - a blank `tool_call`/`tool_result` id: koog would assign mismatched
     *   random ids on re-send, and strict providers reject the request with a
     *   400 — the history would be rejected forever.
     */
    private fun validateHistory(conversationId: String, history: List<HistoryMessage>) {
        history.forEachIndexed { index, message ->
            val location = "chat '$conversationId', message #${index + 1}"
            if (message.role == HistoryRole.Assistant && message.finishReason.isNullOrBlank()) {
                throw IllegalStateException(
                    "Invalid history_json in $location: an assistant message must carry a " +
                            "finishReason, but it is missing."
                )
            }
            if (message.role != HistoryRole.Assistant && message.finishReason != null) {
                throw IllegalStateException(
                    "Invalid history_json in $location: finishReason is assistant-only, but the " +
                            "${message.role} message carries one."
                )
            }
            message.meta?.timestamp?.let { timestamp ->
                if (Instant.parseOrNull(timestamp) == null) {
                    throw IllegalStateException(
                        "Invalid history_json in $location: meta.timestamp '$timestamp' is not a " +
                                "parseable ISO-8601 instant."
                    )
                }
            }
            message.parts.forEach { part ->
                if (!message.role.allowsPart(part)) {
                    throw IllegalStateException(
                        "Invalid history_json in $location: a ${message.role} message cannot " +
                                "contain a ${part::class.simpleName} part."
                    )
                }
                when (part) {
                    is HistoryPart.ToolCall -> part.requireValidId(location)
                    is HistoryPart.ToolResult -> part.requireValidId(location)
                    else -> Unit
                }
            }
        }
    }

    /**
     * The parts a role may carry, mirroring the koog converter's per-role part
     * mapping (`koog/KoogHistoryConverters.kt`): anything else would fail
     * there with a generic `error(...)` message instead of one naming the chat.
     */
    private fun HistoryRole.allowsPart(part: HistoryPart): Boolean = when (this) {
        HistoryRole.System -> part is HistoryPart.Text
        HistoryRole.User ->
            part is HistoryPart.Text || part is HistoryPart.Attachment || part is HistoryPart.ToolResult
        HistoryRole.Assistant ->
            part is HistoryPart.Text || part is HistoryPart.Attachment ||
                    part is HistoryPart.Reasoning || part is HistoryPart.ToolCall
        HistoryRole.Tool -> part is HistoryPart.ToolResult
    }

    private fun HistoryPart.ToolCall.requireValidId(location: String) {
        if (id.isBlank()) {
            throw IllegalStateException(
                "Invalid history_json in $location: a tool_call must carry a stable non-blank id, " +
                        "otherwise the re-sent history is rejected by strict providers."
            )
        }
    }

    private fun HistoryPart.ToolResult.requireValidId(location: String) {
        if (id.isBlank()) {
            throw IllegalStateException(
                "Invalid history_json in $location: a tool_result must carry the non-blank id of " +
                        "the tool_call it answers."
            )
        }
    }
}
