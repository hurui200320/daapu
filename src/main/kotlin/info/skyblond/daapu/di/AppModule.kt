package info.skyblond.daapu.di

import info.skyblond.daapu.agent.ModelCatalog
import info.skyblond.daapu.agent.chat.ChatService
import info.skyblond.daapu.agent.chat.ChatStore
import info.skyblond.daapu.agent.chat.PostgresChatStore
import info.skyblond.daapu.agent.model.LLM
import info.skyblond.daapu.agent.model.LLMCapability
import info.skyblond.daapu.agent.pipeline.TitleGenerator
import info.skyblond.daapu.agent.pipeline.compaction.ChatCompactionService
import info.skyblond.daapu.memory.eltm.EltmToolProvider
import info.skyblond.daapu.agent.pipeline.eltm.EltmWriterService
import info.skyblond.daapu.agent.pipeline.eltm.MemoryExtractionService
import info.skyblond.daapu.agent.pipeline.investigate.InvestigatorService
import info.skyblond.daapu.agent.pipeline.rewrite.QueryRewriteService
import info.skyblond.daapu.agent.persona.PersonaService
import info.skyblond.daapu.agent.persona.PersonaStore
import info.skyblond.daapu.agent.persona.PostgresPersonaStore
import info.skyblond.daapu.agent.persist.GsgToolProvider
import info.skyblond.daapu.agent.persist.MainAgentSystemPromptService
import info.skyblond.daapu.agent.persist.PersistChatService
import info.skyblond.daapu.agent.tool.CombinedToolProvider
import info.skyblond.daapu.agent.tool.LengthSafeToolProvider
import info.skyblond.daapu.agent.tool.WhitelistedToolProvider
import info.skyblond.daapu.agent.tool.filesystem.FsToolProvider
import info.skyblond.daapu.config.AppConfig
import info.skyblond.daapu.db.AdvisoryChatLockManager
import info.skyblond.daapu.hand.HandCallbackService
import info.skyblond.daapu.hand.HandClient
import info.skyblond.daapu.hand.HandRunPolicy
import info.skyblond.daapu.hand.HandService
import info.skyblond.daapu.hand.HttpHandClient
import info.skyblond.daapu.mcp.McpToolProvider
import info.skyblond.daapu.memory.eltm.EltmService
import info.skyblond.daapu.memory.eltm.ExtractionQueue
import info.skyblond.daapu.memory.eltm.ExtractionQueueWorker
import info.skyblond.daapu.memory.eltm.PostgresEltmService
import info.skyblond.daapu.memory.eltm.PostgresExtractionQueue
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
 * when [ChatService] (the graph root) is first requested, so the
 * fail-fast validation here (REQUIRED model ids, tool-call capability, the
 * eager MCP connect) fires at startup, never mid-run.
 *
 * The module is parameterized by the loaded [AppConfig] (see `Main.kt`);
 * tests build it with `testAppConfig()` and override the store/client
 * seams with fakes (see `testutil/TestDi.kt`).
 */
