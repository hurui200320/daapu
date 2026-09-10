package info.skyblond.daapu.memory.eltm

import info.skyblond.daapu.hand.EmbeddingException
import info.skyblond.daapu.memory.eltm.model.*
import java.time.LocalDate

/**
 * The result of appending diary notes to a relationship
 * ([EltmService.attachNotesToRelationship]): the appended notes plus the
 * relationship's structural validity AFTER the attach — the [valid]
 * argument applied, or the row's unchanged state when the argument was
 * null — so the tool layer renders the post-attach state without a
 * read-after-write round trip.
 */
data class RelationshipNotesResult(
    val notes: List<EltmNote>,
    val valid: Boolean,
)

/**
 * The result of the combined retrieval ([EltmService.searchEntitiesAndNotes]):
 * the entity hits and the diary-note hits of ONE embedded query.
 */
data class EltmSearchHits(
    val entities: List<EntityWithScore>,
    val notes: List<EltmNote>,
)

/**
 * The result of an entity create: the current row's full view plus
 * near-match suspects.
 */
data class CreateEntityResult(
    /**
     * The created/fetched row's read view (counts, latest note,
     * attributes), computed inside the create's OWN transaction — the
     * tool layer renders the entity without a follow-up read.
     */
    val view: EntityView,
    /**
     * Similarity candidates (cosine above the configured
     * `entityMatchThreshold`, top 5, excluding the entity itself), so the
     * writer LLM can disambiguate ("use that id") or merge true duplicates.
     * Computed from the row's STORED embedding on the exact-match path too.
     */
    val nearMatches: List<EntityWithScore>,
) {
    val entity: EltmEntity
        get() = view.entity
}

/**
 * The whole-store content snapshot behind the transfer feature
 * (`EltmTransferService`): every entity (id ascending) with its
 * current-state attributes, every relationship (id ascending) and every
 * diary note (chronological). Pure content — no embeddings (recomputed
 * from the text on import) and no derived counts.
 */
