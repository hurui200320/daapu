package info.skyblond.daapu.agent

import info.skyblond.daapu.agent.lc4j.llm.LLM
import info.skyblond.daapu.chat.ChatMessage
import info.skyblond.daapu.chat.ChatMessagePart
import info.skyblond.daapu.chat.ChatMessageRole

/**
 * The model cannot handle content present in the prompt. This is a
 * deterministic failure: the same prompt with the same model fails
 * identically forever.
 */
class ModelCapabilityException(message: String) : Exception(message)

fun checkPromptContentCapabilities(
    chat: List<ChatMessage>,
    model: LLM,
) {
    chat.flatMap { message ->
        // attachments can also arrive nested inside tool results (e.g. an MCP
        // tool returning an image), so descend into the result parts too
        message.parts.flatMap { part ->
            when (part) {
                is ChatMessagePart.ToolResult -> part.parts
                else -> listOf(part)
            }
        }
    }
        .filterIsInstance<ChatMessagePart.Attachment>()
        .map { it.kind }
        .toSet()
        .forEach { kind ->
            if (!model.supportAttachmentKind(kind)) {
                throw ModelCapabilityException(
                    "Model ${model.id} does not support ${kind.name.lowercase()} content."
                )
            }
        }
}

/**
 * Refresh the system prompt in place: only a system message at index 0 is
 * kept (never one buried in chat history), its text is updated to the latest
 * version before execution (identical text hits the provider cache), and a
 * missing system message is inserted at the front.
 */
fun List<ChatMessage>.refreshSystemPrompt(systemPrompt: String): List<ChatMessage> {
    val parts = listOf(ChatMessagePart.Text(systemPrompt))
    val stripped = mapNotNull { message ->
        when { // remove all system message
            message.role == ChatMessageRole.System -> null
            else -> message
        }
    }
    // re-append
    return listOf(
        ChatMessage(role = ChatMessageRole.System, parts = parts)
    ) + stripped
}