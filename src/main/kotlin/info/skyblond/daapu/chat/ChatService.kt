package info.skyblond.daapu.chat

import ai.koog.prompt.message.Message
import info.skyblond.daapu.db.Chats
import info.skyblond.daapu.db.withTransaction
import info.skyblond.daapu.llm.PostgresChatHistoryProvider
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

/**
 * Chat metadata CRUD, plus a read view over the koog-managed message history.
 *
 * koog's `ChatMemory` owns the actual conversation (load before a run, store
 * after it completes) via [PostgresChatHistoryProvider]. This service only:
 * - manages the `chats` table (ownership, title, timestamps);
 * - verifies chat ownership;
 * - exposes a display view of the stored messages for the frontend.
 */
class ChatService(
    private val historyProvider: PostgresChatHistoryProvider,
) {

    /**
     * List the chats owned by [userId], newest first.
     */
    suspend fun listChats(userId: Long): List<Chat> = withTransaction {
        Chats.selectAll()
            .where { Chats.userId eq userId }
            .orderBy(Chats.updatedAt, SortOrder.DESC)
            .map { row ->
                Chat(
                    id = row[Chats.id],
                    title = row[Chats.title],
                    createdAt = row[Chats.createdAt],
                    updatedAt = row[Chats.updatedAt],
                )
            }
    }

    /**
     * Create a chat owned by [userId]. Returns its id.
     */
    suspend fun createChat(userId: Long): Long = withTransaction {
        Chats.insert {
            it[Chats.userId] = userId
            it[Chats.title] = "New chat"
        } get Chats.id
    }

    /**
     * Rename a chat. Returns the updated [Chat], or `null` when the chat does
     * not belong to [userId].
     */
    suspend fun renameChat(userId: Long, chatId: Long, title: String): Chat? =
        withTransaction {
            val updated = Chats.update(
                where = { (Chats.id eq chatId) and (Chats.userId eq userId) }
            ) {
                it[Chats.title] = title
                it[Chats.updatedAt] = java.time.OffsetDateTime.now()
            }
            if (updated == 0) {
                return@withTransaction null
            }

            Chats.selectAll()
                .where { Chats.id eq chatId }
                .single()
                .let { row ->
                    Chat(
                        id = row[Chats.id],
                        title = row[Chats.title],
                        createdAt = row[Chats.createdAt],
                        updatedAt = row[Chats.updatedAt],
                    )
                }
        }

    /**
     * Delete a chat (and its messages, via cascade). Returns `true` when a chat
     * owned by [userId] was deleted.
     */
    suspend fun deleteChat(userId: Long, chatId: Long): Boolean = withTransaction {
        val deleted = Chats.deleteWhere {
            (Chats.id eq chatId) and (Chats.userId eq userId)
        }
        deleted > 0
    }

    /**
     * Whether [chatId] belongs to [userId].
     */
    suspend fun ownsChat(userId: Long, chatId: Long): Boolean = withTransaction {
        Chats.selectAll()
            .where { (Chats.id eq chatId) and (Chats.userId eq userId) }
            .any()
    }

    /**
     * Fetch the display messages of [chatId]. Returns `null` when the chat does
     * not belong to [userId].
     *
     * Only user/assistant messages are surfaced; system and tool messages that
     * koog persisted for its own history are hidden from the chat view.
     */
    suspend fun listMessages(userId: Long, chatId: Long): List<ChatMessage>? {
        if (!ownsChat(userId, chatId)) {
            return null
        }
        return historyProvider.listByChat(chatId).mapNotNull { stored ->
            when (val message = stored.message) {
                is Message.User -> ChatMessage(
                    id = stored.id,
                    role = MessageRole.USER,
                    content = message.textContent(),
                    createdAt = stored.createdAt,
                )

                is Message.Assistant -> ChatMessage(
                    id = stored.id,
                    role = MessageRole.ASSISTANT,
                    content = message.textContent(),
                    createdAt = stored.createdAt,
                )

                else -> null
            }
        }
    }
}