fun appModule(config: AppConfig): Module = module {
    // the hand's per-request run policy (`hand.maxRetries` +
    // `hand.streamIdleTimeoutMs`): shared by the chat loop, every one-shot
    // service and the ELTM embeddings
    val handPolicy = HandRunPolicy(config.hand.maxRetries, config.hand.streamIdleTimeoutMs)

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

    // the model catalog comes straight from the config (`providers.*.llm`
    // and `providers.*.embedding`, see `agent/ModelCatalog.kt`); an empty
    // LLM list, a duplicated composite id, or a gateway-contract violation
    // in an embedding entry fails fast here at boot
    single<ModelCatalog> { ModelCatalog.fromConfig(config.providers) }

    // the per-chat lock: PostgreSQL session-level advisory locks over a
    // DEDICATED connection pool (`db/AdvisoryChatLockManager.kt`), one
    // connection per holder for the whole run/delete — its size
    // (`database.lockPoolSize`) doubles as the cap on concurrent chat runs.
    // Closed through the onClose callback when the shutdown hook closes the
    // Koin application.
    single<AdvisoryChatLockManager> {
        AdvisoryChatLockManager(config.database)
    } withOptions { onClose { it?.close() } }

    // the stores: all chats-table access and the ELTM tables live
    // behind these seams, so tests override them with fakes
    single<ChatStore> { PostgresChatStore() }
    single<PersonaStore> { PostgresPersonaStore() }
    // the persona seam: the code default + the `personas` rows; the
    // servable namespaces are snapshotted from the chat loop's tool set at
    // boot, so persona whitelists validate against exactly what a run would
    // serve (a config change needs a restart anyway — MCP servers connect
    // eagerly at boot)
    single<PersonaService> {
        PersonaService(
            store = get(),
            servedNamespaces = get<CombinedToolProvider>().namespaces(),
        )
    }
    single<EltmService> {
        PostgresEltmService(
            embeddingModel = get<ModelCatalog>().findEmbeddingModel(config.memory.eltm.embeddingModel)
                ?: throw IllegalArgumentException(
                    "memory.eltm.embeddingModel '${config.memory.eltm.embeddingModel}' is not in the model catalog"
                ),
            hand = get(),
            entityMatchThreshold = config.memory.eltm.entityMatchThreshold,
            noteSearchThreshold = config.memory.eltm.noteSearchThreshold,
            policy = handPolicy,
        )
    }

    // the MCP tool servers come from config (`mcp.customs` keyed by
    // namespace plus the dedicated `mcp.exa`, merged by
    // `McpConfig.allServers`); the provider connects eagerly at
    // construction, so a server that cannot be reached aborts startup
    // instead of silently degrading every chat run.
    single<McpToolProvider> {
        McpToolProvider(config.mcp.allServers(), config.mcp.proxy)
    } withOptions { onClose { it?.close() } }

    // the read-only filesystem provider (`agent/tool/filesystem/`), only
    // when enabled: construction canonicalizes the allowed dirs and compiles
    // the blacklist globs, so a bad `tool.fs` config (missing directory,
    // invalid glob) fails fast at boot. When disabled it is not registered
    // at all — the namespace `fs` is hardcoded and would collide with an MCP
    // server under the same namespace (fail fast in CombinedToolProvider).
    if (config.tool.fs.enabled) {
        single<FsToolProvider> {
            FsToolProvider(config.tool.fs.allowedDirs, config.tool.fs.blacklists)
        }
    }

    // one-shot pipeline services: stateless across runs, so a single
    // instance is shared by every concurrent chat run. They talk to the
    // hand through the same `/v1/run` seam as the chat loop, carrying the
    // same `hand.*` policy knobs (transient retry budget, idle timeout).
    single<ChatCompactionService> {
        ChatCompactionService(
            model = requiredLlm("memory.compactModel", config.memory.compactModel),
            hand = get(),
            policy = handPolicy,
        )
    }
    single<TitleGenerator> {
        TitleGenerator(
            model = requiredLlm("title.model", config.title.model),
            hand = get(),
            lastNRound = config.title.lastNRound,
            policy = handPolicy,
        )
    }

    // the chat loop's tool set: the MCP servers plus the `gsg__investigate`
    // tool (see `agent/persist/GsgToolProvider.kt`) — the main agent no
    // longer sees the granular ELTM read tools (`eltm__*`); deep memory and
    // web searches go through the sub-agent. The MCP child is only included
    // when it serves namespaces: a namespace-less provider advertises
    // nothing, and CombinedToolProvider fails fast on a namespace-less
    // child.
    single<EltmToolProvider> {
        EltmToolProvider(
            eltmService = get(),
            readOnly = true,
            namespace = "eltm"
        )
    }
    single<GsgToolProvider> {
        GsgToolProvider(investigator = get())
    }
    single<CombinedToolProvider> {
        CombinedToolProvider(
            buildList {
                val mcp = get<McpToolProvider>()
                if (mcp.namespaces().isNotEmpty()) add(mcp)
                if (config.tool.fs.enabled) add(get<FsToolProvider>())
                add(get<GsgToolProvider>())
            }
        )
    }
    // the chat loop's actual tool set: the combined set wrapped in the
    // length-safe provider (`agent/tool/LengthSafeToolProvider.kt`), so
    // every tool result the loop's model sees is capped at
    // `agent.main.toolResultLimit` chars regardless of what the MCP
    // servers return. The raw combined set stays registered for the
    // persona service's servable-namespace snapshot.
    single<LengthSafeToolProvider> {
        LengthSafeToolProvider(get<CombinedToolProvider>(), config.agent.main.toolResultLimit)
    }
    // the investigate sub-agent (`agent/pipeline/investigate/`): its tool
    // set is its OWN combined provider — the MCP servers plus the read-only
    // ELTM tools — restricted by the `agent.investigator.allowedNamespaces`
    // whitelist and capped by the length-safe provider
    // (`agent.investigator.toolResultLimit`). It is NOT the chat loop's set,
    // deliberately: the loop no longer serves `eltm`, and a separate set
    // means `gsg` is not whitelistable for the sub-agent, ruling out
    // recursion automatically via the construction-time fail-fast. Resolved
    // at boot like the other one-shot models (it is reachable from the graph
    // root through `GsgToolProvider`): an unknown id, a model without
    // tool-call support, or a whitelisted namespace this set does not serve
    // (the `WhitelistedToolProvider` construction invariant) fails fast at
    // startup.
    single<InvestigatorService> {
        val investigator = config.agent.investigator
        val combined = CombinedToolProvider(
            buildList {
                val mcp = get<McpToolProvider>()
                if (mcp.namespaces().isNotEmpty()) add(mcp)
                if (config.tool.fs.enabled) add(get<FsToolProvider>())
                add(get<EltmToolProvider>())
            }
        )
        val whitelist = WhitelistedToolProvider(combined, investigator.allowedNamespaces.toSet())
        val toolProvider = LengthSafeToolProvider(whitelist, investigator.toolResultLimit)
        InvestigatorService(
            model = requiredLlm(
                "agent.investigator.model", investigator.model,
                toolLoopNote = "the investigate agent runs a tool loop",
            ),
            hand = get(),
            toolProvider = toolProvider,
            maxRounds = investigator.maxRounds,
            policy = handPolicy,
        )
    }
    single<EltmWriterService> {
        EltmWriterService(
            writerModel = requiredLlm(
                "memory.eltm.writerModel", config.memory.eltm.writerModel,
                toolLoopNote = "the ELTM writer runs a tool loop",
            ),
            hand = get(),
            eltmService = get(),
            maxWriterRounds = config.memory.eltm.maxWriterRounds,
            policy = handPolicy,
        )
    }
    single<MemoryExtractionService> {
        MemoryExtractionService(
            extractModel = requiredLlm(
                "memory.eltm.extractionModel",
                config.memory.eltm.extractionModel
            ),
            hand = get(),
            policy = handPolicy,
            eltmWriterService = get(),
        )
    }

    // the background extraction queue (Postgres-as-queue, visibility-timeout
    // pattern — see `memory/eltm/ExtractionQueue.kt`): the chat-deletion path
    // and the compaction path (PersistChatService.compactAndEnqueue) enqueue
    // history snapshots and return immediately; the worker below drains it
    // into the ELTM off the request path. Not reachable from the
    // ChatService graph root on purpose: `server/WebServer.kt` resolves and
    // starts it explicitly (only the production server runs poll loops, never
    // a test that merely resolves ChatService), and `stop()` cancels the
    // worker's scope through the container's onClose when the shutdown hook
    // closes the Koin application.
    single<ExtractionQueue> {
        PostgresExtractionQueue(
            jobTimeoutMinutes = config.memory.eltm.jobTimeoutMinutes,
            retryDelayMinutes = config.memory.eltm.retryDelayMinutes,
        )
    }
    single<ExtractionQueueWorker> {
        ExtractionQueueWorker(
            queue = get(),
            memoryExtractionService = get(),
            workers = config.memory.eltm.queueWorkers,
        )
    } withOptions { onClose { it?.stop() } }

    single<QueryRewriteService> {
        QueryRewriteService(
            model = requiredLlm("memory.eltm.rewriteModel", config.memory.eltm.rewriteModel),
            hand = get(),
            policy = handPolicy,
        )
    }

    // the main agent's system prompt renderer: stateless across runs, a
    // single shared instance.
    single<MainAgentSystemPromptService> {
        MainAgentSystemPromptService()
    }

    single<PersistChatService> {
        PersistChatService(
            chatStore = get(),
            eltmService = get(),
            queryRewriteService = get(),
            hand = get(),
            compactionService = get(),
            systemPromptService = get(),
            extractionQueue = get(),
            rewriteRounds = config.memory.eltm.rewriteRounds,
            relatedEntitiesLimit = config.memory.eltm.relatedEntitiesLimit,
            relatedNotesLimit = config.memory.eltm.relatedNotesLimit,
            maxRounds = config.hand.maxRounds,
            policy = handPolicy,
        )
    }

    // the graph root: the Koin compiler plugin auto-wires every constructor
    // parameter from the definitions above. Resolving it eagerly at startup
    // (WebServer.kt) runs every definition above, so configuration errors
    // fail the boot, not the first request. The service owns no resources:
    // the hand client and the MCP clients are closed through the onClose
    // callbacks above when the shutdown hook closes the Koin application.
    single<ChatService>()
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
