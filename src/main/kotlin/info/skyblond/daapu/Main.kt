package info.skyblond.daapu

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

fun main() {
    val port = readEnv("PORT")?.toIntOrNull() ?: 8080
    val config = appConfigFromEnv()

    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        module(config)
    }.start(wait = true)
}
