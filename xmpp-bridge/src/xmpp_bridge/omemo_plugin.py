"""Concrete ``XEP_0384`` plugin: OMEMO encryption with blind trust (BTBV).

With ``_btbv_enabled`` set to ``True``, new devices from new JIDs are
auto-trusted without prompting. The ``_prompt_manual_trust`` hook
auto-trusts as well (a safety net that only fires after a JID has been
manually distrusted).
"""

from __future__ import annotations

import logging
from pathlib import Path
from typing import Any

from omemo.storage import Storage
from omemo.types import DeviceInformation
from slixmpp_omemo import XEP_0384, TrustLevel

from .storage import OmemoJsonFileStorage

log = logging.getLogger(__name__)


class XEP_0384Impl(XEP_0384):
    """Concrete OMEMO plugin for the daapu bot.

    Class name intentionally matches the slixmpp plugin key ``xep_0384`` so it
    can be registered under that name and looked up via ``self["xep_0384"]``.

    Configuration (passed via ``register_plugin("xep_0384", {...})``):

    * ``json_file_path``: on-disk path for the JSON-file OMEMO storage.
    """

    default_config = {
        "fallback_message": (
            "This message is OMEMO encrypted. If you saw this, it means OMEMO is not working."
        )
    }

    json_file_path: str | None

    def __init__(self, *args: Any, **kwargs: Any) -> None:  # noqa: ANN401
        super().__init__(*args, **kwargs)
        # Pull the value out of self.config into a real typed instance attribute
        # so that static type checkers see `Optional[str]` instead of going
        # through BasePlugin's __getattr__ magic (which returns Any).
        self.json_file_path = self.config.get("json_file_path")
        self.__storage: Storage | None = None

    def plugin_init(self) -> None:
        if not self.json_file_path:
            raise ValueError("json_file_path must be configured for the OMEMO storage")
        self.__storage = OmemoJsonFileStorage(Path(self.json_file_path))
        super().plugin_init()

    # --- required abstract members ---

    @property
    def storage(self) -> Storage:
        if self.__storage is None:  # pragma: no cover - plugin_init runs first
            raise RuntimeError("plugin_init has not been called yet")
        return self.__storage

    @property
    def _btbv_enabled(self) -> bool:
        # blindly trust devices
        return True

    async def _prompt_manual_trust(
        self,
        manually_trusted: frozenset[DeviceInformation],
        identifier: str | None,
    ) -> None:
        # Under BTBV this is normally only reached after a manual distrust on
        # the JID. For a bot that cannot verify fingerprints, blindly trust
        # everything that reaches this path too.
        session_manager = await self.get_session_manager()
        for device in manually_trusted:
            log.info(
                "[%s] Auto-trusting (manual path) device %s of %s",
                identifier,
                device.device_id,
                device.bare_jid,
            )
            await session_manager.set_trust(
                device.bare_jid,
                device.identity_key,
                TrustLevel.TRUSTED.value,
            )

    # --- optional hook: log blind trusts ---

    async def _devices_blindly_trusted(
        self,
        blindly_trusted: frozenset[DeviceInformation],
        identifier: str | None,
    ) -> None:
        for device in blindly_trusted:
            log.info(
                "[%s] Blindly trusted device %s of %s",
                identifier,
                device.device_id,
                device.bare_jid,
            )
