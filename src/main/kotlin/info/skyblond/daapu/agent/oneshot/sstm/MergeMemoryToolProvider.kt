package info.skyblond.daapu.agent.oneshot.sstm

import info.skyblond.daapu.agent.chat.ChatMessagePart
import info.skyblond.daapu.agent.tool.ToolCallRequest
import info.skyblond.daapu.agent.tool.ToolProvider
import info.skyblond.daapu.agent.tool.ToolSpec
import info.skyblond.daapu.memory.sstm.SstmService
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.*

/**
 * The memory-edit tools the merge agent may call, backed by an [SstmService].
 */
class MergeMemoryToolProvider(
    private val sstmService: SstmService,
) : ToolProvider {
    override suspend fun specifications(): List<ToolSpec> = listOf(
        ToolSpec(
            name = "list_memories",
            description = "List the current SSTM with their ids.",
            schema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {})
            },
            // local in-process calls, executed directly (never through the
            // hand callback): no timeout needed
            timeoutSeconds = 0,
        ),
        ToolSpec(
            name = "add_memory",
            description = "Add a new memory to the SSTM.",
            schema = objectSchema(
                "content" to stringSchema("The memory content"),
            ),
            timeoutSeconds = 0,
        ),
        ToolSpec(
            name = "update_memory",
            description = "Replace the content of an existing memory, keeping the same id.",
            schema = objectSchema(
                "id" to stringSchema("The memory id"),
                "content" to stringSchema("The new memory content"),
            ),
            timeoutSeconds = 0,
        ),
        ToolSpec(
            name = "delete_memory",
            description = "Delete an existing memory.",
            schema = objectSchema(
                "id" to stringSchema("The memory id"),
            ),
            timeoutSeconds = 0,
        ),
    )

    override suspend fun execute(request: ToolCallRequest): ChatMessagePart.ToolResult {
        logger.info { "Executing tool ${request.name} with arguments: ${request.args}" }
        val args = request.args
        return when (request.name) {
            "list_memories" -> textResult(
                request,
                sstmService.listMemories().memories.joinToString("\n\n") {
                    "## Memory ${it.id}\n${it.content}"
                }
            )

            "add_memory" -> {
                val content = args.requiredText("content") ?: return errorResult(
                    request,
                    "content is required and must not be blank"
                )
                sstmService.createMemory(content)
                textResult(request, "created")
            }

            "update_memory" -> {
                val id = args.requiredLong("id") ?: return errorResult(
                    request,
                    "id is required and must be a number"
                )
                val content = args.requiredText("content") ?: return errorResult(
                    request,
                    "content is required and must not be blank"
                )
                if (sstmService.updateMemory(id, content) == null) {
                    return errorResult(request, "memory $id does not exist")
                }
                textResult(request, "updated")
            }

            "delete_memory" -> {
                val id = args.requiredLong("id") ?: return errorResult(
                    request,
                    "id is required and must be a number"
                )
                if (!sstmService.deleteMemory(id)) return errorResult(
                    request,
                    "memory $id does not exist"
                )
                textResult(request, "deleted")
            }

            else -> errorResult(request, "Unknown memory tool '${request.name}'")
        }
    }

    private fun JsonObject.requiredText(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }

    private fun JsonObject.requiredLong(key: String): Long? =
        this[key]?.jsonPrimitive?.contentOrNull?.toLongOrNull()

    private fun textResult(
        request: ToolCallRequest,
        text: String
    ): ChatMessagePart.ToolResult =
        ChatMessagePart.ToolResult(
            id = request.id,
            tool = request.name,
            parts = listOf(ChatMessagePart.Text(text)),
        )

    private fun errorResult(
        request: ToolCallRequest,
        error: String
    ): ChatMessagePart.ToolResult =
        ChatMessagePart.ToolResult(
            id = request.id,
            tool = request.name,
            parts = listOf(ChatMessagePart.Text("Error: $error")),
            isError = true,
        )

    companion object {
        private val logger = KotlinLogging.logger { }

        private fun stringSchema(description: String) = buildJsonObject {
            put("type", "string")
            put("description", description)
        }

        private fun objectSchema(vararg properties: Pair<String, JsonObject>) =
            buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    properties.forEach { (name, schema) -> put(name, schema) }
                })
                put("required", buildJsonArray {
                    properties.forEach { (name, _) -> add(name) }
                })
            }
    }
}
