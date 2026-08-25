-- User-defined agent personas: the persona half of the main agent's system
-- prompt (the GSG harness introduction is appended by code, see
-- agent/persist/MainAgentSystemPromptService.kt) plus a namespace whitelist over the chat
-- loop's tool set (the MCP servers and `gsg`).
--
-- The DEFAULT persona is NOT stored here: it lives in code
-- (DEFAULT_PERSONA_SYSTEM_PROMPT), so prompt updates need no sync. Its
-- reserved id is the sentinel 0 — a BIGSERIAL identity starts at 1, so no
-- row can collide — and the persona API rejects it.
--
-- allowed_namespaces is a JSON array of tool namespace strings; an empty
-- array means "all namespaces served by the chat loop" (the default persona's
-- shape).
--
-- The row id is a numeric DB identity (BIGSERIAL, like the ELTM tables): the
-- wire types carry it as the number itself (no string conversion), and the
-- reserved code-only id 0 never collides with a row.
CREATE TABLE personas
(
    id                 BIGSERIAL PRIMARY KEY,
    name               TEXT NOT NULL,
    system_prompt      TEXT NOT NULL,
    allowed_namespaces TEXT NOT NULL DEFAULT '[]'
);

-- The per-chat persona RECORD: the persona id of the chat's last successful run
-- used. Not authoritative for runs — every run carries its persona id in the
-- request — it only records the selection for the UI to pre-fill the picker.
-- The default is the reserved id 0 (the code-only default persona).
ALTER TABLE chats ADD COLUMN persona_id BIGINT NOT NULL DEFAULT 0;
