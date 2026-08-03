package info.skyblond.daapu

import com.zaxxer.hikari.HikariDataSource
import info.skyblond.daapu.auth.AuthService
import info.skyblond.daapu.auth.SessionData
import info.skyblond.daapu.auth.authRoutes
import info.skyblond.daapu.chat.ChatService
import info.skyblond.daapu.chat.chatRoutes
import info.skyblond.daapu.db.initDatabase
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.http.content.default
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sessions.SessionTransportTransformerMessageAuthentication
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.cookie
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import javax.crypto.spec.SecretKeySpec

private val logger = LoggerFactory.getLogger("Application")

@Serializable
data class HealthResponse(val status: String)

@Serializable
data class ErrorResponse(val error: String)

/**
 * Initialize the database (Flyway migrations + Exposed connection), then wire up
 * plugins and routes.
 */
fun Application.module(config: AppConfig) {
    val dataSource = initDatabase(config.databaseUrl, config.databaseUser, config.databasePassword)
    configureApp(config, dataSource)
}

/**
 * Wire up plugins and routes against an already-connected [HikariDataSource].
 * Kept separate from [module] so tests can reuse a single shared data source.
 */
fun Application.configureApp(config: AppConfig, dataSource: HikariDataSource) {
    install(CallLogging)
    install(ContentNegotiation) {
        json()
    }
    install(Sessions) {
        cookie<SessionData>("daapu_session") {
            val keySpec = SecretKeySpec(config.sessionCookieKey.toByteArray(Charsets.UTF_8), "HmacSHA256")
            transform(SessionTransportTransformerMessageAuthentication(keySpec))
            cookie.path = "/"
            cookie.maxAgeInSeconds = 30L * 24 * 3600 // 30 days
        }
    }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            logger.error("Unhandled error", cause)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("internal server error"))
        }
    }

    val authService = AuthService()
    val chatService = ChatService()

    routing {
        get("/api/health") {
            call.respond(HealthResponse(status = "ok"))
        }
        route("/api/auth") {
            authRoutes(authService)
        }
        route("/api") {
            chatRoutes(chatService)
        }

        // Serve the built SPA from the classpath. The fallback to index.html
        // keeps client-side routing (e.g. /login) working on a hard reload.
        staticResources("/", "static") {
            default("index.html")
        }
    }
}
