package info.skyblond.daapu.langchain4j

import info.skyblond.daapu.agent.ModelCapabilityException
import info.skyblond.daapu.history.AttachmentKind

/**
 * Verify a prompt only contains content the model's capabilities cover.
 *
 * Framework-agnostic port of the koog-typed check that runs in the strategy's
 * preprocess node (the koog version still lives in
 * `agent/ModelCapabilityCheck.kt` until the runtime switches over in #6).
 *
 * [attachments] must be extracted from the **full prompt** — loaded history +
 * new user input — not from the request alone: images can enter the prompt
 * from either source (an image sent to a vision model is stored in history,
 * and a later run with a text-only model re-sends it from there, not from the
 * request). The caller (the turn loop, in #6) is responsible for scanning the
 * whole prompt; this check only maps kinds to required capabilities.
 *
 * Throws [ModelCapabilityException] (pinned non-retryable in
 * `isRetryableStreamError`): a model cannot process content it lacks the
 * capability for, so the identical prompt would fail identically forever.
 */
fun checkPromptContentCapabilities(
    attachments: Set<AttachmentKind>,
    model: ModelMetadata,
) {
    attachments.forEach { kind ->
        val required = kind.requiredCapability()
        if (!model.supports(required)) {
            throw ModelCapabilityException(
                "Model ${model.id} does not support ${kind.name.lowercase()} content " +
                    "(missing capability $required)."
            )
        }
    }
}

/**
 * The capability needed to process an attachment of this kind. Every kind
 * maps to a capability; whether the model actually has it is checked against
 * its [ModelMetadata.capabilities]. The current catalog's models only
 * declare vision, so video/audio/file always fail there.
 */
private fun AttachmentKind.requiredCapability(): ModelCapability = when (this) {
    AttachmentKind.Image -> ModelCapability.VisionImage
    AttachmentKind.Video -> ModelCapability.VisionVideo
    AttachmentKind.Audio -> ModelCapability.Audio
    AttachmentKind.File -> ModelCapability.Document
}
