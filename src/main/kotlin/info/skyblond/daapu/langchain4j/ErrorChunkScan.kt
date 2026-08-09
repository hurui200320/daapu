package info.skyblond.daapu.langchain4j

import dev.langchain4j.model.chat.response.ChatResponse
import dev.langchain4j.model.openai.OpenAiChatResponseMetadata
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Mid-stream SSE error chunk detection (spike #2 finding).
 *
 * Some gateways (OpenRouter-style, e.g. moderation rejections mapped to 403)
 * deliver errors as a mid-stream SSE `{"error": {"code": 403, ...}}` chunk
 * after a 2xx response instead of an HTTP error status. langchain4j's SSE
 * layer neither swallows the chunk nor throws: the stream **completes
 * normally** with `finishReason() == null` and no error, while the raw chunk
 * is retained verbatim in `OpenAiChatResponseMetadata.rawServerSentEvents()`.
 *
 * Without this scan the completed response would look usable (non-blank text,
 * no finish reason) and the acceptance check would accept a response the
 * gateway actually rejected — so the turn loop runs this scan **before** any
 * acceptance check.
 *
 * Returns the numeric `code` (when the chunk carries one) plus the raw chunk
 * data; `code == null` means the chunk carries no numeric code (e.g. a string
 * code or none at all). The caller maps a numeric code to
 * `dev.langchain4j.exception.HttpException(code, data)` so the retry policy's
 * cause-chain walk classifies it: permanent 4xx (except 408/429) fails the
 * run, everything else retries — matching the old koog client's behavior.
 */
fun ChatResponse.findErrorChunk(): Pair<Int?, String>? {
    val metadata = metadata() as? OpenAiChatResponseMetadata ?: return null
    for (event in metadata.rawServerSentEvents()) {
        val root = runCatching { Json.parseToJsonElement(event.data()).jsonObject }.getOrNull() ?: continue
        val error = root["error"] as? JsonObject ?: continue
        val code = (error["code"] as? JsonPrimitive)?.intOrNull
        return code to event.data()
    }
    return null
}
