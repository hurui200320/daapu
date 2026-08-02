"""NATS client wrapper for the xmpp-bridge.

Owns the nats-py connection + JetStream context and implements the
subject/stream contract documented in :mod:`xmpp_bridge.schemas`.

Lifecycle (driven by ``__main__``)::

    nb = NatsBridge(url, prefix, account_jid)
    await nb.connect()
    await nb.claim_prefix()        # refuse if prefix is live elsewhere
    await nb.ensure_stream()        # idempotent JetStream stream for incoming
    await nb.serve_command("sendTextMessage", SendTextMessageRequest, handler)
    ...
    await nb.close()

Prefix ownership is established via a core-NATS presence probe rather than a
persisted lock. A crashed instance's NATS subscription dies with its
connection, so it cannot hold the prefix hostage (unlike a KV lock, which
would need an explicit TTL). The trade-off is a small simultaneous-start
race window within the probe timeout; acceptable for operator-managed
sidecars. The probe only guards XMPP/OMEMO account uniqueness, not the
Kotlin consumer (single-instance there is the operator's responsibility).
"""

from __future__ import annotations

import logging
import uuid
from collections.abc import Awaitable, Callable
from typing import TypeVar

import msgspec
import nats
from nats.aio.client import Client as NATSClient
from nats.aio.msg import Msg
from nats.errors import NoRespondersError
from nats.errors import TimeoutError as NatsTimeoutError
from nats.js.client import JetStreamContext
from nats.js.errors import NotFoundError

from .schemas import (
    CommandReply,
    IncomingMessage,
    PresenceReply,
)

log = logging.getLogger(__name__)

# Short timeout for the ownership probe. Any live owner answers this fast; a
# timeout means no owner and we may take the prefix.
_PRESENCE_PROBE_TIMEOUT = 0.5

# Type var for the typed RPC dispatch: each command has its own request struct.
ReqT = TypeVar("ReqT")

# A command handler decodes a typed request and returns a CommandReply.
CommandHandler = Callable[[ReqT], Awaitable[CommandReply]]


class PrefixInUseError(RuntimeError):
    """Raised when another live instance already owns the configured prefix."""


