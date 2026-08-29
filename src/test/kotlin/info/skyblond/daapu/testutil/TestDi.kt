package info.skyblond.daapu.testutil

import info.skyblond.daapu.agent.chat.ChatService
import info.skyblond.daapu.agent.chat.ChatStore
import info.skyblond.daapu.agent.persona.PersonaStore
import info.skyblond.daapu.config.AppConfig
import info.skyblond.daapu.config.testAppConfig
import info.skyblond.daapu.di.appModule
import info.skyblond.daapu.hand.HandClient
import info.skyblond.daapu.mcp.McpToolProvider
import info.skyblond.daapu.memory.eltm.EltmService
import info.skyblond.daapu.server.FakePersonaStore
import kotlin.test.assertFails
import org.koin.core.KoinApplication
import org.koin.core.error.InstanceCreationException
import org.koin.core.module.Module
import org.koin.dsl.koinApplication
import org.koin.dsl.module

/**
 * The test seam for the Koin container ([appModule]): override the
 * store/client definitions with fakes, then resolve the graph root.
 *
 * The overrides mirror the pre-Koin `ChatService(...)` constructor
 * parameters (`hand = ...`, `chatStore = ...`, ...), so a call site swap
 * from `ChatService(testAppConfig(), hand = fake)` to
 * `chatService(testAppConfig(), hand = fake)` keeps every named argument.
 *
 * Koin 4 allows overrides by default (`allowOverride = true`), so the
 * override module simply re-declares the seam types after the production
 * module — the later definition wins.
 *
 * [mcpToolProvider] defaults to an EMPTY provider: [testAppConfig] carries
 * the REQUIRED exa server (a live `https://mcp.exa.ai/mcp` URL), and the
 * production provider connects eagerly at construction — tests must never
 * touch the network. Pass a provider explicitly to exercise MCP wiring.
 *
 * [personaStore] defaults to a [FakePersonaStore]: the production
 * [PostgresPersonaStore] would need a live database, and the persona
 * service resolves eagerly with the graph root.
 */
fun testKoinApp(
    config: AppConfig = testAppConfig(),
    hand: HandClient? = null,
    chatStore: ChatStore? = null,
    eltmService: EltmService? = null,
    mcpToolProvider: McpToolProvider? = null,
    personaStore: PersonaStore? = null,
): KoinApplication = koinApplication {
    modules(
        appModule(config),
        testOverrides(
            hand,
            chatStore,
            eltmService,
            mcpToolProvider ?: EMPTY_MCP_TOOL_PROVIDER,
            personaStore ?: FakePersonaStore(),
        ),
    )
}

fun chatService(
    config: AppConfig = testAppConfig(),
    hand: HandClient? = null,
    chatStore: ChatStore? = null,
    eltmService: EltmService? = null,
    mcpToolProvider: McpToolProvider? = null,
    personaStore: PersonaStore? = null,
): ChatService = testKoinApp(
    config, hand, chatStore, eltmService, mcpToolProvider, personaStore,
).koin.get<ChatService>()

/**
 * The empty MCP provider shared by every test that does not pass its own:
 * no servers, so no eager connect, no tools. `McpToolProvider(emptyMap())`
 * construction is a no-op.
 */
private val EMPTY_MCP_TOOL_PROVIDER: McpToolProvider = McpToolProvider(emptyMap())

/**
 * Declare fake definitions over the production module. The override module
 * comes after `AppModule(...)` and Koin 4 allows overrides by default, so
 * a definition here replaces the production one of the same type; anything
 * null stays on the production definition. The MCP provider is always
 * overridden ([testKoinApp] defaults it to an empty provider — never the
 * production one, which connects to the live exa server eagerly). The
 * persona store defaults to an in-memory fake the same way (the production
 * store needs a live database).
 */
fun testOverrides(
    hand: HandClient? = null,
    chatStore: ChatStore? = null,
    eltmService: EltmService? = null,
    mcpToolProvider: McpToolProvider? = null,
    personaStore: PersonaStore? = null,
): Module = module {
    if (hand != null) single<HandClient> { hand }
    if (chatStore != null) single<ChatStore> { chatStore }
    if (eltmService != null) single<EltmService> { eltmService }
    if (mcpToolProvider != null) single<McpToolProvider> { mcpToolProvider }
    if (personaStore != null) single<PersonaStore> { personaStore }
}

/**
 * Run [block] and return the failure it throws, unwrapping Koin's
 * [InstanceCreationException] wrappers down to the root cause: a definition
 * lambda that throws (a fail-fast config validation, e.g. an unknown model
 * id) surfaces through the container as `InstanceCreationException` —
 * possibly nested once per graph level — with the original exception as the
 * deepest cause. The fail-fast behavior itself is unchanged: resolution
 * throws, so startup aborts; this helper only re-exposes the original
 * error type/message to the assertions that pin it.
 */
fun assertFailsFast(block: () -> Unit): Throwable {
    var error = assertFails { block() }
    while (error is InstanceCreationException && error.cause != null) {
        error = error.cause!!
    }
    return error
}
