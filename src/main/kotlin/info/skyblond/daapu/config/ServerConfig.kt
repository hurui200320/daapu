package info.skyblond.daapu.config

import kotlinx.serialization.Serializable

/**
 * The HTTP API server settings.
 */
@Serializable
data class ServerConfig(
    val port: Int = 8080,
) {
    fun validate() {
        require(port in 1..65535) { "server.port must be between 1 and 65535, got $port" }
    }
}
