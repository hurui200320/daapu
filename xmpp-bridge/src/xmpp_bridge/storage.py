"""JSON-file storage for the OMEMO library.

slixmpp-omemo 2.2.0 does not ship a concrete ``omemo.storage.Storage``; this
module provides a minimal JSON-file implementation modeled on the official
``examples/echo_client.py`` from the slixmpp-omemo repository.
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import tempfile
from pathlib import Path

from omemo.storage import Just, Maybe, Nothing, Storage
from omemo.types import JSONType

log = logging.getLogger(__name__)


class OmemoJsonFileStorage(Storage):
    """A simple key/value storage backed by a single JSON file on disk.

    Writes are atomic (write to a temp file in the same directory, then
    ``os.replace``) and performed off the event loop via
    :func:`asyncio.to_thread`, so a slow disk can't stall slixmpp. Reads are
    cached in memory by the base ``Storage`` class.
    """

    def __init__(self, json_file_path: Path) -> None:
        super().__init__()
        self._json_file_path = json_file_path
        self._data: dict[str, JSONType] = {}
        # Serializes read-modify-write cycles. Flushes run off-loop via
        # to_thread and may overlap, so mutation + flush are kept atomic wrt
        # each other to avoid two flushes snapshotting/interleaving and losing
        # an update.
        self._lock = asyncio.Lock()
        try:
            with open(self._json_file_path, encoding="utf8") as f:
                self._data = json.load(f)
            log.debug(
                "Loaded OMEMO storage from %s (%d keys)",
                self._json_file_path,
                len(self._data),
            )
        except FileNotFoundError:
            log.info(
                "OMEMO storage file %s not found; starting fresh",
                self._json_file_path,
            )
        except Exception as e:
            log.warning(
                "Failed to load OMEMO storage from %s (%s); starting fresh",
                self._json_file_path,
                e,
            )

    async def _load(self, key: str) -> Maybe[JSONType]:
        if key in self._data:
            return Just(self._data[key])
        return Nothing()

    async def _store(self, key: str, value: JSONType) -> None:
        async with self._lock:
            self._data[key] = value
            await asyncio.to_thread(self._flush)

    async def _delete(self, key: str) -> None:
        async with self._lock:
            self._data.pop(key, None)
            await asyncio.to_thread(self._flush)

    def _flush(self) -> None:
        """Atomically persist the in-memory state to disk."""
        target = self._json_file_path
        target.parent.mkdir(parents=True, exist_ok=True)
        # Write to a temp file in the same directory so os.replace is atomic
        # on POSIX (same filesystem).
        fd, tmp_path = tempfile.mkstemp(prefix=".omemo-", suffix=".tmp", dir=str(target.parent))
        try:
            with os.fdopen(fd, "w", encoding="utf8") as f:
                json.dump(self._data, f)
            os.replace(tmp_path, target)
        except Exception:
            try:
                os.unlink(tmp_path)
            except OSError:
                pass
            raise
