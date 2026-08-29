package info.skyblond.daapu.server.endpoint

import io.ktor.server.application.*
import io.ktor.server.plugins.*
import java.time.LocalDate
import java.time.format.DateTimeParseException

fun ApplicationCall.longParam(name: String): Long =
    parameters[name]?.toLongOrNull()
        ?: throw BadRequestException("$name must be a number")

fun ApplicationCall.pageLimitParam(default: Int, max: Int): Int {
    val raw = request.queryParameters["limit"] ?: return default
    val value = raw.toIntOrNull() ?: throw BadRequestException("limit must be a number")
    if (value < 1) throw BadRequestException("limit must be >= 1")
    if (value > max) {
        throw BadRequestException("limit must be <= $max")
    }
    return value
}

fun ApplicationCall.pageOffsetParam(): Int {
    val raw = request.queryParameters["offset"] ?: return 0
    val value = raw.toIntOrNull() ?: throw BadRequestException("offset must be a number")
    if (value < 0) throw BadRequestException("offset must be >= 0")
    return value
}

/** A `YYYY-MM-DD` query param, or null when absent; anything else is a 400. */
fun ApplicationCall.dateParam(name: String): LocalDate? {
    val raw = request.queryParameters[name] ?: return null
    return try {
        LocalDate.parse(raw)
    } catch (_: DateTimeParseException) {
        throw BadRequestException("$name must be YYYY-MM-DD")
    }
}

fun checkDateRange(from: LocalDate?, to: LocalDate?) {
    if (from != null && to != null && from.isAfter(to)) {
        throw BadRequestException("from must not be after to")
    }
}