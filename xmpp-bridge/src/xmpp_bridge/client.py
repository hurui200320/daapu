"""The XMPP bridge client.

Slixmpp routes all incoming ``<message/>``
stanzas through a single handler, which branches on whether the stanza is
OMEMO-encrypted.

MUC (XEP-0045) is intentionally not registered — only 1:1 DMs are handled.
Carbons and own-JID reflections are dropped with warning.
"""

from __future__ import annotations

import asyncio
import logging
import socket
import time
from typing import cast

import oldmemo
from omemo.session_manager import MessageNotForUs, SessionManager
from omemo.types import DeviceInformation
from python_socks import ProxyError as _ProxyError
from python_socks.async_.asyncio import Proxy as _Proxy
from slixmpp.clientxmpp import ClientXMPP
from slixmpp.jid import JID
from slixmpp.plugins import register_plugin  # pyright: ignore[reportUnknownVariableType]
from slixmpp.plugins.xep_0444 import XEP_0444
from slixmpp.stanza import Message
from slixmpp.xmlstream.handler import CoroutineCallback
from slixmpp.xmlstream.matcher import MatchXPath
from slixmpp_omemo import XEP_0384

from .nats_bridge import NatsBridge
from .omemo_plugin import XEP_0384Impl
from .schemas import (
    AddReactionRequest,
    CommandReply,
    IncomingMessage,
    ReplyToMessageRequest,
    SendMucMessageRequest,
    SendTextMessageRequest,
)

# Register the concrete OMEMO plugin subclass in slixmpp's global plugin
# registry, replacing the stock `xep_0384` implementation. This is the single
# registration point; per-instance `register_plugin("xep_0384", ...)` below
# only *enables* the class already registered here.
register_plugin(XEP_0384Impl)

log = logging.getLogger(__name__)