data class EltmSnapshot(
    val entities: List<EltmEntity>,
    /** Per-entity current-state attributes (keys alphabetically ordered). */
    val attributes: Map<Long, EntityAttributes>,
    val relationships: List<EltmRelationship>,
    val notes: List<EltmNote>,
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
 * The note embedding text: currently the trimmed note itself — no decoration
 * (no date, no subject). The single point to change if the format ever
 * evolves; the re-embed job (`EmbeddingRefreshService`) and the write path
 * both derive the vector from the stored note through this function, so a
 * format change never strands old embeddings. The trimmed form is what the
 * service validates and stores, so the function applied to the stored row
 * reproduces exactly the text that was embedded.
 */
internal fun noteEmbeddingText(note: String): String = note.trim()

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

/**
 * The entity-batch up-front normalization ([PostgresEltmService.createEntities]'s
 * first step): one (canonical name, category) pair per entry, the whole
 * batch failing fast on a blank field — naming the offending entry's
 * index — before any lookup, embed or write.
 */
internal fun normalizeEntityDrafts(entries: List<EntityDraft>): List<Pair<String, String>> =
    entries.mapIndexed { index, (name, category) ->
        val canonical = normalizeName(name)
        val cat = category.trim().lowercase()
        require(canonical.isNotBlank()) { "entity name must not be blank (entry $index)" }
        require(cat.isNotBlank()) { "entity category must not be blank (entry $index)" }
        canonical to cat
    }

/**
 * The refine-target up-front normalization ([PostgresEltmService.refineEntity]'s
 * first step): a null keeps the current identity, a provided name/category
 * is canonicalized and must not be blank.
 */
internal fun normalizeRefineTarget(
    newName: String?,
    newCategory: String?,
): Pair<String?, String?> {
    val canonical = newName?.let {
        normalizeName(it).also { name ->
            require(name.isNotBlank()) { "entity name must not be blank" }
        }
    }
    val trimmedCategory = newCategory?.trim()?.lowercase()
    if (trimmedCategory != null) {
        require(trimmedCategory.isNotBlank()) { "entity category must not be blank" }
    }
    return canonical to trimmedCategory
}

/**
 * The attribute-batch up-front normalization ([PostgresEltmService.setEntityAttributes]'s
 * first step): one canonical `(key, single-line non-blank value)` entry per
 * input, later keys winning on a canonical-key collision (the map's own
 * semantics). An empty result is the caller's no-op batch.
 */
internal fun normalizeAttributeValues(values: Map<String, String>): LinkedHashMap<String, String> {
    val normalized = LinkedHashMap<String, String>()
    for ((key, value) in values) {
        val k = normalizeAttributeKey(key)
        val v = value.trim()
        require(k.isNotBlank()) { "attribute key must not be blank" }
        require(v.isNotBlank()) { "attribute value must not be blank" }
        // the value is appended to the entity embedding text as a single
        // `key: value` line: a newline would corrupt the line structure
        require(v.none { it == '\n' || it == '\r' }) {
            "attribute value must be a single line"
        }
        normalized[k] = v
    }
    return normalized
}

private val WHITESPACE_REGEX = Regex("\\s+")

/**
 * The ELTM (external long-term memory) store: entities, attributes,
 * relationships, and diary notes (the diary model, see `V1__init.sql`).
 * Written by the extraction pipeline only
 * (`agent/pipeline/eltm/EltmWriterService.kt`); read by the writer,
 * the context injection's searches and the investigate sub-agent.
 *
 * All embeddings go through the hand (`hand/HandService.embed`), are
 * zero-padded to the fixed column width ([MAX_VECTOR_DIMENSIONS]) on write,
 * and queries are padded identically — cosine similarity is invariant under
 * zero-padding, so switching embedding models never needs a schema change.
 *
 * The paged reads ([listEntities], [findEntities], [listRelationships],
 * [getEntityNotes], [getRelationshipNotes]) use classic limit/offset
 * paging DELIBERATELY, not
 * the keyset cursors of `GET /api/chats` (see `PostgresChatStore.listChats`):
 * chats need keyset because a chat can be deleted mid-walk while its
 * consumer only re-reads the newest page, so a skipped chat stays missed.
 * Here rows leave a list only when the background writer merges entities
 * (the loser entity is deleted, some relationships pruned, notes
 * re-pointed — the diary notes themselves are add-only), which is rare and
 * bounded: the browse UI refetches its whole loaded window on every resync
 * (frontend `PagedTab.resync`), so a live row shifted across a page
 * boundary is picked up on the next tick; the LLM readers' primary recall
 * path is semantic search, not offset walks; and the planned GC-style
 * maintenance runs under full app shutdown, so no bulk deletes happen while
 * serving. Accepted drawback: an in-flight offset walk can skip a live row
 * adjacent to a merge until the next resync.
 */
interface EltmService {
    /**
     * Create or fetch an entity by `(normalizeName(name), category)`: an
     * exact match is a pure read — nothing is updated here (identity
     * changes go through [refineEntity] instead); the prominence signal is
     * the read views' computed note/relationship counts — there is nothing
     * else to touch. Otherwise the name+category text is embedded and
     * inserted, and the global write counter
     * (`gsg_meta_number.eltm_version`) is bumped in the same transaction
     * (a concurrent run's unique violation is caught and turned into a
     * re-select of the existing row — true create-or-fetch semantics, an
     * unhandled violation would fail the whole run as `tool_transport`).
     * The whole sequence — the key lookup, the hand embed call, the insert
     * and the near-match search — runs in ONE transaction, and the result
     * carries the row's full view plus the near matches, so a caller never
     * needs a follow-up read.
     * [EmbeddingException] of type `invalid_request` propagates for the tool
     * layer to map to a model-visible error.
     */
    suspend fun createEntity(name: String, category: String): CreateEntityResult

    /**
     * The bulk create-or-fetch of [EltmService.createEntity] over a whole
     * batch: the transfer import's entity pass (never the tool layer — one
     * tool call is one entity). The whole batch is ONE unit of work:
     *
     * - every entry is normalized and validated up front (a blank
     *   name/category fails the whole call before any work, naming the
     *   entry's index);
     * - the missing keys' texts ride the hand's batched `/v1/embed` — ONE
     *   batched call series for the whole batch ([attachNotesToEntity]'s
     *   embedding stance), never one embed call per entity;
     * - ONE transaction holds the key lookups, the embeds, the inserts and
     *   the re-selects (the connection is held across the embeds — the
     *   same stance as [setEntityAttributes]);
     * - ONE global write counter bump, and only when a row was really
     *   inserted (a conflict-adopted row is a pure read, per-entity
     *   semantics preserved);
     * - NO near matches (the only caller, the transfer import, deliberately
     *   keeps near-match disambiguation out of scope — see
     *   `EltmTransferService`).
     *
     * An empty batch is a no-op (no transaction, no embed call). Duplicate
     * keys within the batch fold onto ONE row (create-or-fetch per key).
     *
     * @return the entities in INPUT order, one per entry (folded duplicates
     * repeat the same row).
     */
    suspend fun createEntities(entries: List<EntityDraft>): List<EltmEntity>

    /**
     * Rename ONE entity in place and/or change its category — e.g. a
     * briefly-mentioned "friend" later identified by name, or re-categorized
     * without a rename. The entity's id
     * stays: notes, relationships and attributes keep pointing at it, so a
     * refine is never a create+merge (those are only for true duplicates).
     *
     * A null [newName] keeps the current name, a null [newCategory] keeps
     * the current category; at least one of the two is expected to change
     * something. Like [setEntityAttributes], the whole read-modify-write with
     * the hand embed call inside runs in ONE transaction (the entity row
     * locked `FOR UPDATE` at the start), so the stored embedding always
     * matches the new name+category and the unchanged attributes. The
     * returned view (counts, latest note, attributes) is computed inside
     * the SAME transaction — a caller never needs a follow-up read (a
     * no-op refine is still a pure read: no embed, no bump).
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
    ): EntityView

    /**
     * Create or fetch a relationship by `(srcId, verb, dstId)`. There is
     * exactly ONE row per triple (full unique index) — an existing row,
     * ACTIVE OR INVALIDATED, is a pure read returned as-is: `valid` never
     * changes here (re-establishing an ended relationship is a diary event,
     * see [attachNotesToRelationship]); otherwise the triple is inserted (a
     * concurrent run's unique violation is caught and turned into a
     * re-select, like [createEntity]). Only real inserts bump the global
     * write counter in the same transaction. The whole sequence — the
     * endpoint checks, the find-or-insert and the returned view's reads —
     * runs in ONE transaction (no embed call exists on this path), and the
     * returned view carries the resolved relationship, the note count and
     * the latest note, so a caller never needs a follow-up read.
     */
    suspend fun createRelationship(srcId: Long, dstId: Long, verb: String): RelationshipView

    /**
     * The bulk create-or-fetch of [EltmService.createRelationship] over a
     * whole batch: the transfer import's relationship pass (never the tool
     * layer). Like [createEntities], the whole batch is ONE unit of work:
     * every verb is normalized and every endpoint checked up front (ONE
     * query, the first missing id named), then ONE transaction holds the
     * triple lookups, the inserts and the re-selects, with ONE counter bump
     * iff a row was really inserted. No embeds exist on this path. An empty
     * batch is a no-op; duplicate triples within the batch fold onto ONE
     * row.
     *
     * @return the relationships in INPUT order, one per entry (folded
     * duplicates repeat the same row).
     */
    suspend fun createRelationships(triples: List<RelationshipDraft>): List<EltmRelationship>

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
     * Append dated diary notes to ONE entity in ONE transaction: the notes
     * are embedded through the hand's batched `/v1/embed` (never one call
     * per note; the store caps each call's input size) and the rows
     * appended add-only (no update/delete methods exist) with ONE
     * global write counter bump. The subject is checked before any embed
     * call, so a missing one fails fast with a clear message. An empty
     * batch is a no-op. Notes are trimmed and must be non-blank.
     *
     * @throws EmbeddingException of type `invalid_request` (content too large
     * for the embedding model) propagates to the caller.
     */
    @Throws(EmbeddingException::class)
    suspend fun attachNotesToEntity(
        entityId: Long,
        notes: List<NoteDraft>,
    ): List<EltmNote>

    /**
     * The single-note form of [attachNotesToEntity] — the batch semantics
     * (batched embed calls, one transaction, one counter bump, add-only)
     * live there.
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
     * Append dated diary notes to ONE relationship — [attachNotesToEntity]'s
     * batch semantics (batched embed calls, one transaction, ONE counter
     * bump; the subject checked before any embed call). When [valid] is
     * non-null, the structural change of the relationship rides the SAME
     * transaction: `false` CLOSES it (the event ended the edge — e.g. "left
     * the company"; a note must explain the ending), `true` RE-OPENS it (a
     * revival event — e.g. "rejoined the company"). Setting the current
     * state is a no-op (idempotent — the notes still attach either way),
     * and the compound event is still ONE bump. The diary is the content
     * truth: a bare structural change without a note carries no reason, so
     * [valid] requires at least one note — the transfer import's merge rule
     * is the only note-less validity write ([setRelationshipValid]). The
     * result carries the relationship's structural validity AFTER the
     * attach (the [valid] argument applied, or the row's unchanged state
     * when null), so a caller never needs a follow-up read.
     *
     * @throws IllegalArgumentException when [valid] is set with an empty
     * note batch.
     * @throws EmbeddingException of type `invalid_request` (content too large
     * for the embedding model) propagates to the caller.
     */
    @Throws(EmbeddingException::class)
    suspend fun attachNotesToRelationship(
        relationshipId: Long,
        notes: List<NoteDraft>,
        valid: Boolean? = null,
    ): RelationshipNotesResult

    /**
     * The single-note form of [attachNotesToRelationship] — the batch
     * semantics (including the [valid] structural change and the returned
     * post-attach validity) live there; the result's [notes][RelationshipNotesResult.notes]
     * holds exactly one entry.
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
    ): RelationshipNotesResult

    /**
     * Set a relationship's structural validity directly, WITHOUT a diary
     * note — the transfer import's merge rule only
     * (`EltmTransferService.importEltm`; the diary model's own paths always
     * ride a note via [attachNotesToRelationship], because a bare structural
     * change carries no reason). Setting the current state is a no-op
     * (pure read: no write, no counter bump).
     *
     * @return `true` when the value changed (a real write), `false` when the
     * row already held [valid].
     * @throws IllegalArgumentException when the relationship does not exist.
     */
    suspend fun setRelationshipValid(relationshipId: Long, valid: Boolean): Boolean

    /**
     * Set several current-state facts (key-value attributes) on ONE entity
     * in ONE transaction: one row per `(entity, key)` — a new key inserts,
     * an existing key OVERWRITES the value (attributes are facts, not a
     * diary; the notes are the diary). Keys are canonicalized like
     * [normalizeAttributeKey]; values are trimmed and must be non-blank
     * single lines. Setting a key to its identical value is a no-op for
     * that key. An empty map is a no-op — no existence check, no write.
     *
     * The whole batch is ONE read-modify-write: ONE transaction (the entity
     * row locked `FOR UPDATE` at the start, the hand embed call included —
     * the connection is held across the embed, the price of consistency),
     * ONE embed of the final `name + category` + all-attributes text (the
     * text is `entityEmbeddingText`: the attributes as `key: value` lines,
     * alphabetically by key — the embedding never re-runs per key), ONE
     * global write counter bump. A concurrent attribute write serializes
     * against the whole sequence instead of racing the embed, so the stored
     * embedding can never diverge from the attribute rows. A batch whose
     * every key is already set to its identical value touches nothing (pure
     * read: no embed call, no counter bump).
     *
     * Two raw keys that canonicalize alike fold onto ONE entry — the later
     * value wins (the map's own semantics; one row per `(entity, key)`).
     *
     * @return how many keys actually changed (a real write); the rest were
     * already set to exactly their value.
     * @throws EmbeddingException of type `invalid_request` (the composed
     * content too large for the embedding model) propagates to the caller,
     * rolled back before anything moved.
     */
    @Throws(EmbeddingException::class)
    suspend fun setEntityAttributes(entityId: Long, values: Map<String, String>): Int

    /**
     * The single-key form of [setEntityAttributes] — the batch semantics
     * (one transaction, one embed, one bump; a no-op set) live there.
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
     * bumps the global write counter — like [setEntityAttributes], the whole
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
     * The lexical counterpart of [searchEntities]: entities matching the
     * verbatim regex filters, AND-combined — [name] on the canonical name,
     * [category] on the category, [attr] on ANY of the entity's attributes
     * rendered as `key=value` lines. A null filter imposes no condition;
     * the whole-store browse is the all-null call. Ordered by id ascending
     * for a stable page, paginated via [limit]/[offset] (the interface
     * KDoc's deliberate limit/offset stance). No embedding is involved:
     * the filters are applied case-insensitively by PostgreSQL's `~*`
     * operator, so a known-name lookup (e.g. the canonical "user" entity)
     * is deterministic where the semantic search can miss — the entity
     * vector embeds the attributes too and drifts as they accumulate.
     *
     * The regex dialect is PostgreSQL's, and the caller-side validation
     * (see the tool layer's `ls_entities`) is a best-effort
     * [java.util.regex.Pattern] pre-check, not a dialect guarantee — the
     * mismatch cuts both ways: a pattern Kotlin accepts but PostgreSQL's
     * `~*` rejects is converted to an [IllegalArgumentException] INSIDE the
     * transaction (a deterministic failure must not be retried — see the
     * retry note in `db/Database.kt`), and a Postgres-valid pattern the
     * pre-check rejects is refused with a model-visible error (see the
     * tool layer's `regexFilterArg`).
     */
    suspend fun findEntities(
        name: String?,
        category: String?,
        attr: String?,
        limit: Int,
        offset: Int,
    ): List<EntityView>

    /**
     * All entities (whatever their prominence), ordered by id ascending for
     * a stable page, each with its note and relationship counts and its
     * latest diary note inline. Paginated via [limit]/[offset] — the
     * frontend ELTM view's browse-all surface (the LLM readers reach the
     * store through [searchEntities] / [findEntities] instead).
     */
    suspend fun listEntities(limit: Int, offset: Int): List<EntityView>

    /**
     * All relationships (active and invalidated), ordered by id ascending,
     * each resolved with its note count and its latest diary note inline.
     * Paginated via [limit]/[offset].
     */
    suspend fun listRelationships(limit: Int, offset: Int): List<RelationshipView>

    /**
     * The whole-store content snapshot for the transfer feature
     * (`EltmTransferService`): all entities with their attributes, all
     * relationships, all diary notes — [EltmSnapshot] for the exact
     * contents and ordering. Read in ONE transaction at REPEATABLE READ —
     * a true snapshot: under the default READ COMMITTED each statement of
     * this multi-query read would take its own snapshot, and the
     * extraction pipeline committing between them would leak a
     * relationship whose endpoint entity the entity select never saw
     * (breaking the export's uuid join) or notes for a subject outside
     * the snapshot (silently dropped from the backup). Deliberately NOT
     * selecting the embedding columns: the vectors dominate the row size
     * and the import re-embeds through the local hand, so they never
     * travel.
     */
    suspend fun exportAll(): EltmSnapshot

    /**
     * The entity with its latest diary note inline plus its note and
     * relationship counts, or null when missing.
     */
    suspend fun getEntity(id: Long): EntityView?

    /**
     * The entity's relationships in BOTH directions, each resolved with
     * its latest note inline and its note count; active only unless
     * [includeInvalid].
     */
    suspend fun getRelationships(entityId: Long, includeInvalid: Boolean): List<RelationshipView>

    /**
     * One relationship with its endpoints resolved, its latest note
     * inline, and its note count, or null when missing.
     */
    suspend fun getRelationship(id: Long): RelationshipView?

    /**
     * The entities for a batch of ids in ONE transaction — the batched
     * counterpart of [getEntity] for readers that only need the identity
     * (name + category), like the context injection's related-note
     * resolution. Only the content columns are read (no counts, no latest
     * note, no attributes). An empty input answers an empty map with no
     * query; ids with no row are absent from the map.
     */
    suspend fun getEntitiesByIds(ids: List<Long>): Map<Long, EltmEntity>

    /**
     * The [ResolvedRelationship]s for a batch of relationship ids in ONE
     * transaction — the batched counterpart of [getRelationship] for
     * readers that only need the resolved identity (no note count, no
     * latest note), like the context injection's related-note resolution.
     * One bounded `inList` read per `BULK_QUERY_CHUNK_SIZE` chunk (the
     * rows, then their endpoints), never the page builder's batch queries.
     * An empty input answers an empty map with no query; ids with no row
     * are absent from the map. A found row whose endpoint is gone fails
     * fast (a broken state or a concurrent merge landing mid-read — see
     * `toRelationshipViews` in `EltmRelationshipQueries.kt`), never a
     * silent drop.
     */
    suspend fun getResolvedRelationships(ids: List<Long>): Map<Long, ResolvedRelationship>

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
     * The chat loop's combined retrieval: [searchEntities] and
     * [searchNotes] over ONE embedded [query] — the same rewritten query
     * feeds both halves, so embedding it once (the hand's `/v1/embed` is a
     * network round trip per call) halves the embedding cost of every
     * memory-injecting chat run. Each half keeps its own method's
     * semantics: entities use the `entityMatchThreshold`, notes the
     * `noteSearchThreshold`, no subject/date filters (the injection
     * retrieval has none). A zero [entityLimit]/[noteLimit] skips that half
     * (no rows, and when BOTH are zero no embed call and no query at all).
     *
     * @throws IllegalArgumentException when the query is blank or a limit
     * is negative.
     */
    suspend fun searchEntitiesAndNotes(
        query: String,
        entityLimit: Int,
        noteLimit: Int,
    ): EltmSearchHits

    /**
     * The current ELTM version, read from the store: the global write
     * counter (`gsg_meta_number.eltm_version`), bumped atomically by
     * every visible-state write inside ITS transaction. NOT a
     * content hash — any write that changes the visible state moves it.
     * Compared against `chats.eltm_version` for the `eltm-updated`
     * injection flag. A loose indicator for the LLM that the ELTM has been
     * updated and info in the context **might** be outdated — the store is
     * never fetched as a whole, so a whole-store snapshot check is impossible,
     * and the plain version is the right (cheap) signal.
     */
    suspend fun version(): String
}

