package info.skyblond.daapu

import info.skyblond.daapu.nats.NatsClient
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("Application")

fun main() {
    val natsUrl = requireEnv("NATS_URL")
    val natsPrefix = requireEnv("NATS_PREFIX")

    val client = NatsClient(natsUrl, natsPrefix)

    runBlocking {
        client.connect()
        logger.info("Bot online; consuming {} incoming messages", natsPrefix)

        for (message in client.incomingMessages) {
            logger.info("[{}]{}({}): {}", message.type, message.from, message.stanzaId, message.body)
            client.sendTextMessage(
                to = message.from,
                text = "Got it: ${message.body}",
                forceEncrypted = message.encrypted,
            )
        }
    }

    client.close()
}
