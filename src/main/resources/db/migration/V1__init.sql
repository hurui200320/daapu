-- pgvector extension is provisioned from day one so the memory system can add
-- vector columns without a schema migration later.
CREATE EXTENSION IF NOT EXISTS vector;

-- koog's ChatMemory owns the conversation history: the full message list is
-- serialized into history_json as one JSON array, loaded and stored as a unit.
CREATE TABLE chats
(
    id           TEXT PRIMARY KEY,
    history_json TEXT NOT NULL DEFAULT '[]'
);
