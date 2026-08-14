package info.skyblond.daapu.agent.lc4j.provider

import dev.langchain4j.http.client.jdk.JdkHttpClient
import dev.langchain4j.model.openai.OpenAiChatModel
import dev.langchain4j.model.openai.OpenAiStreamingChatModel
import info.skyblond.daapu.agent.lc4j.provider.client.ReasoningRewriteHttpClient
import info.skyblond.daapu.config.SAFE_ID_REGEX

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

    open fun createOpenAiChatModelBuilder(): OpenAiChatModel.OpenAiChatModelBuilder =
        OpenAiChatModel.builder()
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

    override fun createOpenAiChatModelBuilder(): OpenAiChatModel.OpenAiChatModelBuilder =
        super.createOpenAiChatModelBuilder()
            // the rewrite client passes non-SSE requests through untouched, so
            // it is safe to share; a non-streaming response's reasoning field
            // is simply not parsed (the one-shots only need the text content)
            .httpClientBuilder(ReasoningRewriteHttpClient.Builder(JdkHttpClient.builder()))
}
