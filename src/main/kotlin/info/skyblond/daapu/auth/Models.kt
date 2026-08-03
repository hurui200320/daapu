package info.skyblond.daapu.auth

import kotlinx.serialization.Serializable

@Serializable
data class RegisterRequest(val username: String, val password: String)

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class UserResponse(val id: Long, val username: String)

@Serializable
data class AuthError(val error: String) {
    constructor(ex: Exception) : this(ex.message ?: "No error message provided")
}

/**
 * Server-side session payload carried in a signed cookie.
 */
@Serializable
data class SessionData(
    val userId: Long,
)
