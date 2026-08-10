package info.skyblond.daapu.db

import kotlin.random.Random

/**
 * Placeholder user id used in chat ids until real users exist.
 */
const val DEFAULT_USER_ID = "0"

/**
 * Generate a snowflake-like chat id: `$timeMillis-$userId-$randomInt`.
 *
 * The millis prefix keeps ids lexicographically sortable by creation time. The
 * random part (full non-negative Int range) guards against two chats created in
 * the same millisecond by the same user colliding. The id is only ever stored
 * as a string and used as the `chats` primary key, never parsed.
 */
// TODO: remove default value when we have user system
fun newChatId(userId: String = DEFAULT_USER_ID): String =
    "${System.currentTimeMillis()}-$userId-${Random.nextInt(0, Int.MAX_VALUE)}"
