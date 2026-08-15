package info.skyblond.daapu.agent.model

import info.skyblond.daapu.agent.chat.AttachmentKind
import info.skyblond.daapu.agent.chat.ChatMessage
import info.skyblond.daapu.agent.chat.ChatMessagePart

/**
 * A gateway the models are served through: the OpenAI-compatible base URL
 * (with the `/v1` root) and its API key. The hand
 * receives a copy of these per request — the brain holds no connections.
 */
class ModelProvider(
    val id: String,
    val baseUrl: String,
    val apiKey: String,
)

/**
 * One catalog entry: a model served by a gateway ([provider]) together with
 * the metadata the turn loop needs to run it.
 *
 * [id] is the wire-visible, user-facing identifier (`{provider.id}/{modelId}`,
 * served via `/api/models`); stored history records the raw [modelId] in
 * `ChatMessageMeta.modelId` (stamped by the hand on every response).
 * [contextLength] and [maxOutputTokens] are the
 * budgets used to classify context-vs-output exhaustion, [capabilities]
 * drive the pre-send content check and attachment support. Execution is
 * owned by the hand (`hand-pi/`); the brain only passes this metadata per
 * request (see `hand/HandMappers.kt`).
 */
class LLM(
    val provider: ModelProvider,
    val modelId: String,
    val contextLength: Long,
    val maxOutputTokens: Long,
    val capabilities: Set<LLMCapability>,
    /**
     * Pre-round compaction trigger: compact when the measured prompt size
     * (the last assistant message's provider-reported input tokens) exceeds
     * this fraction of the model's context window. `0.0` disables the
     * proactive path (the reactive `context_exhausted` path still compacts).
     * Per-model: the trigger headroom depends on the context size.
     */
    val compactionTriggerFraction: Double,
    /** Complete rounds kept verbatim at the tail of a compaction. */
    val compactionKeepRounds: Int,
) {
    /**
     * The unique id with provider + model id.
     * */
    val id = "${provider.id}/$modelId"

    init {
        require(compactionTriggerFraction in 0.0..1.0) {
            "CompactionTriggerFraction must be in [0, 1], got $compactionTriggerFraction"
        }
        require(compactionKeepRounds >= 1) {
            "CompactionKeepRounds must be at least 1, got $compactionKeepRounds"
        }
    }

    fun supports(capability: LLMCapability): Boolean =
        capabilities.any { it::class == capability::class }

    fun supportAttachmentKind(kind: AttachmentKind): Boolean {
        return kind.requiredCapabilities.all { supports(it) }
    }

    fun hasReasoning(): Boolean =
        capabilities.filterIsInstance<LLMCapability.Output.Reasoning>().isNotEmpty()

    /**
     * The reasoning effort to send with every request, from the model's
     * [LLMCapability.Output.Reasoning] capability — null for models without
     * reasoning support (the effort is omitted on the wire).
     */
    fun reasoningEffort(): String? =
        capabilities.filterIsInstance<LLMCapability.Output.Reasoning>()
            .firstOrNull()?.reasoningEffort

    /**
     * Check this model can process every attachment in [chat], throwing
     * [ModelCapabilityException] on the first unsupported kind. Attachments
     * are collected from the full prompt: they can arrive nested inside tool
     * results too (e.g. an MCP tool returning an image), so the scan
     * descends into result parts.
     *
     * The failure is deterministic: the same prompt with the same model
     * fails identically forever, so callers fail fast before any LLM call.
     */
    fun checkPromptContentCapabilities(chat: List<ChatMessage>) {
        chat.flatMap { message ->
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
                if (!supportAttachmentKind(kind)) {
                    throw ModelCapabilityException(
                        "Model ${this.id} does not support ${kind.name.lowercase()} content."
                    )
                }
            }
    }
}