class NatsBridge:
    """NATS connection + JetStream context for the bridge."""

    def __init__(self, url: str, prefix: str, account_jid: str) -> None:
        self._url = url
        self._prefix = prefix
        self._account_jid = account_jid
        self._instance_id = uuid.uuid4().hex
        self._nc: NATSClient | None = None
        self._js: JetStreamContext | None = None

    # ------------------------------------------------------------------ #
    # Subject / stream naming
    # ------------------------------------------------------------------ #

    @property
    def prefix(self) -> str:
        return self._prefix

    @property
    def message_subject(self) -> str:
        return f"{self._prefix}.message"

    @property
    def stream_name(self) -> str:
        return f"{self._prefix}-stream"

    @property
    def presence_subject(self) -> str:
        return f"{self._prefix}.presence"

    def command_subject(self, name: str) -> str:
        return f"{self._prefix}.command.{name}"

    # ------------------------------------------------------------------ #
    # Lifecycle
    # ------------------------------------------------------------------ #

    async def connect(self) -> None:
        """Connect to the NATS server (reconnects forever on disconnect)."""
        # nats-py stubs use `**options: Unknown` / partially-typed coroutines.
        self._nc = await nats.connect(  # pyright: ignore[reportUnknownMemberType]
            servers=[self._url],
            name=f"xmpp-bridge:{self._account_jid}:{self._instance_id[:8]}",
            connect_timeout=10,
            max_reconnect_attempts=-1,
        )
        self._js = self._nc.jetstream()  # pyright: ignore[reportUnknownMemberType]
        log.info("Connected to NATS at %s (prefix=%s)", self._url, self._prefix)

    async def claim_prefix(self) -> None:
        """Refuse to start if another live instance owns this prefix.

        Probes ``<prefix>.presence`` with a short timeout. Any response means
        another instance is up -> raise :class:`PrefixInUseError`. On timeout
        (or no-responders) we assume the prefix is free and install our own
        presence responder so a duplicate started later refuses.
        """
        if self._nc is None:
            raise RuntimeError("connect() must be called first")

        log.info("Probing prefix ownership on %s ...", self.presence_subject)
        resp: Msg | None
        try:
            resp = await self._nc.request(
                self.presence_subject, b"ping", timeout=_PRESENCE_PROBE_TIMEOUT
            )
        # PEP 758 (py3.14): a bare comma-separated except tuple is valid here;
        # ruff formats it this way because the project targets Python 3.14.
        except NatsTimeoutError, NoRespondersError:
            resp = None

        if resp is not None:
            owner = self._decode_presence(resp.data)
            raise PrefixInUseError(
                f"NATS prefix {self._prefix!r} is already owned by "
                f"account={owner.account} instance={owner.instance}"
            )

        await self._nc.subscribe(  # pyright: ignore[reportUnknownMemberType]
            self.presence_subject, cb=self._on_presence_probe
        )
        log.info("Claimed NATS prefix %r (instance=%s)", self._prefix, self._instance_id)

    async def _on_presence_probe(self, msg: Msg) -> None:
        reply = PresenceReply(account=self._account_jid, instance=self._instance_id)
        await msg.respond(msgspec.json.encode(reply))

    @staticmethod
    def _decode_presence(data: bytes) -> PresenceReply:
        """Best-effort decode of the responder's identity for diagnostics."""
        try:
            return msgspec.json.decode(data, type=PresenceReply)
        except msgspec.DecodeError:
            return PresenceReply(account="unknown", instance="unknown")

    async def ensure_stream(self) -> None:
        """Idempotently create (or reconcile) the JetStream incoming stream.

        Binds only ``<prefix>.message``. If the stream already exists (e.g.
        after a restart of this instance) the subjects are reconciled via
        ``update_stream`` so config drift doesn't silently drop messages.
        """
        if self._js is None:
            raise RuntimeError("connect() must be called first")
        name = self.stream_name
        subjects = [self.message_subject]
        try:
            info = await self._js.stream_info(name)
        except NotFoundError:
            info = None

        if info is None:
            await self._js.add_stream(  # pyright: ignore[reportUnknownMemberType]
                name=name, subjects=subjects
            )
            log.info("Created JetStream stream %r (subjects=%s)", name, subjects)
            return

        existing = set(info.config.subjects or [])
        if set(subjects) - existing:
            await self._js.update_stream(  # pyright: ignore[reportUnknownMemberType]
                name=name, subjects=subjects
            )
            log.info("Updated JetStream stream %r subjects -> %s", name, subjects)
        else:
            log.debug("JetStream stream %r already configured", name)

    async def publish_incoming(self, msg: IncomingMessage) -> None:
        """Publish a decrypted incoming message to ``<prefix>.message`` (durable).

        Publishes exactly once and re-raises on failure so the caller can signal
        the sender via a ⚠️ reaction instead of silently dropping the message.
        There is no retry: the publish is driven from an XMPP callback, and
        retrying would either block the callback for backoff * max_retries or
        let later messages overtake the retried one in the stream. The ⚠️
        reaction tells the sender the at-least-once guarantee may be broken:
        since JetStream may have stored the message before the ack was lost,
        the reaction does not assert "discarded" — the sender should check
        whether they got a bot reply and resend if not.
        """
        if self._js is None:
            raise RuntimeError("connect() must be called first")
        ack = await self._js.publish(  # pyright: ignore[reportUnknownMemberType]
            self.message_subject,
            msgspec.json.encode(msg),
        )
        log.debug(
            "Published incoming message from %s to %s (seq=%d)",
            msg.from_,
            self.message_subject,
            ack.seq,
        )

    async def serve_command(
        self,
        name: str,
        req_type: type[ReqT],
        handler: CommandHandler[ReqT],
    ) -> None:
        """Subscribe to ``<prefix>.command.<name>`` and dispatch typed requests.

        Decodes the JSON body as ``req_type``, awaits ``handler(req)`` to
        produce a :class:`CommandReply`, and responds on the request's
        ``reply`` inbox. Any handler exception or decode failure yields a
        ``CommandReply(ok=False, error=...)`` so the caller (kotlin) gets a
        well-formed error instead of a silent RPC timeout.
        """
        if self._nc is None:
            raise RuntimeError("connect() must be called first")
        # Capture the non-None client in a local so the closure below doesn't
        # narrow against `self._nc` (which could be set back to None by close()
        # before the callback fires).
        nc = self._nc
        subject = self.command_subject(name)

        async def cb(msg: Msg) -> None:
            payload = await self._handle_command(msg, req_type, handler)
            if msg.reply:
                await nc.publish(msg.reply, payload)
            else:
                log.warning("Command on %s had no reply inbox; dropping reply", msg.subject)

        await nc.subscribe(subject, cb=cb)  # pyright: ignore[reportUnknownMemberType]
        log.info("Serving RPC command on %s", subject)

    @staticmethod
    async def _handle_command(
        msg: Msg,
        req_type: type[ReqT],
        handler: CommandHandler[ReqT],
    ) -> bytes:
        try:
            req = msgspec.json.decode(msg.data, type=req_type)
            reply = await handler(req)
            return msgspec.json.encode(reply)
        except msgspec.DecodeError as e:
            log.warning("Bad RPC request on %s: %s", msg.subject, e)
            return msgspec.json.encode(CommandReply(ok=False, error=f"bad request: {e}"))
        except Exception as e:
            log.exception("RPC handler on %s failed", msg.subject)
            return msgspec.json.encode(CommandReply(ok=False, error=str(e)))

    async def close(self) -> None:
        """Drain and close the NATS connection (graceful shutdown)."""
        if self._nc is None:
            return
        nc = self._nc
        self._nc = None
        self._js = None
        try:
            await nc.drain()
        except Exception as e:
            log.warning("Error draining NATS connection: %s", e)
        log.info("NATS connection closed")
