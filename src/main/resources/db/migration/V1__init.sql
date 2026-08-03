-- pgvector extension is provisioned from day one so the memory system can add
-- vector columns without a schema migration later.
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE chats
(
    id         BIGSERIAL PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE messages
(
    id           BIGSERIAL PRIMARY KEY,
    chat_id      BIGINT      NOT NULL REFERENCES chats (id) ON DELETE CASCADE,
    message_json TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS messages_chat_id_idx ON messages (chat_id, id);
