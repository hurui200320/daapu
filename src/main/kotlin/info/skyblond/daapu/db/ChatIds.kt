package info.skyblond.daapu.db

import kotlin.random.Random

/**
 * Generate a snowflake-like chat id: `$timeMillis-$randomInt`.
 *
 * The millis prefix keeps ids lexicographically sortable by creation time. The
 * random part (full non-negative Int range) guards against two chats created
 * in the same millisecond colliding. The id is only ever stored as a string
 * and used as the `chats` primary key — never decomposed into parts; the
 * `GET /api/chats` cursor validates only its shape (see `chatCursorParam` in
 * `server/endpoint/ChatsRoute.kt`).
 */
fun newChatId(): String =
    "${System.currentTimeMillis()}-${Random.nextInt(0, Int.MAX_VALUE)}"
