package info.skyblond.daapu.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import java.time.OffsetDateTime

/**
 * Mirror of the `users` table created by `V1__init.sql`.
 *
 * `timestampWithTimeZone` maps to `TIMESTAMPTZ`; [OffsetDateTime] round-trips
 * cleanly with the column.
 */
object Users : Table("users") {
    val id = long("id").autoIncrement()
    val username = varchar("username", 255).uniqueIndex()
    val passwordHash = varchar("password_hash", 255)
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestampWithTimeZone)

    override val primaryKey = PrimaryKey(id)
}

/**
 * Mirror of the `chats` table created by `V2__chats_messages.sql`.
 */
object Chats : Table("chats") {
    val id = long("id").autoIncrement()
    val userId = long("user_id").references(Users.id, onDelete = ReferenceOption.CASCADE)
    val title = varchar("title", 255).default("New chat")
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestampWithTimeZone)
    val updatedAt = timestampWithTimeZone("updated_at").defaultExpression(CurrentTimestampWithTimeZone)

    override val primaryKey = PrimaryKey(id)
}

/**
 * Mirror of the `messages` table created by `V3__messages_koog.sql`.
 *
 * Each row stores a serialized koog `Message` object; the role/content columns
 * were removed because koog's ChatMemory feature owns the conversation history.
 * The row still carries `chat_id` so we know which message belongs to which chat.
 */
object Messages : Table("messages") {
    val id = long("id").autoIncrement()
    val chatId = long("chat_id").references(Chats.id, onDelete = ReferenceOption.CASCADE)
    val messageJson = text("message_json")
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestampWithTimeZone)

    override val primaryKey = PrimaryKey(id)
}
