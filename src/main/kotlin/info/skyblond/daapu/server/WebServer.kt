package info.skyblond.daapu.server

import info.skyblond.daapu.agent.ModelCatalog
import info.skyblond.daapu.agent.chat.ChatLockPoolExhaustedException
import info.skyblond.daapu.agent.chat.ChatRunConflictException
import info.skyblond.daapu.agent.chat.ChatService
import info.skyblond.daapu.agent.chat.ChatValidationException
import info.skyblond.daapu.agent.model.ModelCapabilityException
import info.skyblond.daapu.agent.persona.PersonaService
import info.skyblond.daapu.agent.pipeline.eltm.MemoryExtractionService
import info.skyblond.daapu.config.AppConfig
import info.skyblond.daapu.di.appModule
import info.skyblond.daapu.hand.HandCallbackService
import info.skyblond.daapu.memory.eltm.EltmService
import info.skyblond.daapu.memory.eltm.EltmTransferService
import info.skyblond.daapu.memory.eltm.ExtractionQueueWorker
import info.skyblond.daapu.server.endpoint.*
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.ETagProvider
import io.ktor.server.http.content.staticResources
import io.ktor.server.netty.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.plugins.conditionalheaders.ConditionalHeaders
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.core.Koin
import org.koin.dsl.koinApplication

private val logger = KotlinLogging.logger("WebServer")

@Serializable
data class ErrorResponse(val error: String)

/**
 * Start the HTTP server: the API plus, when the packaged web UI exists, the
 * compiled frontend (see [staticWebUi] — the Docker build populates the
 * resource package; dev does not).
 *
 * The whole object graph lives in the Koin container (`di/AppModule.kt`);
 * resolving the graph eagerly before the server starts runs every
 * definition reachable from the root ([ChatService]) — the fail-fast
 * config validation (including the investigate sub-agent's model and tool
 * whitelist, reachable through the loop's `gsg__investigate` tool) and the
 * MCP tool servers' eager connect (a server that cannot be reached aborts
 * startup instead of silently degrading every chat run) fire at boot.
 * Resource cleanup is Koin's too: the shutdown hook closes the container,
 * which fires the `onClose` callbacks (hand client, MCP clients).
 */
fun startWebServer(config: AppConfig) {
    val koinApp = koinApplication { modules(appModule(config)) }
    // eager resolution: every fail-fast validation above fires here, never
    // mid-run (the resolved service is what the module below serves)
    koinApp.koin.get<ChatService>()
    // the background extraction queue worker (the deletion and compaction
    // paths' async memory extraction, `memory/eltm/ExtractionQueueWorker.kt`):
    // started explicitly because it is deliberately NOT reachable from the
    // ChatService graph root — a test resolving the container must not
    // spawn poll loops. Stopped via the container's onClose when the
    // shutdown hook closes the Koin application.
    koinApp.koin.get<ExtractionQueueWorker>().start()
    // graceful close of the hand client and the MCP clients (stdio
    // subprocesses, HTTP sessions) via the container's onClose callbacks
    Runtime.getRuntime().addShutdownHook(Thread { koinApp.close() })
    embeddedServer(Netty, port = config.server.port, host = "0.0.0.0") {
        module(koinApp.koin)
    }.start(wait = true)
}

/**
 * The HTTP API: routes take their services from the Koin container, not
 * from [ChatService] — the service only holds what its own methods use
 * (the stores and the hand callback service live here as independent
 * definitions, shared with the run pipeline). Tests assemble the container
 * with fake seams (see `testutil/TestDi.kt`) and pass its `Koin` instance.
 */
