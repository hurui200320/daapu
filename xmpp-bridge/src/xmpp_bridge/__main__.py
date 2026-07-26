"""Entry point for the xmpp-bridge.

Replaces ``Main.kt``. Loads configuration from environment, instantiates the
``BridgeBot`` and runs the asyncio event loop. No startup announcement is
sent (the bot just logs its device id + identity key and waits for incoming
DMs, which it echoes back to the console and the sender).

Shutdown: SIGTERM (``docker stop``) and SIGINT (Ctrl-C) both trigger a graceful
disconnect via ``BridgeBot.request_shutdown``; the resulting ``disconnected``
event stops the loop and the process exits 0. A fatal auth failure exits 1 so
docker's restart policy restarts the container.
"""

from __future__ import annotations

import asyncio
import logging
import signal
import sys

from .client import BridgeBot
from .config import BridgeConfig


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
        "Starting xmpp-bridge for %s (host=%s)",
        config.xmpp_jid,
        config.xmpp_server_host,
    )

    bot = BridgeBot(
        jid=config.xmpp_jid,
        password=config.xmpp_password,
        omemo_json_path=str(config.omemo_data_path),
        server_host=config.xmpp_server_host,
        proxy_url=config.proxy_url,
    )

    # Use an explicit event loop so we can install signal handlers on the same
    # loop slixmpp runs on. slixmpp's `loop` property is settable, so we assign
    # it here before connect() so every internal task shares this loop.
    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)
    bot.loop = loop  # pyright: ignore[reportUnknownMemberType]  # slixmpp stubs incomplete

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
        # failure); docker's `restart: unless-stopped` then restarts it. A
        # graceful SIGTERM/SIGINT exits 0 so docker does not restart.
        sys.exit(1 if bot.fatal_exit else 0)


if __name__ == "__main__":
    main()
