package info.skyblond.daapu.db

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the [newChatId] format (`$timeMillis-$userId-$randomInt`): the id
 * doubles as koog's opaque conversation id and the `chats.id` primary key,
 * and the millis prefix keeps ids lexicographically sortable by creation
 * time.
 */
class ChatIdsTest {

    @Test
    fun `chat id has the expected format`() {
        val before = System.currentTimeMillis()
        val id = newChatId("42")
        val after = System.currentTimeMillis()

        val parts = id.split("-")
        assertEquals(3, parts.size, "Expected \$timeMillis-\$userId-\$randomInt, got: $id")
        val millis = parts[0].toLong()
        assertTrue(millis in before..after, "millis prefix out of range: $millis")
        assertEquals("42", parts[1])
        val random =
            assertNotNull(parts[2].toIntOrNull(), "random part should be an int: ${parts[2]}")
        assertTrue(random >= 0, "random part should be non-negative: $random")
    }

    @Test
    fun `chat id defaults to the placeholder user id`() {
        assertTrue(newChatId().contains("-$DEFAULT_USER_ID-"))
    }

    @Test
    fun `chat ids are unique across many generations`() {
        // a collision requires the same millisecond AND the same random int
        val ids = (1..1_000).map { newChatId() }
        assertEquals(ids.size, ids.toSet().size)
    }
}
