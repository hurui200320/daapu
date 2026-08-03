package info.skyblond.daapu.auth

import info.skyblond.daapu.db.Users
import info.skyblond.daapu.db.withTransaction
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.mindrot.jbcrypt.BCrypt

class AuthService {

    /**
     * Create a user.
     *
     * @throws UsernameExistsException if username already exists.
     */
    @Throws(UsernameExistsException::class)
    suspend fun register(username: String, password: String): Unit = withTransaction {
        val exists = Users.selectAll().where { Users.username eq username }.any()
        if (exists) throw UsernameExistsException(username)

        Users.insert {
            it[Users.username] = username
            it[Users.passwordHash] = BCrypt.hashpw(password, BCrypt.gensalt())
        }
    }

    /**
     * Validate credentials. Returns the user id on success, `null` otherwise.
     */
    suspend fun authenticate(username: String, password: String): Long? = withTransaction {
        Users.selectAll()
            .where { Users.username eq username }
            .firstOrNull()
            ?.let { row ->
                val hash = row[Users.passwordHash]
                if (BCrypt.checkpw(password, hash)) row[Users.id] else null
            }
    }

    /**
     * Look up a user by id. Returns the response object, or `null` if the user
     * does not exist.
     */
    suspend fun getById(id: Long): UserResponse? = withTransaction {
        Users.selectAll()
            .where { Users.id eq id }
            .firstOrNull()
            ?.let { row -> UserResponse(row[Users.id], row[Users.username]) }
    }
}
