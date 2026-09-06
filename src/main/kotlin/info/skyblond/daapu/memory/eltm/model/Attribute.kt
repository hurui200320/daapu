package info.skyblond.daapu.memory.eltm.model

/**
 * An entity's structured key-value facts (e.g. a kindle's `model`, a
 * person's `realname`/`nickname`), complementary to the diary notes:
 * attributes are CURRENT-STATE facts (one row per (entity, key); setting
 * the same key again overwrites, deleting removes), the notes are the
 * temporal narrative. Keys are canonicalized like verbs; values must be
 * single-line. The entity embedding text appends them as `key: value`
 * lines alphabetically by key, so facts are semantically searchable.
 */
typealias EntityAttributes = Map<String, String>
