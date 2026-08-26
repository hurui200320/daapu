package info.skyblond.daapu.server

import info.skyblond.daapu.agent.chat.AttachmentKind
import info.skyblond.daapu.agent.chat.ChatMessagePart
import info.skyblond.daapu.agent.persist.StreamingExecutionCallback
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The [StreamingExecutionCallback] that maps turn-loop stream events to SSE
 * events — the contract the frontend (`frontend/src/lib/api.ts`) parses.
 * Extracted from `ChatRunService` so the exact event payloads can be
 * unit-tested.
 */
internal fun streamEventCallback(
    sendEvent: suspend (event: String, data: String) -> Unit,
): StreamingExecutionCallback = object : StreamingExecutionCallback {
    override suspend fun onTextDelta(text: String) {
        sendEvent("text", sseData("delta" to text))
    }

    override suspend fun onReasoningDelta(text: String) {
        sendEvent("reasoning", sseData("delta" to text))
    }

    override suspend fun onToolCall(name: String, args: JsonObject) {
        sendEvent(
            "tool_call",
            buildJsonObject {
                put("name", name)
                put("args", args)
            }.toString()
        )
    }

    override suspend fun onToolResults(results: List<ChatMessagePart.ToolResult>) {
        // stream tool results as they are produced; the frontend shows
        // them live (the `done` history reload re-renders them anyway)
        results.forEach { result ->
            sendEvent(
                "tool_result",
                buildJsonObject {
                    put("id", result.id)
                    put("name", result.tool)
                    put("content", result.parts.joinToString("\n") {
                        when (it) {
                            is ChatMessagePart.Text -> it.text

                            // the live SSE carries no attachment payload — the frontend
                            // can only render it after the round's end (the `done` history
                            // reload); images are the only kind the frontend renders
                            is ChatMessagePart.Attachment -> when (it.kind) {
                                AttachmentKind.Image -> "Image appears once the round finishes."
                                else -> "Attachment is not displayed."
                            }
                        }
                    })
                    put("isError", result.isError)
                }.toString()
            )
        }
    }

    override suspend fun onStreamError(error: String) {
        // the stream hit a transient error and will be retried
        // frontend should clear the current round (after previous tool call)
        sendEvent("retry", sseData("message" to error))
    }
}

private fun sseData(vararg pairs: Pair<String, String>): String =
    buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }.toString()
