package info.skyblond.daapu.agent.model

/**
 * Capabilities of a model, used to reject content a model cannot process
 * before any LLM request ([LLM.checkPromptContentCapabilities] / attachment
 * kinds) and to configure per-model behavior. Nested under `Input`/`Output`
 * to reflect the side of the conversation the capability belongs to.
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
        /**
         * The model supports a reasoning phase. [reasoningEffort] is sent as
         * `reasoning_effort` on every request (the pi-ai union:
         * minimal/low/medium/high/xhigh/max); membership itself is
         * effort-agnostic (see [LLM.supports]).
         */
        data class Reasoning(val reasoningEffort: String) : LLMCapability()
        data object ToolCalls : LLMCapability()
        // TODO: structural output? Add when we need it
    }
}

/**
 * The model cannot handle content present in the prompt. This is a
 * deterministic failure: the same prompt with the same model fails
 * identically forever.
 */
class ModelCapabilityException(message: String) : Exception(message)
