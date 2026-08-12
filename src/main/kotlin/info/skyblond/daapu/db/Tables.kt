package info.skyblond.daapu.db

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * Chats.
 */
object Chats : Table("chats") {
    val id = text("id")
    // TODO: wire it up when the chat list shows titles, also add rename chat title in service
    val title = text("title")
    val chatJson = text("chat_json").default("[]")

    override val primaryKey = PrimaryKey(id)
}

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