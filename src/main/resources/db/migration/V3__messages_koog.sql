-- V3: messages now store serialized koog Message objects (JSON) instead of
-- role/content columns. koog's ChatMemory feature owns the conversation history;
-- this table is the persistence backend for the ChatHistoryProvider, still
-- keyed by chat_id so we know which message belongs to which chat.
--
-- The table is dropped and recreated rather than altered: the previous
-- role/content schema is incompatible and the old rows are not meaningful to
-- koog-managed history anyway.
DROP TABLE IF EXISTS messages;

CREATE TABLE messages
(
    id           BIGSERIAL PRIMARY KEY,
    chat_id      BIGINT      NOT NULL REFERENCES chats (id) ON DELETE CASCADE,
    message_json TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS messages_chat_id_idx ON messages (chat_id, id);
