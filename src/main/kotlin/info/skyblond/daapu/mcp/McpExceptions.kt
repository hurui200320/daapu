package info.skyblond.daapu.mcp

import info.skyblond.daapu.agent.tool.ToolTransportException

/**
 * The MCP transport itself failed (connect refused, stdio process died, ...),
 * or the entry is closed — [ClientEntry.close] raises a gate that fails
 * further connects fast (a closed entry's handshake scope is cancelled, so
 * a connect could never finish; see [ClientEntry]'s KDoc) — hence the
 * optional cause: a closed entry has no causal exception.
 *
 * Thrown by [McpToolProvider.connectAll] at startup — a server that cannot
 * be reached aborts startup — and from [McpToolProvider.specifications] when the
 * tool-list refresh cannot restore a dropped connection: the model cannot
 * react to a dead transport, so the chat run fails and surfaces a clear SSE
 * `error` event.
 *
 * A transport failure mid-execution does NOT throw: [McpToolProvider.execute]
 * drops the cached client and answers an error tool-result instead (no
 * in-turn retry or reconnect — the hand re-queries `specifications` before
 * every LLM request, which is the sole reconnection point). When the server
 * stays down, that refresh throws, so the run still ends with the same
 * `tool_transport` failure a round later.
 */
class McpTransportException(
    message: String, cause: Throwable? = null
) : ToolTransportException(message, cause)
