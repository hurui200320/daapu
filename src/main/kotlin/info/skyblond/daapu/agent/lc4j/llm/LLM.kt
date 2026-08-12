package info.skyblond.daapu.agent.lc4j.llm

import dev.langchain4j.model.openai.OpenAiStreamingChatModel
import info.skyblond.daapu.chat.AttachmentKind
import info.skyblond.daapu.agent.lc4j.provider.OpenAICompatibleProvider
import java.time.Duration

/**
 * Capabilities of a model, used to reject content a model cannot process
 * before any LLM request ([checkPromptContentCapabilities] / attachment
 * kinds) and to configure per-model behavior ([LLM.toStreamingChatModel]).
 * Nested under `Input`/`Output` to reflect the side of the conversation the
 * capability belongs to.
 */
sealed class LLMCapability {
    class Input {
        class Vision {
            data object Image : LLMCapability()
            data object Video : LLMCapability()
        }

        data object Audio : LLMCapability()
        data object Document : LLMCapability()
    }

    class Output {
        data object Reasoning : LLMCapability()
        data object ToolCalls : LLMCapability()
        // TODO: structural output? Add when we need it
    }
}

/**
 * One catalog entry: a model served by a gateway ([provider]) together with
 * the metadata the turn loop needs to run it.
 *
 * [id] is the wire-visible, user-facing identifier (`{provider.id}/{modelId}`,
 * served via `/api/models` and stored in chat history as
 * `ChatMessageMeta.modelId`); [contextLength] and [maxOutputTokens] are the
 * budgets used to classify context-vs-output exhaustion, [capabilities] drive
 * the pre-send content check and attachment support, and
 * [toStreamingChatModel] builds a fresh streaming model per run — cheap, as
 * it holds configuration only, no connections.
 */
class LLM(
    val provider: OpenAICompatibleProvider,
    val modelId: String,
    val contextLength: Long,
    val maxOutputTokens: Long,
    val capabilities: Set<LLMCapability>,
) {
    /**
     * The unique id with provider + model id.
     * */
    val id = "${provider.id}/${modelId}"

    fun supports(capability: LLMCapability): Boolean = capability in capabilities

    fun supportAttachmentKind(kind: AttachmentKind): Boolean {
        return kind.requiredCapabilities.all { supports(it) }
    }

    fun hasReasoning(): Boolean = supports(LLMCapability.Output.Reasoning)

    /**
     * Build a streaming chat model for one run. Cheap: configuration only,
     * no connections, so building one per request is fine (see
     * `ChatRunService`). Thinking is sent/returned according to
     * [LLMCapability.Output.Reasoning].
     */
    fun toStreamingChatModel(
        reasoningEffort: String,
        timeout: Duration = Duration.ofSeconds(60),
    ): OpenAiStreamingChatModel {
        val builder = provider.createOpenAiStreamingChatModelBuilder()
            .modelName(modelId)
            .timeout(timeout)
            // TODO: thinkingFieldName is set for both send and return thinking
            //       need to investigate and provided per provider
            // TODO: also the field name is shared for both req and resp,
            //       what if they are different? And how do we verify input? Provider API doc?
            .sendThinking(hasReasoning())
            .returnThinking(hasReasoning())

        if (hasReasoning()) builder.reasoningEffort(reasoningEffort)

        return builder.build()
    }
}
