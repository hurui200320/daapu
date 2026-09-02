-- The background memory-extraction queue (Postgres-as-queue): when a chat is
-- deleted, its history snapshot is enqueued here and the chats row is removed
-- right away; a background worker (memory/eltm/ExtractionQueueWorker.kt)
-- extracts the memories into the ELTM off the request path.
--
-- Visibility-timeout pattern: a worker claims a job inside ONE transaction —
-- SELECT..FOR UPDATE SKIP LOCKED over the oldest ids (FIFO) followed by an
-- update that pushes `visible_after` one job timeout into the future — the
-- claimed row is invisible to every worker until the window lapses. That single
-- mechanism covers all failure modes:
-- a known failure leaves the row to re-emerge (the worker additionally
-- reschedules it to the shorter retry delay), a crash/shutdown mid-extraction
-- leaves it to re-emerge at the lease boundary. Nothing is ever lost: rows
-- are deleted only on success, and the ELTM writer deduplicates re-runs.
--
-- Deliberately minimal: no chat id (log correlation rides the enqueue log
-- line "chat X -> job N"), no attempts/error columns (failures log).
-- The id PK's btree serves the claim's ORDER BY id LIMIT 1 directly (the scan
-- stops at the first visible row), so no extra index is needed.
--
-- Retention note: the snapshot carries the deleted chat's FULL content (text
-- and image attachments) and stays in this table until the job completes —
-- deleting a chat no longer removes its content from the database right away.
-- A permanently failing job retries forever, so its snapshot lingers; delete
-- the row manually when that matters.
CREATE TABLE pending_extractions
(
    id            BIGSERIAL PRIMARY KEY,
    chat_json     TEXT NOT NULL,                      -- ChatCodec snapshot of the deleted chat's history
    visible_after TIMESTAMPTZ NOT NULL DEFAULT now()  -- claimable when <= now(); a fresh row is immediately claimable
);
