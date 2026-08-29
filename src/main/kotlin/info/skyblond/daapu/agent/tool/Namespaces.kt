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
 *
 * This file also owns the id-charset vocabulary the rest of the system
 * validates against ([SAFE_ID_REGEX], [TOOL_RESERVED_NAMESPACES],
 * `validateToolNamespaceSyntax`): the namespace is the primary consumer,
 * and the config layer (provider ids, MCP server keys) validates its
 * values against the same contract here.
 */

/**
 * Namespaces reserved for the harness's own internal/harness tools: an MCP
 * server must not use one of these, or its advertised tool names would
 * collide with the internal tools' namespaces. All lowercase, matching
 * [SAFE_ID_REGEX] — the check in `McpServerConfig.validate` is exact.
 */
val TOOL_RESERVED_NAMESPACES: Set<String> = setOf(
    "system", "inner", "internal", "gsg",
    "eltm", "harness"
)

/**
 * Charset for ids that become part of wire-visible strings: MCP namespaces
 * (prefixed onto every advertised tool name) and provider ids (prefixed onto
 * every model id served via `/api/models` and stored in chat history).
 * OpenAI-compatible gateways only accept `[0-9a-z_-]` in such strings;
 * uppercase is rejected so the reserved-namespace check stays an exact match.
 */
val SAFE_ID_REGEX: Regex = Regex("[0-9a-z_-]+")

/**
 * Fail fast on a namespace that cannot become part of an advertised tool
 * name. A blank namespace is allowed (the one-shot providers' default: tools
 * are advertised unprefixed, e.g. the ELTM tool provider's bare-name shape);
 * a non-blank one must match [SAFE_ID_REGEX], must not contain the `__`
 * separator that joins namespaces to tool names, and must not start or end
 * with `_`: an ending `_` would read as the separator's first underscore
 * (namespace `a_` advertising `a___tool` splits as namespace `a` under the
 * first-`__` rule, silently misrouting the call), and a starting `_` blurs
 * the boundary the same way visually. Shared by the MCP server config
 * validation, the namespaced tool providers and the persona namespace
 * whitelist; reserved names are a caller-specific concern
 * ([TOOL_RESERVED_NAMESPACES] applies to MCP servers only — the internal
 * tools own those namespaces).
 */
fun validateToolNamespaceSyntax(namespace: String, owner: String) {
    if (namespace.isBlank()) return
    if (!namespace.matches(SAFE_ID_REGEX)) {
        throw IllegalArgumentException(
            "$owner namespace '$namespace' is invalid: tool names are prefixed with it, so only [0-9a-z_-] is allowed"
        )
    }
    if (namespace.contains("__")) {
        throw IllegalArgumentException(
            "$owner namespace '$namespace' is invalid: it must not contain '__', which separates the parts of advertised tool names"
        )
    }
    if (namespace.startsWith("_") || namespace.endsWith("_")) {
        throw IllegalArgumentException(
            "$owner namespace '$namespace' is invalid: it must not start or end with '_', which would blur the '__' separator that joins namespaces to tool names"
        )
    }
}

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
