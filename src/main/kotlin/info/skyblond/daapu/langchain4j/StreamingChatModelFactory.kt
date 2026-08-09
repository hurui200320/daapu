package info.skyblond.daapu.langchain4j

import dev.langchain4j.model.openai.OpenAiStreamingChatModel
import java.time.Duration

/**
 * Construct configured [OpenAiStreamingChatModel] instances from catalog
 * [ModelMetadata], with the builder knobs pinned by the streaming spike
 * (#1, verified live against Cerebras/Novita on langchain4j 1.18.1).
 *
 * Caveats the turn loop (#6) must handle, all recorded in the spike outcome:
 * - `stream_options.include_usage` is **hardcoded on** in langchain4j's
 *   `doChat` — usage tokens land in `ChatResponseMetadata.tokenUsage()` with
 *   no config needed here.
 * - A stream that ends with NO `finish_reason` (clean EOF) is **silently
 *   accepted**: `onCompleteResponse` fires with `finishReason() == null`.
 *   There is no `requireEndFrame` equivalent — the retry policy must treat
 *   `finishReason() == null` as truncated itself. Unknown/custom finish
 *   reasons (e.g. `model_length`) map to `null` too, landing in the same
 *   bucket.
 * - For one failure, both `onError` **and** `onCompleteResponse` can fire
 *   (parser `onError` + `onClose` race) — the turn loop must tolerate that.
 * - Cerebras streams reasoning as `delta.reasoning` (plain text), which
 *   langchain4j's `Delta` (hardcoded to `reasoning_content`) silently drops
 *   — the same bug class the old koog client patched. A custom decorator
 *   using the raw-SSE hooks (`onUnmappedRawEvent` + `rawServerSentEvents`)
 *   to feed `onPartialThinking` is deferred to #6.
 * - Novita's `deepseek-r1` family is load-balanced across backends: ~1/3 of
 *   runs stream reasoning inline in `content` as `<think>...</think>` which
 *   cannot be separated from the answer.
 *
 * [returnThinking]/[sendThinking] are enabled exactly when the model declares
 * [ModelCapability.Reasoning]: `sendThinking(true)` round-trips
 * `AiMessage.thinking()` back as `reasoning_content` on later requests, which
 * preserves the current koog behavior (accepted messages keep their reasoning
 * part and it is re-sent on the next run). A provider that rejects the field
 * would 400 every later run of that chat — same caveat as the koog path.
 *
 * @param apiKey the single gateway API key (`LLM_API_KEY`); the project routes
 *   every model through one gateway, so there is one key for all models.
 */
fun ModelMetadata.toStreamingChatModel(
    apiKey: String,
    timeout: Duration = Duration.ofSeconds(60),
): OpenAiStreamingChatModel {
    val builder = OpenAiStreamingChatModel.builder()
        .baseUrl(baseUrl)
        .apiKey(apiKey)
        .modelName(id)
        .timeout(timeout)
        .returnThinking(hasReasoning())
        .sendThinking(hasReasoning())
    if (hasReasoning()) builder.reasoningEffort(REASONING_EFFORT)
    return builder.build()
}

private fun ModelMetadata.hasReasoning(): Boolean = supports(ModelCapability.Reasoning)
