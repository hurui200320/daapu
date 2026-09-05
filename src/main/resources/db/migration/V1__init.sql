-- pgvector extension is provisioned from day one so the memory system can add
-- vector columns without a schema migration later.
CREATE EXTENSION IF NOT EXISTS vector;

-- The conversation is stored in chat_json as one JSON array in the
-- project-owned framework-neutral format.
CREATE TABLE chats
(
    id           TEXT PRIMARY KEY,
    title        TEXT NOT NULL DEFAULT 'New chat',
    chat_json    TEXT NOT NULL DEFAULT '[]',
    eltm_version TEXT NOT NULL DEFAULT ''
);

-- External long-term memory, diary model:
-- + entities (a named thing with a category)
-- + relationships (directed edges with a structural valid flag — the diary notes are the content truth)
-- + notes (the diary: add-only, strictly single-subject).
-- Entities and relationships carry no timestamps: their content lives in the
-- notes, and change detection rides a single global write counter
-- (memory_meta_number.eltm_version) bumped atomically by every write.
--
-- Every vector column is fixed at 2000 dimensions, pgvector's HNSW indexing
-- limit for the `vector` type (MAX_VECTOR_DIMENSIONS in config/Config.kt).
-- Embedding models may output fewer dimensions: the service zero-pads every
-- vector (and every query) to this width on write. Cosine similarity is
-- invariant under zero-padding, so switching embedding models never requires
-- a schema change. Only that the model's output dimensions do not exceed
-- this width.

-- Simple key-value meta store for numeric counters; the only entry is the
-- ELTM write counter feeding the eltm-updated version marker (bumped with an atomic
-- `value = value + 1` UPDATE inside every ELTM write transaction).
CREATE TABLE memory_meta_number
(
    key   TEXT PRIMARY KEY,
    value BIGINT NOT NULL
);
INSERT INTO memory_meta_number (key, value) VALUES ('eltm_version', 0);

CREATE TABLE eltm_entities
(
    id             BIGSERIAL PRIMARY KEY,
    canonical_name TEXT NOT NULL,                 -- trim, collapse whitespace, lowercase
    category       TEXT NOT NULL DEFAULT 'general',
    embedding      vector(2000),                  -- embed(canonical_name || ' ' || category), zero-padded
    UNIQUE (canonical_name, category)
);
CREATE INDEX eltm_entities_embedding_idx ON eltm_entities USING hnsw (embedding vector_cosine_ops);

CREATE TABLE eltm_relationships
(
    id             BIGSERIAL PRIMARY KEY,
    src_id         BIGINT NOT NULL REFERENCES eltm_entities(id) ON DELETE CASCADE,
    dst_id         BIGINT NOT NULL REFERENCES eltm_entities(id) ON DELETE CASCADE,
    verb           TEXT NOT NULL,                 -- lowercase, whitespace collapsed to underscores
    valid          BOOLEAN NOT NULL DEFAULT true  -- structural state; the diary is the content truth
);
-- ONE row per triple: valid is a state of the relationship, never a second row.
-- A re-assertion revives the row; an ending invalidates it.
CREATE UNIQUE INDEX eltm_relationships_triple_idx
    ON eltm_relationships (src_id, dst_id, verb);

CREATE TABLE eltm_notes                          -- the diary: add-only
(
    id              BIGSERIAL PRIMARY KEY,
    entity_id       BIGINT REFERENCES eltm_entities(id) ON DELETE CASCADE,
    relationship_id BIGINT REFERENCES eltm_relationships(id) ON DELETE CASCADE,
    event_date      DATE NOT NULL,                -- LLM-resolved absolute date of the event
    note            TEXT NOT NULL,
    embedding       vector(2000),                 -- embed(note), zero-padded
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),  -- when the note was logged
    CHECK (num_nonnulls(entity_id, relationship_id) = 1)
);
CREATE INDEX eltm_notes_entity_idx ON eltm_notes (entity_id, event_date DESC, id DESC);
CREATE INDEX eltm_notes_rel_idx    ON eltm_notes (relationship_id, event_date DESC, id DESC);
CREATE INDEX eltm_notes_embedding_idx ON eltm_notes USING hnsw (embedding vector_cosine_ops);

-- Entity attributes: structured key-value facts about an entity, e.g. a
-- kindle's model, a person's realname/nickname. Complementary to the diary
-- notes: attributes are the CURRENT-STATE facts (one row per (entity, key);
-- setting the same key again overwrites the value), the notes are the
-- temporal narrative. The key is canonicalized like a verb (trim, collapse
-- whitespace, lowercase, spaces to underscores); the value must be a single
-- line — the entity embedding text appends the attributes as
-- `key: value` lines (alphabetically by key), so facts are semantically
-- searchable, and a newline would corrupt the line structure.
CREATE TABLE eltm_entity_attributes
(
    entity_id BIGINT NOT NULL REFERENCES eltm_entities(id) ON DELETE CASCADE,
    key       TEXT   NOT NULL,
    value     TEXT   NOT NULL,
    PRIMARY KEY (entity_id, key)
);
