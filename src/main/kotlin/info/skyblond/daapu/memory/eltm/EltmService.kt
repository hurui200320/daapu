package info.skyblond.daapu.memory.eltm

import info.skyblond.daapu.hand.EmbeddingException
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * One ELTM entity: a named thing with a category. The category disambiguates
 * homonyms ("Apple" as fruit vs company). All descriptive content lives in
 * the diary notes, never here.
 */
data class EltmEntity(
    val id: Long,
    val canonicalName: String,
    val category: String,
)

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

/**
 * One ELTM relationship: a directed edge (source entity, verb, destination
 * entity) with a structural [valid] state. There is exactly ONE row per
 * triple (full unique index): `valid=false` is a flag — an ending
 * invalidates it, a re-establishment is a diary event (a note with
 * `valid=true`). The diary notes
 * are the content truth — the flag is only an index on whether the edge
 * currently holds.
 */
data class EltmRelationship(
    val id: Long,
    val srcId: Long,
    val dstId: Long,
    val verb: String,
    val valid: Boolean,
)

/**
 * One ELTM diary note: an add-only entry attached to exactly ONE subject (an
 * entity or a relationship), carrying the LLM-resolved absolute [eventDate]
 * of the event. A new note supersedes older information; nothing is ever
 * removed.
 */
data class EltmNote(
    val id: Long,
    val entityId: Long?,
    val relationshipId: Long?,
    val eventDate: LocalDate,
    val note: String,
    val createdAt: OffsetDateTime,
)

/**
 * An entity read view carrying its latest diary note inline plus its
 * content-backed prominence counters (the "how much do we know" signal:
 * [noteCount] diary entries, [relationshipCount] relationships in BOTH
 * directions, valid or invalidated — each triple counts once, `valid` is a
 * state, not a second row; drill into history via
 * [EltmService.getEntityNotes]) and its [attributes] (current-state facts,
 * keys alphabetically ordered).
 */
data class EntityView(
    val entity: EltmEntity,
    val noteCount: Int,
    val relationshipCount: Int,
    val latestNote: EltmNote?,
    val attributes: EntityAttributes,
)

/**
 * A relationship read view carrying both endpoint names, its diary-note
 * count, and its latest diary note inline.
 */
data class RelationshipView(
    val relationship: EltmRelationship,
    val srcName: String,
    val dstName: String,
    val noteCount: Int,
    val latestNote: EltmNote?,
)

/**
 * A vector-search hit: the entity plus its cosine similarity, its
 * content-backed prominence counters (diary-note count and relationship
 * degree), its latest diary note, and its [attributes] (current-state
 * facts, keys alphabetically ordered) — the whole model-visible picture in
 * one batch, so the writer LLM can weigh candidates beyond similarity
 * without a per-hit drill-down.
 */
data class EntityWithScore(
    val entity: EltmEntity,
    val noteCount: Int,
    val latestNote: EltmNote?,
    val relationshipCount: Int,
    val score: Double,
    val attributes: EntityAttributes,
)

/** The result of an entity create: the current row plus near-match suspects. */
data class CreateEntityResult(
    val entity: EltmEntity,
    /**
     * Similarity candidates (cosine above the configured
     * `entityMatchThreshold`, top 5, excluding the entity itself), so the
     * writer LLM can disambiguate ("use that id") or merge true duplicates.
     * Computed from the row's STORED embedding on the exact-match path too.
     */
    val nearMatches: List<EntityWithScore>,
)

/**
 * Normalize a name to its canonical form: trim, collapse internal
 * whitespace, lowercase (spaces kept). The canonical form is what the
 * `(canonical_name, category)` uniqueness constraint deduplicates on.
 */
fun normalizeName(name: String): String =
    name.trim().replace(WHITESPACE_REGEX, " ").lowercase()

/**
 * Normalize a relationship verb: [normalizeName], then spaces to
 * underscores (`"works at"` → `"works_at"`).
 */
fun normalizeVerb(verb: String): String = normalizeName(verb).replace(' ', '_')

/**
 * Normalize an attribute key: like a verb ([normalizeVerb]) — attribute
 * keys are single lowercase tokens (`"Real Name"` → `"real_name"`), so
 * `set`/`delete` always address the same row.
 */
fun normalizeAttributeKey(key: String): String = normalizeVerb(key)

/**
 * The entity embedding text: the canonical name + category, plus the
 * attributes as `key: value` lines, keys ordered alphabetically — so the
 * text (and the vector) never depend on insertion order. Values are
 * single-line (enforced by the service), so the lines are unambiguous.
 */
