package info.skyblond.daapu.config

import kotlinx.serialization.Serializable

/**
 * One OpenAI-compatible LLM provider, keyed by its id in [AppConfig.providers]
 * (`{provider.id}/{modelId}` prefixes every model id, so the id must match
 * [SAFE_ID_REGEX] — enforced at provider construction, see
 * `agent/hand/HandMappers.kt`). [baseUrl] is used as-is by the hand
 * (`hand-pi/`), which appends the OpenAI API path to it — so the value must
 * carry the full `/v1` root (e.g. `http://localhost:8000/v1`); nothing
 * appends `/v1`.
 */
@Serializable
data class LlmProviderConfig(
    val apiKey: String,
    val baseUrl: String,
) {
    fun validate(id: String) {
        require(apiKey.isNotBlank()) { "providers['$id'].apiKey must not be blank" }
        require(baseUrl.isNotBlank()) { "providers['$id'].baseUrl must not be blank" }
    }
}
