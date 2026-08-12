package info.skyblond.daapu.mcp

/**
 * The MCP transport itself failed (connect refused, stdio process died, ...).
 * Thrown by [McpToolProvider] at construction — a server that cannot be
 * reached aborts startup — and from [McpToolProvider.execute] when the
 * in-turn reconnect after a transport failure cannot restore the connection:
 * the model cannot react to a dead transport, so the chat run fails and
 * surfaces a clear SSE `error` event. The provider drops the cached client
 * first, so a later run reconnects.
 *
 * (A transport failure that recovers on the in-turn retry — the call is
 * re-executed once on a fresh connection — or that keeps the server reachable
 * but fails the call twice surfaces as an error tool-result instead, so the
 * model sees the failure and the run continues.)
 */
class McpTransportException(message: String, cause: Throwable) : Exception(message, cause)
