package info.skyblond.daapu.agent.chat

import kotlinx.serialization.Serializable

/**
 * One chat row: id + the user-visible title. The wire shape of
 * `GET /api/chats` and the rename/title endpoints.
 */
@Serializable
data class ChatInfo(val id: String, val title: String)

/**
 * One chat row's content: the message history plus the ELTM version
 * fingerprint of the last successful run.
 */
data class ChatContent(
    val messages: List<ChatMessage>,
    val eltmVersion: String
)

data class ChatEntry(
    val info: ChatInfo,
    val content: ChatContent
)

/**
 * The `chats`-table seam: every raw database access to chat rows (list,
 * create, rename, delete, message load/store) lives here, so callers
 * (`server/ChatRunService.kt` and the turn loop) never touch Exposed
 * directly. The turn loop's store upsert writes only `id` + `chat_json` +
 * `eltm_version` — never the title.
 */
interface ChatStore {
    /** All chat rows as `id` + `title`, newest first, capped. */
    suspend fun listChats(): List<ChatInfo>

    /** Insert a row with the default title and an empty history. */
    suspend fun newChat(): ChatInfo

    /** The full chat row, or null when the chat doesn't exist. */
    suspend fun load(chatId: String): ChatEntry?

    /** Store the chat's messages (upsert on `id`; never touches the title). */
    suspend fun store(chatId: String, chat: ChatContent)

    /** Rename; returns the updated row, or null when the chat doesn't exist. */
    suspend fun rename(chatId: String, title: String): ChatInfo?

    /** Delete the row; returns whether a row was deleted. */
    suspend fun delete(chatId: String): Boolean
}
