package info.skyblond.daapu.nats

import io.nats.client.*
import io.nats.client.api.AckPolicy
import io.nats.client.api.ConsumerConfiguration
import io.nats.client.api.DeliverPolicy
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/**
 * NATS client for the main daapu bot.
 *
 * The incoming JetStream stream `<prefix>-stream` (binding `<prefix>.message`)
 * is created by the bridge; this client only creates a durable consumer on it.
 * `depends_on` in compose only waits for the bridge container to *start*, not
 * for the stream to exist, so consumer creation is retried until it succeeds.
 *
 * The consumer is a *persistent* durable: it has no inactive threshold, so the
 * server never auto-deletes it. On every (re)start the bot reuses the same
 * durable and resumes from its last acked position, which gives at-least-once
 * delivery across restarts without replaying or losing the stream backlog.
 *
 * Single-instance-per-prefix is the operator's responsibility: two bots on the
 * same durable would compete for deliveries (each message goes to only one of
 * them) and desync. The bridge's `<prefix>.presence` probe only guards XMPP /
 * OMEMO account uniqueness, not this consumer. If you misconfigure two
 * consumers against the same stream, that's on you.
 *
 * In-process redelivery duplicates (e.g. when ackWait fires during a slow
 * downstream) are suppressed by a bounded in-memory dedup set keyed by stream
 * sequence. A sequence is forgotten when its envelope is acked or nacked, so a
 * redelivery of a message whose ack was lost is processed again; the set does
 * not survive a crash, so a redelivery after a restart is also processed again
 * (true at-least-once).
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

    // Guards against concurrent resume triggers. Only one resume runs at a time.
    private val resumeInProgress = AtomicBoolean(false)

    // Bounded in-memory record of stream sequences currently in-process, to
    // suppress redeliveries that arrive when ackWait fires during a slow
    // downstream (the main cause of duplicate echoes). A sequence is forgotten
    // when the envelope is acked/nacked, so a lost ack still leads to a
    // redelivery that is reprocessed (at-least-once). Lost on crash, so a
    // redelivery after a restart is also reprocessed.
    private val dedup = StreamSeqDedup(capacity = 512)

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
                .connectionListener { _, event ->
                    when (event) {
                        ConnectionListener.Events.DISCONNECTED ->
                            logger.warn("NATS connection disconnected; will attempt to reconnect")

                        ConnectionListener.Events.RESUBSCRIBED -> {
                            logger.info("NATS reconnected and subscriptions re-established; resuming consumer")
                            resumeConsumer()
                        }

                        else -> logger.debug("NATS connection event: {}", event)
                    }
                }
                .build()
            natsConn = withContext(Dispatchers.IO) { Nats.connect(opts) }
            val connection = natsConn!!
            jetstream = connection.jetStream()
            logger.info("Connected to NATS at {} (prefix={})", url, prefix)

            ensureConsumer(connection, streamRetryInterval)
        }
    }

    /**
     * Create (or reuse) the durable consumer on `<prefix>-stream` and start
     * pushing decoded [IncomingMessage]s into [incomingMessages].
     *
     * The durable has no inactive threshold, so it is never auto-deleted by the
     * server. `createConsumer` is an add-or-update upsert: a restart reuses the
     * existing durable and resumes from its last acked position (at-least-once
     * across restarts, no backlog replay, no message loss). [DeliverPolicy.New]
     * only matters at first-ever creation (when the stream is empty anyway), so
     * it is left as-is to avoid the non-updatable migration a change would force.
     *
     * Single-instance-per-prefix is the operator's responsibility; this client
     * does not detect or prevent a duplicate bot on the same durable.
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
            // No inactiveThreshold: the durable is persistent and never
            // auto-deleted by the server. A restart reuses it and resumes from
            // the last acked position (at-least-once across restarts), instead of
            // being recreated from "new" and skipping messages published while
            // the bot was down.
            .deliverPolicy(DeliverPolicy.New)
            .build()

        // Retry until the stream exists or close() is requested. `closed` is
        // set by close() before it blocks on the mutex, so this loop observes
        // it even though connect() holds the lock while retrying.
        while (!closed) {
            try {
                jsm.addOrUpdateConsumer(streamName, cc)
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
            // Suppress in-process redelivery duplicates: if ackWait fires while a
            // slow downstream is still processing, JetStream redelivers the same
            // stream sequence. The set is bounded and in-memory only, so it guards
            // the ackWait race without surviving a crash (at-least-once across
            // restarts comes from the persistent durable resuming its ack
            // position, not from this set). The sequence stays in the set until
            // the envelope is acked/nacked (see [IncomingEnvelope]), so a
            // redelivery of an already-finished message is processed again.
            val seq = runCatching { msg.metaData().streamSequence() }.getOrDefault(-1L)
            if (seq >= 0 && !dedup.shouldProcess(seq)) {
                // inProgress, not ack: acking a redelivery would commit the
                // stream sequence before the original finishes processing,
                // so a crash in between would silently drop the message
                // (at-least-once violation). inProgress only resets the ack
                // timer, leaving the commit to the original's deferred
                // envelope.ack().
                logger.debug("In-progress for redelivered seq={} on {}", seq, msg.subject)
                try {
                    msg.inProgress()
                } catch (e: Exception) {
                    logger.warn(
                        "Failed to mark in-progress for seq={} on {}: {}",
                        seq, msg.subject, e.toString()
                    )
                }
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
                val envelope = IncomingEnvelope(
                    incoming,
                    seq,
                    dedup,
                    ackAction = { safeAck(msg) },
                    nackAction = { safeNak(msg) },
                )
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
                    // forever. Forget the sequence so a redelivery of a lost ack
                    // is not suppressed (see [StreamSeqDedup]).
                    logger.warn(
                        "Failed to decode/queue incoming message on {}: {}",
                        msg.subject, e.toString()
                    )
                    dedup.forget(seq)
                    safeAck(msg)
                }
            }
        }
    }

    /**
     * Re-attach the durable JetStream consumer after a NATS reconnect.
     *
     * jnats does not transparently resume JetStream consumers across a server
     * restart — the [MessageConsumer] goes silently dead (known issue:
     * nats.java #892, #997). On `RESUBSCRIBED` (reconnect complete), we stop the old
     * consumer and re-create it via [ensureConsumer].
     *
     * The durable is persistent, so re-binding resumes from the last acked
     * position (at-least-once, no message loss). If re-creation fails after
     * bounded retries, the process exits so Docker's `restart: unless-stopped`
     * brings it back fresh.
     *
     * Safe to call from jnats callback threads: [runBlocking] enters the
     * coroutine [connectionMutex], and [resumeInProgress] prevents concurrent
     * triggers from stacking.
     */
    private fun resumeConsumer() {
        if (closed) return
        if (!resumeInProgress.compareAndSet(false, true)) {
            logger.debug("Consumer resume already in progress; skipping")
            return
        }
        runBlocking {
            connectionMutex.withLock {
                try {
                    if (closed) return@withLock
                    val connection = natsConn ?: return@withLock
                    logger.info("Recovering JetStream consumer...")
                    // Stop the old (dead) consumer before re-creating so the
                    // server releases the deliver subscription.
                    consumer?.let { old ->
                        consumer = null
                        try {
                            old.stop()
                            old.close()
                        } catch (e: Exception) {
                            logger.warn(
                                "Error stopping old consumer during resume: {}",
                                e.toString()
                            )
                        }
                    }
                    try {
                        ensureConsumer(connection, Duration.ofSeconds(2))
                        // ensureConsumer returns silently if `closed` flipped during
                        // its retry loop (shutdown race). Don't log "recovered" then.
                        if (!closed) logger.info("JetStream consumer recovered")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // TODO: make a cleaner exit
                        logger.error("Failed to recover consumer, crashing intentionally", e)
                        closed = true
                        exitProcess(1)
                    }
                } finally {
                    resumeInProgress.set(false)
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

    /**
     * Nack a JetStream message, swallowing errors. A message that was neither
     * acked nor nacked is redelivered by JetStream on the next reconnect, so a
     * failed nak does not lose the message (at-least-once).
     */
    private fun safeNak(msg: Message) {
        try {
            msg.nak()
        } catch (e: Exception) {
            logger.warn("Failed to nack message on {}: {}", msg.subject, e.toString())
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
 * A decoded [IncomingMessage] together with a handle to acknowledge or nack the
 * underlying JetStream message.
 *
 * Acking is deferred until the message has been fully processed (i.e. the reply
 * was dispatched), which gives at-least-once delivery: a message that was never
 * acked is redelivered to the durable consumer after [NatsClient]'s consumer
 * reconnects. Nacking signals the processing failed, so JetStream redelivers the
 * message for a retry.
 *
 * Both [ack] and [nack] first drop the stream sequence from the in-process dedup
 * set, then fire the underlying JetStream ack/nak. Forgetting the sequence even
 * when the ack/nak itself fails keeps at-least-once true: the message stays
 * unacked and is redelivered, and because it is no longer in the dedup set it is
 * processed again (re-acked or re-nacked) instead of being suppressed forever.
 */
class IncomingEnvelope internal constructor(
    val message: IncomingMessage,
    private val seq: Long,
    private val dedup: StreamSeqDedup,
    private val ackAction: () -> Unit,
    private val nackAction: () -> Unit,
) {
    /**
     * Acknowledge the underlying JetStream message. Idempotent; also removes the
     * message from the in-process dedup set.
     */
    fun ack() {
        dedup.forget(seq)
        ackAction()
    }

    /**
     * Nack the underlying JetStream message, requesting a redelivery (used when
     * the reply could not be delivered). Idempotent; also removes the message
     * from the in-process dedup set.
     */
    fun nack() {
        dedup.forget(seq)
        nackAction()
    }
}

/**
 * Bounded in-memory set of stream sequences currently being processed by the
 * downstream, used by [NatsClient] to suppress redeliveries of a message that
 * is still in-flight within the same process lifetime.
 *
 * The typical cause is the ackWait race: a message whose ack is delayed (e.g.
 * by a slow downstream RPC) is redelivered by JetStream after the ackWait
 * timeout. Without this set the downstream would process the duplicate and emit
 * a duplicate side effect (e.g. echo the message twice).
 *
 * A sequence is admitted by [shouldProcess] when the message is first delivered
 * and forgotten by [forget] once the envelope is acked or nacked. The latter is
 * what keeps at-least-once true: if the ack/nak itself fails or is lost, the
 * sequence is already out of the set, so the next redelivery is processed (and
 * re-acked/re-nacked) instead of being suppressed forever.
 *
 * The set is bounded to [capacity] entries (FIFO eviction of the oldest) and
 * is intentionally in-memory only: it does not survive a crash, so a
 * redelivery after a restart is processed again. That keeps at-least-once true
 * across restarts (the persistent durable's ack position is the source of
 * truth) while collapsing in-process duplicates cheaply.
 */
internal class StreamSeqDedup(private val capacity: Int) {
    private val seen = object : LinkedHashMap<Long, Unit>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Unit>?): Boolean =
            this.size > this@StreamSeqDedup.capacity
    }

    /**
     * Record [seq] and return `true` if it has not been seen before (i.e. the
     * caller should process it), or `false` if it is a duplicate (the caller
     * should mark in-progress and skip). Thread-safe: jnats may invoke the
     * consume callback on multiple dispatcher threads.
     */
    fun shouldProcess(seq: Long): Boolean = synchronized(seen) {
        if (seen.containsKey(seq)) {
            false
        } else {
            seen[seq] = Unit
            true
        }
    }

    /**
     * Forget [seq], i.e. mark its message as no longer in-flight. Call this when
     * the envelope is acked or nacked so a later redelivery (e.g. after a lost
     * ack) is processed again instead of suppressed. Thread-safe.
     */
    fun forget(seq: Long) {
        synchronized(seen) {
            seen.remove(seq)
        }
    }
}
