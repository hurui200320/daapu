package info.skyblond.daapu.auth

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*

fun Route.authRoutes(authService: AuthService) {
    post("/register") {
        val body = call.receive<RegisterRequest>()
        if (body.username.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, AuthError("username is required"))
            return@post
        }
        if (body.password.length < 8) {
            call.respond(
                HttpStatusCode.BadRequest,
                AuthError("password must be at least 8 characters")
            )
            return@post
        }

        try {
            authService.register(body.username.trim(), body.password)
        } catch (ex: UsernameExistsException) {
            call.respond(HttpStatusCode.Conflict, AuthError(ex))
            return@post
        }

        val userId = authService.authenticate(body.username.trim(), body.password)!!
        call.sessions.set(SessionData(userId))
        call.respond(HttpStatusCode.Created, UserResponse(userId, body.username.trim()))
    }

    post("/login") {
        val body = call.receive<LoginRequest>()
        val userId = authService.authenticate(body.username, body.password)
        if (userId == null) {
            call.respond(HttpStatusCode.Unauthorized, AuthError("invalid username or password"))
            return@post
        }
        call.sessions.set(SessionData(userId))
        call.respond(UserResponse(userId, body.username))
    }

    post("/logout") {
        call.sessions.clear<SessionData>()
        call.respond(HttpStatusCode.NoContent)
    }

    get("/me") {
        val session = call.sessions.get<SessionData>()
        if (session == null) {
            call.respond(HttpStatusCode.Unauthorized, AuthError("not logged in"))
            return@get
        }
        val user = authService.getById(session.userId)
        if (user == null) {
            // Session references a user that no longer exists; drop it.
            call.sessions.clear<SessionData>()
            call.respond(HttpStatusCode.Unauthorized, AuthError("not logged in"))
        } else {
            call.respond(user)
        }
    }
}
