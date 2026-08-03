package info.skyblond.daapu.chat

import info.skyblond.daapu.db.Chats
import info.skyblond.daapu.db.MessageRole
import info.skyblond.daapu.db.Messages
import info.skyblond.daapu.db.withTransaction
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

class ChatService {

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
     * Fetch the messages of [chatId]. Returns `null` when the chat does not
     * belong to [userId].
     */
    suspend fun listMessages(userId: Long, chatId: Long): List<ChatMessage>? = withTransaction {
        val owns = Chats.selectAll()
            .where { (Chats.id eq chatId) and (Chats.userId eq userId) }
            .any()
        if (!owns) {
            return@withTransaction null
        }
        Messages.selectAll()
            .where { Messages.chatId eq chatId }
            .orderBy(Messages.id, SortOrder.ASC)
            .map { row ->
                ChatMessage(
                    id = row[Messages.id],
                    role = MessageRole.fromDbValue(row[Messages.role]),
                    content = row[Messages.content],
                    createdAt = row[Messages.createdAt],
                )
            }
    }

    /**
     * Append a message to a chat. Returns the persisted message, or `null` when
     * the chat does not belong to [userId]. Touches `updated_at` so the chat
     * bubbles to the top of the list.
     */
    suspend fun appendMessage(
        userId: Long,
        chatId: Long,
        role: MessageRole,
        content: String
    ): ChatMessage? =
        withTransaction {
            val owns = Chats.selectAll()
                .where { (Chats.id eq chatId) and (Chats.userId eq userId) }
                .any()
            if (!owns) {
                null
            } else {
                Chats.update(
                    where = { Chats.id eq chatId }
                ) {
                    it[Chats.updatedAt] = java.time.OffsetDateTime.now()
                }
                val id = Messages.insert {
                    it[Messages.chatId] = chatId
                    it[Messages.role] = role.dbValue
                    it[Messages.content] = content
                } get Messages.id
                ChatMessage(
                    id = id,
                    role = role,
                    content = content,
                    createdAt = java.time.OffsetDateTime.now(),
                )
            }
        }
}
