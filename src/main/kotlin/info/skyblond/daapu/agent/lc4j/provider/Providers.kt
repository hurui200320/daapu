package info.skyblond.daapu.agent.lc4j.provider

import dev.langchain4j.http.client.jdk.JdkHttpClient
import dev.langchain4j.model.openai.OpenAiStreamingChatModel
import info.skyblond.daapu.SAFE_ID_REGEX
import info.skyblond.daapu.agent.lc4j.provider.client.ReasoningRewriteHttpClient

abstract class OpenAICompatibleProvider(
    val id: String,
    protected val baseUrl: String,
    protected val apiKey: String,
) {
    init {
        // the id is prefixed onto every model id (`provider/modelId`), which is
        // served via /api/models and stored in chat history, so it must stay
        // unambiguous: no '/', no whitespace, no uppercase — see SAFE_ID_REGEX
        require(id.matches(SAFE_ID_REGEX)) {
            "Provider id '$id' is invalid: only [0-9a-z_-] is allowed"
        }
    }

    open fun createOpenAiStreamingChatModelBuilder(): OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder =
        OpenAiStreamingChatModel.builder()
            .baseUrl(baseUrl)
            .apiKey(apiKey)
}

class BifrostProvider(
    id: String,
    baseUrl: String,
    apiKey: String,
) : OpenAICompatibleProvider(
    id = id, baseUrl = baseUrl, apiKey = apiKey
) {
    override fun createOpenAiStreamingChatModelBuilder(): OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder =
        super.createOpenAiStreamingChatModelBuilder()
            // Bifrost gateway streams reasoning as delta.reasoning; the
            // rewrite client normalizes it to reasoning_content so the stock
            // parser accumulates AiMessage.thinking()
            .httpClientBuilder(ReasoningRewriteHttpClient.Builder(JdkHttpClient.builder()))
}
