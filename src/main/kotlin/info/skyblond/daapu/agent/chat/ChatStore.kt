package info.skyblond.daapu.agent.chat

import info.skyblond.daapu.agent.persona.DEFAULT_PERSONA_ID
import kotlinx.serialization.Serializable

/**
 * The title a chat starts with; mirrors the `chats.title` column default in
 * `V1__init.sql` (kept in sync manually so inserts state the title explicitly).
 */
const val DEFAULT_CHAT_TITLE = "New chat"

/**
 * One chat row: id + the user-visible title + the persona RECORD (the
 * persona id of the chat's last successful run; the UI pre-fills its picker
 * from it). The wire shape of one `GET /api/chats` entry and the
 * rename/title endpoints.
 */
@Serializable
data class ChatInfo(val id: String, val title: String, val personaId: Long)

/**
 * One page of `GET /api/chats` (keyset pagination — the cursor is the last
 * chat id of the previous page, anchoring a POSITION in the newest-first
 * `id` order, so concurrent deletes between pages can never skip a row).
 * [nextCursor] is null/absent when the list is exhausted; the wire omits a
 * null cursor (`encodeDefaults = false` — the ContentNegotiation Json in
 * `server/WebServer.kt`), so absence means "no more pages".
 */
@Serializable
data class ChatListPage(val chats: List<ChatInfo>, val nextCursor: String? = null)

/**
 * One chat row's content: the message history, the ELTM version fingerprint
 * of the last successful run, and the persona RECORD of that run (stamped
 * by the store upsert, never used for prompt/tool resolution — the run's
 * persona travels in the request).
 */
data class ChatContent(
    val messages: List<ChatMessage>,
    val eltmVersion: String,
    val personaId: Long,
)

data class ChatEntry(
    val info: ChatInfo,
    val content: ChatContent
)

/**
 * The `chats`-table seam: every raw database access to chat rows (list,
 * create, rename, delete, message load/store) lives here, so callers
 * (`agent/chat/ChatService.kt` and the turn loop) never touch Exposed
 * directly. The turn loop's store upsert writes `id` + `chat_json` +
 * `eltm_version` + `persona_id` — never the title.
 */
interface ChatStore {
    /**
     * One page of chat rows as `id` + `title` (+ persona record), newest
     * first (keyset pagination on the immutable snowflake id — see
     * [ChatListPage]); [cursor] is the previous page's `nextCursor` (null =
     * first page).
     */
    suspend fun listChats(cursor: String?): ChatListPage

    /**
     * Insert a row with the given title (the default title unless named —
     * the chat import reuses the exported title), an empty history and
     * [personaId] as the record.
     */
    suspend fun newChat(personaId: Long = DEFAULT_PERSONA_ID, title: String = DEFAULT_CHAT_TITLE): ChatInfo

    /** The full chat row, or null when the chat doesn't exist. */
    suspend fun load(chatId: String): ChatEntry?

    /** Store the chat's messages (upsert on `id`; never touches the title). */
    suspend fun store(chatId: String, chat: ChatContent)

    /** Rename; returns the updated row, or null when the chat doesn't exist. */
    suspend fun rename(chatId: String, title: String): ChatInfo?

    /** Delete the row; returns whether a row was deleted. */
    suspend fun delete(chatId: String): Boolean
}
