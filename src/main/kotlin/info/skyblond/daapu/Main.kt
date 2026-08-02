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
                val reply = client.sendTextMessage(
                    to = message.from,
                    text = "Got it: ${message.body}",
                    forceEncrypted = message.encrypted,
                )
                if (reply.ok) {
                    // Ack only after the reply was dispatched; an unacked message
                    // is redelivered on reconnect (at-least-once).
                    envelope.ack()
                } else {
                    // The reply failed, so the user didn't see our response.
                    // Nack so JetStream redelivers the message and we retry.
                    // Future LLM chatbot note: on such a failed reply, do NOT
                    // include this round in the chat history — the user never
                    // saw the response, so the failed turn must not pollute the
                    // context; the redelivered message restarts the turn.
                    // If the nack also fails, the message was still not acked,
                    // so JetStream redelivers it anyway; the envelope already
                    // dropped the message from the in-process dedup set, so the
                    // redelivery is reprocessed instead of suppressed.
                    envelope.nack()
                }
            }
        } finally {
            client.close()
        }
    }
}