internal fun entityEmbeddingText(
    canonicalName: String,
    category: String,
    attributes: EntityAttributes,
): String {
    val base = "$canonicalName $category"
    if (attributes.isEmpty()) return base
    val lines = attributes.toSortedMap().entries.joinToString("\n") { "${it.key}: ${it.value}" }
    return "$base\n$lines"
}

/**
 * The attribute-folding plan for a merge ([EltmService.mergeEntities]):
 * which of the loser's attribute rows fold into the winner, which are
 * dropped (the winner already holds the key — the winner's value wins; a
 * re-point would collide on the composite PK), and the winner's post-fold
 * attribute map. Pure decision logic, shared by the Postgres service and
 * the test fakes, so fake-backed tests exercise the real code.
 */
data class AttributeFoldPlan(
    /** Keys only the loser holds: its rows re-point to the winner. */
    val foldableKeys: Set<String>,
    /** Keys both entities hold: the loser's rows are dropped. */
    val droppedKeys: Set<String>,
    /** The winner's post-fold attribute map (`loserAttrs + winnerAttrs`). */
    val winnerAttributes: EntityAttributes,
) {
    /**
     * Whether the fold changes the winner's attribute text — exactly when a
     * new key folds in (a colliding key keeps the winner's value, so the
     * text is unchanged) — and with it the stored embedding: an
     * attribute-less merge reuses the winner's vector.
     */
    val changesText: Boolean get() = foldableKeys.isNotEmpty()
}

/**
 * Plan a merge's attribute fold (see [AttributeFoldPlan]): the winner keeps
 * its value on a colliding key, the loser's unique keys fold in.
 */
fun planAttributeFold(
    winnerAttrs: EntityAttributes,
    loserAttrs: EntityAttributes,
): AttributeFoldPlan {
    val winnerKeys = winnerAttrs.keys
    return AttributeFoldPlan(
        foldableKeys = loserAttrs.keys - winnerKeys,
        droppedKeys = winnerKeys intersect loserAttrs.keys,
        winnerAttributes = loserAttrs + winnerAttrs,
    )
}

private val WHITESPACE_REGEX = Regex("\\s+")

/**
 * Zero-pad a vector to [width]. The extra zero dimensions contribute nothing to
 * dot products or norms, so cosine similarity is preserved exactly and the
 * stored width never depends on the embedding model in use.
 */
internal fun padVector(vector: List<Float>, width: Int): List<Float> {
    require(vector.size <= width) { "vector has ${vector.size} dimensions, width is $width" }
    if (vector.size == width) return vector
    return vector + List(width - vector.size) { 0f }
}

/**
 * The ELTM (external long-term memory) store: entities, attributes,
 * relationships, and diary notes (the diary model, see `V1__init.sql`).
 * Written by the SSTM
 * purge pipeline only (`agent/oneshot/eltm/EltmWriterService.kt`); read by
 * the writer and (Phase 4) the recall sub-session.
 *
 * All embeddings go through the hand (`hand/HandService.embed`), are
 * zero-padded to the fixed column width ([MAX_VECTOR_DIMENSIONS]) on write,
 * and queries are padded identically — cosine similarity is invariant under
 * zero-padding, so switching embedding models never needs a schema change.
 */
interface EltmService {
    /**
     * Create or fetch an entity by `(normalizeName(name), category)`: an
     * exact match is a pure read — nothing is updated here (identity
     * changes go through [refineEntity] instead); the prominence signal is
     * the read views' computed note/relationship counts — there is nothing
     * else to touch. Otherwise the name+category text is embedded and
     * inserted, and the global write counter
     * (`memory_meta_number.eltm_version`) is bumped in the same transaction
     * (a concurrent run's unique violation is caught and turned into a
     * re-select of the existing row — true create-or-fetch semantics, an
     * unhandled violation would fail the whole run as `tool_transport`).
     * [EmbeddingException] of type `invalid_request` propagates for the tool
     * layer to map to a model-visible error.
     */
    suspend fun createEntity(name: String, category: String): CreateEntityResult

    /**
     * Rename ONE entity in place and/or change its category — e.g. a
     * briefly-mentioned "friend" later identified by name, or re-categorized
     * without a rename. The entity's id
     * stays: notes, relationships and attributes keep pointing at it, so a
     * refine is never a create+merge (those are only for true duplicates).
     *
     * A null [newName] keeps the current name, a null [newCategory] keeps
     * the current category; at least one of the two is expected to change
     * something. Like [setEntityAttribute], the whole read-modify-write with
     * the hand embed call inside runs in ONE transaction (the entity row
     * locked `FOR UPDATE` at the start), so the stored embedding always
     * matches the new name+category and the unchanged attributes.
     *
     * @throws IllegalArgumentException when the entity does not exist, a
     * provided name/category is blank, or another entity already holds the
     * target `(normalizeName(newName), newCategory)` — the caller must merge
     * the two instead (fail-fast, never a silent auto-merge).
     * @throws EmbeddingException of type `invalid_request` (content too large
     * for the embedding model) propagates for the tool layer to map, rolled
     * back before anything moved.
     */
    @Throws(EmbeddingException::class)
    suspend fun refineEntity(
        entityId: Long,
        newName: String?,
        newCategory: String?,
    ): EltmEntity

