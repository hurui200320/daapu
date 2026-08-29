package info.skyblond.daapu.agent.tool

/**
 * The tool-name namespace contract shared by every namespaced [ToolProvider]
 * (see [ToolProvider.namespaces]): an advertised tool name is
 * `{namespace}__{bareName}`, joined and split by the helpers below. The
 * `__` separator is written in exactly one place ([nsToolName]) and
 * split in exactly one place ([splitNsToolName]), so the join and
 * the split can never drift — every provider (MCP, filesystem, ELTM) and
 * every router (`CombinedToolProvider`, `WhitelistedToolProvider`) goes
 * through them.
 *
 * The split rule is THE first `__` occurrence: namespaces themselves can
 * never contain `__` (nor start or end with `_`, which would blur the
 * boundary — `validateToolNamespaceSyntax`), so the first
 * separator always ends the namespace — but a bare tool name MAY contain
 * `__` (MCP server names are sanitized at advertisement time, local
 * providers name their own tools), which is why providers that only ever
 * advertise strict `ns__tool` names use [splitStrictNsToolName].
 */

/**
 * Join [namespace] and [bareName] into the advertised name
 * (`{namespace}__{bareName}`).
 */
internal fun nsToolName(namespace: String, bareName: String): String =
    "${namespace}__$bareName"

/**
 * Split an advertised tool name at its FIRST `__` into
 * `(namespace, bareName)`; null for a bare name (no `__` at all). The bare
 * part is returned verbatim: it may itself contain `__`.
 * For strict parsing, see [splitStrictNsToolName].
 */
internal fun splitNsToolName(toolName: String): Pair<String, String>? {
    val separator = toolName.indexOf("__")
    if (separator < 0) return null
    return toolName.substring(0, separator) to toolName.substring(separator + 2)
}

/**
 * The STRICT `ns__tool` shape: like [splitNsToolName], but the name
 * is only accepted when it reads exactly `ns__tool`.
 * Will return null for a bare name (no `__` at all) AND for a name whose
 * tool part still contains `__` (e.g. `ns__too__l`).
 */
internal fun splitStrictNsToolName(toolName: String): Pair<String, String>? =
    splitNsToolName(toolName)?.takeIf { "__" !in it.second }
