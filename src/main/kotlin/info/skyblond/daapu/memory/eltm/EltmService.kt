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
 * [EltmService.getEntityNotes]).
 */
data class EntityView(
    val entity: EltmEntity,
    val noteCount: Int,
    val relationshipCount: Int,
    val latestNote: EltmNote?,
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
 * A vector-search hit: the entity plus its cosine similarity and its
 * content-backed prominence counters (diary-note count and relationship
 * degree), so the writer LLM can weigh candidates beyond similarity.
 */
data class EntityWithScore(
    val entity: EltmEntity,
    val noteCount: Int,
    val relationshipCount: Int,
    val score: Double,
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
 * The ELTM (external long-term memory) store: entities, relationships, and
 * diary notes (the diary model, see `V1__init.sql`). Written by the SSTM
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
     * exact match is a pure read — nothing is updated here (a rename or
     * re-categorization is a NEW entity: create it, then [mergeEntities]
     * the old one into it); the prominence signal is the read views'
     * computed note/relationship counts — there is nothing else to touch.
     * Otherwise the name+category text is embedded and inserted, and the
     * global write counter (`memory_meta_number.eltm_version`) is bumped in
     * the same transaction (a concurrent run's unique violation is caught
     * and turned into a re-select of the existing row — true create-or-fetch
     * semantics, an unhandled violation would fail the whole run as
     * `tool_transport`).
     * [EmbeddingException] of type `invalid_request` propagates for the tool
     * layer to map to a model-visible error.
     */
    suspend fun createEntity(name: String, category: String): CreateEntityResult

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
     * loser's entity notes are re-pointed, then the loser row is deleted.
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

