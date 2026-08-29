package info.skyblond.daapu.agent.tool

import info.skyblond.daapu.agent.chat.ChatMessagePart
import info.skyblond.daapu.agent.tool.validateToolNamespaceSyntax

/**
 * A transparent namespace-level filter over another [ToolProvider]: the
 * delegate keeps its full tool set, but only the whitelisted namespaces are
 * visible and executable. The main agent's own tool set stays untouched —
 * this wrapper exists for a restricted sub-agent run that must not see every
 * tool of a [CombinedToolProvider] (e.g. an investigate agent allowed only
 * the `eltm` and MCP web-search namespaces).
 *
 * Namespace contract — [whitelist] entries are validated like any namespace
 * (`SAFE_ID_REGEX`, no `__`, see [validateToolNamespaceSyntax]), must be
 * non-empty and non-blank, and every entry MUST be served by the delegate
 * (fail fast at construction: a whitelisted namespace the delegate does not
 * advertise is a config typo, not a runtime surprise). Tool names are
 * matched the same way [CombinedToolProvider] routes them: split at the
 * FIRST `__`; a bare (unprefixed) name is never whitelisted.
 *
 * - [specifications] returns the delegate's advertisements filtered to the
 *   whitelisted namespaces (the model never sees a disallowed tool).
 * - [execute] delegates whitelisted names to the delegate unchanged (it
 *   strips its own prefix); anything else answers an `isError` result the
 *   model can react to, never a transport failure.
 * - [executionTimeoutSeconds] delegates for whitelisted names (0 for
 *   others): the callback route resolves the execution budget from the
 *   in-flight run's provider — if THIS wrapper is that provider (a
 *   sub-agent run), the delegate's budgets (e.g. an MCP server's 120s) must
 *   survive the wrapper.
 * - [namespaces] is the whitelisted subset of the delegate's namespaces, so
 *   the contract stays truthful if this wrapper is ever a
 *   [CombinedToolProvider] child.
 */
class WhitelistedToolProvider(
    private val delegate: ToolProvider,
    whitelist: Set<String>,
) : ToolProvider {

    // snapshot: the construction-time invariant "whitelist ⊆
    // delegate.namespaces()" must survive a caller mutating its set later
    private val whitelist: Set<String> = whitelist.toSet()

    init {
        require(whitelist.isNotEmpty()) {
            "Whitelisted tool provider: the whitelist must not be empty"
        }
        whitelist.forEach {
            require(it.isNotBlank()) {
                "Whitelisted tool provider: a whitelist namespace must not be blank"
            }
            validateToolNamespaceSyntax(it, "Whitelisted tool provider")
        }
        require(delegate.namespaces().containsAll(whitelist)) {
            "Whitelisted tool provider: whitelist namespaces not served by the delegate: " +
                    (whitelist - delegate.namespaces()).joinToString(", ")
        }
    }

    override fun namespaces(): Set<String> =
        delegate.namespaces().filter { it in whitelist }.toSet()

    override suspend fun specifications(): List<ToolSpec> =
        delegate.specifications().filter { prefixOf(it.name) in whitelist }

    override fun executionTimeoutSeconds(toolName: String): Long =
        if (prefixOf(toolName) in whitelist) delegate.executionTimeoutSeconds(toolName) else 0

    override suspend fun execute(request: ToolCallRequest): ChatMessagePart.ToolResult {
        if (prefixOf(request.name) !in whitelist) {
            return errorResult(
                request.id, request.name,
                "tool '${request.name}' is not allowed by this whitelist"
            )
        }
        return delegate.execute(request)
    }

    /**
     * The namespace prefix of an advertised name (the same FIRST-`__` rule
     * [CombinedToolProvider] routes by, [splitNsToolName]); a bare
     * name has no prefix and is never whitelisted.
     */
    private fun prefixOf(toolName: String): String? =
        splitNsToolName(toolName)?.first
}
