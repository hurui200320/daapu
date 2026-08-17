package info.skyblond.daapu.mcp

import info.skyblond.daapu.agent.chat.ChatMessagePart

/**
 * The model-visible answer for a transport failure: the connection is
 * dropped, reconnection happens at the next tool-list refresh.
 */
const val TRANSPORT_FAILURE_MESSAGE =
    "tool call failed with transport failure, will reconnect on next call"

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
