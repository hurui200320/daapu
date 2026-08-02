"""Tests for the warning-reaction fallback in ``_dispatch_incoming``.

When publishing to NATS fails, the bridge reacts to the sender's original
message with ⚠️ to signal that the at-least-once guarantee may be broken.
The reaction anchors on the XEP-0359 ``origin-id``, falling back to the
legacy stanza ``id``, or is skipped if neither is present.
"""

from __future__ import annotations

import asyncio
import unittest.mock
from typing import cast

from slixmpp.jid import JID
from slixmpp.plugins.xep_0334.stanza import Store
from slixmpp.plugins.xep_0359.stanza import OriginID
from slixmpp.plugins.xep_0444.stanza import Reaction, Reactions
from slixmpp.stanza import Message
from slixmpp.xmlstream import register_stanza_plugin

from xmpp_bridge.client import BridgeBot
from xmpp_bridge.nats_bridge import NatsBridge

# Ensure stanza plugins used by _dispatch_incoming are registered on Message
# so a bare Message (without a full BridgeBot plugin manager) can carry
# reactions, store hints, and origin-id.
register_stanza_plugin(Message, Reactions)
register_stanza_plugin(Reactions, Reaction, iterable=True)
register_stanza_plugin(Message, Store)
register_stanza_plugin(Message, OriginID)

_REACTIONS_NS = "{urn:xmpp:reactions:0}"


def _make_bot() -> tuple[BridgeBot, list[Message]]:
    """Create a BridgeBot with ``__init__`` bypassed.

    Only the attributes used by ``_dispatch_incoming`` are wired up; messages
    sent via ``_send_or_raise`` are captured in the returned list. The NATS
    mock is configured to raise on ``publish_incoming`` by default.
    """
    # Bypass ClientXMPP.__init__ (needs a live event loop, plugin manager, and
    # OMEMO store file); only set the attributes _dispatch_incoming touches.
    bot = BridgeBot.__new__(BridgeBot)
    bot.boundjid = JID("bot@example.com")
    # XMLStream.__del__ accesses _run_out_filters; set it to None so the
    # GC doesn't raise AttributeError when the bot is collected.
    bot._run_out_filters = None  # type: ignore[attr-defined]

    nats_mock = unittest.mock.AsyncMock()
    nats_mock.publish_incoming.side_effect = RuntimeError("nats down")
    # Tests access _nats directly to configure the mock (reportPrivateUsage).
    bot._nats = cast(NatsBridge, nats_mock)  # pyright: ignore[reportPrivateUsage]

    sent: list[Message] = []

    def _make_message(mto: object, mtype: str) -> Message:
        msg = Message()
        msg["to"] = str(mto)
        msg["type"] = mtype
        return msg

    def _send_or_raise(stanza: Message) -> None:
        sent.append(stanza)

    # Monkey-patch the methods that normally need a live XMPP session.
    bot.make_message = _make_message  # type: ignore[method-assign]
    bot._send_or_raise = _send_or_raise  # type: ignore[method-assign]
    return bot, sent


def _make_stanza(stanza_id: str | None, origin_id: str | None) -> Message:
    """Build a Message with optional legacy id and/or XEP-0359 origin-id."""
    msg = Message()
    if stanza_id is not None:
        msg["id"] = stanza_id
    if origin_id is not None:
        msg["origin_id"]["id"] = origin_id
    return msg


def _reaction_id(msg: Message) -> str | None:
    """Extract the reaction anchor id from a sent message, or None."""
    el = msg.xml.find(f"{_REACTIONS_NS}reactions")
    if el is None:
        return None
    return el.get("id")


def _reaction_values(msg: Message) -> list[str | None]:
    """Extract the reaction emoji strings from a sent message."""
    el = msg.xml.find(f"{_REACTIONS_NS}reactions")
    if el is None:
        return []
    return [r.text for r in el.findall(f"{_REACTIONS_NS}reaction")]


def test_reaction_uses_origin_id_when_present() -> None:
    """When both origin-id and stanza id exist, the reaction anchors on origin-id."""
    bot, sent = _make_bot()
    stanza = _make_stanza(stanza_id="stanza-1", origin_id="origin-1")
    asyncio.run(
        BridgeBot._dispatch_incoming(  # pyright: ignore[reportPrivateUsage]
            bot, stanza, JID("peer@example.com"), "hello", True
        )
    )
    assert len(sent) == 1
    assert _reaction_id(sent[0]) == "origin-1"
    assert _reaction_values(sent[0]) == ["\u26a0\ufe0f"]


def test_reaction_falls_back_to_stanza_id_when_no_origin_id() -> None:
    """Without an origin-id, the reaction falls back to the legacy stanza id."""
    bot, sent = _make_bot()
    stanza = _make_stanza(stanza_id="stanza-1", origin_id=None)
    asyncio.run(
        BridgeBot._dispatch_incoming(  # pyright: ignore[reportPrivateUsage]
            bot, stanza, JID("peer@example.com"), "hello", True
        )
    )
    assert len(sent) == 1
    assert _reaction_id(sent[0]) == "stanza-1"


def test_reaction_skipped_when_neither_id_present() -> None:
    """No origin-id and no stanza id: the reaction is skipped (log + return)."""
    bot, sent = _make_bot()
    stanza = _make_stanza(stanza_id=None, origin_id=None)
    asyncio.run(
        BridgeBot._dispatch_incoming(  # pyright: ignore[reportPrivateUsage]
            bot, stanza, JID("peer@example.com"), "hello", True
        )
    )
    assert len(sent) == 0


def test_no_reaction_when_publish_succeeds() -> None:
    """When publish_incoming succeeds, no reaction is sent."""
    bot, sent = _make_bot()
    # Override the mock to succeed instead of raising.
    # Tests access _nats directly to configure the mock (reportPrivateUsage).
    cast(unittest.mock.AsyncMock, bot._nats).publish_incoming.side_effect = (  # pyright: ignore[reportPrivateUsage]
        None
    )
    stanza = _make_stanza(stanza_id="stanza-1", origin_id="origin-1")
    asyncio.run(
        BridgeBot._dispatch_incoming(  # pyright: ignore[reportPrivateUsage]
            bot, stanza, JID("peer@example.com"), "hello", True
        )
    )
    assert len(sent) == 0