    /**
     * Create or fetch a relationship by `(srcId, verb, dstId)`. There is
     * exactly ONE row per triple (full unique index) — an existing row,
     * ACTIVE OR INVALIDATED, is a pure read returned as-is: `valid` never
     * changes here (re-establishing an ended relationship is a diary event,
     * see [attachNoteToRelationship]); otherwise the triple is inserted (a
     * concurrent run's unique violation is caught and turned into a
     * re-select, like [createEntity]). Only real inserts bump the global
     * write counter in the same transaction.
     */
    suspend fun createRelationship(srcId: Long, dstId: Long, verb: String): EltmRelationship

    /**
     * Merge [loserId] into [winnerId] (ONE transaction): every relationship
     * touching the loser is re-pointed to the winner — colliding with an
     * existing row of the same triple folds the duplicate away (its notes
     * re-pointed to the survivor BEFORE the duplicate's delete, so the
     * `ON DELETE CASCADE` must never destroy diary notes; the survivor
     * holds the edge if either row held it); a re-point that would become
     * a self-loop (winner—winner) invalidates instead of re-pointing. The
     * loser's entity notes are re-pointed, its attributes fold into the
     * winner (winner's value wins a colliding key), the loser's embedding
     * is irrelevant, the winner is re-embedded when the fold changed its
     * attribute text, then the loser row is deleted. The whole merge — the
     * reads, the fold's hand embed call and the writes — runs in that ONE
     * transaction with both entity rows locked `FOR UPDATE` at the start
     * (the connection is held across the embed), so the fold can never
     * race a concurrent attribute write. The row locks are taken in
     * ascending id order, so opposite-direction concurrent merges can
     * never deadlock.
     */
    suspend fun mergeEntities(winnerId: Long, loserId: Long)

    /**
     * Append a dated diary note to an entity. The note is embedded and the
     * row appended (add-only — no update/delete methods exist).
     *
     * @throws EmbeddingException of type `invalid_request` (content too large for
     * the embedding model) propagates for the tool layer to map.
     */
    @Throws(EmbeddingException::class)
    suspend fun attachNoteToEntity(
        entityId: Long,
        eventDate: LocalDate,
        note: String,
    ): EltmNote

    /**
     * Append a dated diary note to a relationship. When [valid] is
     * non-null, the note records a structural change of the relationship:
     * `false` CLOSES it (the event ended the edge — e.g. "left the
     * company"; the note text must explain the ending), `true` RE-OPENS it
     * (a revival event — e.g. "rejoined the company"; the edge becomes
     * `valid=true` again). Setting the current state is a no-op
     * (idempotent — the note still attaches either way). The note and the
     * structural change are committed in ONE transaction with ONE counter bump.
     * The diary is the content truth: a bare structural change without a note carries no
     * reason.
     *
     * @throws EmbeddingException of type `invalid_request` (content too large for
     * the embedding model) propagates for the tool layer to map.
     */
    @Throws(EmbeddingException::class)
    suspend fun attachNoteToRelationship(
        relationshipId: Long,
        eventDate: LocalDate,
        note: String,
        valid: Boolean? = null,
    ): EltmNote

    /**
     * Set a current-state fact (key-value attribute) on an entity: one row
     * per `(entity, key)` — a new key inserts, an existing key OVERWRITES
     * the value (attributes are facts, not a diary; the notes are the
     * diary). Setting the identical value is a no-op (pure read: no
     * embedding call, no counter bump). A changed write re-embeds the
     * entity (the embedding text is `name + category` plus the attributes
     * as `key: value` lines, alphabetically by key) and bumps the global
     * write counter — the whole read-modify-write, the hand embed call
     * included, runs in ONE transaction with the entity row locked
     * `FOR UPDATE` at the start (the connection is held across the embed),
     * so the stored embedding can never diverge from the attribute rows:
     * a concurrent attribute write serializes against the whole sequence
     * instead of racing the embed.
     *
     * @return `true` when the value changed (a real write), `false` when it
     * was already set to exactly [value] (a no-op).
     * @throws EmbeddingException of type `invalid_request` (content too large
     * for the embedding model) propagates for the tool layer to map.
     */
    @Throws(EmbeddingException::class)
    suspend fun setEntityAttribute(entityId: Long, key: String, value: String): Boolean

