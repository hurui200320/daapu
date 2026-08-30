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
import kotlin.test.assertFails
import org.koin.core.KoinApplication
import org.koin.core.error.InstanceCreationException
import org.koin.core.module.Module
import org.koin.dsl.koinApplication
import org.koin.dsl.module

/**
 * The test seam for the Koin container ([appModule]): optionally override
 * the store/client definitions, then resolve the graph root.
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
 * The stores ([ChatStore], [PersonaStore], [EltmService]) are the PRODUCTION
 * Postgres implementations against a throwaway testcontainers database
 * (`testutil/TestDb.kt`) — the DB-backed tests run real SQL, no in-memory
 * fakes. [testKoinApp] starts the container; the per-test table reset is
 * the test class's job ([DbTestBase]). Pass an override explicitly to
 * inject a stub above the real store.
 *
 * Every container created here is TRACKED (the advisory-lock manager's
 * Hikari pool closes only through the container's `onClose` callback, so an
 * unclosed app now leaks a real connection pool, not just in-memory
 * objects). [DbTestBase] tears the tracked containers down after each test
 * via [closeTestKoinApps]; a test class that does NOT extend [DbTestBase]
 * must close its containers itself — either hold the returned
 * [KoinApplication] and call `close()` on it, or call [closeTestKoinApps]
 * in its own teardown. Closing is idempotent.
 */
private val openTestApps = mutableSetOf<KoinApplication>()

/**
 * Close every Koin application [testKoinApp] has created and not yet
 * closed: runs the container's `onClose` callbacks (the advisory-lock
 * manager's pool shutdown, the hand/MCP cleanup), then clears the registry.
 * Called by [DbTestBase] after each test; standalone (non-[DbTestBase])
 * test classes must call it in their own teardown. Idempotent.
 */
fun closeTestKoinApps() {
    synchronized(openTestApps) {
        openTestApps.forEach { runCatching { it.close() } }
        openTestApps.clear()
    }
}

fun testKoinApp(
    config: AppConfig = testAppConfig(),
    hand: HandClient? = null,
    chatStore: ChatStore? = null,
    eltmService: EltmService? = null,
    mcpToolProvider: McpToolProvider? = null,
    personaStore: PersonaStore? = null,
): KoinApplication {
    // the production stores resolve against the test database: connect it
    // (fail fast with the start-the-container hint) before the graph builds
    TestDb.init()
    // the advisory-lock manager builds its own pool from config.database
    // (db/AdvisoryChatLockManager.kt) — point it at the testcontainers
    // database too (testAppConfig's placeholder URL would never work)
    val dbBackedConfig = config.copy(
        database = config.database.copy(
            url = TestDb.url,
            user = TestDb.user,
            password = TestDb.password,
        )
    )
    return koinApplication {
        modules(
            appModule(dbBackedConfig),
            testOverrides(
                hand,
                chatStore,
                eltmService,
                mcpToolProvider ?: EMPTY_MCP_TOOL_PROVIDER,
                personaStore,
            ),
        )
    }.also { app ->
        synchronized(openTestApps) { openTestApps += app }
    }
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
 * Declare override definitions over the production module. The override
 * module comes after `AppModule(...)` and Koin 4 allows overrides by
 * default, so a definition here replaces the production one of the same
 * type; anything null stays on the production definition. The MCP provider
 * is always overridden ([testKoinApp] defaults it to an empty provider —
 * never the production one, which connects to the live exa server
 * eagerly). The stores default to the production Postgres implementations
 * over the test database.
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
