package info.skyblond.daapu.memory.eltm.postgres

import info.skyblond.daapu.agent.model.EmbeddingModel
import info.skyblond.daapu.config.MAX_VECTOR_DIMENSIONS
import info.skyblond.daapu.db.*
import info.skyblond.daapu.hand.HandRunPolicy
import info.skyblond.daapu.hand.HandService
import info.skyblond.daapu.memory.eltm.CreateEntityResult
import info.skyblond.daapu.memory.eltm.EltmEntity
import info.skyblond.daapu.memory.eltm.EltmNote
import info.skyblond.daapu.memory.eltm.EltmRelationship
import info.skyblond.daapu.memory.eltm.EltmSearchHits
import info.skyblond.daapu.memory.eltm.EltmService
import info.skyblond.daapu.memory.eltm.EltmSnapshot
import info.skyblond.daapu.memory.eltm.EntityDraft
import info.skyblond.daapu.memory.eltm.EntityView
import info.skyblond.daapu.memory.eltm.EntityWithScore
import info.skyblond.daapu.memory.eltm.NoteDraft
import info.skyblond.daapu.memory.eltm.RelationshipDraft
import info.skyblond.daapu.memory.eltm.RelationshipNotesResult
import info.skyblond.daapu.memory.eltm.RelationshipView
import info.skyblond.daapu.memory.eltm.entityEmbeddingText
import info.skyblond.daapu.memory.eltm.normalizeAttributeKey
import info.skyblond.daapu.memory.eltm.normalizeName
import info.skyblond.daapu.memory.eltm.normalizeVerb
import info.skyblond.daapu.memory.eltm.noteEmbeddingText
import info.skyblond.daapu.memory.eltm.planAttributeFold
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import java.sql.Connection
import java.time.LocalDate

/**
 * Postgres-backed [EltmService] over the `eltm_entities` /
 * `eltm_entity_attributes` / `eltm_relationships` / `eltm_notes` tables
 * (`V1__init.sql`).
 *
 * The entity embedding is `embed(canonical_name || ' ' || category)`, plus
 * the attributes as `key: value` lines alphabetically by key
 * ([entityEmbeddingText]) — attribute writes re-embed the entity, so facts
 * are semantically searchable.
 *
 * Embeddings go through the hand ([HandService.embed]) and are zero-padded
 * to the fixed column width ([MAX_VECTOR_DIMENSIONS]) on write; similarity
 * queries use the pgvector cosine distance helper
 * ([VectorColumnType.cosineDistance], rendered as the `<=>` operator) with
 * the query vector padded identically — cosine similarity is invariant under
 * zero-padding.
 *
 * Decision logic worth unit-testing (normalization, merge/collision
 * planning) lives outside the SQL; the SQL paths are covered by the
 * DB-backed `PostgresEltmServiceTest` (throwaway testcontainers database).
 *
 * The service owns the transaction composition, the validation and the
 * embedding calls; the ambient-transaction SQL bodies (row mappers,
 * finders, create-or-fetch inserts, view builders, the batched note
 * insert, the search SQL bodies, the merge's fold-and-delete writes)
 * live in the sibling files `EltmEntityQueries.kt` /
 * `EltmRelationshipQueries.kt` / `EltmNoteQueries.kt` /
 * `EltmMergeQueries.kt` (same package, internal — every call is inside
 * withTransaction).
 */
