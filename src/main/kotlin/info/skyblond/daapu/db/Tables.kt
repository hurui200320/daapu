package info.skyblond.daapu.db

import info.skyblond.daapu.config.MAX_VECTOR_DIMENSIONS
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

/**
 * Chats.
 */
object Chats : Table("chats") {
    val id = text("id")
    val title = text("title")
    val chatJson = text("chat_json").default("[]")
    val sstmVersion = text("sstm_version").default("")
    // the ELTM version fingerprint of the last successful run; "" means
    // "never compared yet", so the first run flags eltm-updated. Updated by
    // the recall tool mid-run (a column-only UPDATE, never the store upsert).
    val eltmVersion = text("eltm_version").default("")

    override val primaryKey = PrimaryKey(id)
}

/**
 * The title a chat starts with; mirrors the `chats.title` column default in
 * `V1__init.sql` (kept in sync manually so inserts state the title explicitly).
 */
const val DEFAULT_CHAT_TITLE = "New chat"

/**
 * Shared Short Term Memories.
 */
object SSTMs : Table("sstms") {
    val id = long("id").autoIncrement()
    val lastUpdate = timestamp("last_update")
        .defaultExpression(CurrentTimestamp)
    val content = text("content")

    override val primaryKey = PrimaryKey(id)
}

/**
 * Simple numeric key-value meta store (`memory_meta_number` in
 * `V1__init.sql`); the only entry is the global ELTM write counter
 * (`eltm_version`) that feeds the eltm-updated digest — every ELTM write
 * bumps it with an atomic `value = value + 1` UPDATE inside its own
 * transaction.
 */
object MemoryMetaNumber : Table("memory_meta_number") {
    val key = text("key")
    val value = long("value")

    override val primaryKey = PrimaryKey(key)
}

/**
 * ELTM entities: a named thing with a category. The category disambiguates
 * homonyms ("Apple" as fruit vs company); all descriptive content lives in
 * the diary notes, never here. The embedding is
 * `embed(canonical_name || ' ' || category)` zero-padded to
 * [MAX_VECTOR_DIMENSIONS] (see `db/VectorColumnType.kt`).
 */
object EltmEntities : Table("eltm_entities") {
    val id = long("id").autoIncrement()
    val canonicalName = text("canonical_name")
    val category = text("category")
    val embedding = registerColumn(
        "embedding",
        VectorColumnType(MAX_VECTOR_DIMENSIONS),
    ).nullable()

    override val primaryKey = PrimaryKey(id)

    init {
        // mirrors the UNIQUE (canonical_name, category) constraint in
        // `V1__init.sql` (the true upsert semantics rely on the violation
        // being observable as a unique violation)
        uniqueIndex(canonicalName, category)
    }
}

/**
 * ELTM relationships: a directed edge (source entity, verb, destination
 * entity) with a structural [valid] state. There is exactly ONE row per
 * triple (full unique index): `valid=false` is a state flag — re-asserting
 * the triple revives the same row, an ending invalidates it. The diary
 * notes are the content truth — the flag is only an index on whether the
 * edge currently holds.
 */
object EltmRelationships : Table("eltm_relationships") {
    val id = long("id").autoIncrement()
    val srcId = long("src_id").references(EltmEntities.id, ReferenceOption.CASCADE)
    val dstId = long("dst_id").references(EltmEntities.id, ReferenceOption.CASCADE)
    val verb = text("verb")
    val valid = bool("valid").default(true)

    override val primaryKey = PrimaryKey(id)
}

/**
 * ELTM entity attributes: structured key-value facts about an entity,
 * complementary to the diary notes — attributes are current-state facts (one
 * row per (entity, key); setting again overwrites, deleting removes), the
 * notes are the temporal narrative. The value must be a single line: the
 * entity embedding text appends `key: value` lines, alphabetically by key.
 */
object EltmEntityAttributes : Table("eltm_entity_attributes") {
    val entityId = long("entity_id").references(EltmEntities.id, ReferenceOption.CASCADE)
    val key = text("key")
    val value = text("value")

    override val primaryKey = PrimaryKey(entityId, key)
}

/**
 * ELTM diary notes: add-only (no update/delete methods exist in
 * `memory/eltm/`), strictly single-subject (exactly one of [entityId] /
 * [relationshipId], enforced by the migration's CHECK). A new note supersedes
 * older information; nothing is ever removed.
 */
object EltmNotes : Table("eltm_notes") {
    val id = long("id").autoIncrement()
    val entityId = long("entity_id")
        .references(EltmEntities.id, ReferenceOption.CASCADE)
        .nullable()
    val relationshipId = long("relationship_id")
        .references(EltmRelationships.id, ReferenceOption.CASCADE)
        .nullable()
    val eventDate = date("event_date")
    val note = text("note")
    val embedding = registerColumn(
        "embedding",
        VectorColumnType(MAX_VECTOR_DIMENSIONS),
    ).nullable()
    val createdAt = timestampWithTimeZone("created_at")
        .defaultExpression(CurrentTimestampWithTimeZone)

    override val primaryKey = PrimaryKey(id)
}