class BridgeBot(ClientXMPP):
    """XMPP+OMEMO bot that bridges incoming DMs to NATS and services RPC commands.

    Incoming decrypted/plaintext DMs are published to NATS via the
    :class:`NatsBridge` (``<prefix>.message`` JetStream subject). Outbound
    sends happen through ``<prefix>.command.*`` RPC handlers registered against
    the same bridge. MUC (XEP-0045) is intentionally not registered — only
    1:1 DMs are handled. Carbons and own-JID reflections are dropped with
    warning.
    """

    def __init__(
        self,
        jid: str,
        password: str,
        omemo_json_path: str,
        nats_bridge: NatsBridge,
        server_host: str | None = None,
        proxy_url: str | None = None,
        loop: asyncio.AbstractEventLoop | None = None,
    ) -> None:
        super().__init__(  # pyright: ignore[reportUnknownMemberType]
            jid,
            password,
            loop=loop,  # slixmpp stubs are incomplete; the kwarg is forwarded to XMLStream
        )

        self._omemo_json_path = omemo_json_path
        self._server_host = server_host
        self._proxy_url = proxy_url
        self._omemo_ready = asyncio.Event()
        self._nats = nats_bridge

        # Connection lifecycle flags.
        # _intentional_disconnect: True when we initiated shutdown (SIGTERM/SIGINT
        #   or a fatal auth failure); the `disconnected` handler then stops the
        #   loop instead of reconnecting.
        # _fatal_exit: True when the process should exit non-zero so docker
        #   restarts it (currently only set on permanent auth failure).
        self._intentional_disconnect: bool = False
        self._fatal_exit: bool = False

        # Enable the OMEMO plugin (the class itself is registered globally at
        # import time above) with its JSON-file storage config. xep_0384 pulls
        # in xep_0004/0030/0060/0163/0280/0334 automatically.
        # MUC (xep_0045) is deliberately NOT registered.
        self.register_plugin(  # pyright: ignore[reportUnknownMemberType]
            "xep_0384",
            {"json_file_path": self._omemo_json_path},
        )
        # XMPP Ping keepalive. keepalive lets slixmpp detect silent mid-session
        # TCP drops (half-open links, server killed without FIN) and auto-reconnect
        # on ping timeout. Without it the bot would hang forever waiting for stanzas
        # that never arrive. 60s interval / 30s timeout detects a dead link within ~90s.
        self.register_plugin(  # pyright: ignore[reportUnknownMemberType]
            "xep_0199",
            {"keepalive": True, "interval": 60, "timeout": 30},
        )
        # Explicit Message Encryption (<eme/>)
        self.register_plugin("xep_0380")  # pyright: ignore[reportUnknownMemberType]
        # Message Reactions (<reactions/>). Used to react with ⚠️ on the
        # sender's message when publishing to NATS fails, signaling that the
        # at-least-once guarantee may be broken (JetStream may have stored the
        # message before the ack was lost). Reactions are sent in plain text:
        # slixmpp_omemo only encrypts the <body/> (oldmemo) and has no SCE
        # plugin for full-stanza encryption (twomemo), so an encrypted reaction
        # would require implementing the missing upstream feature. The metadata
        # leak (a ⚠️ appeared) is negligible.
        self.register_plugin("xep_0444")  # pyright: ignore[reportUnknownMemberType]

        self.add_event_handler("session_start", self._on_session_start)
        self.add_event_handler("omemo_initialized", self._on_omemo_ready)

        # Connection lifecycle handlers.
        # `connection_failed` is fired per failed TCP/DNS attempt; slixmpp's own
        # _connect_loop retries with backoff, so we only log for visibility.
        # `disconnected` is fired on any disconnect; slixmpp does NOT auto-reconnect
        # on clean server disconnects (only keepalive ping-timeout does), so we
        # reconnect here unless the disconnect was intentional.
        # `failed_all_auth` / `no_auth` fire when all SASL mechanisms are exhausted
        # — slixmpp then disconnects and halts (retrying won't help), so we mark
        # the exit fatal and let the resulting `disconnected` stop the loop.
        self.add_event_handler("connection_failed", self._on_connection_failed)
        self.add_event_handler("disconnected", self._on_disconnected)
        self.add_event_handler("failed_all_auth", self._on_fatal_auth_failure)
        self.add_event_handler("no_auth", self._on_fatal_auth_failure)

        # Single handler for every incoming <message/> (plain + encrypted + carbons).
        self.register_handler(
            CoroutineCallback(
                "Messages",
                MatchXPath(f"{{{self.default_ns}}}message"),
                self.message_handler,  # pyright: ignore[reportArgumentType]
            )
        )

    # ------------------------------------------------------------------ #
    # Connection / proxy
    # ------------------------------------------------------------------ #

    def connect(self, host: str | None = None, port: int | None = None) -> asyncio.Future[object]:
        """Connect, optionally overriding the resolved host."""
        if self._server_host:
            host = self._server_host
            port = port or 5222
        # slixmpp stubs are incomplete; the Future's parameter type is Unknown.
        return cast(
            asyncio.Future[object],
            super().connect(host=host, port=port),  # pyright: ignore[reportUnknownMemberType]
        )

    async def _attempt_connection(
        self, host: str, port: int, tls: bool, server_hostname: str | None
    ) -> bool:
        """Override to tunnel through a proxy when configured."""
        if self._proxy_url is None:
            return await super()._attempt_connection(host, port, tls, server_hostname)
        return await self._attempt_connection_via_proxy(host, port, tls, server_hostname)

    async def _attempt_connection_via_proxy(
        self, host: str, port: int, tls: bool, server_hostname: str | None
    ) -> bool:
        """Tunnel an XMPP connection through a SOCKS4/5 or HTTP proxy.

        Delegates the proxy handshake to ``python-socks`` (supports SOCKS4(a),
        SOCKS5(h) with optional username/password auth, and HTTP CONNECT). The
        returned non-blocking socket is handed to slixmpp with optional TLS.
        """
        self.event_when_connected = "connected"
        self._connect_loop_wait += 1

        if self._current_connection_attempt is None:  # pyright: ignore[reportUnknownMemberType]
            return False

        loop = self.loop or asyncio.get_running_loop()

        ssl_context = self.get_ssl_context() if tls else None

        sock: socket.socket | None = None
        try:
            proxy = _Proxy.from_url(cast(str, self._proxy_url))  # pyright: ignore[reportUnknownMemberType]
            sock = await proxy.connect(dest_host=host, dest_port=port)

            # Tunnel is up; hand the socket off to slixmpp with optional TLS.
            await loop.create_connection(
                lambda: self,
                sock=sock,
                ssl=ssl_context,
                server_hostname=server_hostname,
            )
            sock = None  # ownership transferred
            self._connect_loop_wait = 0
            log.info("Connected via proxy %s", self._proxy_url)
            return True
        except (OSError, _ProxyError) as e:
            log.debug("Proxy connection to %s failed: %s", self._proxy_url, e)
            self.event("connection_failed", e)
            return False
        except Exception as e:
            log.error("Unexpected error during proxy connection: %s", e, exc_info=True)
            self.event("connection_failed", e)
            return False
        finally:
            if sock is not None:
                try:
                    sock.close()
                except OSError:
                    pass

    # ------------------------------------------------------------------ #
    # Event handlers
    # ------------------------------------------------------------------ #

    async def _on_session_start(self, _event: object) -> None:
        self.send_presence()
        await self.get_roster()  # pyright: ignore[reportUnknownMemberType]
        log.info("XMPP session started")

    async def _on_omemo_ready(self, _event: object) -> None:
        try:
            xep_0384 = cast(XEP_0384, self["xep_0384"])
            session_manager = await xep_0384.get_session_manager()
            own_device, other_devices = await session_manager.get_own_device_information()
            log.info("Bot device id: %s", own_device.device_id)
            fingerprint = " ".join(SessionManager.format_identity_key(own_device.identity_key))
            log.info("Bot key fingerprint: %s", fingerprint)

            # The bridge exclusively owns this account: ensure the server-published
            # device lists contain only this device, purging any stale entries.
            await self._purge_other_devices(session_manager, own_device, other_devices)
        except Exception as e:
            log.warning("Failed to read own OMEMO device info: %s", e)
        self._omemo_ready.set()
        log.info("OMEMO ready; listening for incoming DMs.")

    async def _purge_other_devices(
        self,
        session_manager: SessionManager,
        own_device: DeviceInformation,
        other_devices: frozenset[DeviceInformation],
    ) -> None:
        """Ensure the server-published OMEMO device lists contain only this device.

        The bridge is the sole owner of the XMPP account, so any other device id
        registered on the server is leftover from a previous run or another client.
        Only when stale devices exist is the list wiped (``clear_device_lists``
        for all backends, twomemo + oldmemo). ``set_own_label`` then re-downloads
        the (possibly empty) list, adds this device back with its existing label,
        and uploads it — yielding a server list of exactly one entry. Running it
        unconditionally also recovers from a crash during a previous purge, where
        the server list may be empty.
        """
        if other_devices:
            log.info(
                "Purging %d stale device(s) from OMEMO device list: %s",
                len(other_devices),
                ", ".join(str(d.device_id) for d in other_devices),
            )
            await session_manager.clear_device_lists()

        try:
            await session_manager.set_own_label(own_device.label)
            log.info(
                "OMEMO device list now contains only this device (%s)",
                own_device.device_id,
            )
        except Exception as e:
            log.warning("Failed to ensure own OMEMO device in server list: %s", e)

    # ------------------------------------------------------------------ #
    # Connection lifecycle
    # ------------------------------------------------------------------ #

    def _on_connection_failed(self, error: object) -> None:
        """Log only — slixmpp's `_connect_loop` retries TCP/DNS failures with
        exponential backoff (capped at 300s) and never gives up, so we must not
        crash here or we'd pre-empt its retry loop.
        """
        log.warning("XMPP connection attempt failed (slixmpp will retry): %s", error)

    def _on_disconnected(self, _event: object) -> None:
        """Reconnect on unplanned disconnects; stop the loop on intentional ones.

        slixmpp does NOT auto-reconnect on a clean server-side disconnect (only
        the xep_0199 keepalive plugin does, on ping timeout), so we drive that
        path here. Auth failures mark `_intentional_disconnect` before slixmpp
        calls `disconnect()`, so they take the stop-loop branch instead of
        looping back into a connect that will just fail again.

        On an intentional disconnect, the NATS connection is drained (in-flight
        subscriptions flushed) before the loop stops; the drain runs as a task
        on the still-running loop and stops the loop once done.
        """
        if self._intentional_disconnect:
            log.info("XMPP disconnected (intentional); draining NATS before exit.")
            asyncio.ensure_future(self._drain_nats_and_stop())
            return
        log.warning("XMPP disconnected unexpectedly; reconnecting.")
        # Our connect() override reuses self._server_host. slixmpp's connect()
        # cancels any in-flight attempt first, so overlap with keepalive's
        # ping-timeout reconnect is harmless.
        self.connect()  # pyright: ignore[reportUnknownMemberType]

    async def _drain_nats_and_stop(self) -> None:
        """Drain the NATS connection, then stop the event loop."""
        try:
            await self._nats.close()
        finally:
            self.loop.call_soon(self.loop.stop)

    def _on_fatal_auth_failure(self, _event: object) -> None:
        """All SASL mechanisms exhausted — slixmpp will disconnect and halt.

        Retry won't change credentials, so mark the exit fatal and let the
        subsequent `disconnected` event stop the loop. Setting
        `_intentional_disconnect` here makes `_on_disconnected` take the
        stop-loop branch (instead of reconnecting into a known-failing auth).
        """
        log.error(
            "XMPP authentication failed permanently (no usable SASL mechanism); "
            "exiting for docker restart."
        )
        self._fatal_exit = True
        self._intentional_disconnect = True

    def request_shutdown(self) -> None:
        """Initiate graceful shutdown (called from SIGTERM/SIGINT handlers).

        Marks the next `disconnected` event as intentional so `_on_disconnected`
        stops the loop instead of reconnecting, then kicks off a clean disconnect.
        """
        log.info("Shutdown requested; disconnecting from XMPP...")
        self._intentional_disconnect = True
        self.disconnect()  # pyright: ignore[reportUnknownMemberType]

    @property
    def fatal_exit(self) -> bool:
        """True if the process should exit non-zero so docker restarts it."""
        return self._fatal_exit

    async def message_handler(self, stanza: Message) -> None:
        """Single handler for plain, encrypted and carbon-wrapped messages."""
        xep_0384 = cast(XEP_0384, self["xep_0384"])

        mfrom: JID = stanza["from"]
        mtype = stanza.get_type()

        # DM-only: skip groupchat (MUC), error, headline, etc.
        if mtype not in {"chat", "normal"}:
            return

        # Drop carbons / own-message reflections
        if (
            stanza.xml.find("{urn:xmpp:carbons:2}sent") is not None
            or stanza.xml.find("{urn:xmpp:carbons:2}received") is not None
        ):
            log.warning(
                "Received carbon copy; current impl does NOT support HA setup "
                "(may cause desync). Ignoring carbon message."
            )
            return
        if mfrom.bare == self.boundjid.bare:
            log.debug("Skip our own message")
            return

        namespaces: set[str] = xep_0384.is_encrypted(stanza)
        if len(namespaces) == 0:
            # Plaintext message
            body = stanza["body"]
            if not body:
                return
            log.info("[plain] %s: %s", mfrom, body)
            await self._dispatch_incoming(stanza, mfrom, body, encrypted=False)
            return

        # Encrypted message
        log.debug("Encrypted message in namespaces %s from %s", namespaces, mfrom)
        try:
            decrypted, device_information = await xep_0384.decrypt_message(stanza)
        except MessageNotForUs:
            session_manager = await xep_0384.get_session_manager()
            # pull sender's latest device info for encryption
            await session_manager.get_device_information(mfrom.bare)
            # if target support OMEMO, this should exchange our key to them
            # so from next message, they should encrypt for us
            # TODO reply to failed message?
            await self.send_text_message(
                mfrom,
                (
                    "Failed to decrypt previous message because your client doesn't "
                    "encrypt for my device.\n\nIf this message is encrypted for you, "
                    "then your device should now have my device key. You can retry the "
                    "last message"
                ),
            )
            return
        except Exception as e:
            log.warning("Failed to decrypt message from %s: %s", mfrom, e)
            return

        body = decrypted.get("body", "")
        if not body:
            # Key-transport message (no plaintext body)
            log.debug("Skip key transportation message (no body)")
            return

        log.info("[omemo] %s (device %s): %s", mfrom, device_information.device_id, body)
        await self._dispatch_incoming(stanza, mfrom, body, encrypted=True)

    async def _dispatch_incoming(
        self, stanza: Message, sender: JID, body: str, encrypted: bool
    ) -> None:
        """Publish the decrypted incoming DM to NATS for the kotlin bot to consume.

        ``from`` is the sender's bare JID (matches the schema contract); the
        kotlin bot addresses replies to it, which is correct for OMEMO routing.

        Publishes exactly once with no retry (see ``NatsBridge.publish_incoming``).
        On failure the sender's message is reacted to with ⚠️, signaling that
        the at-least-once guarantee may be broken: JetStream may have stored
        the message before the ack was lost, so the reaction does not assert
        "discarded" — the sender should check whether they got a bot reply and
        resend if not.
        """
        stanza_id: str | None = stanza["id"] or None
        msg = IncomingMessage(
            account=self.boundjid.bare,
            from_=sender.bare,
            body=body,
            encrypted=encrypted,
            type="DM",
            timestamp=int(time.time()),
            stanza_id=stanza_id,
        )
        try:
            await self._nats.publish_incoming(msg)
        except Exception as e:
            log.error(
                "Failed to publish incoming message from %s to NATS: %s",
                sender,
                e,
            )
            # XEP-0444 reactions anchor on a target id. Prefer the XEP-0359
            # origin-id (the sender's own stable id for their outgoing message),
            # then fall back to the legacy stanza id. Without either we can't
            # anchor a reaction; log and skip rather than falling back to a
            # plain-text DM (noisier and still can't confirm delivery state).
            origin_id_el = stanza.xml.find("{urn:xmpp:sid:0}origin-id")
            reaction_target: str | None = (
                origin_id_el.get("id") if origin_id_el is not None else None
            ) or stanza_id
            if reaction_target is None:
                log.warning(
                    "Cannot react to message from %s (no origin-id or stanza id); skipping",
                    sender,
                )
                return
            try:
                reaction = self.make_message(mto=sender, mtype="chat")
                XEP_0444.set_reactions(reaction, reaction_target, ["⚠️"])
                reaction.enable("store")
                self._send_or_raise(reaction)
            except Exception as ne:
                log.error(
                    "Also failed to react to %s's message: %s",
                    sender,
                    ne,
                )

    # ------------------------------------------------------------------ #
    # NATS RPC commands (kotlin -> bridge)
    # ------------------------------------------------------------------ #

    async def register_nats_commands(self) -> None:
        """Register all ``<prefix>.command.*`` RPC handlers on the NATS bridge.

        Handlers block on OMEMO readiness internally (via
        :meth:`send_text_message`), so RPCs received before OMEMO is ready
        will queue up; the kotlin caller's RPC timeout must accommodate that.
        """
        nats = self._nats
        await nats.serve_command(
            "sendTextMessage", SendTextMessageRequest, self._handle_send_text_message
        )
        await nats.serve_command(
            "replyToMessage", ReplyToMessageRequest, self._handle_reply_to_message
        )
        await nats.serve_command(
            "sendMucMessage", SendMucMessageRequest, self._handle_send_muc_message
        )
        await nats.serve_command("addReaction", AddReactionRequest, self._handle_add_reaction)

    async def _handle_send_text_message(self, req: SendTextMessageRequest) -> CommandReply:
        """RPC: send a 1:1 DM to ``req.to``."""
        try:
            await self.send_text_message(JID(req.to), req.text, req.force_encrypted)
            return CommandReply(ok=True)
        except Exception as e:
            log.error("sendTextMessage RPC failed: %s", e)
            return CommandReply(ok=False, error=str(e))

    async def _handle_reply_to_message(self, req: ReplyToMessageRequest) -> CommandReply:
        """RPC: reply to a stanza (XEP-0359). Scaffolded — not implemented."""
        return CommandReply(ok=False, error="not implemented")

    async def _handle_send_muc_message(self, req: SendMucMessageRequest) -> CommandReply:
        """RPC: send a MUC message. Scaffolded — MUC is not supported yet."""
        return CommandReply(ok=False, error="not implemented")

    async def _handle_add_reaction(self, req: AddReactionRequest) -> CommandReply:
        """RPC: add an emoji reaction (XEP-0444). Scaffolded — not implemented."""
        return CommandReply(ok=False, error="not implemented")

    # ------------------------------------------------------------------ #
    # Sending
    # ------------------------------------------------------------------ #

    async def send_text_message(self, to: JID, text: str, force_encrypted: bool = False) -> None:
        """Port of ``XMPPChatClient.sendTextMessage``.

        If the contact supports OMEMO (or ``force_encrypted`` is set), the
        message is encrypted; otherwise it is sent as plain text.
        """
        await self._omemo_ready.wait()

        xep_0384 = cast(XEP_0384, self["xep_0384"])
        session_manager = await xep_0384.get_session_manager()

        supports_omemo = False
        try:
            devices = await session_manager.get_device_information(to.bare)
            supports_omemo = len(devices) > 0
        except Exception:
            supports_omemo = False

        if not supports_omemo and not force_encrypted:
            # Plaintext path
            msg = self.make_message(mto=to, mtype="chat")
            msg["body"] = text
            self._send_or_raise(msg)
            return

        if not supports_omemo and force_encrypted:
            raise RuntimeError("Contact doesn't support OMEMO but encryption is enforced")

        # Encrypted path: encrypt_message refreshes device lists internally.
        stanza = self.make_message(mto=to, mtype="chat")
        stanza["body"] = text

        recipient_set: set[JID] = {to}
        encrypted_message, encryption_errors = await xep_0384.encrypt_message(stanza, recipient_set)

        if len(encryption_errors) > 0:
            log.warning("Non-critical encryption errors: %s", encryption_errors)

        if encrypted_message is None:
            # encrypt_message returns None when there is nothing to encrypt (e.g.
            # every recipient device failed). Surface it as an error so the RPC
            # caller sees ok=False instead of us silently reporting success.
            raise RuntimeError(f"Nothing to encrypt for {to}; message not sent")

        # Tag with <eme/> so plain-only clients render a hint.
        encrypted_message["eme"]["namespace"] = oldmemo.oldmemo.NAMESPACE
        encrypted_message["eme"]["name"] = self["xep_0380"].mechanisms[  # pyright: ignore[reportUnknownMemberType, reportAttributeAccessIssue]
            oldmemo.oldmemo.NAMESPACE
        ]

        self._send_or_raise(encrypted_message)

    def _send_or_raise(self, stanza: Message) -> None:
        """Send a stanza, raising on failures we can detect synchronously.

        slixmpp's ``send()`` is fire-and-forget: the stanza is queued for the
        writer coroutine, so transport-level errors (e.g. ``NotConnectedError``
        when the socket dropped) surface asynchronously in slixmpp's logs, not
        at the call site. What we *can* catch here is a missing
        session/transport, where ``send()`` would otherwise queue the stanza
        and drop it silently.
        """
        if not self._session_started or self.transport is None:  # pyright: ignore[reportUnknownMemberType]
            raise RuntimeError("XMPP session is not active; message not sent")
        stanza.send()
