package info.skyblond.daapu.nats

import io.nats.client.*
import io.nats.client.api.AckPolicy
import io.nats.client.api.ConsumerConfiguration
import io.nats.client.api.DeliverPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.time.Duration

/**
 * NATS client for the main daapu bot.
 *
 * The incoming JetStream stream `<prefix>-stream` (binding `<prefix>.message`)
 * is created by the bridge; this client only creates a durable consumer on it.
 * `depends_on` in compose only waits for the bridge container to *start*, not
 * for the stream to exist, so consumer creation is retried until it succeeds.
 *
 * The bot is strictly single-instance per NATS prefix (two instances draining
 * the same durable consumer would split the queue and desync; enforced at the
 * stack level via the bridge's `<prefix>.presence` claim). The consumer itself
 * is auto-released by the server once the bot's connection is gone for a while,
 * so restarts never accumulate stale consumers. See [ensureConsumer].
 */
class NatsClient(
    private val url: String,
    private val prefix: String,
) : AutoCloseable {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // Bounded so a burst of incoming messages cannot grow memory without limit;
    // the consumer callback blocks (see ensureConsumer) once the channel is
    // full, giving natural backpressure while JetStream holds unacked messages.
    private val _incomingMessages = Channel<IncomingEnvelope>(capacity = 128)

    /**
     * Decrypted incoming messages published by the bridge to `<prefix>.message`.
     *
     * Each element carries the JetStream ack for the underlying message; the
     * consumer must call [IncomingEnvelope.ack] once the message has been fully
     * processed (see the class docs).
     */
    val incomingMessages: ReceiveChannel<IncomingEnvelope> = _incomingMessages

    @Volatile
    private var natsConn: Connection? = null

    @Volatile
    private var jetstream: JetStream? = null

    @Volatile
    private var consumer: MessageConsumer? = null

    // mutex protects the connecting and closing stage
    private val connectionMutex = Mutex()

    // flag to keep `close()` idempotent
    // also for components has loops to abort the loop on close.
    @Volatile
    private var closed = false

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
        connectionMutex.withLock {
            check(!closed) { "connect() called after close()" }
            val opts = Options.builder()
                .server(url)
                .maxReconnects(-1)
                .connectionTimeout(Duration.ofSeconds(10))
                .connectionName("daapu-bot:$prefix")
                .build()
            natsConn = withContext(Dispatchers.IO) { Nats.connect(opts) }
            val connection = natsConn!!
            jetstream = connection.jetStream()
            logger.info("Connected to NATS at {} (prefix={})", url, prefix)

            ensureConsumer(connection, streamRetryInterval)
        }
    }

    /**
     * Create a durable consumer on `<prefix>-stream` and start pushing decoded
     * [IncomingMessage]s into [incomingMessages].
     *
     * The consumer has a fixed durable name per prefix and an `inactiveThreshold`
     * of 60s: once the bot's connection dies and no message activity arrives for
     * that long, the server deletes the consumer on its own. Restarts therefore
     * never leave stale consumers behind. A restart while the old consumer is
     * still around (within the threshold) simply reuses it — `createConsumer`
     * is idempotent for an identical config on current servers — and resumes its
     * ack position (at-least-once). After the consumer was auto-released, a fresh
     * one is created that starts from "new" so the stream backlog is not replayed.
     *
     * The bot is strictly single-instance per prefix: two instances draining the
     * same durable consumer would split the queue and desync. That invariant is
     * enforced at the stack level (the bridge claims `<prefix>.presence`), not
     * by this client.
     *
     * Only a missing stream (the bridge hasn't created it yet) is retried; any
     * other JetStream error is a permanent condition and fails fast instead of
     * looping forever.
     */
    private suspend fun ensureConsumer(connection: Connection, retryInterval: Duration) {
        val jsm: JetStreamManagement = connection.jetStreamManagement()
        val cc = ConsumerConfiguration.builder()
            .durable(consumerName)
            .ackPolicy(AckPolicy.Explicit)
            .ackWait(Duration.ofSeconds(30))
            // The server auto-deletes the consumer once it has seen no activity
            // (pulls/acks/deliveries) for this long — i.e. shortly after the
            // bot's connection dies (crash or graceful shutdown). This is what
            // lets a restarted bot get a fresh consumer instead of accumulating
            // stale ones. Must stay comfortably above the 30s JetStream pull
            // expiry (which the consume loop keeps refreshing while connected),
            // so an idle-but-connected bot is never mistaken for dead.
            .inactiveThreshold(Duration.ofSeconds(60))
            // A freshly created consumer only receives messages published after
            // its creation. When the old consumer was auto-released while the bot
            // was down, the fresh one must not replay the whole stream backlog
            // (which would re-echo every historical message).
            .deliverPolicy(DeliverPolicy.New)
            .build()

        // Retry until the stream exists or close() is requested. `closed` is
        // set by close() before it blocks on the mutex, so this loop observes
        // it even though connect() holds the lock while retrying.
        while (!closed) {
            try {
                jsm.createConsumer(streamName, cc)
                break
            } catch (e: CancellationException) {
                throw e
            } catch (e: JetStreamApiException) {
                if (!isStreamNotFound(e)) throw e
                logger.warn(
                    "Waiting for JetStream stream '{}' (bridge not ready?): {}",
                    streamName, e.errorDescription
                )
                delay(retryInterval.toMillis())
            }
        }
        if (closed) {
            // Shutdown during startup; close() tears everything down.
            return
        }
        logger.info("Attached durable consumer '{}' to stream '{}'", consumerName, streamName)

        val consumerContext = jetstream!!.getConsumerContext(streamName, consumerName)
        consumer = consumerContext.consume { msg: Message ->
            if (msg.subject != messageSubject) {
                // A message on a stream subject we don't handle (e.g. config
                // drift); ack it so it doesn't stay pending and redeliver.
                logger.warn("Unknown message subject: '{}', discarded", msg.subject)
                safeAck(msg)
                return@consume
            }
            try {
                val incoming =
                    json.decodeFromString<IncomingMessage>(String(msg.data, Charsets.UTF_8))
                // NOTE: the message is intentionally NOT acked here. Acking is
                // deferred to the consumer loop (IncomingEnvelope.ack) after the
                // message has been processed, so a crash in between redelivers the
                // message instead of silently dropping it (at-least-once). Blocking
                // on a full channel is the backpressure that bounds the queue.
                val envelope = IncomingEnvelope(incoming) { safeAck(msg) }
                runBlocking(Dispatchers.IO) { _incomingMessages.send(envelope) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (closed) {
                    // Shutdown raced with delivery: close() closed the channel
                    // under us. Leave the message unacked so JetStream redelivers
                    // it on the next start instead of dropping it silently.
                    logger.debug(
                        "Dropping incoming message on {} during shutdown: {}",
                        msg.subject, e.toString()
                    )
                } else {
                    // Undecodable/unqueueable message: we can't do anything with
                    // it, so ack (discard) instead of nak, which would redeliver
                    // forever.
                    logger.warn(
                        "Failed to decode/queue incoming message on {}: {}",
                        msg.subject, e.toString()
                    )
                    safeAck(msg)
                }
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
        } catch (e: CancellationException) {
            throw e
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
        val connection = natsConn ?: error("connect() must be called first")
        connection.request(subject, payload, timeout)
    }

    /**
     * Ack a JetStream message, swallowing errors (e.g. the connection being
     * closed mid-shutdown). An unacked message is simply redelivered.
     */
    private fun safeAck(msg: Message) {
        try {
            msg.ack()
        } catch (e: Exception) {
            logger.warn("Failed to ack message on {}: {}", msg.subject, e.toString())
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runBlocking {
            connectionMutex.withLock {
                // first release consumer
                consumer?.let {
                    consumer = null
                    try {
                        it.stop()
                        it.close()
                    } catch (e: Exception) {
                        logger.warn("Error stopping consumer: {}", e.toString())
                    }
                }
                // then release connection
                natsConn?.let {
                    jetstream = null
                    natsConn = null
                    try {
                        it.close()
                    } catch (e: Exception) {
                        logger.warn("Error closing NATS connection: {}", e.toString())
                    }
                }
                // finally close the channel
                _incomingMessages.close()
            }
        }
        logger.info("NATS connection closed")
    }

    companion object {
        private val logger = LoggerFactory.getLogger(NatsClient::class.java)

        /**
         * True when the JetStream API error means the stream (or consumer)
         * does not exist yet. NATS JetStream err code 10059 is "stream not
         * found"; HTTP 404 covers the generic not-found response.
         */
        private fun isStreamNotFound(e: JetStreamApiException): Boolean =
            e.apiErrorCode == 10059 || e.errorCode == 404
    }
}

/**
 * A decoded [IncomingMessage] together with a handle to acknowledge the
 * underlying JetStream message.
 *
 * Acking is deferred until the message has been fully processed (i.e. the
 * reply was dispatched), which gives at-least-once delivery: a message that
 * was never acked is redelivered to the durable consumer after [NatsClient]'s
 * consumer reconnects.
 */
class IncomingEnvelope internal constructor(
    val message: IncomingMessage,
    private val ackAction: () -> Unit,
) {
    /** Acknowledge the underlying JetStream message. Idempotent. */
    fun ack() = ackAction()
}
