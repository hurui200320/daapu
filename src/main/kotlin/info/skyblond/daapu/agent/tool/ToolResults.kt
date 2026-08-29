package info.skyblond.daapu.agent.tool

import info.skyblond.daapu.agent.chat.ChatMessagePart

/**
 * The uniform tool-result constructors shared by the tool providers: every
 * model-visible failure is an `isError` [ChatMessagePart.ToolResult] whose
 * text carries the `Error: ` prefix (the model reacts to it in the next
 * round — a failed execution must never throw, which would end the hand run
 * as `fatal`), and every text answer is a single-part success result.
 * Building them here keeps the wire shape — and the error prefix —
 * identical across the MCP, filesystem, ELTM and GSG providers.
 */

/** A model-visible tool failure: the [errorMessage] under the `Error: ` prefix. */
internal fun errorResult(id: String, name: String, errorMessage: String): ChatMessagePart.ToolResult =
    ChatMessagePart.ToolResult(
        id = id, tool = name,
        parts = listOf(
            ChatMessagePart.Text(
                "Error: $errorMessage"
            )
        ),
        isError = true,
    )

/** [errorResult] for a [ToolCallRequest] (the id and advertised name travel on it). */
internal fun errorResult(request: ToolCallRequest, errorMessage: String): ChatMessagePart.ToolResult =
    errorResult(request.id, request.name, errorMessage)

/** A successful single-text tool answer. */
internal fun textResult(request: ToolCallRequest, text: String): ChatMessagePart.ToolResult =
    ChatMessagePart.ToolResult(
        id = request.id,
        tool = request.name,
        parts = listOf(ChatMessagePart.Text(text)),
    )
