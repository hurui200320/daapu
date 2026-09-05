package info.skyblond.daapu.db

import info.skyblond.daapu.agent.persona.DEFAULT_PERSONA_ID
import info.skyblond.daapu.config.MAX_VECTOR_DIMENSIONS
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

/**
 * Chats.
 */
object Chats : Table("chats") {
    val id = text("id")
    val title = text("title")
    val chatJson = text("chat_json").default("[]")
    // the ELTM version fingerprint of the last successful run; "" means
    // "never compared yet", so the first run flags eltm-updated. Stamped by
    // the persist loop's successful store upsert.
    val eltmVersion = text("eltm_version").default("")
    // the per-chat persona RECORD: the persona id of the chat's last
    // successful run (stamped by the store upsert). Not authoritative for
    // runs — every run carries its persona id in the request. The default
    // mirrors DEFAULT_PERSONA_ID (agent/persona/Persona.kt): the reserved
    // code default is the sentinel 0, a BIGSERIAL identity never produces.
    val personaId = long("persona_id").default(DEFAULT_PERSONA_ID)

    override val primaryKey = PrimaryKey(id)
}

/**
 * User-defined agent personas: the persona text plus a tool-namespace
 * whitelist over the chat loop's tool set (see `V2__personas.sql`). The
 * DEFAULT persona is NOT a row here — it lives in code
 * (`agent/persona/DefaultPersona.kt`), so prompt updates need no sync.
 */
object Personas : Table("personas") {
    val id = long("id").autoIncrement()
    val name = text("name")
    val systemPrompt = text("system_prompt")
    // a JSON array of tool namespace strings; `[]` = all namespaces served
    // by the chat loop
    val allowedNamespaces = text("allowed_namespaces").default("[]")

    override val primaryKey = PrimaryKey(id)
}

/**
 * Simple numeric key-value meta store (`memory_meta_number` in
 * `V1__init.sql`); the only entry is the global ELTM write counter
 * (`eltm_version`) that feeds the eltm-updated version marker — every ELTM write
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
 * The background memory-extraction queue (`V3__pending_extractions.sql`):
 * deleted chats' history snapshots waiting for the extraction worker
 * (`memory/eltm/ExtractionQueue.kt`). Access goes ONLY through that seam.
 * A row is deleted on success; `visible_after` is the visibility-timeout
 * marker the claim moves forward (the migration's header comment holds the
 * authoritative mechanism description).
 */
object PendingExtractions : Table("pending_extractions") {
    val id = long("id").autoIncrement()
    val chatJson = text("chat_json")
    val visibleAfter = timestampWithTimeZone("visible_after")
        .defaultExpression(CurrentTimestampWithTimeZone)

    override val primaryKey = PrimaryKey(id)
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

    override val primaryKey = PrimaryKey(id)
}