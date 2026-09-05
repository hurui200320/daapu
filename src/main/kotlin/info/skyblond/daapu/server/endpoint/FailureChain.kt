package info.skyblond.daapu.server.endpoint

/**
 * One message per link of a failure's cause chain: the root exception's
 * message first, then every wrapper's, innermost last (`null` messages
 * render as `toString()`). The single traversal both error renderers build
 * on, so their chain shapes cannot drift apart: the SSE `error` event's
 * `errorEventData` (ChatsRoute.kt) keeps the root plus the FIRST cause,
 * the ELTM digest's 502 body (EltmRoute.kt) joins the WHOLE chain —
 * different depth budgets, one source of the per-link messages.
 */
internal fun failureChainMessages(e: Throwable): List<String> {
    val messages = mutableListOf<String>()
    var current: Throwable? = e
    while (current != null) {
        messages += current.message ?: current.toString()
        current = current.cause
    }
    return messages
}
