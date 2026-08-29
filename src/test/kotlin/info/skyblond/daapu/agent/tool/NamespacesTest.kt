package info.skyblond.daapu.agent.tool

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The shared namespace join/split helpers: the `__` separator is written
 * and split in exactly one place, so the providers and the routers can
 * never drift ([nsToolName], [splitNsToolName],
 * [splitStrictNsToolName]).
 */
class NamespacesTest {

    @Test
    fun `join and split round-trip`() {
        assertEquals("fs__read_text_file", nsToolName("fs", "read_text_file"))
        assertEquals(
            "fs" to "read_text_file",
            splitNsToolName("fs__read_text_file"),
        )
    }

    @Test
    fun `splits at the FIRST separator and keeps the bare part verbatim`() {
        assertEquals("a" to "b__c", splitNsToolName("a__b__c"))
    }

    @Test
    fun `a bare name does not split`() {
        assertNull(splitNsToolName("add"))
        assertNull(splitNsToolName(""))
    }

    @Test
    fun `strict ns__tool split accepts exactly namespace__tool`() {
        assertEquals("calc" to "add", splitStrictNsToolName("calc__add"))
        assertNull(splitStrictNsToolName("a__b__c"), "a second __ is not a strict ns__tool name")
        assertNull(
            splitStrictNsToolName("a__b__"),
            "a trailing __ is a second __ too — never a strict ns__tool name",
        )
        assertNull(splitStrictNsToolName("add"), "a bare name is not a strict ns__tool name")
    }

    @Test
    fun `an empty bare part is still a valid split`() {
        assertEquals("a" to "", splitNsToolName("a__"))
        assertEquals(
            "a" to "",
            splitStrictNsToolName("a__"),
            "an empty bare part contains no __, so the strict split accepts it",
        )
        assertEquals(
            "" to "b",
            splitStrictNsToolName("__b"),
            "an empty namespace splits at the leading separator",
        )
    }
}
