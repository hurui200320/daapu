package info.skyblond.daapu

import info.skyblond.daapu.nats.NatsClient
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("Application")

fun main() {
    val natsUrl = requireEnv("NATS_URL")
    val natsPrefix = requireEnv("NATS_PREFIX")

    val client = NatsClient(natsUrl, natsPrefix)

    // SIGTERM (docker stop) / SIGINT (Ctrl-C) -> graceful NATS shutdown:
    // close() drains the consumer and connection, which closes the incoming
    // channel and lets the runBlocking loop below exit normally.
    Runtime.getRuntime().addShutdownHook(Thread({
        client.close()
    }, "daapu-shutdown"))

    runBlocking {
        try {
            client.connect()
            logger.info("Bot online; consuming {} incoming messages", natsPrefix)

            for (envelope in client.incomingMessages) {
                val message = envelope.message
                logger.info("[{}]{}({}): {}", message.type, message.from, message.stanzaId, message.body)
                client.sendTextMessage(
                    to = message.from,
                    text = "Got it: ${message.body}",
                    forceEncrypted = message.encrypted,
                )
                // Ack only after the reply was dispatched; an unacked message is
                // redelivered on reconnect (at-least-once).
                envelope.ack()
            }
        } finally {
            client.close()
        }
    }
}
