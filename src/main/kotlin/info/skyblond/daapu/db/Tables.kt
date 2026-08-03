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

enum class MessageRole(val dbValue: String) {
    USER("user"),
    ASSISTANT("assistant");

    companion object {
        fun fromDbValue(value: String): MessageRole = entries.first { it.dbValue == value }
    }
}

/**
 * Mirror of the `messages` table created by `V2__chats_messages.sql`.
 *
 * `role` is stored as a string and mapped to [MessageRole] in the service layer;
 * a custom column type keeps this table definition simple.
 */
object Messages : Table("messages") {
    val id = long("id").autoIncrement()
    val chatId = long("chat_id").references(Chats.id, onDelete = ReferenceOption.CASCADE)
    val role = varchar("role", 16)
    val content = text("content")
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestampWithTimeZone)

    override val primaryKey = PrimaryKey(id)
}
