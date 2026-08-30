package info.skyblond.daapu.db

import java.sql.SQLException

/**
 * Whether [this] or any of its causes is a unique-constraint violation
 * (SQLState `23505`). Callers must convert expected violations to
 * non-SQL exceptions inside the transaction (see `withTransaction`'s
 * retry note in `db/Database.kt`) — this check is how they recognize
 * one from a caught [SQLException] cause chain.
 */
fun Throwable.isUniqueViolation(): Boolean {
    var cause: Throwable? = this
    while (cause != null) {
        if (cause is SQLException && cause.sqlState == "23505") return true
        cause = cause.cause
    }
    return false
}
