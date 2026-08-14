package info.skyblond.daapu.agent.oneshot

import info.skyblond.daapu.agent.model.LLM
import info.skyblond.daapu.agent.model.LLMCapability
import info.skyblond.daapu.agent.tool.ToolCallRequest
import info.skyblond.daapu.agent.tool.ToolProvider
import info.skyblond.daapu.agent.tool.ToolSpec
import info.skyblond.daapu.agent.chat.ChatMessage
import info.skyblond.daapu.agent.chat.ChatMessagePart
import info.skyblond.daapu.agent.chat.ChatMessageRole
import info.skyblond.daapu.hand.HandClient
import info.skyblond.daapu.hand.HandCompleteRequest
import info.skyblond.daapu.hand.HandToolSpec
import info.skyblond.daapu.hand.toHandModelSpec
import info.skyblond.daapu.memory.sstm.ShortTermMemory
import info.skyblond.daapu.memory.sstm.SstmService
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private val logger = KotlinLogging.logger("SstmExtractor")

/**
 * The two-LLM memory extraction pipeline (see `agent/persist/SystemPrompt.kt`:
 * when messages are removed from context, extract info from the raw messages
 * and merge it into the SSTM before discarding them):
 *
 * 1. **Extractor** — one hand `/complete` call with the raw dropped history
 *    (attachments included, so the model needs the matching input
 *    capabilities) plus the extraction system prompt, returning a free-text
 *    list of candidate memories (or the [NOTHING_TO_REMEMBER_TEXT]
 *    sentinel).
 * 2. **Merger** — a hand `/complete` tool loop against the existing SSTM
 *    ([SstmService]) with add/update/delete/list tools, following the
 *    ADD/UPDATE/DELETE/NONE semantics. The caller must serialize the whole
 *    merge against concurrent SSTM readers/writers (see `ChatRunService`'s
 *    write lock), so an injection read never observes a half-merged SSTM.
 *
 * A failure throws and fails the run:
 * - [info.skyblond.daapu.agent.model.ModelCapabilityException] when the
 *   extraction model cannot process the dropped content (e.g. images with a
 *   text-only model), which is a configuration error (`memory.extractModel`)
 *   and fails fast;
 * - a failed extraction (a classified hand error such as a truncated
 *   `length` finish) or one producing tool calls or no text;
 * - a classified hand error or a non-`stop`/`tool_calls` finish in a merge
 *   round. Transient `upstream` merge failures retry indefinitely; the SSTM
 *   keeps whatever was already applied when a later round fails.
 */
