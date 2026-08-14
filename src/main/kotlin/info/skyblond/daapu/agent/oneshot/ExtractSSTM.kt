package info.skyblond.daapu.agent.oneshot

import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.chat.request.json.JsonObjectSchema
import dev.langchain4j.model.openai.OpenAiChatModel
import dev.langchain4j.model.output.FinishReason
import info.skyblond.daapu.agent.checkPromptContentCapabilities
import info.skyblond.daapu.agent.lc4j.chat.toLc4jMessages
import info.skyblond.daapu.agent.lc4j.chat.toNeutralAssistantMessage
import info.skyblond.daapu.agent.lc4j.executor.withGeneratedToolCallIds
import info.skyblond.daapu.agent.lc4j.llm.LLM
import info.skyblond.daapu.agent.lc4j.llm.LLMCapability
import info.skyblond.daapu.agent.lc4j.tool.ToolProvider
import info.skyblond.daapu.agent.oneshot.SstmExtractor.Companion.NOTHING_TO_REMEMBER_TEXT
import info.skyblond.daapu.agent.refreshSystemPrompt
import info.skyblond.daapu.chat.ChatMessage
import info.skyblond.daapu.chat.ChatMessagePart
import info.skyblond.daapu.chat.ChatMessageRole
import info.skyblond.daapu.memory.sstm.ShortTermMemory
import info.skyblond.daapu.memory.sstm.SstmService
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

private val logger = KotlinLogging.logger("SstmExtractor")

/**
 * The two-LLM memory extraction pipeline (see `agent/persist/SystemPrompt.kt`:
 * when messages are removed from context, extract info from the raw messages
 * and merge it into the SSTM before discarding them):
 *
 * 1. **Extractor** — one call with the raw dropped history (attachments
 *    included, so the model needs the matching input capabilities) plus the
 *    extraction system prompt, returning a free-text list of candidate
 *    memories (or the [NOTHING_TO_REMEMBER_TEXT] sentinel).
 * 2. **Merger** — a tool loop against the existing SSTM ([SstmService]) with
 *    add/update/delete/list tools, following the ADD/UPDATE/DELETE/NONE
 *    semantics. The caller must serialize the whole merge against concurrent
 *    SSTM readers/writers (see `ChatRunService`'s write lock), so an
 *    injection read never observes a half-merged SSTM.
 *
 * A failure in either step never throws into the caller — extraction is a
 * best-effort lossy pipeline, and the run must proceed without it — EXCEPT
 * a [info.skyblond.daapu.agent.ModelCapabilityException]: the extraction
 * model cannot process the dropped content (e.g. images with a text-only
 * model), which is a configuration error (`memory.extractModel`) and fails
 * fast.
 */
