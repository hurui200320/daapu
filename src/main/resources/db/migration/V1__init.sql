-- pgvector extension is provisioned from day one so the memory system can add
-- vector columns without a schema migration later.
CREATE EXTENSION IF NOT EXISTS vector;

-- The conversation history is stored in history_json as one JSON array in the
-- project-owned framework-neutral format.
CREATE TABLE chats
(
    id           TEXT PRIMARY KEY,
    title        TEXT NOT NULL DEFAULT 'New chat',
    history_json TEXT NOT NULL DEFAULT '[]'
);

-- Shared short term memories
-- content is raw text, no escape.
CREATE TABLE sstms
(
    id BIGSERIAL PRIMARY KEY,
    last_update TIMESTAMP NOT NULL DEFAULT now(),
    content TEXT NOT NULL
)