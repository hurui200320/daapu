"""Round-trip + wire-format tests for the NATS schemas shared with the Kotlin bot.

The Kotlin consumer uses kotlinx.serialization with default camelCase keys; the
bridge serializes with msgspec ``rename="camel"``. These tests guard the
cross-language contract (notably the ``from`` and ``stanzaId`` keys) against
silent drift on either side.
"""

from __future__ import annotations

import msgspec

from xmpp_bridge.schemas import (
    CommandReply,
    IncomingMessage,
    SendTextMessageRequest,
)


def test_incoming_message_round_trip_and_camel_case_keys() -> None:
    original = IncomingMessage(
        account="bot@example.com",
        from_="peer@example.com",
        body="hello",
        encrypted=True,
        type="DM",
        timestamp=1_700_000_000,
        stanza_id="id-1",
    )
    encoded = msgspec.json.encode(original)
    decoded = msgspec.json.decode(encoded, type=IncomingMessage)
    assert decoded == original

    # Wire keys must match what the Kotlin consumer expects (camelCase, `from`,
    # `stanzaId`).
    raw: dict[str, object] = msgspec.json.decode(encoded)
    assert set(raw.keys()) == {
        "account",
        "from",
        "body",
        "encrypted",
        "type",
        "timestamp",
        "stanzaId",
    }
    assert raw["from"] == "peer@example.com"
    assert raw["stanzaId"] == "id-1"


def test_incoming_message_stanza_id_optional() -> None:
    original = IncomingMessage(
        account="a@b",
        from_="c@d",
        body="x",
        encrypted=False,
        type="MUC",
        timestamp=0,
    )
    encoded = msgspec.json.encode(original)
    raw: dict[str, object] = msgspec.json.decode(encoded)
    # msgspec serializes Optional fields with their value (None -> null); it
    # does not omit them. The Kotlin side decodes `null` into `stanzaId = null`.
    assert raw["stanzaId"] is None
    decoded = msgspec.json.decode(encoded, type=IncomingMessage)
    assert decoded == original


def test_send_text_message_request_defaults_force_encrypted() -> None:
    req = SendTextMessageRequest(to="to@b", text="hi")
    encoded = msgspec.json.encode(req)
    raw: dict[str, object] = msgspec.json.decode(encoded)
    assert raw["to"] == "to@b"
    assert raw["text"] == "hi"
    assert raw["forceEncrypted"] is False


def test_command_reply_round_trip() -> None:
    ok = CommandReply(ok=True)
    err = CommandReply(ok=False, error="boom")

    ok_enc = msgspec.json.encode(ok)
    err_enc = msgspec.json.encode(err)

    assert msgspec.json.decode(ok_enc, type=CommandReply) == ok
    assert msgspec.json.decode(err_enc, type=CommandReply) == err

    err_raw: dict[str, object] = msgspec.json.decode(err_enc)
    assert err_raw["error"] == "boom"