class SstmExtractor(
    private val extractModel: LLM,
    private val extractChatModel: OpenAiChatModel,
    private val mergeModel: LLM = extractModel,
    private val mergeChatModel: OpenAiChatModel = extractChatModel,
    private val sstmService: SstmService,
    private val maxMergeRounds: Int = 150,
) {
    /**
     * Extract memories from [droppedMessages] (the raw messages compaction is
     * about to discard) and merge them into the SSTM. Returns true when the
     * SSTM was modified; never throws — except a
     * [info.skyblond.daapu.agent.ModelCapabilityException] when the
     * extraction model cannot process the dropped content (fail fast on a
     * configuration error).
     */
    suspend fun processDiscardedMessages(
        droppedMessages: List<ChatMessage>,
    ) {
        if (droppedMessages.isEmpty()) return
        // fail fast on a capability mismatch before the LLM call: the same
        // prompt would fail identically forever (the loop's per-round check
        // semantics, applied to the configured extraction model)
        checkPromptContentCapabilities(droppedMessages, extractModel)
        val extraction = extract(droppedMessages)
        logger.info { "Extracted SSTM from dropped messages: $extraction" }
        if (extraction.isBlank() || extraction == NOTHING_TO_REMEMBER_TEXT)
            return

        return mergeToSstm(extraction)
    }

    /**
     * The extraction call: the raw dropped history plus the extraction
     * instruction, capped so a huge drop region cannot overflow the
     * extraction model's own window (keep the most recent portion, like the
     * compactor keeps the tail). Returns null when the call produced no
     * clean text.
     */
    private suspend fun extract(droppedMessages: List<ChatMessage>): String {
        val chat = (droppedMessages + ChatMessage(
            role = ChatMessageRole.User,
            parts = listOf(
                ChatMessagePart.Text(
                    "Extract memories item according to the system prompt."
                )
            ),
        )).refreshSystemPrompt(
            renderExtractorSystemPrompt()
        )

        val response = withContext(Dispatchers.IO) { extractChatModel.chat(chat.toLc4jMessages()) }
        if (response.finishReason() != FinishReason.STOP) {
            error("One-shot call ended with finish_reason=${response.finishReason()}, not a clean stop")
        }
        if (response.aiMessage().hasToolExecutionRequests()) {
            error("One-shot call produced tool calls instead of text")
        }
        // TODO: when ELTM is ready, detect SSTM length and trigger ELTM
        return response.aiMessage().text()?.trim()?.takeIf { it.isNotBlank() }
            ?: error("One-shot call produced no text")
    }

    /**
     * Run the merge agent: a tool loop over the SSTM until the model answers
     * without tool calls (or the round cap is hit). A failed merge round
     * stops the loop, keeping whatever was already applied.
     */
    private suspend fun mergeToSstm(extraction: String) {
        require(mergeModel.supports(LLMCapability.Output.ToolCalls)) {
            "Merge model ${mergeModel.id} does not support tool calls"
        }
        val toolProvider = MemoryToolProvider(sstmService)
        var chat = listOf(
            ChatMessage(
                ChatMessageRole.System,
                listOf(ChatMessagePart.Text(renderMergerSystemPrompt()))
            ),
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
                val request = ChatRequest.builder()
                    .messages(chat.toLc4jMessages())
                    .toolSpecifications(toolProvider.specifications())
                    .build()
                withContext(Dispatchers.IO) { mergeChatModel.chat(request) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) { // TODO: maybe don't retry blindly?
                logger.warn(e) { "SSTM merge round failed; retry..." }
                continue
            }
            val aiMessage = response.aiMessage().withGeneratedToolCallIds()
            val assistant = response.toNeutralAssistantMessage(aiMessage)

            if (assistant.finishReason !in listOf("stop", "tool_calls")) {
                error("SSTM merge round failed: ${assistant.finishReason}")
            }

            chat = chat + assistant
            if (assistant.parts.none { it is ChatMessagePart.ToolCall }) {
                logger.info { "SSTM merge round done, no tool call, return" }
                return // no tool call, done, return
            }
            val results = aiMessage.toolExecutionRequests()
                .map { toolProvider.execute(it) }
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
    override suspend fun specifications(): List<ToolSpecification> = listOf(
        ToolSpecification.builder()
            .name("list_memories")
            .description("List the current SSTM with their ids.")
            .build(),
        ToolSpecification.builder()
            .name("add_memory")
            .description("Add a new memory to the SSTM.")
            .parameters(
                JsonObjectSchema.builder()
                    .addStringProperty("content", "The memory content")
                    .required("content")
                    .build()
            )
            .build(),
        ToolSpecification.builder()
            .name("update_memory")
            .description("Replace the content of an existing memory, keeping the same id.")
            .parameters(
                JsonObjectSchema.builder()
                    .addStringProperty("id", "The memory id")
                    .addStringProperty("content", "The new memory content")
                    .required("id", "content")
                    .build()
            )
            .build(),
        ToolSpecification.builder()
            .name("delete_memory")
            .description("Delete an existing memory.")
            .parameters(
                JsonObjectSchema.builder()
                    .addStringProperty("id", "The memory id")
                    .required("id")
                    .build()
            )
            .build(),
    )

    override suspend fun execute(request: ToolExecutionRequest): ChatMessagePart.ToolResult {
        logger.info { "Executing tool ${request.name()} with arguments: ${request.arguments()}" }
        val args = runCatching {
            Json.parseToJsonElement(request.arguments()).jsonObject
        }.getOrNull()
            ?: return errorResult(request, "Invalid tool arguments: ${request.arguments()}")
        return when (request.name()) {
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

            else -> errorResult(request, "Unknown memory tool '${request.name()}'")
        }
    }

    private fun JsonObject.requiredText(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }

    private fun JsonObject.requiredLong(key: String): Long? =
        this[key]?.jsonPrimitive?.contentOrNull?.toLongOrNull()

    private fun textResult(
        request: ToolExecutionRequest,
        text: String
    ): ChatMessagePart.ToolResult =
        ChatMessagePart.ToolResult(
            id = request.id(),
            tool = request.name(),
            parts = listOf(ChatMessagePart.Text(text)),
        )

    private fun errorResult(
        request: ToolExecutionRequest,
        error: String
    ): ChatMessagePart.ToolResult =
        ChatMessagePart.ToolResult(
            id = request.id(),
            tool = request.name(),
            parts = listOf(ChatMessagePart.Text("Error: $error")),
            isError = true,
        )
}

internal fun buildMergeInput(existing: List<ShortTermMemory>, candidates: String): String {
    val existingBlock = if (existing.isEmpty()) "(none)"
    else existing.joinToString("\n\n") { "## Memory ${it.id}\n${it.content}" }
    return "Current SSTM:\n```\n$existingBlock\n```\n\n" +
            "Candidate facts extracted from a recent conversation:\n```\n$candidates\n```"
}