class PostgresEltmService(
    private val embeddingModel: EmbeddingModel,
    private val hand: HandService,
    private val entityMatchThreshold: Double,
    private val noteSearchThreshold: Double,
    private val policy: HandRunPolicy,
) : EltmService {

    // ------------------------------------------------------------------
    // writes
    // ------------------------------------------------------------------

    override suspend fun createEntity(name: String, category: String): CreateEntityResult {
        val canonical = normalizeName(name)
        val cat = category.trim().lowercase()
        require(canonical.isNotBlank()) { "entity name must not be blank" }
        require(cat.isNotBlank()) { "entity category must not be blank" }

        // ONE transaction for the whole create-or-fetch — the key lookup,
        // the hand embed call, the insert, the near-match search and the
        // returned view's reads. The connection is held across the embed
        // (the same stance as setEntityAttributes); nothing is locked at
        // embed time (a plain SELECT takes no locks under READ COMMITTED),
        // so the embed-inside-transaction adds no deadlock surface.
        return withTransaction {
            val row = findEntityByKey(canonical, cat) ?: run {
                val embedding = embedText(entityEmbeddingText(canonical, cat, emptyMap()))
                // create-or-fetch in ONE transaction: the ON CONFLICT DO
                // NOTHING RETURNING insert, its re-select semantics and
                // the untargeted-clause caveat live on insertEntityRow
                // (EltmEntityQueries.kt)
                val inserted = insertEntityRow(canonical, cat, embedding)
                if (inserted != null) {
                    bumpWriteVersion()
                    inserted
                } else {
                    findEntityByKey(canonical, cat)
                        ?: error("unique conflict but the entity ($canonical, $cat) is not visible")
                }
            }

            val entity = row.toEntity()
            val nearMatches = row[EltmEntities.embedding]?.let { stored ->
                similarEntities(
                    queryVector = stored,
                    excludeId = entity.id,
                    threshold = entityMatchThreshold,
                    limit = NEAR_MATCH_LIMIT
                )
            } ?: emptyList()
            // the view rides the SAME transaction — the caller never pays
            // a read-after-write round trip
            CreateEntityResult(view = entityViewOf(entity), nearMatches = nearMatches)
        }
    }

    override suspend fun createEntities(entries: List<EntityDraft>): List<EltmEntity> {
        if (entries.isEmpty()) return emptyList()
        // normalize/validate every entry up front (fail fast before any
        // work), naming the offending index
        val normalized = entries.mapIndexed { index, (name, category) ->
            val canonical = normalizeName(name)
            val cat = category.trim().lowercase()
            require(canonical.isNotBlank()) { "entity name must not be blank (entry $index)" }
            require(cat.isNotBlank()) { "entity category must not be blank (entry $index)" }
            canonical to cat
        }
        // the bulk create-or-fetch (the batch unit's semantics:
        // EltmService.createEntities): ONE transaction holds the batched
        // key lookups, the batched embeds, the per-key ON CONFLICT inserts
        // and the re-selects — the connection is held across the embeds,
        // the same stance as setEntityAttributes. The SQL body (the
        // conflict adoption included) lives in
        // EltmEntityQueries.bulkCreateOrFetchEntities.
        return withTransaction {
            val (rows, insertedAny) = bulkCreateOrFetchEntities(normalized) { missing ->
                // the missing keys' texts ride the hand's batched /v1/embed —
                // ONE batched call series for the whole batch, never one
                // embed call per entity (see embedAll)
                embedAll(missing.map { (name, cat) -> entityEmbeddingText(name, cat, emptyMap()) })
            }
            // only a real insert bumps the write counter — a fully
            // conflict-adopted batch is a pure read (per-entity
            // semantics preserved)
            if (insertedAny) bumpWriteVersion()
            rows.map { it.toEntity() }
        }
    }

    override suspend fun refineEntity(
        entityId: Long, newName: String?, newCategory: String?,
    ): EntityView {
        val canonical = newName?.let {
            normalizeName(it).also { name ->
                require(name.isNotBlank()) { "entity name must not be blank" }
            }
        }
        val trimmedCategory = newCategory?.trim()?.lowercase()
        if (trimmedCategory != null) {
            require(trimmedCategory.isNotBlank()) { "entity category must not be blank" }
        }
        // like setEntityAttributes: ONE transaction for the whole
        // read-modify-write, the hand embed call included — the connection is
        // held across the embed. The entity row is locked FOR UPDATE before
        // the collision check, so the rename can never race a concurrent
        // write on the same entity (or a merge). A no-op refine is a pure
        // read: no embedding call, no counter bump. The returned view rides
        // the SAME transaction — the caller never pays a read-after-write
        // round trip.
        return withTransaction {
            val row = findEntityRowByIdForUpdate(entityId)
                ?: throw IllegalArgumentException("entity $entityId does not exist")
            val currentName = row[EltmEntities.canonicalName]
            val currentCat = row[EltmEntities.category]
            val newCat = trimmedCategory ?: currentCat
            val newCanonical = canonical ?: currentName
            if (currentName == newCanonical && currentCat == newCat) {
                // identical (name, category): nothing changes — a refine that
                // only echoes the current state is a pure read
                return@withTransaction entityViewOf(row.toEntity())
            }
            // fail fast on a collision before the embed: the target
            // (name, category) is already another entity's key — the caller
            // must merge the two instead (an unhandled unique violation on
            // the UPDATE below would escape as a raw SQL error)
            checkNoNameCollision(entityId, newCanonical, newCat)
            val embedding = embedText(
                entityEmbeddingText(
                    newCanonical,
                    newCat,
                    attributesOf(entityId),
                )
            )
            try {
                EltmEntities.update({ EltmEntities.id eq entityId }) {
                    it[EltmEntities.canonicalName] = newCanonical
                    it[EltmEntities.category] = newCat
                    it[EltmEntities.embedding] = embedding
                }
            } catch (e: Exception) {
                if (!e.isUniqueViolation()) throw e
                // a concurrent run created the target (name, category)
                // between the check above and the UPDATE: the update rolled
                // back AND the transaction is now aborted (PostgreSQL refuses
                // every further statement in it, so no re-check is possible
                // here) — raise the merge-instead error DIRECTLY (the racing
                // row's id cannot be included: it is not queryable from an
                // aborted transaction). NEVER let a raw SQLException escape:
                // one that escapes this withTransaction block makes Exposed
                // re-run the whole block (the hand embed call included) up to
                // defaultMaxAttempts (3) times; an IllegalArgumentException
                // is terminal and maps to the tool layer's model-visible
                // error.
                throw IllegalArgumentException(
                    "an entity \"$newCanonical\" (category $newCat) already exists " +
                            "as another entity: merge the two instead",
                    e,
                )
            }
            bumpWriteVersion()
            entityViewOf(EltmEntity(entityId, newCanonical, newCat))
        }
    }

    override suspend fun createRelationship(
        srcId: Long, dstId: Long, verb: String
    ): RelationshipView {
        val v = normalizeVerb(verb)
        require(v.isNotBlank()) { "relationship verb must not be blank" }
        // ONE transaction for the whole create-or-fetch — the endpoint
        // checks, the find-or-insert and the returned view's reads (no
        // embed exists on this path). The endpoint check rides the SAME
        // transaction, which also decides the missing id — no second
        // re-query, and the message matches the state that failed the
        // check. The returned view rides the same transaction too, so the
        // caller never pays a read-after-write round trip.
        return withTransaction {
            val missingEntityId = when {
                findEntityById(srcId) == null -> srcId
                findEntityById(dstId) == null -> dstId
                else -> null
            }
            require(missingEntityId == null) { "entity $missingEntityId does not exist" }
            // exactly ONE row per triple: the row IS the relationship — an
            // existing row (active OR invalidated) is a pure read returned
            // as-is; validity only moves with a diary event
            // (attachNotesToRelationship's valid flag), never here.
            // The insert rides ON CONFLICT DO NOTHING RETURNING (see
            // insertRelationshipRow in EltmRelationshipQueries.kt), so a
            // concurrent same-triple insert never aborts the transaction,
            // and the re-select below stays in it.
            val rel = findRelationshipByTriple(srcId, v, dstId)?.toRelationship() ?: run {
                val insertedId = insertRelationshipRow(srcId, dstId, v)
                if (insertedId != null) {
                    bumpWriteVersion()
                    // a fresh row always starts active (the column default)
                    // — constructed directly, no trailing re-read (a re-read
                    // in a SEPARATE transaction could even miss the row: a
                    // concurrent merge folding it away between the two)
                    EltmRelationship(insertedId, srcId, dstId, v, valid = true)
                } else {
                    findRelationshipByTriple(srcId, v, dstId)?.toRelationship()
                        ?: error("unique conflict but the relationship ($srcId -[$v]-> $dstId) is not visible")
                }
            }
            relationshipViewOf(rel)
        }
    }

    override suspend fun createRelationships(triples: List<RelationshipDraft>): List<EltmRelationship> {
        if (triples.isEmpty()) return emptyList()
        // normalize every verb up front (fail fast before any work, naming
        // the entry's index)
        val normalized = triples.mapIndexed { index, (srcId, verb, dstId) ->
            val v = normalizeVerb(verb)
            require(v.isNotBlank()) { "relationship verb must not be blank (entry $index)" }
            Triple(srcId, v, dstId)
        }
        // the bulk create-or-fetch (the batch unit's semantics:
        // EltmService.createRelationships): ONE transaction holds the
        // endpoint check, the batched triple lookups, the per-triple ON
        // CONFLICT inserts and the re-selects — no embed exists on this
        // path. The SQL body (the endpoint check and the conflict
        // adoption included) lives in
        // EltmRelationshipQueries.bulkCreateOrFetchRelationships.
        return withTransaction {
            val (rels, insertedAny) = bulkCreateOrFetchRelationships(normalized)
            // only a real insert bumps the write counter — a fully
            // conflict-adopted batch is a pure read
            if (insertedAny) bumpWriteVersion()
            rels
        }
    }

    override suspend fun attachNotesToEntity(
        entityId: Long,
        notes: List<NoteDraft>,
    ): List<EltmNote> {
        val drafts = notes.map { it.validated() }
        if (drafts.isEmpty()) return emptyList()
        // fail fast on a missing subject with a clear message before the
        // embed call (the FK would catch it later with a SQL error)
        require(withTransaction { findEntityById(entityId) != null }) {
            "entity $entityId does not exist"
        }
        // the batch's embeddings ride the hand's batched /v1/embed — never
        // one call per note (see EltmService)
        val embeddings = embedAll(drafts.map { noteEmbeddingText(it.note) })
        return withTransaction {
            val notes = insertNotes(
                entityId = entityId,
                relationshipId = null,
                drafts = drafts,
                embeddings = embeddings,
            )
            bumpWriteVersion()
            notes
        }
    }

    override suspend fun attachNoteToEntity(
        entityId: Long, eventDate: LocalDate, note: String,
    ): EltmNote = attachNotesToEntity(entityId, listOf(NoteDraft(eventDate, note))).single()

    override suspend fun attachNotesToRelationship(
        relationshipId: Long,
        notes: List<NoteDraft>,
        valid: Boolean?,
    ): RelationshipNotesResult {
        val drafts = notes.map { it.validated() }
        // a bare structural change carries no reason (see EltmService): a
        // [valid] flip must ride at least one note — setRelationshipValid is
        // the only note-less validity write
        require(drafts.isNotEmpty() || valid == null) {
            "a bare structural change carries no reason: attach a note or use setRelationshipValid"
        }
        // fail fast on a missing subject with a clear message before the
        // embed call (the FK would catch it later with a SQL error); the
        // check also reads the current validity — the post-attach state the
        // result reports when no [valid] argument moves it
        val currentValid = withTransaction {
            val rel = findRelationshipById(relationshipId)
                ?: throw IllegalArgumentException("relationship $relationshipId does not exist")
            rel.valid
        }
        if (drafts.isEmpty()) {
            // a degenerate no-op (no notes, no validity change): nothing
            // embeds, nothing writes, nothing bumps — a pure read
            return RelationshipNotesResult(emptyList(), currentValid)
        }
        val embeddings = embedAll(drafts.map { noteEmbeddingText(it.note) })
        return withTransaction {
            if (valid != null) {
                // idempotent: setting the current state affects no row
                // (an already-closed edge stays closed, an already-valid
                // one stays valid) — the compound event still bumps once
                EltmRelationships.update({ EltmRelationships.id eq relationshipId }) {
                    it[EltmRelationships.valid] = valid
                }
            }
            val notes = insertNotes(
                entityId = null,
                relationshipId = relationshipId,
                drafts = drafts,
                embeddings = embeddings,
            )
            bumpWriteVersion()
            RelationshipNotesResult(
                notes = notes,
                valid = valid ?: currentValid,
            )
        }
    }

    override suspend fun attachNoteToRelationship(
        relationshipId: Long, eventDate: LocalDate, note: String, valid: Boolean?,
    ): RelationshipNotesResult = attachNotesToRelationship(
        relationshipId,
        listOf(NoteDraft(eventDate, note)),
        valid,
    )

    override suspend fun setEntityAttributes(entityId: Long, values: Map<String, String>): Int {
        // normalize/validate every entry up front (fail fast before any
        // write). One row per (entity, key): two raw keys that canonicalize
        // alike fold onto one entry, the later value wins (the map's own
        // semantics)
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
        if (normalized.isEmpty()) return 0
        // ONE transaction for the whole read-modify-write, the hand embed
        // call included: the connection is held across the embed (the price
        // of consistency). The entity row is locked FOR UPDATE before the
        // attribute read, so a concurrent attribute write on the same entity
        // (or a merge) blocks here and then sees the fresh state — the
        // embedding text and the attribute rows can never diverge. ONE embed
        // of the FINAL text for the whole batch (never per key), ONE bump.
        return withTransaction {
            // fail fast on a missing subject with a clear message before
            // the embed call (the FK would catch it later with a SQL error);
            // the FOR UPDATE lock is the serialization point
            val row = findEntityRowByIdForUpdate(entityId)
                ?: throw IllegalArgumentException("entity $entityId does not exist")
            val current = attributesOf(entityId)
            // a no-op key is a pure read for that key: only the changed
            // keys write, and an all-identical batch never embeds nor bumps
            val changed = normalized.filter { (k, v) -> current[k] != v }
            if (changed.isEmpty()) {
                return@withTransaction 0
            }
            // EmbeddingException (e.g. invalid_request) propagates to the
            // caller (rolled back)
            val embedding = embedText(
                entityEmbeddingText(
                    row[EltmEntities.canonicalName],
                    row[EltmEntities.category],
                    current + changed,
                )
            )
            // one row per (entity, key): the batched UPSERT and the
            // re-embedded vector ride writeEntityAttributes
            // (EltmEntityQueries.kt)
            writeEntityAttributes(entityId, changed, embedding)
            bumpWriteVersion()
            changed.size
        }
    }

    override suspend fun setEntityAttribute(entityId: Long, key: String, value: String): Boolean =
        setEntityAttributes(entityId, mapOf(key to value)) > 0

    override suspend fun deleteEntityAttribute(entityId: Long, key: String) {
        val k = normalizeAttributeKey(key)
        require(k.isNotBlank()) { "attribute key must not be blank" }
        // like setEntityAttributes: ONE transaction with the hand embed call
        // inside and the entity row locked FOR UPDATE at the start, so the
        // embedding always matches the surviving attributes
        withTransaction {
            val row = findEntityRowByIdForUpdate(entityId)
                ?: throw IllegalArgumentException("entity $entityId does not exist")
            val current = attributesOf(entityId)
            require(current.containsKey(k)) {
                "attribute \"$k\" does not exist on entity $entityId"
            }
            val embedding = embedText(
                entityEmbeddingText(
                    row[EltmEntities.canonicalName],
                    row[EltmEntities.category],
                    current - k,
                )
            )
            deleteEntityAttributeRow(entityId, k)
            updateEntityEmbedding(entityId, embedding)
            bumpWriteVersion()
        }
    }

    override suspend fun setRelationshipValid(relationshipId: Long, valid: Boolean): Boolean =
        withTransaction {
            // existence check + change detection, then the atomic UPDATE —
            // no FOR UPDATE lock needed: validity is not part of the
            // embedding text, so unlike setEntityAttributes a concurrent
            // write between the read and the update can never make a
            // stored embedding diverge (last write wins on a boolean). The
            // UPDATE's row count IS checked: a concurrent merge fold that
            // deleted the row between the read and the update fails fast
            // instead of reporting a write that wrote nothing (and bumping
            // the counter for it).
            val current = findRelationshipById(relationshipId)
                ?: throw IllegalArgumentException("relationship $relationshipId does not exist")
            if (current.valid == valid) {
                false
            } else {
                val updated = EltmRelationships.update({ EltmRelationships.id eq relationshipId }) {
                    it[EltmRelationships.valid] = valid
                }
                if (updated == 0) {
                    throw IllegalArgumentException("relationship $relationshipId no longer exists")
                }
                bumpWriteVersion()
                true
            }
        }

    override suspend fun mergeEntities(winnerId: Long, loserId: Long) = withTransaction {
        require(winnerId != loserId) { "cannot merge an entity into itself" }
        // the WHOLE merge — the reads, the fold's embed and the writes —
        // runs in ONE transaction, the hand embed call included: the
        // connection is held across the embed (the price of consistency).
        // Both entity rows are locked FOR UPDATE first, so the fold can
        // never race a concurrent attribute write: an attribute write on
        // either entity blocks here and then sees the merged state — the
        // loser's rows present at write time are exactly the ones folded
        // (no orphaned row silently cascade-deleted, no PK collision).
        // Winner wins a colliding key.
        // The two row locks are taken in ascending id order (not winner
        // first), so two opposite-direction concurrent merges (A→B and B→A)
        // acquire the locks in the same order and can never deadlock — the
        // second blocks on the first and then proceeds on the merged state.
        val firstId = minOf(winnerId, loserId)
        val secondId = maxOf(winnerId, loserId)
        val firstRow = findEntityRowByIdForUpdate(firstId)
            ?: throw IllegalArgumentException("entity $firstId does not exist")
        val secondRow = findEntityRowByIdForUpdate(secondId)
            ?: throw IllegalArgumentException("entity $secondId does not exist")
        // the row of [winnerId]/[loserId] among the two locked rows
        val winnerRow = if (firstId == winnerId) firstRow else secondRow
        val loserRow = if (firstId == winnerId) secondRow else firstRow
        val winnerName = winnerRow[EltmEntities.canonicalName]
        val winnerCat = winnerRow[EltmEntities.category]
        val winnerAttrs = attributesOf(winnerId)
        val loserAttrs = attributesOf(loserId)
        // the fold plan is the shared decision logic (the loser's unique
        // keys fold in, the colliding ones keep the winner's value), so the
        // writes below and the re-embed decision can never disagree
        val foldPlan = planAttributeFold(winnerAttrs, loserAttrs)
        // only re-embed when the fold actually changed the winner's
        // attribute text — an attribute-less merge reuses the stored vector
        val winnerEmbedding = if (foldPlan.changesText) {
            embedText(entityEmbeddingText(winnerName, winnerCat, foldPlan.winnerAttributes))
        } else null

        // the whole SQL fold-and-delete below — the loser's relationships
        // (the decision tree lives in EltmMergeQueries.kt), diary notes
        // and attributes, then the loser row itself — rides
        // executeEntityMerge inside this same transaction
        executeEntityMerge(winnerId, loserId, foldPlan, winnerEmbedding)
        // one bump for the whole transactional merge (the loser delete is
        // the reliable change signal; the re-points ride the same commit)
        bumpWriteVersion()
    }

    // ------------------------------------------------------------------
    // reads
    // ------------------------------------------------------------------

    override suspend fun searchEntities(query: String, limit: Int): List<EntityWithScore> {
        require(query.isNotBlank()) { "query must not be blank" }
        require(limit >= 1) { "limit must be >= 1, got $limit" }
        val q = embedText(query)
        return withTransaction {
            similarEntities(q, excludeId = null, entityMatchThreshold, limit)
        }
    }

    override suspend fun listEntities(limit: Int, offset: Int): List<EntityView> = withTransaction {
        requirePaging(limit, offset)
        // the whole page's counts, latest notes and attributes ride the
        // batch builder selectEntityViews (EltmEntityQueries.kt) instead
        // of the single-subject helpers' 5 per row (the single-subject
        // reads stay per-row: one row, five queries)
        selectEntityViews(limit, offset)
    }

    override suspend fun listRelationships(limit: Int, offset: Int): List<RelationshipView> =
        withTransaction {
            requirePaging(limit, offset)
            // the whole page's views ride the batch builder
            // selectRelationshipViews (EltmRelationshipQueries.kt)
            selectRelationshipViews(limit, offset)
        }

    override suspend fun exportAll(): EltmSnapshot = withTransaction(
        // REPEATABLE READ, not the default READ COMMITTED: the four SELECTs
        // below must be ONE snapshot — under READ COMMITTED each statement
        // takes its own, so the extraction pipeline committing between them
        // would leak a relationship whose endpoint entity the first select
        // never saw (breaking the export's uuid join) or notes for a
        // subject outside the snapshot (silently dropped from the backup).
        // See EltmService.exportAll. The read-only workload cannot hit the
        // write-conflict aborts REPEATABLE READ can raise.
        isolation = Connection.TRANSACTION_REPEATABLE_READ,
    ) {
        // the whole store (the snapshot rationale lives on
        // EltmService.exportAll); only the content columns — the embedding
        // vectors (2000 dims per row) never travel (the content-only
        // selects live in the query files)
        val entities = selectAllEntityContent()
        EltmSnapshot(
            entities = entities,
            attributes = attributesFor(entities.map { it.id }),
            relationships = selectAllRelationshipContent(),
            notes = selectAllNoteContent(),
        )
    }

    override suspend fun getEntity(id: Long): EntityView? = withTransaction {
        val entity = findEntityById(id) ?: return@withTransaction null
        entityViewOf(entity)
    }

    override suspend fun getRelationship(id: Long): RelationshipView? = withTransaction {
        val rel = findRelationshipById(id) ?: return@withTransaction null
        // the single-subject cheap helpers, NOT the page builder's batch
        // queries — one row must never pay for a page (and never
        // materialize the subject's whole diary, see noteCountsAndLatest)
        relationshipViewOf(rel)
    }

    override suspend fun entityExists(entityId: Long): Boolean =
        withTransaction { findEntityRowById(entityId) != null }

    override suspend fun relationshipExists(relationshipId: Long): Boolean =
        withTransaction { findRelationshipById(relationshipId) != null }

    override suspend fun getRelationships(
        entityId: Long,
        includeInvalid: Boolean,
    ): List<RelationshipView> = withTransaction {
        // the whole drill-down rides the batch builder
        // selectEntityRelationshipViews (EltmRelationshipQueries.kt), not
        // a per-row view build
        selectEntityRelationshipViews(entityId, includeInvalid)
    }

    override suspend fun getEntityNotes(
        entityId: Long,
        from: LocalDate?,
        to: LocalDate?,
        limit: Int,
        offset: Int,
    ): List<EltmNote> = noteQuery(EltmNotes.entityId, entityId, from, to, limit, offset)

    override suspend fun getRelationshipNotes(
        relationshipId: Long,
        from: LocalDate?,
        to: LocalDate?,
        limit: Int,
        offset: Int,
    ): List<EltmNote> = noteQuery(EltmNotes.relationshipId, relationshipId, from, to, limit, offset)

    override suspend fun searchNotes(
        query: String,
        entityId: Long?,
        relationshipId: Long?,
        from: LocalDate?,
        to: LocalDate?,
        limit: Int,
    ): List<EltmNote> {
        require(query.isNotBlank()) { "query must not be blank" }
        require(entityId == null || relationshipId == null) {
            "a note search accepts at most one subject"
        }
        require(limit >= 1) { "limit must be >= 1, got $limit" }
        require(from == null || to == null || !from.isAfter(to)) {
            "from must not be after to"
        }
        val q = embedText(query)
        return withTransaction {
            searchNotesByVector(q, noteSearchThreshold, entityId, relationshipId, from, to, limit)
        }
    }

    override suspend fun searchEntitiesAndNotes(
        query: String,
        entityLimit: Int,
        noteLimit: Int,
    ): EltmSearchHits {
        require(query.isNotBlank()) { "query must not be blank" }
        require(entityLimit >= 0) { "entityLimit must be >= 0, got $entityLimit" }
        require(noteLimit >= 0) { "noteLimit must be >= 0, got $noteLimit" }
        // both limits zero: nothing to retrieve — no embed call, no query
        if (entityLimit == 0 && noteLimit == 0) return EltmSearchHits(emptyList(), emptyList())
        // ONE embedded query feeds both halves (the whole point — see
        // EltmService.searchEntitiesAndNotes); the two searches ride ONE
        // transaction
        val q = embedText(query)
        return withTransaction {
            EltmSearchHits(
                entities = if (entityLimit > 0) {
                    similarEntities(q, excludeId = null, entityMatchThreshold, entityLimit)
                } else emptyList(),
                notes = if (noteLimit > 0) {
                    searchNotesByVector(q, noteSearchThreshold, null, null, null, null, noteLimit)
                } else emptyList(),
            )
        }
    }

    // ------------------------------------------------------------------
    // version
    // ------------------------------------------------------------------

    override suspend fun version(): String =
        withTransaction { currentWriteVersion() }.toString()

    // ------------------------------------------------------------------
    // shared helpers (ambient transaction: only called inside withTransaction)
    // ------------------------------------------------------------------

    /**
     * Atomically bump the global ELTM write counter
     * (`memory_meta_number.eltm_version`, see `db/MetaCounter.kt`) by one.
     * Called inside the same transaction as every visible-state write, so
     * the bump commits with the write — the version moves
     * exactly when the ELTM changes.
     */
    private fun bumpWriteVersion() = bumpMetaCounter(ELTM_VERSION_KEY)

    /** Read the global ELTM write counter (the write version). Ambient transaction. */
    private fun currentWriteVersion(): Long = readMetaCounter(ELTM_VERSION_KEY)

    // ------------------------------------------------------------------
    // embedding
    // ------------------------------------------------------------------

    /** The padded vector for ONE text (entity texts, search queries). */
    private suspend fun embedText(text: String): List<Float> =
        embedAll(listOf(text)).single()

    /**
     * The padded vectors for a whole batch of texts, at most
     * [EMBED_BATCH_SIZE] inputs per hand `/v1/embed` call — the batch is
     * the point (never one HTTP round trip per note), the cap keeps one
     * batch inside the embedding gateway's per-request input limits. An
     * empty batch calls nothing.
     */
    private suspend fun embedAll(texts: List<String>): List<List<Float>> =
        texts.chunked(EMBED_BATCH_SIZE).flatMap { chunk ->
            hand.embed(embeddingModel, chunk, policy).vectors
                .map { padVector(it, MAX_VECTOR_DIMENSIONS) }
        }

    companion object {
        private const val NEAR_MATCH_LIMIT = 5

        /**
         * Per-embed-call input cap: embedding gateways cap the `input`
         * array (and its total tokens), so an over-cap batch splits into
         * several calls instead of one request the gateway refuses.
         * Internal so the DB-backed tests can build an over-cap batch.
         */
        internal const val EMBED_BATCH_SIZE = 64
    }
}
