"""NATS wire-format schemas for the xmpp-bridge.

All structs use ``msgspec.Struct`` with ``rename="camel"`` so that snake_case
Python fields serialize to camelCase JSON keys (e.g. ``force_encrypted`` ->
``forceEncrypted``), which is idiomatic for kotlinx.serialization on the Kotlin
consumer side. The ``from`` field is reserved in Python, so it is declared as
``from_`` with an explicit ``name="from"`` override.

Contract:

* ``IncomingMessage``  -> published to ``<prefix>.message`` (JetStream, durable)
* ``*Request``         -> sent to ``<prefix>.command.<name>`` (core NATS RPC)
* ``CommandReply``     -> returned on the request's ``reply`` inbox

Subject/Stream naming (per-instance ``prefix``):

* ``<prefix>-stream``                     JetStream stream binding ``<prefix>.message``
* ``<prefix>.message``                    incoming DM (bridge -> kotlin)
* ``<prefix>.command.sendTextMessage``     RPC: send a 1:1 DM
* ``<prefix>.command.replyToMessage``     RPC: reply to a stanza (scaffold/stub)
* ``<prefix>.command.sendMucMessage``     RPC: send a MUC message (scaffold/stub)
* ``<prefix>.command.addReaction``         RPC: add an emoji reaction (scaffold/stub)
* ``<prefix>.presence``                    liveness probe for duplicate-prefix detection
"""

from __future__ import annotations

from typing import Literal

import msgspec

# Message type discriminator mirroring the Kotlin ChatMessage.Type enum.
MessageType = Literal["DM", "MUC"]


class IncomingMessage(msgspec.Struct, rename="camel"):
    """An incoming XMPP message published to ``<prefix>.message``."""

    account: str
    """Bare JID of the bridge account that received the message."""

    from_: str = msgspec.field(name="from")
    """Bare JID of the sender. MUC (room-nick) is not produced yet."""

    body: str
    """Decrypted (or plaintext) message body."""

    encrypted: bool
    """Whether the original stanza was OMEMO-encrypted."""

    type: MessageType
    """DM (1:1) or MUC (group chat). Only DM is currently produced."""

    timestamp: int
    """Unix epoch seconds at which the bridge processed the message."""

    stanza_id: str | None = None
    """XMPP stanza id, when available; used as a reply target reference."""


class SendTextMessageRequest(msgspec.Struct, rename="camel"):
    """RPC payload for ``<prefix>.command.sendTextMessage``."""

    to: str
    """Recipient JID."""

    text: str
    """Message body to send."""

    force_encrypted: bool = False
    """If True, fail when the contact doesn't support OMEMO."""


class ReplyToMessageRequest(msgspec.Struct, rename="camel"):
    """RPC payload for ``<prefix>.command.replyToMessage`` (scaffolded)."""

    to: str
    """Recipient JID."""

    text: str
    """Reply body."""

    reply_to_stanza_id: str
    """XMPP stanza id being replied to (XEP-0359 thread/reply)."""

    force_encrypted: bool = False


class SendMucMessageRequest(msgspec.Struct, rename="camel"):
    """RPC payload for ``<prefix>.command.sendMucMessage`` (scaffolded).

    MUC sending is not yet implemented; the handler returns a
    ``CommandReply(ok=False, error="not implemented")``.
    """

    to: str
    """MUC room JID."""

    text: str
    """Message body to send to the room."""


class AddReactionRequest(msgspec.Struct, rename="camel"):
    """RPC payload for ``<prefix>.command.addReaction`` (scaffolded, XEP-0444)."""

    to: str
    """JID of the conversation the reacted-to stanza belongs to."""

    stanza_id: str
    """XMPP stanza id of the message being reacted to."""

    emoji: str
    """A single emoji (unicode) to attach as a reaction."""


class CommandReply(msgspec.Struct, rename="camel"):
    """Generic RPC reply returned on the request's ``reply`` inbox."""

    ok: bool
    error: str | None = None


class PresenceReply(msgspec.Struct, rename="camel"):
    """Reply to a ``<prefix>.presence`` probe (duplicate-prefix detection)."""

    account: str
    """Bare JID of the bridge account holding this prefix."""

    instance: str
    """Per-process UUID (hex) identifying the owning instance."""
