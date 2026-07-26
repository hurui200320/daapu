"""Environment configuration for the xmpp-bridge.

Reads values from ``os.environ`` (the compose stack passes them via ``env_file``).
"""

from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path


def _require_env(key: str) -> str:
    value = os.environ.get(key)
    if not value:
        raise ValueError(f"{key} is not present in the environment")
    return value


@dataclass(frozen=True)
class BridgeConfig:
    """Resolved configuration loaded from environment variables."""

    xmpp_jid: str
    xmpp_password: str
    xmpp_server_host: str
    omemo_store_dir: Path
    proxy_url: str | None

    @property
    def omemo_data_path(self) -> Path:
        return self.omemo_store_dir / "omemo-data.json"

    @classmethod
    def from_env(cls) -> BridgeConfig:
        host = _require_env("XMPP_SERVER_HOST")
        jid = _require_env("XMPP_ACCOUNT_JID")
        password = _require_env("XMPP_ACCOUNT_PASSWORD")

        store_dir_env = os.environ.get("XMPP_OMEMO_STORE_DIR", "./omemo-store")
        store_dir = Path(store_dir_env)
        store_dir.mkdir(parents=True, exist_ok=True)

        proxy_url: str | None = None
        proxy_env = os.environ.get("XMPP_PROXY", "").strip()
        if proxy_env:
            if "://" not in proxy_env:
                raise ValueError(
                    "XMPP_PROXY must be a full proxy URL with scheme, e.g. "
                    "'socks5://user:pass@host:port' (accepted schemes: "
                    "socks5, socks5h, socks4, socks4a, http). Got: " + proxy_env
                )
            proxy_url = proxy_env

        return cls(
            xmpp_jid=jid,
            xmpp_server_host=host,
            xmpp_password=password,
            omemo_store_dir=store_dir,
            proxy_url=proxy_url,
        )
