package info.skyblond.daapu.agent.tool

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * The strict/lenient matrix of the shared tool-argument extraction
 * ([textArg]/[longArg]/[intArg]/[boolArg]/[stringArrayArg]): the lenient
 * mode serves the LLM-authored ELTM/GSG tools (unparseable → null, the
 * caller answers its own "required" error), the strict mode mirrors the
 * filesystem server's zod schemas (wrong-typed → [IllegalArgumentException]
 * with the key in the message).
 */
class ToolArgsTest {

    // ---------- textArg ----------

    @Test
    fun `lenient text trims strings and coerces other primitives`() {
        assertEquals("hello", buildJsonObject { put("k", " hello ") }.textArg("k"))
        assertNull(buildJsonObject { }.textArg("k"), "absent")
        assertNull(buildJsonObject { put("k", "  ") }.textArg("k"), "blank")
        assertEquals("5", buildJsonObject { put("k", 5) }.textArg("k"), "number coerced")
        assertEquals("true", buildJsonObject { put("k", true) }.textArg("k"), "bool coerced")
        assertNull(buildJsonObject { put("k", JsonNull) }.textArg("k"), "json null")
    }

    @Test
    fun `lenient text still fails a non-primitive value`() {
        assertFailsWith<IllegalArgumentException> {
            buildJsonObject { putJsonObject("k") { } }.textArg("k")
        }
    }

    @Test
    fun `strict text keeps strings and rejects wrong types like zod`() {
        assertEquals("hello", buildJsonObject { put("k", " hello ") }.textArg("k", strict = true))
        assertNull(buildJsonObject { }.textArg("k", strict = true), "absent")
        assertNull(buildJsonObject { put("k", " ") }.textArg("k", strict = true), "blank")
        assertEquals(
            "k must be a string",
            assertFailsWith<IllegalArgumentException> {
                buildJsonObject { put("k", 1) }.textArg("k", strict = true)
            }.message,
        )
        assertFailsWith<IllegalArgumentException> {
            buildJsonObject { put("k", true) }.textArg("k", strict = true)
        }
        assertFailsWith<IllegalArgumentException> {
            buildJsonObject { put("k", JsonNull) }.textArg("k", strict = true)
        }
        assertFailsWith<IllegalArgumentException> {
            buildJsonObject { putJsonObject("k") { } }.textArg("k", strict = true)
        }
    }

    // ---------- longArg / intArg ----------

    @Test
    fun `long and int args parse leniently from numbers and numeric strings`() {
        assertEquals(5L, buildJsonObject { put("k", "5") }.longArg("k"))
        assertEquals(5L, buildJsonObject { put("k", 5) }.longArg("k"))
        assertNull(buildJsonObject { }.longArg("k"), "absent")
        assertNull(buildJsonObject { put("k", "abc") }.longArg("k"), "garbage")
        assertNull(buildJsonObject { put("k", true) }.longArg("k"), "bool")
        assertNull(buildJsonObject { put("k", JsonNull) }.longArg("k"), "json null")

        assertEquals(5, buildJsonObject { put("k", "5") }.intArg("k"))
        assertEquals(5, buildJsonObject { put("k", 5) }.intArg("k"))
        assertNull(buildJsonObject { put("k", "abc") }.intArg("k"))
    }

    @Test
    fun `strict int rejects strings, floats and non-primitives like zod`() {
        assertEquals(5, buildJsonObject { put("k", 5) }.intArg("k", strict = true))
        assertNull(buildJsonObject { }.intArg("k", strict = true), "absent")
        assertEquals(
            "k must be a number",
            assertFailsWith<IllegalArgumentException> {
                buildJsonObject { put("k", "5") }.intArg("k", strict = true)
            }.message,
        )
        assertEquals(
            "k must be a number",
            assertFailsWith<IllegalArgumentException> {
                buildJsonObject { putJsonObject("k") { } }.intArg("k", strict = true)
            }.message,
        )
        assertTrue(
            assertFailsWith<IllegalArgumentException> {
                buildJsonObject { put("k", 1.5) }.intArg("k", strict = true)
            }.message!!.contains("must be an integer, got"),
            "a float is not a whole number",
        )
        assertFailsWith<IllegalArgumentException> {
            buildJsonObject { put("k", true) }.intArg("k", strict = true)
        }
    }

    // ---------- boolArg ----------

    @Test
    fun `bool args parse leniently`() {
        assertTrue(buildJsonObject { put("k", "true") }.boolArg("k")!!)
        assertFalse(buildJsonObject { put("k", false) }.boolArg("k")!!)
        assertTrue(buildJsonObject { put("k", true) }.boolArg("k")!!)
        assertNull(buildJsonObject { }.boolArg("k"), "absent")
        assertNull(buildJsonObject { put("k", "yes") }.boolArg("k"), "not a boolean literal")
        assertNull(buildJsonObject { put("k", 1) }.boolArg("k"), "number")
    }

    @Test
    fun `lenient long, int and bool still fail a non-primitive value`() {
        assertFailsWith<IllegalArgumentException> {
            buildJsonObject { putJsonObject("k") { } }.longArg("k")
        }
        assertFailsWith<IllegalArgumentException> {
            buildJsonObject { putJsonArray("k") { add("a") } }.intArg("k")
        }
        assertFailsWith<IllegalArgumentException> {
            buildJsonObject { putJsonArray("k") { add("a") } }.boolArg("k")
        }
    }

    // ---------- stringArrayArg ----------

    @Test
    fun `string arrays trim entries and drop blanks`() {
        assertEquals(
            listOf("a", "b"),
            buildJsonObject { putJsonArray("k") { add("a"); add(" b ") } }.stringArrayArg("k"),
        )
        assertEquals(
            listOf("a"),
            buildJsonObject { putJsonArray("k") { add("a"); add("  ") } }.stringArrayArg("k"),
            "blank entries are dropped",
        )
        assertNull(buildJsonObject { putJsonArray("k") { } }.stringArrayArg("k"), "empty array")
        assertNull(
            buildJsonObject { putJsonArray("k") { add(" ") } }.stringArrayArg("k"),
            "all-blank array",
        )
        assertNull(buildJsonObject { }.stringArrayArg("k"), "absent")
    }

    @Test
    fun `string arrays reject non-array values and non-string elements`() {
        assertEquals(
            "k must be an array of strings",
            assertFailsWith<IllegalArgumentException> {
                buildJsonObject { putJsonArray("k") { add(42) } }.stringArrayArg("k")
            }.message,
        )
        assertFailsWith<IllegalArgumentException> {
            buildJsonObject { putJsonArray("k") { add("a"); add(true) } }.stringArrayArg("k")
        }
        // a present non-array value is a zod-style type error
        assertFailsWith<IllegalArgumentException> {
            buildJsonObject { put("k", "a") }.stringArrayArg("k")
        }
    }
}
