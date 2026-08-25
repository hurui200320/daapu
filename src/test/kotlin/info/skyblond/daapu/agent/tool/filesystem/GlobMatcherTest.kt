package info.skyblond.daapu.agent.tool.filesystem

import java.util.regex.PatternSyntaxException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the blob/gitignore-style glob semantics of [GlobMatcher]: the JDK
 * `glob:` engine plus the minimatch-compatible alternates for a leading or
 * middle `**`-then-slash (zero directories) that a naive `glob:` pattern
 * does not provide.
 */
class GlobMatcherTest {

    private fun matcher(pattern: String) = GlobMatcher(pattern)

    @Test
    fun `a leading globstar slash matches zero directories`() {
        val m = matcher("**/.env")
        assertTrue(m.matches(".env"))
        assertTrue(m.matches("a/.env"))
        assertTrue(m.matches("a/b/.env"))
        assertFalse(m.matches(".env.local"))
        assertFalse(m.matches("a/.env.local"))
        assertFalse(m.matches("a/b/env"))
    }

    @Test
    fun `a globstar star pattern matches dot env files at any depth`() {
        val m = matcher("**/*.env")
        assertTrue(m.matches(".env"))
        assertTrue(m.matches("a.env"))
        assertTrue(m.matches("a/b.env"))
        assertTrue(m.matches("a/b/c/x.env"))
        assertFalse(m.matches("a/.env.local"))
        assertFalse(m.matches("a/b/x.env/shadow"))
    }

    @Test
    fun `a middle globstar slash matches zero directories in between`() {
        val m = matcher("a/**/b")
        assertTrue(m.matches("a/b"))
        assertTrue(m.matches("a/x/b"))
        assertTrue(m.matches("a/x/y/b"))
        assertFalse(m.matches("a/b/c"))
        assertFalse(m.matches("x/a/b"))
    }

    @Test
    fun `* stays within one segment and matches dot files`() {
        val m = matcher("*.env")
        assertTrue(m.matches(".env"))
        assertTrue(m.matches("foo.env"))
        assertFalse(m.matches("a/foo.env"))
    }

    @Test
    fun `a trailing ** matches everything below a directory but not the directory itself`() {
        val m = matcher("secrets/**")
        assertTrue(m.matches("secrets/key.pem"))
        assertTrue(m.matches("secrets/a/b/c"))
        assertFalse(m.matches("secrets"))
        assertFalse(m.matches("a/secrets/key.pem"))
    }

    @Test
    fun `? matches exactly one character within a segment`() {
        val m = matcher("f?o")
        assertTrue(m.matches("foo"))
        assertFalse(m.matches("fo"))
        assertFalse(m.matches("f/o"))
        assertFalse(m.matches("fooo"))
    }

    @Test
    fun `char classes and negation work`() {
        assertTrue(matcher("file[0-9].txt").matches("file5.txt"))
        assertFalse(matcher("file[0-9].txt").matches("fileX.txt"))
        assertTrue(matcher("[!a]x").matches("bx"))
        assertFalse(matcher("[!a]x").matches("ax"))
    }

    @Test
    fun `brace alternation works`() {
        val m = matcher("{a,b}c")
        assertTrue(m.matches("ac"))
        assertTrue(m.matches("bc"))
        assertFalse(m.matches("cc"))
    }

    @Test
    fun `a bare ** matches everything`() {
        val m = matcher("**")
        assertTrue(m.matches("a"))
        assertTrue(m.matches("a/b/c"))
        assertTrue(m.matches(".env"))
    }

    @Test
    fun `an invalid pattern fails fast at construction`() {
        assertFailsWith<PatternSyntaxException> { GlobMatcher("[") }
        assertFailsWith<PatternSyntaxException> { GlobMatcher("{a,b") }
    }

    @Test
    fun `lenient compilation answers null for invalid patterns`() {
        // model-supplied patterns degrade to "matches nothing" instead of
        // failing the tool (minimatch never rejects a pattern)
        assertEquals(null, GlobMatcher.lenient("["))
        assertEquals(null, GlobMatcher.lenient("{a,b"))
    }

    @Test
    fun `lenient compilation still compiles valid patterns`() {
        assertTrue(GlobMatcher.lenient("**/.env")!!.matches("a/.env"))
        assertFalse(GlobMatcher.lenient("**/.env")!!.matches("a/.env.local"))
    }
}
