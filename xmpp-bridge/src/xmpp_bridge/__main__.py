"""Entry point for the xmpp-bridge.

Replaces ``Main.kt``. Loads configuration from environment, instantiates the
``BridgeBot`` and a :class:`NatsBridge`, and runs the asyncio event loop.

Startup sequence (all on the same asyncio loop as slixmpp; nats-py is
asyncio-native)::

    NATS: connect -> claim_prefix -> register RPC commands -> ensure_stream
    XMPP: connect -> session_start -> omemo_ready (commands block on this)

The NATS prefix is claimed *before* connecting to XMPP so a duplicate instance
refuses to start (fatal exit) rather than racing on the XMPP account / OMEMO
store. RPC command handlers are registered *before* the JetStream stream is
ensured: the kotlin bot retries consumer attachment until the stream exists,
so it can never attach its consumer (and start replying) before we can answer
its RPCs.

Shutdown: SIGTERM (``docker stop``) and SIGINT (Ctrl-C) both trigger a graceful
disconnect via ``BridgeBot.request_shutdown``; the resulting ``disconnected``
event drains NATS and stops the loop, then the process exits 0. A fatal auth
failure exits 1 so docker's restart policy restarts the container.
"""

from __future__ import annotations

import asyncio
import logging
import signal
import sys

from .client import BridgeBot
from .config import BridgeConfig
from .nats_bridge import NatsBridge, PrefixInUseError


def main() -> None:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)-7s %(name)s - %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S",
    )
    # Tame noisy third-party loggers.
    for noisy in ("slixmpp",):
        logging.getLogger(noisy).setLevel(logging.WARNING)

    log = logging.getLogger(__name__)

    config = BridgeConfig.from_env()

    log.info(
        "Starting xmpp-bridge for %s (host=%s, nats_prefix=%s)",
        config.xmpp_jid,
        config.xmpp_server_host,
        config.nats_prefix,
    )

    # Use an explicit event loop so we can install signal handlers on the same
    # loop slixmpp and nats-py run on.
    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)

    nats_bridge = NatsBridge(
        url=config.nats_url,
        prefix=config.nats_prefix,
        account_jid=config.xmpp_jid,
    )

    # Connect + claim the NATS prefix *before* touching XMPP, so a duplicate
    # instance fails fast instead of contending for the XMPP account / OMEMO
    # store. run_until_complete blocks the main thread on this loop; slixmpp
    # is not yet connected so there is no conflict.
    try:
        loop.run_until_complete(nats_bridge.connect())
        loop.run_until_complete(nats_bridge.claim_prefix())
    except PrefixInUseError as e:
        log.error("%s", e)
        sys.exit(1)
    except Exception as e:
        log.error("Failed to initialize NATS: %s", e)
        sys.exit(1)

    bot = BridgeBot(
        jid=config.xmpp_jid,
        password=config.xmpp_password,
        omemo_json_path=str(config.omemo_data_path),
        nats_bridge=nats_bridge,
        server_host=config.xmpp_server_host,
        proxy_url=config.proxy_url,
        loop=loop,
    )

    # Register RPC command handlers *before* ensuring the JetStream stream
    # (see module docstring for why). Handlers block on OMEMO readiness
    # internally, so they can be registered before XMPP/OMEMO is ready.
    try:
        loop.run_until_complete(bot.register_nats_commands())
        loop.run_until_complete(nats_bridge.ensure_stream())
    except Exception as e:
        log.error("Failed to initialize NATS: %s", e)
        sys.exit(1)

    def _request_shutdown() -> None:
        bot.request_shutdown()

    # Graceful shutdown on docker stop (SIGTERM) and Ctrl-C (SIGINT).
    loop.add_signal_handler(signal.SIGTERM, _request_shutdown)
    loop.add_signal_handler(signal.SIGINT, _request_shutdown)

    bot.connect()  # pyright: ignore[reportUnknownMemberType]
    try:
        loop.run_forever()
    finally:
        loop.close()
        # Non-zero exit only when the bot flagged a fatal condition (e.g. auth
        # failure or NATS prefix conflict); docker's `restart: unless-stopped`
        # then restarts it. A graceful SIGTERM/SIGINT exits 0 so docker does
        # not restart.
        sys.exit(1 if bot.fatal_exit else 0)


if __name__ == "__main__":
    main()
