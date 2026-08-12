package info.skyblond.daapu.mcp

import dev.langchain4j.exception.ToolExecutionException
import info.skyblond.daapu.chat.ChatMessagePart

fun errorResult(id: String, name: String, errorMessage: String) =
    ChatMessagePart.ToolResult(
        id = id, tool = name,
        parts = listOf(
            ChatMessagePart.Text(
                "Error: $errorMessage"
            )
        ),
        isError = true,
    )

/**
 * Whether a [ToolExecutionException] means the MCP transport itself died
 * (drop the connection and retry) rather than a tool-level failure (error
 * tool-result).
 *
 * The langchain4j-mcp client (1.18.1-beta28) builds the exception in a few
 * shapes, distinguished by error code and cause:
 * - a server-side `isError` result (`{"result": {...}, "isError": true}`) is
 *   thrown message-only — `ToolExecutionException(String)` — which chains to
 *   the `(String, Integer)` ctor and finally to `super(new RuntimeException(
 *   message))`: the cause is EXACTLY a plain `RuntimeException` carrying the
 *   very same message (ToolExecutionHelper.extractResult);
 * - a JSON-RPC `error` response carries the protocol error code —
 *   `ToolExecutionException(String, Integer)` (and
 *   `ToolArgumentsException(String, Integer)` for -32602);
 * - a transport failure wraps the underlying exception as the cause —
 *   `ToolExecutionException(Throwable)` — e.g. an `IOException` (connect
 *   refused), an `IllegalStateException` ("Process has exited" / "Process is
 *   not alive" for a dead stdio subprocess), a `CompletionException` from the
 *   HTTP transport's CompletableFuture, or an `IllegalResponseException`
 *   (malformed response). Its error code is null and its cause is never a
 *   plain `RuntimeException`.
 *
 * An error code is therefore always a server-side failure, and a cause that
 * is exactly a plain `RuntimeException` is the `isError`-result shape — only
 * a null error code with any OTHER cause means the transport died.
 */
internal fun ToolExecutionException.isTransportFailure(): Boolean =
    errorCode() == null && cause != null && cause!!::class != RuntimeException::class
