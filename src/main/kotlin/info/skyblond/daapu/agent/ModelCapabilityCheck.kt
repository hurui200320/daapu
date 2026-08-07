package info.skyblond.daapu.agent

import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.AttachmentSource
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart

/**
 * The model cannot handle content present in the prompt. This is a
 * deterministic failure: the same prompt with the same model fails
 * identically forever, so it is pinned as non-retryable in
 * [isRetryableStreamError].
 *
 * Thrown by [checkPromptContentCapabilities] in the strategy's preprocess
 * node, before any LLM request is made.
 */
class ModelCapabilityException(message: String) : Exception(message)

/**
 * Verify the prompt only contains content the model's capabilities cover.
 *
 * Called after the user message is appended, so [messages] is exactly what
 * will be sent to the LLM: loaded history + refreshed system prompt + new
 * user input. This matters because images can enter the prompt from *either*
 * source — an image sent to a vision model is stored in history, and a later
 * run with a text-only model re-sends it from there, not from the request.
 */
fun checkPromptContentCapabilities(messages: List<Message>, model: LLModel) {
    val messageAttachments = messages.flatMap { it.parts }
        .filterIsInstance<MessagePart.Attachment>()
    // image
    if (messageAttachments.any { it.source is AttachmentSource.Image }) {
        if (!model.supports(LLMCapability.Vision.Image)) {
            throw ModelCapabilityException(
                "Model ${model.id} does not support image content " +
                        "(missing capability ${LLMCapability.Vision.Image.id})."
            )
        }
    }
    // Video
    if (messageAttachments.any { it.source is AttachmentSource.Video }) {
        if (!model.supports(LLMCapability.Vision.Video)) {
            throw ModelCapabilityException(
                "Model ${model.id} does not support video content " +
                        "(missing capability ${LLMCapability.Vision.Video.id})."
            )
        }
    }
    // Audio
    if (messageAttachments.any { it.source is AttachmentSource.Audio }) {
        if (!model.supports(LLMCapability.Audio)) {
            throw ModelCapabilityException(
                "Model ${model.id} does not support audio content " +
                        "(missing capability ${LLMCapability.Audio.id})."
            )
        }
    }
    // File
    if (messageAttachments.any { it.source is AttachmentSource.File }) {
        if (!model.supports(LLMCapability.Document)) {
            throw ModelCapabilityException(
                "Model ${model.id} does not support file content " +
                        "(missing capability ${LLMCapability.Document.id})."
            )
        }
    }
}
