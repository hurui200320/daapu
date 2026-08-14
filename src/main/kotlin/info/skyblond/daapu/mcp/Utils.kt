package info.skyblond.daapu.mcp

import info.skyblond.daapu.agent.chat.ChatMessagePart

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