    /**
     * Remove a current-state fact from an entity. Fail-fast on a missing
     * entity or a key the entity does not have. Re-embeds the entity and
     * bumps the global write counter — like [setEntityAttribute], the whole
     * read-modify-write with the hand embed call inside runs in ONE
     * transaction (the entity row locked `FOR UPDATE` at the start), so the
     * embedding always matches the surviving attributes.
     *
     * @throws EmbeddingException of type `invalid_request` propagates for the
     * tool layer to map.
     */
    @Throws(EmbeddingException::class)
    suspend fun deleteEntityAttribute(entityId: Long, key: String)

    /**
     * Semantic search over entities: embeds [query], returns entities with
     * cosine similarity at or above the configured `entityMatchThreshold`,
     * most similar first, capped at [limit]; each hit carries its
     * note/relationship counts.
     */
    suspend fun searchEntities(query: String, limit: Int): List<EntityWithScore>

    /**
     * All entities (whatever their prominence), ordered by id ascending for
     * a stable page, each with its note and relationship counts and its
     * latest diary note inline. Paginated via [limit]/[offset] — the
     * frontend ELTM view's browse-all surface (the recall sub-session uses
     * [searchEntities] instead).
     */
    suspend fun listEntities(limit: Int, offset: Int): List<EntityView>

    /**
     * All relationships (active and invalidated), ordered by id ascending,
     * each with its endpoint names, its note count, and its latest diary
     * note inline. Paginated via [limit]/[offset].
     */
    suspend fun listRelationships(limit: Int, offset: Int): List<RelationshipView>

    /**
     * The entity with its latest diary note inline plus its note and
     * relationship counts, or null when missing.
     */
    suspend fun getEntity(id: Long): EntityView?

    /**
     * The entity's relationships in BOTH directions, each with its latest
     * note inline and its note count; active only unless [includeInvalid].
     */
    suspend fun getRelationships(entityId: Long, includeInvalid: Boolean): List<RelationshipView>

    /**
     * One relationship with its endpoint names, its latest note inline, and
     * its note count, or null when missing.
     */
    suspend fun getRelationship(id: Long): RelationshipView?

    /**
     * Cheap existence probe for an entity (a single indexed lookup) — the
     * read tools fail fast on a missing subject with it, without paying for
     * the full [getEntity] view (entity row + note count + relationship
     * count + latest note + attributes).
     */
    suspend fun entityExists(entityId: Long): Boolean

    /**
     * Cheap existence probe for a relationship (a single indexed lookup),
     * the [getRelationship] counterpart of [entityExists].
     */
    suspend fun relationshipExists(relationshipId: Long): Boolean

    /**
     * The diary notes of one entity, newest event first
     * (`event_date DESC, id DESC`), paginated via [limit]/[offset],
     * optionally narrowed to a date range.
     */
    suspend fun getEntityNotes(
        entityId: Long,
        from: LocalDate?,
        to: LocalDate?,
        limit: Int,
        offset: Int,
    ): List<EltmNote>

    /**
     * The diary notes of one relationship, newest event first
     * (`event_date DESC, id DESC`), paginated via [limit]/[offset],
     * optionally narrowed to a date range.
     */
    suspend fun getRelationshipNotes(
        relationshipId: Long,
        from: LocalDate?,
        to: LocalDate?,
        limit: Int,
        offset: Int,
    ): List<EltmNote>

    /**
     * Semantic search over diary notes (cosine at or above the configured
     * `noteSearchThreshold`), with optional subject (XOR) and date-range
     * filters.
     */
    suspend fun searchNotes(
        query: String,
        entityId: Long?,
        relationshipId: Long?,
        from: LocalDate?,
        to: LocalDate?,
        limit: Int,
    ): List<EltmNote>

    /**
     * The current ELTM version, read from the store: the global write
     * counter (`memory_meta_number.eltm_version`), bumped atomically by
     * every visible-state write inside ITS transaction. NOT a
     * content hash — any write that changes the visible state moves it.
     * Compared against `chats.eltm_version` for the `eltm-updated`
     * injection flag. A loose indicator for the LLM that the ELTM has been
     * updated and info in the context **might** be outdated — unlike SSTM
     * the store is never fetched as a whole, so a snapshot digest is
     * impossible, and the plain version is the right (cheap) signal.
     */
    suspend fun version(): String
}

