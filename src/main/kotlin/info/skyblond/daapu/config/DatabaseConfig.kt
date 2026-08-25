package info.skyblond.daapu.config

import kotlinx.serialization.Serializable

/**
 * PostgreSQL connection (pgvector enabled). All fields are required and must
 * not be blank: `DATABASE_URL=`-style holes would otherwise fail later with a
 * confusing JDBC/auth error instead of the intended config error.
 */
@Serializable
data class DatabaseConfig(
    val url: String,
    val user: String,
    val password: String,
) {
    fun validate() {
        require(url.isNotBlank()) { "database.url must not be blank" }
        require(user.isNotBlank()) { "database.user must not be blank" }
        require(password.isNotBlank()) { "database.password must not be blank" }
    }
}