internal fun Application.module(koin: Koin) {
    val service = koin.get<ChatService>()
    val eltmService = koin.get<EltmService>()
    val memoryExtractionService = koin.get<MemoryExtractionService>()
    val eltmTransferService = koin.get<EltmTransferService>()
    val handCallback = koin.get<HandCallbackService>()
    val personaService = koin.get<PersonaService>()
    val modelCatalog = koin.get<ModelCatalog>()

    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
    // answer HEAD on GET routes (the packaged web UI, the /api GET routes):
    // without this install ktor answers 404 for HEAD, so a HEAD-based probe
    // (monitors, proxies, link checkers) would report a healthy UI as down
    install(AutoHeadResponse)
    // conditional requests (If-None-Match → 304) for the static web UI's
    // ETags (see staticWebUi): the static responder only ATTACHES the ETag
    // versions to the response content — this plugin evaluates them. API
    // responses carry no versions, so this is a no-op outside the packaged UI.
    install(ConditionalHeaders)
    install(StatusPages) {
        exception<CancellationException> { _, cause ->
            // the client disconnected mid-stream (the sink wrapper in
            // handleChatMessage converts the failed write into this): the run
            // is already aborted, so don't log an "Unhandled error" stack
            // trace or attempt a 500 on the committed SSE response — let the
            // exception propagate so the connection just closes
            throw cause
        }
        exception<ChatRunConflictException> { call, cause ->
            call.respond(HttpStatusCode.Conflict, ErrorResponse(cause.message ?: "Conflict"))
        }
        // the chat-lock pool gave out no connection (exhausted by concurrent
        // runs/history mutations, or the database unreachable — both land on
        // the same Hikari timeout, db/AdvisoryChatLockManager.kt): not a
        // per-chat conflict — 503, client should retry later
        exception<ChatLockPoolExhaustedException> { call, cause ->
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                ErrorResponse(cause.message ?: "Chat lock pool timed out")
            )
        }
        exception<ChatValidationException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(cause.message ?: "Bad request"))
        }
        exception<BadRequestException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(cause.message ?: "Bad request"))
        }
        exception<NotFoundException> { call, cause ->
            call.respond(HttpStatusCode.NotFound, ErrorResponse(cause.message ?: "Not found"))
        }
        // a capability mismatch (e.g. a text-only `title.model` with image
        // history) is a configuration error the client can act on: surface
        // the reason as a 400 instead of an opaque 500. In-run capability
        // failures never reach here (they fail the SSE stream, which handles
        // them itself).
        exception<ModelCapabilityException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(cause.message ?: "Model capability mismatch")
            )
        }
        // a failed ELTM digest behind `POST /api/eltm/digest` (the
        // extraction one-shot or the writer run): logged with the stage's
        // stack (the 502 body carries only the failure chain, so without
        // this a failed digest leaves no server-side trace) and answered
        // 502 with the real failure reason (upstream error, the writer
        // round cap, ...) — the submitter is interactively waiting and
        // decides on a retry (whatever the writer already recorded sticks,
        // see EltmRoute.kt)
        exception<EltmDigestException> { call, cause ->
            logger.warn(cause) { "ELTM digest failed on ${call.request.uri}" }
            call.respond(
                HttpStatusCode.BadGateway,
                ErrorResponse(cause.message ?: "ELTM digest failed")
            )
        }
        // a failed ELTM import behind `POST /api/eltm/import` (an embedding
        // call inside the merge — see EltmRoute.kt): the same treatment as
        // the digest's failure — 502 with the reason, whatever the merge
        // already wrote sticks and re-running the same file resumes
        exception<EltmImportException> { call, cause ->
            logger.warn(cause) { "ELTM import failed on ${call.request.uri}" }
            call.respond(
                HttpStatusCode.BadGateway,
                ErrorResponse(cause.message ?: "ELTM import failed")
            )
        }
        // must be registered before ContentTransformationException: StatusPages
        // picks the nearest registered class, so a directly thrown
        // UnsupportedMediaTypeException (e.g. from multipart handling) gets its
        // semantically correct 415 with the real reason. Note that a wrong
        // request Content-Type never reaches this handler: ktor's own default
        // transformation checker answers 415 (body-less) for it first.
        exception<UnsupportedMediaTypeException> { call, cause ->
            call.respond(
                HttpStatusCode.UnsupportedMediaType,
                ErrorResponse(cause.message ?: "Unsupported media type")
            )
        }
        exception<ContentTransformationException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Malformed request body"))
        }
        exception<Throwable> { call, cause ->
            logger.error(cause) { "Unhandled error on ${call.request.uri}" }
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Internal server error"))
        }
    }

    routing {
        route("/api") {
            registerModelsEndpoints(modelCatalog)
            registerHandEndpoints(handCallback)
            registerChatsEndpoints(service)
            registerEltmEndpoints(eltmService, memoryExtractionService, eltmTransferService)
            registerPersonasEndpoints(personaService)
        }
        staticWebUi()
    }
}

/** The classpath package the Docker build copies the compiled frontend into (see the root `Dockerfile`). */
private const val WEB_UI_RESOURCE_PACKAGE = "frontend"

/**
 * Serve the packaged web UI from classpath resources: the Docker build
 * copies the frontend dist into [WEB_UI_RESOURCE_PACKAGE] before the Gradle
 * build packages it into the application jar, so the deployed brain serves
 * the API and the UI on one origin. In development nothing populates the
 * package (the UI runs on the vite dev server, which proxies `/api` here),
 * so every request resolves nothing and ktor answers a plain 404.
 *
 * A low-priority tailcard: ktor's routing prefers literal matches, so the
 * `/api` routes win regardless of registration order. There is
 * deliberately no `default("index.html")` SPA fallback — it would answer
 * unknown `/api` paths (and everything else) with index.html; the UI uses
 * hash routing, so in practice only `/` and the hashed asset paths are
 * ever requested, and both resolve directly (`index = "index.html"` maps
 * the directory request `/` to the package's index.html).
 *
 * Caching: the vite dist's `assets/` files are content-hashed (immutable
 * per URL) and are cached long; `index.html` references those hashes and is
 * served `no-cache` (revalidated every load; the strong content ETag turns
 * that into a cheap 304 — evaluated by the `ConditionalHeaders` install in
 * `module`). The assets decision reads the in-package path back out of the
 * resource URL (the part after the last `resourcePackage/` segment).
 */
internal fun Route.staticWebUi(resourcePackage: String = WEB_UI_RESOURCE_PACKAGE) {
    staticResources("/", resourcePackage, index = "index.html") {
        cacheControl { url ->
            if (url.path.substringAfterLast("$resourcePackage/").startsWith("assets/")) {
                // content-hashed dist assets: immutable per URL
                listOf(CacheControl.MaxAge(maxAgeSeconds = 7 * 24 * 3600))
            } else {
                // index.html: revalidate before reuse — a new deploy changes
                // the hashed names it references
                listOf(CacheControl.NoCache(visibility = null))
            }
        }
        etag(ETagProvider.StrongSha256)
    }
}
