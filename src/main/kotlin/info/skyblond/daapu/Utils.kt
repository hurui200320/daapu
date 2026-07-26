package info.skyblond.daapu

import java.io.File

fun readEnv(key: String): String? {
    return File("./.env").useLines { lines ->
        lines.map { it.split("#")[0].trim() }
            .filter { it.isNotBlank() }
            .map { l -> l.split("=", limit = 2).let { it[0] to it[1] } }
            .associate { it }
    }[key]
}

fun requireEnv(key: String): String = readEnv(key) ?: throw IllegalArgumentException("$key is not present in .env")