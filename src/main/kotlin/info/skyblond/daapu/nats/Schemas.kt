package info.skyblond.daapu.nats

import kotlinx.serialization.Serializable

/**
 * NATS wire-format schemas for talking to the xmpp-bridge sidecar.
 *
 * Kotlin mirror of `xmpp-bridge/src/xmpp_bridge/schemas.py`. The bridge uses
 * msgspec with `rename="camel"`, so JSON keys are camelCase; kotlinx matches
 * that by default for data-class property names (e.g. `forceEncrypted`).
 *
 * Contract (per-instance `[NATS_PREFIX]`):
 * - `<prefix>.message`               -> [IncomingMessage] published by the bridge (JetStream, durable)
 * - `<prefix>.command.sendTextMessage` -> [SendTextMessageRequest] request / [CommandReply] reply (core NATS RPC)
 * - `<prefix>.presence`              -> bridge-owned liveness probe; ignored here.
 */
@Serializable
enum class MessageType {
    DM, MUC
}

/**
 * An incoming XMPP message published to `<prefix>.message` by the bridge.
 */
@Serializable
data class IncomingMessage(
    /** Bare JID of the bridge account that received the message. */
    val account: String,
    /** Bare JID of the sender. MUC (room-nick) is not produced yet. */
    val from: String,
    /** Decrypted (or plaintext) message body. */
    val body: String,
    /** Whether the original stanza was OMEMO-encrypted. */
    val encrypted: Boolean,
    /** DM (1:1) or MUC (group chat). Only DM is currently produced. */
    val type: MessageType,
    /** Unix epoch seconds at which the bridge processed the message. */
    val timestamp: Long,
    /** XMPP stanza id, when available; used as a reply target reference. */
    val stanzaId: String? = null,
)

/**
 * RPC payload for `<prefix>.command.sendTextMessage`.
 */
@Serializable
data class SendTextMessageRequest(
    /** Recipient JID. */
    val to: String,
    /** Message body to send. */
    val text: String,
    /** If true, fail when the contact doesn't support OMEMO. */
    val forceEncrypted: Boolean = false,
)

/**
 * Generic RPC reply returned on the request's reply inbox.
 */
@Serializable
data class CommandReply(
    val ok: Boolean,
    val error: String? = null,
)