class SstmExtractor(
    private val extractModel: LLM,
    private val mergeModel: LLM = extractModel,
    private val hand: HandClient,
    private val sstmService: SstmService,
    private val maxMergeRounds: Int = 150,
) {
    /**
     * Extract memories from [droppedMessages] (the raw messages compaction is
     * about to discard) and merge them into the SSTM. Throws per the class
     * KDoc (the extraction pipeline fails the run on failure).
     */
    suspend fun processDiscardedMessages(
        droppedMessages: List<ChatMessage>,
    ) {
        if (droppedMessages.isEmpty()) return
        // fail fast on a capability mismatch before the LLM call: the same
        // prompt would fail identically forever (the loop's per-round check
        // semantics, applied to the configured extraction model)
        extractModel.checkPromptContentCapabilities(droppedMessages)
        val extraction = extract(droppedMessages)
        logger.info { "Extracted SSTM from dropped messages: $extraction" }
        if (extraction.isBlank() || extraction == NOTHING_TO_REMEMBER_TEXT)
            return

        return mergeToSstm(extraction)
    }

    /**
     * The extraction call: the raw dropped history plus the extraction
     * instruction. Fails on anything but a clean `stop` with text (the
     * fail-fast semantics depend on distinguishing `length` from `stop`).
     */
    private suspend fun extract(droppedMessages: List<ChatMessage>): String {
        val chat = droppedMessages + ChatMessage(
            role = ChatMessageRole.User,
            parts = listOf(
                ChatMessagePart.Text(
                    "Extract memories item according to the system prompt."
                )
            ),
        )

        val response = hand.complete(
            HandCompleteRequest(
                model = extractModel.toHandModelSpec(),
                messages = chat,
                systemPrompt = renderExtractorSystemPrompt(),
            )
        )
        if (!response.ok) {
            error("One-shot call failed (${response.error?.type}): ${response.error?.message}")
        }
        val assistant = response.message
            ?: error("One-shot call returned no message")
        if (assistant.finishReason != "stop") {
            error("One-shot call ended with finish_reason=${assistant.finishReason}, not a clean stop")
        }
        if (assistant.parts.any { it is ChatMessagePart.ToolCall }) {
            error("One-shot call produced tool calls instead of text")
        }
        // TODO: when ELTM is ready, detect SSTM length and trigger ELTM
        return assistant.parts.filterIsInstance<ChatMessagePart.Text>()
            .joinToString("\n") { it.text }
            .trim()
            .takeIf { it.isNotBlank() }
            ?: error("One-shot call produced no text")
    }

    /**
     * Run the merge agent: a tool loop over the SSTM until the model answers
     * without tool calls (or the round cap is hit). Transient upstream
     * failures retry indefinitely; a non-`stop`/`tool_calls` finish or a
     * classified hand error fails the merge (the run stays alive; the SSTM
     * keeps whatever was already applied).
     */
    private suspend fun mergeToSstm(extraction: String) {
        require(mergeModel.supports(LLMCapability.Output.ToolCalls)) {
            "Merge model ${mergeModel.id} does not support tool calls"
        }
        val toolProvider = MemoryToolProvider(sstmService)
        var chat = listOf(
            ChatMessage(
                ChatMessageRole.User,
                listOf(
                    ChatMessagePart.Text(
                        buildMergeInput(
                            sstmService.listMemories().memories,
                            extraction
                        )
                    )
                )
            ),
        )
        var rounds = 0
        while (true) {
            val response = try {
                hand.complete(
                    HandCompleteRequest(
                        model = mergeModel.toHandModelSpec(),
                        messages = chat,
                        systemPrompt = renderMergerSystemPrompt(),
                        tools = toolProvider.specifications().map {
                            HandToolSpec(it.name, it.description, it.schema)
                        },
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) { // TODO: maybe don't retry blindly?
                logger.warn(e) { "SSTM merge round failed; retry..." }
                continue
            }
            if (!response.ok) {
                val error = response.error!!
                if (error.type == "upstream") {
                    logger.warn { "SSTM merge round failed (upstream); retry..." }
                    continue
                }
                error("SSTM merge round failed: ${error.type} — ${error.message}")
            }
            val assistant = response.message
                ?: error("SSTM merge round returned no message")

            if (assistant.finishReason !in listOf("stop", "tool_calls")) {
                error("SSTM merge round failed: ${assistant.finishReason}")
            }

            chat = chat + assistant
            if (assistant.parts.none { it is ChatMessagePart.ToolCall }) {
                logger.info { "SSTM merge round done, no tool call, return" }
                return // no tool call, done, return
            }
            // the hand guarantees non-blank tool-call ids (uuidv7 synthesis
            // for id-less calls), so no id normalization is needed here
            val results = assistant.parts
                .filterIsInstance<ChatMessagePart.ToolCall>()
                .map { call -> toolProvider.execute(ToolCallRequest(call.id, call.tool, call.args)) }
            chat = chat + results.map { ChatMessage(ChatMessageRole.ToolResult, listOf(it)) }
            if (++rounds >= maxMergeRounds) {
                logger.warn { "SSTM merge round exceeded max rounds, force stop" }
                return
            }
        }
    }

    companion object {
        private const val NOTHING_TO_REMEMBER_TEXT = "Nothing worth remember."

        private fun renderExtractorSystemPrompt(): String = """
You're extracting memories from a discarded conversation.
Extract **all** important information from the conversation history into a list of self-contained facts suitable for long-term memory.

Focus on:
- The user's preferences, likes, dislikes, and personal details
- Plans, goals, pending tasks, and unresolved questions
- Decisions and constraints
- Facts about people, projects, and entities: keep names, numbers, ids, and values verbatim
- Anything a future conversation would need to know

Rules:
- Each fact must be self-contained: replace pronouns with the entity name or "the user"
- Write facts in the same language as the conversation
- Do not invent details that are not present in the history
- Merge overlapping information into one fact
- When nothing is worth remembering, output sentence "$NOTHING_TO_REMEMBER_TEXT"
""".trimIndent().trim()


        private fun renderMergerSystemPrompt(): String = """
You're merging summarized memories into the memory system (SSTM).
You have access to tools that manipulating the SSTM.
The SSTM is a numbered list of memories.
The current state and the candidate facts extracted from a recent conversation are given in the user message.

Update the SSTM with the memory tools:
- list_memories: view the current SSTM (also listed in the user message)
- add_memory(content): add a new memory
- update_memory(id, content): replace an existing memory's content in place
- delete_memory(id): remove an existing memory

For each candidate decide exactly one action:
- ADD: the candidate is new information -> add_memory
- UPDATE: the candidate refines an existing memory (the same fact with more detail or a correction) -> update_memory with the existing id
- DELETE: the candidate contradicts an existing memory and the new fact is authoritative -> delete_memory, then add_memory for the new fact
- NONE: the candidate is already covered by an existing memory -> do nothing

Rules:
- Never modify memories unrelated to the candidates
- Keep memories concise and self-contained
- When all candidates are handled, reply with a short confirmation and make no further tool calls
""".trimIndent().trim()
    }
}

/**
 * The memory-edit tools the merge agent may call, backed by an [SstmService].
 */
class MemoryToolProvider(
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
        ),
        ToolSpec(
            name = "add_memory",
            description = "Add a new memory to the SSTM.",
            schema = objectSchema(
                "content" to stringSchema("The memory content"),
            ),
        ),
        ToolSpec(
            name = "update_memory",
            description = "Replace the content of an existing memory, keeping the same id.",
            schema = objectSchema(
                "id" to stringSchema("The memory id"),
                "content" to stringSchema("The new memory content"),
            ),
        ),
        ToolSpec(
            name = "delete_memory",
            description = "Delete an existing memory.",
            schema = objectSchema(
                "id" to stringSchema("The memory id"),
            ),
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
        private fun stringSchema(description: String) = buildJsonObject {
            put("type", "string")
            put("description", description)
        }

        private fun objectSchema(vararg properties: Pair<String, kotlinx.serialization.json.JsonObject>) =
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

internal fun buildMergeInput(existing: List<ShortTermMemory>, candidates: String): String {
    val existingBlock = if (existing.isEmpty()) "(none)"
    else existing.joinToString("\n\n") { "## Memory ${it.id}\n${it.content}" }
    return "Current SSTM:\n```\n$existingBlock\n```\n\n" +
            "Candidate facts extracted from a recent conversation:\n```\n$candidates\n```"
}
