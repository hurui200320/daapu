package info.skyblond.daapu.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import java.time.OffsetDateTime

/**
 * Mirror of the `chats` table created by `V1__init.sql`.
 */
object Chats : Table("chats") {
    val id = long("id").autoIncrement()
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestampWithTimeZone)

    override val primaryKey = PrimaryKey(id)
}

/**
 * Mirror of the `messages` table created by `V1__init.sql`.
 *
 * Each row stores a serialized koog `Message` object; koog's ChatMemory feature
 * owns the conversation history. The row carries `chat_id` so we know which
 * message belongs to which chat.
 */
object Messages : Table("messages") {
    val id = long("id").autoIncrement()
    val chatId = long("chat_id").references(Chats.id, onDelete = ReferenceOption.CASCADE)
    val messageJson = text("message_json")
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestampWithTimeZone)

    override val primaryKey = PrimaryKey(id)
}
