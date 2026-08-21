package info.skyblond.daapu.di

import info.skyblond.daapu.agent.ModelCatalog
import info.skyblond.daapu.agent.chat.ChatStore
import info.skyblond.daapu.agent.chat.PostgresChatStore
import info.skyblond.daapu.agent.model.EmbeddingModel
import info.skyblond.daapu.agent.model.LLM
import info.skyblond.daapu.agent.model.LLMCapability
import info.skyblond.daapu.agent.model.ModelProvider
import info.skyblond.daapu.agent.oneshot.TitleGenerator
import info.skyblond.daapu.agent.oneshot.compaction.ChatCompactionService
import info.skyblond.daapu.agent.oneshot.eltm.EltmToolProvider
import info.skyblond.daapu.agent.oneshot.eltm.EltmWriterService
import info.skyblond.daapu.agent.oneshot.sstm.SstmExtractionService
import info.skyblond.daapu.agent.persist.PersistChatService
import info.skyblond.daapu.agent.persist.renderMainAgentSystemPrompt
import info.skyblond.daapu.agent.tool.CombinedToolProvider
import info.skyblond.daapu.config.AppConfig
import info.skyblond.daapu.hand.HandCallbackService
import info.skyblond.daapu.hand.HandClient
import info.skyblond.daapu.hand.HandService
import info.skyblond.daapu.hand.HttpHandClient
import info.skyblond.daapu.mcp.McpToolProvider
import info.skyblond.daapu.memory.eltm.EltmService
import info.skyblond.daapu.memory.eltm.PostgresEltmService
import info.skyblond.daapu.memory.sstm.PostgresSstmService
import info.skyblond.daapu.memory.sstm.SstmService
import info.skyblond.daapu.server.ChatRunService
import org.koin.core.module.Module
import org.koin.core.module.dsl.onClose
import org.koin.core.module.dsl.withOptions
import org.koin.core.scope.Scope
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

/**
 * The Koin container's module: the whole application object graph. Every
 * definition is a singleton — the services are stateless across runs and
 * shared by every concurrent chat run — and everything resolves eagerly
 * when [ChatRunService] (the graph root) is first requested, so the
 * fail-fast validation here (REQUIRED model ids, tool-call capability, the
 * eager MCP connect) fires at startup, never mid-run.
 *
 * The module is parameterized by the loaded [AppConfig] (see `Main.kt`);
 * tests build it with `testAppConfig()` and override the store/client
 * seams with fakes (see `testutil/TestDi.kt`).
 */
