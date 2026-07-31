package info.skyblond.daapu.nats

import io.nats.client.*
import io.nats.client.api.AckPolicy
import io.nats.client.api.ConsumerConfiguration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.IOException
import java.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * NATS client for the main daapu bot.
 *
 * The incoming JetStream stream `<prefix>-stream` (binding `<prefix>.message`)
 * is created by the bridge; this client only creates a durable consumer on it.
 * `depends_on` in compose only waits for the bridge container to *start*, not
 * for the stream to exist, so consumer creation is retried until it succeeds.
 */
class NatsClient(
    private val url: String,
    private val prefix: String,
) : AutoCloseable {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _incomingMessages = Channel<IncomingMessage>(Channel.UNLIMITED)

    /** Decrypted incoming messages published by the bridge to `<prefix>.message`. */
    val incomingMessages: ReceiveChannel<IncomingMessage> = _incomingMessages

    @Volatile
    private var nc: Connection? = null

    @Volatile
    private var js: JetStream? = null

    @Volatile
    private var consumer: MessageConsumer? = null

    private val messageSubject: String get() = "$prefix.message"
    private val streamName: String get() = "$prefix-stream"
    private val consumerName: String get() = "$prefix-consumer"

    private fun commandSubject(name: String): String = "$prefix.command.$name"

    /**
     * Connect to NATS (reconnect forever) and start consuming `<prefix>.message`.
     *
     * Retries consumer creation every [streamRetryInterval] until the bridge has
     * created the JetStream stream, so startup order is not a hard dependency.
     */
    suspend fun connect(streamRetryInterval: Duration = Duration.ofSeconds(2)) {
        val opts = Options.builder()
            .server(url)
            .maxReconnects(-1)
            .connectionTimeout(Duration.ofSeconds(10))
            .connectionName("daapu-bot:$prefix")
            .build()
        nc = withContext(Dispatchers.IO) { Nats.connect(opts) }
        val connection = nc!!
        js = connection.jetStream()
        logger.info("Connected to NATS at {} (prefix={})", url, prefix)

        ensureConsumer(connection, streamRetryInterval)
    }

    /**
     * Create (idempotently) a durable consumer on `<prefix>-stream` and start
     * pushing decoded [IncomingMessage]s into [incomingMessages].
     *
     * `createConsumer` is an upsert: safe to call when the consumer already
     * exists with the same config. Stream-not-yet-created errors are retried.
     */
    private suspend fun ensureConsumer(connection: Connection, retryInterval: Duration) {
        val jsm: JetStreamManagement = connection.jetStreamManagement()
        val cc = ConsumerConfiguration.builder()
            .durable(consumerName)
            .ackPolicy(AckPolicy.Explicit)
            .ackWait(Duration.ofSeconds(30))
            .build()

        while (true) {
            try {
                jsm.createConsumer(streamName, cc)
                break
            } catch (e: Exception) {
                logger.warn(
                    "Waiting for JetStream stream '{}' (bridge not ready?): {}",
                    streamName, e.message
                )
                delay(retryInterval.toMillis().milliseconds)
            }
        }
        logger.info("Attached durable consumer '{}' to stream '{}'", consumerName, streamName)

        val consumerContext = js!!.getConsumerContext(streamName, consumerName)
        consumer = consumerContext.consume { msg: Message ->
            if (msg.subject != messageSubject) {
                return@consume
            }
            try {
                val incoming =
                    json.decodeFromString<IncomingMessage>(String(msg.data, Charsets.UTF_8))
                _incomingMessages.trySend(incoming)
                msg.ack()
            } catch (e: Exception) {
                logger.warn(
                    "Failed to decode/queue incoming message on {}: {}",
                    msg.subject,
                    e.toString()
                )
                msg.nak()
            }
        }
    }

    /**
     * RPC: send a 1:1 DM via `<prefix>.command.sendTextMessage`.
     *
     * The bridge blocks on OMEMO readiness inside its handler, so [timeout]
     * must accommodate that (default 30s). On a transport-level failure (no
     * responders / timeout) a synthetic [CommandReply] with `ok=false` is
     * returned; on a successful round-trip the bridge's own [CommandReply]
     * (which may carry `ok=false` + error) is returned as-is.
     */
    suspend fun sendTextMessage(
        to: String,
        text: String,
        forceEncrypted: Boolean = false,
        timeout: Duration = Duration.ofSeconds(30),
    ): CommandReply {
        val req = SendTextMessageRequest(to = to, text = text, forceEncrypted = forceEncrypted)
        val payload = json.encodeToString(SendTextMessageRequest.serializer(), req)
            .toByteArray(Charsets.UTF_8)

        val reply: Message? = try {
            request(commandSubject("sendTextMessage"), payload, timeout)
        } catch (e: Exception) {
            logger.warn("sendTextMessage RPC failed: {}", e.toString())
            return CommandReply(ok = false, error = e.toString())
        }

        if (reply == null) {
            logger.warn(
                "sendTextMessage RPC got no response (no responders or timeout) on {}",
                prefix
            )
            return CommandReply(ok = false, error = "no response (no responders or timeout)")
        }

        return try {
            json.decodeFromString<CommandReply>(String(reply.data, Charsets.UTF_8))
        } catch (e: Exception) {
            logger.warn("Malformed RPC reply on sendTextMessage: {}", e.toString())
            CommandReply(ok = false, error = "malformed reply: ${e.message}")
        }
    }

    /**
     * jnats' synchronous [Connection.request] blocks until the reply arrives or
     * [timeout] elapses; run it on [Dispatchers.IO]. Returns `null` on
     * no-responders/timeout (older jnats) — the caller treats both as failure.
     */
    private suspend fun request(
        subject: String,
        payload: ByteArray,
        timeout: Duration,
    ): Message? = withContext(Dispatchers.IO) {
        val connection = nc ?: error("connect() must be called first")
        connection.request(subject, payload, timeout)
    }

    override fun close() {
        consumer?.let {
            try {
                it.stop()
                it.close()
            } catch (e: Exception) {
                logger.warn("Error stopping consumer: {}", e.toString())
            }
        }
        val connection = nc ?: return
        try {
            connection.close()
        } catch (e: IOException) {
            logger.warn("Error closing NATS connection: {}", e.toString())
        }
        _incomingMessages.close()
        logger.info("NATS connection closed")
    }

    companion object {
        private val logger = LoggerFactory.getLogger(NatsClient::class.java)
    }
}
