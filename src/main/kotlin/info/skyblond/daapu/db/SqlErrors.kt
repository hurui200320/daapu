package info.skyblond.daapu.db

import java.sql.SQLException

/**
 * Whether [this] or any of its causes carries SQLState [state]. The driver
 * usually nests the state-carrying exception, so the whole cause chain is
 * walked.
 */
private fun Throwable.hasSqlState(state: String): Boolean {
    var cause: Throwable? = this
    while (cause != null) {
        if (cause is SQLException && cause.sqlState == state) return true
        cause = cause.cause
    }
    return false
}

/**
 * Whether [this] or any of its causes is a unique-constraint violation
 * (SQLState `23505`). Callers must convert expected violations to
 * non-SQL exceptions inside the transaction (see `withTransaction`'s
 * retry note in `db/Database.kt`) — this check is how they recognize
 * one from a caught [SQLException] cause chain.
 */
fun Throwable.isUniqueViolation(): Boolean = hasSqlState("23505")

/**
 * Whether [this] or any of its causes is a foreign-key violation
 * (SQLState `23503`) — e.g. a note insert racing a subject delete/merge.
 * Like [isUniqueViolation], callers convert it to a non-SQL exception
 * inside the transaction so `withTransaction` does not retry a write it
 * already attempted (see `db/Database.kt`).
 */
fun Throwable.isForeignKeyViolation(): Boolean = hasSqlState("23503")

/**
 * Whether [this] or any of its causes is an invalid-regular-expression
 * error (SQLState `2201B`) — PostgreSQL's `~` / `~*` rejecting a pattern.
 * Like [isUniqueViolation], callers convert it to a non-SQL exception
 * inside the transaction so `withTransaction` does not retry the
 * deterministically failing read (see `db/Database.kt`).
 */
fun Throwable.isInvalidRegex(): Boolean = hasSqlState("2201B")