fun daapuModule(config: AppConfig): Module = module {
    // the hand-pi client plus the callback wiring (the in-flight run
    // registry the hand's tool callbacks resolve through); the callback and
    // tool-list URLs are loopback PoC values derived from the server port
    single<HandClient> { HttpHandClient(config.hand.baseUrl, config.hand.token) }
    single<HandCallbackService> { HandCallbackService(config.hand.token) }
    single<HandService> {
        HandService(
            hand = get(),
            handCallback = get(),
            // TODO: make these two URL configurable
            //       fine for now, but will break if use multiple container for production
            toolCallbackUrl = "http://127.0.0.1:${config.server.port}/api/hand/tool",
            toolListUrl = "http://127.0.0.1:${config.server.port}/api/hand/tools",
        )
    } withOptions { onClose { it?.close() } }

    // the model catalog, pinned to the bifrost gateway (see ModelCatalog.kt):
    // a config without it is a wiring bug, so fail fast at startup
    single<ModelCatalog> {
        val bifrostConfig = config.providers["bifrost"]
            ?: error("Provider config 'bifrost' not found")
        ModelCatalog(
            mapOf(
                "bifrost" to ModelProvider(
                    id = "bifrost",
                    baseUrl = bifrostConfig.baseUrl,
                    apiKey = bifrostConfig.apiKey,
                )
            )
        )
    }

    // the stores: all chats-table access and the SSTM/ELTM tables live
    // behind these seams, so tests override them with fakes
    single<ChatStore> { PostgresChatStore() }
    single<SstmService> { PostgresSstmService() }
    single<EltmService> {
        PostgresEltmService(
            embeddingModel = get<ModelCatalog>().findEmbeddingModel(config.memory.eltm.embeddingModel)
                ?: throw IllegalArgumentException(
                    "memory.eltm.embeddingModel '${config.memory.eltm.embeddingModel}' is not in the model catalog"
                ),
            hand = get(),
            entityMatchThreshold = config.memory.eltm.entityMatchThreshold,
            noteSearchThreshold = config.memory.eltm.noteSearchThreshold,
            maxRetries = config.hand.maxRetries,
            timeoutMs = config.hand.streamIdleTimeoutMs,
        )
    }

    // the MCP tool servers come from config (`mcp.servers`); the provider
    // connects eagerly at construction, so a server that cannot be reached
    // aborts startup instead of silently degrading every chat run. The
    // default (`McpToolProvider(emptyList())`) never appears here: the
    // container always wires the configured servers.
    single<McpToolProvider> { McpToolProvider(config.mcp.servers) }

    // one-shot pipeline services: stateless across runs, so a single
    // instance is shared by every concurrent chat run. They talk to the
    // hand through the same `/v1/run` seam as the chat loop, carrying the
    // same `hand.*` policy knobs (transient retry budget, idle timeout).
    single<ChatCompactionService> {
        ChatCompactionService(
            model = requiredLlm("memory.compactModel", config.memory.compactModel),
            hand = get(),
            maxRetries = config.hand.maxRetries,
            streamIdleTimeoutMs = config.hand.streamIdleTimeoutMs,
        )
    }
    single<TitleGenerator> {
        TitleGenerator(
            model = requiredLlm("title.model", config.title.model),
            hand = get(),
            lastNRound = config.title.lastNRound,
            maxRetries = config.hand.maxRetries,
            streamIdleTimeoutMs = config.hand.streamIdleTimeoutMs,
        )
    }

    // the chat loop's tool set: the MCP servers plus the read-only ELTM
    // tools (`eltm__*`), so the main agent can query the external long-term
    // memory directly (the `recall` sub-session tool that offloads this is
    // deferred). The MCP child is only included when it serves namespaces:
    // a namespace-less provider advertises nothing, and CombinedToolProvider
    // fails fast on a namespace-less child.
    single<EltmToolProvider> {
        EltmToolProvider(
            eltmService = get(),
            readOnly = true,
            namespace = "eltm"
        )
    }
    single<CombinedToolProvider> {
        CombinedToolProvider(
            buildList {
                val mcp = get<McpToolProvider>()
                if (mcp.namespaces().isNotEmpty()) add(mcp)
                add(get<EltmToolProvider>())
            }
        )
    } withOptions { onClose { it?.close() } }
    single<EltmWriterService> {
        EltmWriterService(
            writerModel = requiredLlm(
                "memory.eltm.writerModel", config.memory.eltm.writerModel,
                toolLoopNote = "the ELTM writer runs a tool loop",
            ),
            hand = get(),
            eltmService = get(),
            maxWriterRounds = config.memory.eltm.maxWriterRounds,
            maxRetries = config.hand.maxRetries,
            streamIdleTimeoutMs = config.hand.streamIdleTimeoutMs,
        )
    }
    single<SstmExtractionService> {
        SstmExtractionService(
            extractModel = requiredLlm("memory.sstm.extractModel", config.memory.sstm.extractModel),
            mergeModel = requiredLlm(
                "memory.sstm.mergeModel", config.memory.sstm.mergeModel,
                toolLoopNote = "the memory merge agent runs a tool loop",
            ),
            hand = get(),
            sstmService = get(),
            maxMergeRounds = config.memory.sstm.maxMergeRounds,
            maxRetries = config.hand.maxRetries,
            streamIdleTimeoutMs = config.hand.streamIdleTimeoutMs,
            eltmWriterService = get(),
            sstmCapacity = config.memory.sstm.maxCapacity,
            purgeBatchSize = config.memory.sstm.purgeBatchSize,
        )
    }
    single<PersistChatService> {
        PersistChatService(
            chatStore = get(),
            sstmService = get(),
            eltmService = get(),
            hand = get(),
            compactionService = get(),
            sstmExtractionService = get(),
            maxRounds = config.hand.maxRounds,
            maxRetries = config.hand.maxRetries,
            streamIdleTimeoutMs = config.hand.streamIdleTimeoutMs,
        )
    }

    // the graph root: the Koin compiler plugin auto-wires every constructor
    // parameter from the definitions above. Resolving it eagerly at startup
    // (WebServer.kt) runs every definition above, so configuration errors
    // fail the boot, not the first request. The service owns no resources:
    // the hand client and the MCP clients are closed through the onClose
    // callbacks above when the shutdown hook closes the Koin application.
    single<ChatRunService>()
}

/**
 * Resolve a REQUIRED one-shot pipeline model from the catalog by its config
 * key, fail fast with the config key in the error (a typo must fail at
 * startup, not silently skip every compaction/extraction). [toolLoopNote]
 * is non-null for the models that must support tool calls.
 */
private fun Scope.requiredLlm(
    configKey: String,
    id: String,
    toolLoopNote: String? = null,
): LLM {
    val model = get<ModelCatalog>().findModel(id)
        ?: throw IllegalArgumentException("$configKey '$id' is not in the model catalog")
    if (toolLoopNote != null) {
        require(model.supports(LLMCapability.Output.ToolCalls)) {
            "$configKey '${model.id}' must support tool calls ($toolLoopNote)"
        }
    }
    return model
}
