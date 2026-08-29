package info.skyblond.daapu.agent.tool

import info.skyblond.daapu.agent.chat.ChatMessagePart
import info.skyblond.daapu.config.validateToolNamespaceSyntax

/**
 * The tool set for the chat loop: one provider combining several
 * namespaced children (e.g. an `McpToolProvider` plus a namespaced local
 * provider like `EltmToolProvider("eltm")`), so one run can advertise both
 * without name collisions.
 *
 * Namespace contract — every child MUST serve at least one non-blank
 * namespace (fail fast at construction; the one-shot providers' bare-tool
 * shape is NOT allowed here, its unprefixed names could not be routed):
 * - each child's `namespaces()` is validated (`SAFE_ID_REGEX`, no `__`,
 *   see [validateToolNamespaceSyntax]) and must be unique across children;
 * - every advertised tool name is therefore `{namespace}__{toolName}`, so
 *   cross-child collisions are impossible by construction and [execute]
 *   routes by the namespace prefix alone: the name is split at the FIRST
 *   `__`; a known prefix routes to that child (which strips its own
 *   prefix), anything else — including a bare name or an unknown prefix —
 *   answers an `isError` result the model can react to, never a transport
 *   failure.
 *
 * [specifications] concatenates the children's advertisements in child
 * order; [executionTimeoutSeconds] routes the same way and delegates to the
 * owning child (0 for unroutable names). Child cleanup is the DI container's
 * job: the MCP provider closes its cached clients through Koin's `onClose`
 * (`di/AppModule.kt`), not through this composite.
 */
class CombinedToolProvider(
    private val children: List<ToolProvider>,
) : ToolProvider {

    // the routing table: namespace -> owning child. Namespaces are validated
    // (charset + no `__`), non-blank and unique across children, so a tool
    // name's first `__` prefix identifies its owner unambiguously.
    private val byNamespace: Map<String, ToolProvider> = buildMap {
        for (child in children) {
            val namespaces = child.namespaces()
            require(namespaces.isNotEmpty()) {
                "Combined tool provider: every child must serve at least one non-blank " +
                        "namespace, but ${child::class.simpleName} serves none"
            }
            for (ns in namespaces) {
                validateToolNamespaceSyntax(ns, "Combined tool provider child")
                require(!containsKey(ns)) {
                    "Combined tool provider: namespace '$ns' is served by multiple children"
                }
                put(ns, child)
            }
        }
    }

    override fun namespaces(): Set<String> = byNamespace.keys

    override suspend fun specifications(): List<ToolSpec> =
        children.flatMap { it.specifications() }

    override fun executionTimeoutSeconds(toolName: String): Long =
        route(toolName)?.executionTimeoutSeconds(toolName) ?: 0

    override suspend fun execute(request: ToolCallRequest): ChatMessagePart.ToolResult {
        val child = route(request.name)
            ?: return errorResult(
                request.id, request.name,
                "tool '${request.name}' is not advertised by any combined tool provider"
            )
        return child.execute(request)
    }

    /**
     * The child owning an advertised name: split at the FIRST `__`
     * ([splitNsToolName], the shared namespace contract); the
     * prefix must be a registered namespace (tool names are always
     * `{namespace}__{toolName}` in a combined set, so a bare name or an
     * unknown prefix is unroutable). Returns null for unroutable names.
     */
    private fun route(toolName: String): ToolProvider? =
        splitNsToolName(toolName)?.let { (namespace, _) -> byNamespace[namespace] }
}
