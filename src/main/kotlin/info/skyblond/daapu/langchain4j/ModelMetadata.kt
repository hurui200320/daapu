package info.skyblond.daapu.langchain4j

/**
 * Framework-neutral model metadata, owned by this project.
 *
 * Different provider may have different API behavior.
 * This represents the real provider.
 * For example, Gemma 4 31B it can be served from cerebras,
 * or via bifrost (backed by cerebras). In the later case,
 * the provider should be bifrost since the API must talk
 * and parse in bifrost way.
 */
enum class ModelProvider {
    Cerebras,
    Novita,
    Bifrost
}

enum class ModelCapability {
    /** The model accepts image content (e.g. `gemma-4-31b`, not `gpt-oss-120b`). */
    VisionImage,
    /** The model accepts video content (e.g. some commercial endpoint like Google's gemma 4) */
    VisionVideo,
    /** The model accepts audio content (e.g. gemma 4 e4b) */
    Audio,
    /** The model accepts document like PDF or ppt files */
    Document,
    /** The model streams reasoning and accepts `reasoning_effort` and return thinking fields. */
    Reasoning,
    /** The model accepts tool call attachments. */
    ToolCalls,
}

/**
 * One entry of the model catalog ([ModelCatalog]).
 *
 * [baseUrl] is the gateway this model is reached through: the project routes
 * every model through the single configured gateway (`LLM_BASE_URL`), so all
 * catalog entries carry the same base URL, stamped at catalog construction.
 * [id] is both the id the web UI sends and the model name sent to the gateway.
 *
 * [contextLength]/[maxOutputTokens] are always known for catalog entries, so
 * they are non-null here.
 */
data class ModelMetadata(
    val provider: ModelProvider,
    val baseUrl: String,
    val id: String,
    val contextLength: Long,
    val maxOutputTokens: Long,
    val capabilities: Set<ModelCapability>,
) {
    fun supports(capability: ModelCapability): Boolean = capability in capabilities
}
