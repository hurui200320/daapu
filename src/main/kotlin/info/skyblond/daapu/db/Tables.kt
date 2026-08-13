package info.skyblond.daapu.db

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * Chats.
 */
object Chats : Table("chats") {
    val id = text("id")
    val title = text("title")
    val chatJson = text("chat_json").default("[]")

    override val primaryKey = PrimaryKey(id)
}

/**
 * The title a chat starts with; mirrors the `chats.title` column default in
 * `V1__init.sql` (kept in sync manually so inserts state the title explicitly).
 */
const val DEFAULT_CHAT_TITLE = "New chat"

/**
 * Shared Short Term Memories.
 */
object SSTMs : Table("sstms") {
    val id = long("id").autoIncrement()
    val lastUpdate = timestamp("last_update")
        .defaultExpression(CurrentTimestamp)
    val content = text("content")

    override val primaryKey = PrimaryKey(id)
}