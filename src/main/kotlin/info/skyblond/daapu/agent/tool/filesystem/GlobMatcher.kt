package info.skyblond.daapu.agent.tool.filesystem

import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.PathMatcher

/**
 * A blob/gitignore-style glob matcher for the filesystem tool provider's
 * blacklist patterns (e.g. any `.env` file at any depth, anything under
 * `secrets`), built on the JDK's own
 * `FileSystem.getPathMatcher("glob:...")` — no third-party dependency,
 * battle-tested pattern syntax (`*`, `**`, `?`, `[...]`, `{a,b}`; `*`
 * matches dot files too, like minimatch with `dot: true`).
 *
 * The JDK glob is close to minimatch except ONE rule: a `**` that ends a
 * path segment still requires at least one character — so a pattern like
 * `**.env` at the root level is not matched by a leading `**`, and a
 * middle `**` segment requires at least one directory in between, whereas
 * minimatch (and gitignore) treat `**`-then-slash as "zero or more
 * directories". [GlobMatcher] compiles the pattern plus the alternates
 * that restore that rule:
 *
 * - a leading `**`-then-slash also matches zero directories,
 * - a middle slash-`**`-slash also matches zero directories in between.
 *
 * A path matches when ANY of the compiled alternates matches it. Invalid
 * patterns (unclosed `[`, ...) throw [IllegalArgumentException] at
 * construction — fail fast, never a silently non-matching blacklist. The
 * model-facing patterns (search patterns, excludePatterns) use
 * [Companion.lenient] instead: minimatch — the server's engine — treats a
 * malformed pattern as a literal string (it never throws), so an invalid
 * model pattern must degrade to "matches nothing", not fail the tool.
 */
class GlobMatcher(pattern: String) {

    private val matchers: List<PathMatcher>

    init {
        val candidates = mutableListOf(pattern)
        if (pattern.startsWith("**/")) {
            candidates += pattern.removePrefix("**/")
        }
        if (pattern.contains("/**/")) {
            candidates += pattern.replace("/**/", "/")
        }
        if (pattern.startsWith("**/") && pattern.contains("/**/")) {
            candidates += pattern.removePrefix("**/").replace("/**/", "/")
        }
        matchers = candidates.distinct().map {
            FileSystems.getDefault().getPathMatcher("glob:$it")
        }
    }

    /**
     * Whether [relativePath] (separator-normalized, no leading `./` or `/`)
     * matches the pattern.
     */
    fun matches(relativePath: String): Boolean =
        matchers.any { it.matches(Path.of(relativePath)) }

    companion object {
        /**
         * Compiles [pattern] without throwing: an invalid pattern answers
         * `null`, and the caller treats `null` as matching nothing
         * (minimatch parity — the server's engine never rejects a pattern).
         * Use ONLY for model-supplied patterns; configuration patterns
         * (the blacklists) keep the throwing constructor.
         */
        fun lenient(pattern: String): GlobMatcher? =
            runCatching { GlobMatcher(pattern) }.getOrNull()
    }
}
