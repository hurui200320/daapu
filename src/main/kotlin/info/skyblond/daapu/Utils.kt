package info.skyblond.daapu

import java.io.File

fun readEnv(key: String): String? {
    return File("./.env").useLines { lines ->
        lines.map { it.split("#")[0].trim() }
            .filter { it.isNotBlank() }
            .map { l -> l.split("=").let { it[0] to it[1] } }
            .associate { it }
    }[key]
}