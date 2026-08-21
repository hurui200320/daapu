package info.skyblond.daapu.memory.eltm

import info.skyblond.daapu.agent.model.EmbeddingModel
import info.skyblond.daapu.config.MAX_VECTOR_DIMENSIONS
import info.skyblond.daapu.db.*
import info.skyblond.daapu.hand.HandService
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.Function
import org.jetbrains.exposed.v1.core.functions.vector.VectorDistance
import org.jetbrains.exposed.v1.core.functions.vector.VectorDistanceMetric
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.*
import java.sql.SQLException
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
 * queries use Exposed's built-in pgvector support ([VectorDistance] with
 * [VectorDistanceMetric.COSINE], rendered as the `<=>` operator) with the
 * query vector padded identically — cosine similarity is invariant under
 * zero-padding.
 *
 * Decision logic worth unit-testing (normalization, merge/collision
 * planning) lives outside the SQL; the queries stay covered only by
 * fakes until DB-backed integration tests exist.
 */
class PostgresEltmService(
    private val embeddingModel: EmbeddingModel,
    private val hand: HandService,
    private val entityMatchThreshold: Double,
    private val noteSearchThreshold: Double,
    private val maxRetries: Int,
    private val timeoutMs: Long,
) : EltmService {

    // ------------------------------------------------------------------
    // writes
    // ------------------------------------------------------------------

    override suspend fun createEntity(name: String, category: String): CreateEntityResult {
        val canonical = normalizeName(name)
        val cat = category.trim().lowercase()
        require(canonical.isNotBlank()) { "entity name must not be blank" }
        require(cat.isNotBlank()) { "entity category must not be blank" }

        val existing = withTransaction { findEntityByKey(canonical, cat) }
        val row = if (existing != null) {
            // exact match: create-or-fetch — pure read, nothing to touch (an
            // identity change goes through refineEntity, which keeps the id;
            // the prominence signal is the read views' note/relationship
            // counts), NO second embed call — the near matches
            // are computed from the row's stored embedding
            existing
        } else {
            val embedding = embedText(entityEmbeddingText(canonical, cat, emptyMap()))
            val id = withTransaction {
                try {
                    val inserted = EltmEntities.insert {
                        it[EltmEntities.canonicalName] = canonical
                        it[EltmEntities.category] = cat
                        it[EltmEntities.embedding] = embedding
                    } get EltmEntities.id
                    // bump the write counter atomically with the insert (only
                    // on a real insert; a collision below must not flag a change)
                    bumpWriteVersion()
                    inserted
                } catch (e: Exception) {
                    if (!isUniqueViolation(e)) throw e
                    // a concurrent run inserted the same (name, category)
                    // first: true create-or-fetch semantics — adopt the
                    // existing row
                    // (an unhandled violation would escape the tool callback
                    // and fail the whole run as tool_transport)
                    withTransaction { findEntityByKey(canonical, cat)?.get(EltmEntities.id) }
                        ?: throw IllegalStateException(
                            "unique violation but the existing entity is not visible", e
                        )
                }
            }
            withTransaction { findEntityRowById(id)!! }
        }

        val entity = row.toEntity()
        val nearMatches = row[EltmEntities.embedding]?.let { stored ->
            withTransaction {
                similarEntities(
                    queryVector = stored,
                    excludeId = entity.id,
                    threshold = entityMatchThreshold,
                    limit = NEAR_MATCH_LIMIT
                )
            }
        } ?: emptyList()
        return CreateEntityResult(entity, nearMatches)
    }

    override suspend fun refineEntity(
        entityId: Long, newName: String?, newCategory: String?,
    ): EltmEntity {
        val canonical = newName?.let {
            normalizeName(it).also { name ->
                require(name.isNotBlank()) { "entity name must not be blank" }
            }
        }
        val trimmedCategory = newCategory?.trim()?.lowercase()
        if (trimmedCategory != null) {
            require(trimmedCategory.isNotBlank()) { "entity category must not be blank" }
        }
        // like setEntityAttribute: ONE transaction for the whole
        // read-modify-write, the hand embed call included — the connection is
        // held across the embed. The entity row is locked FOR UPDATE before
        // the collision check, so the rename can never race a concurrent
        // write on the same entity (or a merge). A no-op refine is a pure
        // read: no embedding call, no counter bump.
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
                return@withTransaction row.toEntity()
            }
            // fail fast on a collision before the embed: the target
            // (name, category) is already another entity's key — the caller
            // must merge the two instead (an unhandled unique violation on
            // the UPDATE below would escape as a raw SQL error)
            findEntityByKey(newCanonical, newCat)?.let { existing ->
                if (existing[EltmEntities.id] != entityId) {
                    throw IllegalArgumentException(
                        "an entity \"$newCanonical\" (category $newCat) already exists " +
                            "as entity ${existing[EltmEntities.id]}: merge the two instead"
                    )
                }
            }
            // EmbeddingException (e.g. invalid_request) propagates for the
            // tool layer to map to a model-visible error (rolled back)
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
                if (!isUniqueViolation(e)) throw e
                // a concurrent run created the target (name, category)
                // between the check above and the UPDATE: the update rolled
                // back — re-raise the same collision error the pre-check
                // would have given (never a raw SQL error)
                findEntityByKey(newCanonical, newCat)?.let { existing ->
                    if (existing[EltmEntities.id] != entityId) {
                        throw IllegalArgumentException(
                            "an entity \"$newCanonical\" (category $newCat) already exists " +
                                "as entity ${existing[EltmEntities.id]}: merge the two instead"
                        )
                    }
                }
                throw e
            }
            bumpWriteVersion()
            EltmEntity(entityId, newCanonical, newCat)
        }
    }

    override suspend fun createRelationship(
        srcId: Long, dstId: Long, verb: String
    ): EltmRelationship {
        val v = normalizeVerb(verb)
        require(v.isNotBlank()) { "relationship verb must not be blank" }
        // fail fast on missing endpoints before the transaction (the FK
        // would catch them later with a raw SQL error)
        require(withTransaction { findEntityById(srcId) != null }) {
            "entity $srcId does not exist"
        }
        require(withTransaction { findEntityById(dstId) != null }) {
            "entity $dstId does not exist"
        }

        val id = withTransaction {
            // exactly ONE row per triple: the row IS the relationship — an
            // existing row (active OR invalidated) is a pure read returned
            // as-is; validity only moves with a diary event
            // (attachNoteToRelationship's valid flag), never here
            val existing = findRelationshipByTriple(srcId, v, dstId)
            if (existing != null) {
                existing[EltmRelationships.id]
            } else {
                try {
                    val inserted = EltmRelationships.insert {
                        it[EltmRelationships.srcId] = srcId
                        it[EltmRelationships.dstId] = dstId
                        it[EltmRelationships.verb] = v
                    } get EltmRelationships.id
                    // bump the write counter atomically with the insert
                    // (only on a real insert; a collision below must not
                    // flag a change)
                    bumpWriteVersion()
                    inserted
                } catch (e: Exception) {
                    if (!isUniqueViolation(e)) throw e
                    // a concurrent run inserted the same triple first: true
                    // create-or-fetch semantics — adopt the existing row (an
                    // unhandled violation would escape the tool callback
                    // and fail the whole run as tool_transport)
                    withTransaction {
                        findRelationshipByTriple(srcId, v, dstId)?.get(
                            EltmRelationships.id
                        )
                    } ?: throw IllegalStateException(
                        "unique violation but the relationship is not visible", e
                    )
                }
            }
        }
        return withTransaction { findRelationshipById(id)!! }
    }

    override suspend fun attachNoteToEntity(
        entityId: Long, eventDate: LocalDate, note: String,
    ): EltmNote {
        val trimmed = note.trim()
        require(trimmed.isNotBlank()) { "note must not be blank" }
        // fail fast on a missing subject with a clear message before the
        // embed call (the FK would catch it later with a SQL error)
        require(withTransaction { findEntityById(entityId) != null }) {
            "entity $entityId does not exist"
        }
        // EmbeddingException (e.g. invalid_request: content too large)
        // propagates for the tool layer to map to a model-visible error
        val embedding = embedText(trimmed)
        val id = withTransaction {
            val inserted = EltmNotes.insert {
                it[EltmNotes.entityId] = entityId
                it[EltmNotes.eventDate] = eventDate
                it[EltmNotes.note] = trimmed
                it[EltmNotes.embedding] = embedding
            } get EltmNotes.id
            bumpWriteVersion()
            inserted
        }
        return withTransaction { findNoteById(id)!! }
    }

    override suspend fun attachNoteToRelationship(
        relationshipId: Long, eventDate: LocalDate, note: String, valid: Boolean?,
    ): EltmNote {
        val trimmed = note.trim()
        require(trimmed.isNotBlank()) { "note must not be blank" }
        // fail fast on a missing subject with a clear message before the
        // embed call (the FK would catch it later with a SQL error)
        require(withTransaction { findRelationshipById(relationshipId) != null }) {
            "relationship $relationshipId does not exist"
        }
        // EmbeddingException (e.g. invalid_request: content too large)
        // propagates for the tool layer to map to a model-visible error
        val embedding = embedText(trimmed)
        val id = insertNoteWithValidity(relationshipId, eventDate, trimmed, embedding, valid)
        return withTransaction { findNoteById(id)!! }
    }

    /**
     * Insert the note and, when [valid] is non-null, set the relationship's
     * structural validity in the SAME transaction (ONE counter bump for the
     * compound event): `false` closes the edge, `true` re-opens it. There
     * is exactly ONE row per triple, so the change is always applied to the
     * row itself — no collision can arise.
     */
    private suspend fun insertNoteWithValidity(
        relationshipId: Long,
        eventDate: LocalDate,
        trimmed: String,
        embedding: List<Float>,
        valid: Boolean?,
    ): Long = withTransaction {
        if (valid != null) {
            // idempotent: setting the current state affects no row
            // (an already-closed edge stays closed, an already-valid
            // one stays valid) and never bumps on its own
            EltmRelationships.update({ EltmRelationships.id eq relationshipId }) {
                it[EltmRelationships.valid] = valid
            }
        }
        val inserted = EltmNotes.insert {
            it[EltmNotes.relationshipId] = relationshipId
            it[EltmNotes.eventDate] = eventDate
            it[EltmNotes.note] = trimmed
            it[EltmNotes.embedding] = embedding
        } get EltmNotes.id
        bumpWriteVersion()
        inserted
    }

    override suspend fun setEntityAttribute(entityId: Long, key: String, value: String): Boolean {
        val k = normalizeAttributeKey(key)
        val v = value.trim()
        require(k.isNotBlank()) { "attribute key must not be blank" }
        require(v.isNotBlank()) { "attribute value must not be blank" }
        // the value is appended to the entity embedding text as a single
        // `key: value` line: a newline would corrupt the line structure
        require(v.none { it == '\n' || it == '\r' }) {
            "attribute value must be a single line"
        }
        // ONE transaction for the whole read-modify-write, the hand embed
        // call included: the connection is held across the embed (the price
        // of consistency). The entity row is locked FOR UPDATE before the
        // attribute read, so a concurrent attribute write on the same entity
        // (or a merge) blocks here and then sees the fresh state — the
        // embedding text and the attribute row can never diverge. The no-op
        // set is a pure read: no embedding call, no counter bump.
        return withTransaction {
            // fail fast on a missing subject with a clear message before
            // the embed call (the FK would catch it later with a SQL error);
            // the FOR UPDATE lock is the serialization point
            val row = findEntityRowByIdForUpdate(entityId)
                ?: throw IllegalArgumentException("entity $entityId does not exist")
            val current = attributesOf(entityId)
            if (current[k] == v) {
                return@withTransaction false
            }
            // EmbeddingException (e.g. invalid_request) propagates for the
            // tool layer to map to a model-visible error (rolled back)
            val embedding = embedText(
                entityEmbeddingText(
                    row[EltmEntities.canonicalName],
                    row[EltmEntities.category],
                    current + (k to v),
                )
            )
            // one row per (entity, key): overwrite the value in place
            EltmEntityAttributes.upsert(
                keys = arrayOf(EltmEntityAttributes.entityId, EltmEntityAttributes.key),
            ) {
                it[EltmEntityAttributes.entityId] = entityId
                it[EltmEntityAttributes.key] = k
                it[EltmEntityAttributes.value] = v
            }
            EltmEntities.update({ EltmEntities.id eq entityId }) {
                it[EltmEntities.embedding] = embedding
            }
            bumpWriteVersion()
            true
        }
    }

    override suspend fun deleteEntityAttribute(entityId: Long, key: String) {
        val k = normalizeAttributeKey(key)
        require(k.isNotBlank()) { "attribute key must not be blank" }
        // like setEntityAttribute: ONE transaction with the hand embed call
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
            EltmEntityAttributes.deleteWhere {
                (EltmEntityAttributes.entityId eq entityId) and
                    (EltmEntityAttributes.key eq k)
            }
            EltmEntities.update({ EltmEntities.id eq entityId }) {
                it[EltmEntities.embedding] = embedding
            }
            bumpWriteVersion()
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
        // writes below and the re-embed decision can never drift from the
        // fakes' behavior
        val foldPlan = planAttributeFold(winnerAttrs, loserAttrs)
        // only re-embed when the fold actually changed the winner's
        // attribute text — an attribute-less merge reuses the stored vector
        // (an EmbeddingException propagates for the tool layer to map,
        // rolled back before anything moved)
        val winnerEmbedding = if (foldPlan.changesText) {
            embedText(entityEmbeddingText(winnerName, winnerCat, foldPlan.winnerAttributes))
        } else null

        val loserRels = EltmRelationships.selectAll().where {
            (EltmRelationships.srcId eq loserId) or (EltmRelationships.dstId eq loserId)
        }.toList()
        for (rel in loserRels) {
            val newSrc =
                if (rel[EltmRelationships.srcId] == loserId) winnerId
                else rel[EltmRelationships.srcId]
            val newDst =
                if (rel[EltmRelationships.dstId] == loserId) winnerId
                else rel[EltmRelationships.dstId]

            val survivor = findRelationshipByTriple(newSrc, rel[EltmRelationships.verb], newDst)
            when {
                // the re-pointed edge would become a self-loop (winner—winner,
                // from winner—loser, loser—winner or loser—loser): invalidate
                // instead of re-pointing — a self-loop is never meaningful.
                // A twin self-loop row may already exist (another loser edge
                // collapsed onto the same triple first): fold into it instead
                // of violating the unique index
                newSrc == newDst -> if (survivor != null) {
                    EltmNotes.update({ EltmNotes.relationshipId eq rel[EltmRelationships.id] }) {
                        it[EltmNotes.relationshipId] = survivor[EltmRelationships.id]
                    }
                    EltmRelationships.deleteWhere {
                        EltmRelationships.id eq rel[EltmRelationships.id]
                    }
                } else {
                    EltmRelationships.update({ EltmRelationships.id eq rel[EltmRelationships.id] }) {
                        it[EltmRelationships.srcId] = newSrc
                        it[EltmRelationships.dstId] = newDst
                        it[EltmRelationships.valid] = false
                    }
                }

                survivor != null && survivor[EltmRelationships.id] != rel[EltmRelationships.id] -> {
                    // collides with an existing row of the same triple
                    // (valid or not): re-point the duplicate's diary notes
                    // to the survivor, fold the validity (the survivor
                    // holds the edge if either row held it), and only
                    // THEN delete the duplicate row (the ON DELETE
                    // CASCADE must never destroy diary notes)
                    EltmNotes.update({ EltmNotes.relationshipId eq rel[EltmRelationships.id] }) {
                        it[EltmNotes.relationshipId] = survivor[EltmRelationships.id]
                    }
                    if (rel[EltmRelationships.valid] && !survivor[EltmRelationships.valid]) {
                        EltmRelationships.update({ EltmRelationships.id eq survivor[EltmRelationships.id] }) {
                            it[EltmRelationships.valid] = true
                        }
                    }
                    EltmRelationships.deleteWhere {
                        EltmRelationships.id eq rel[EltmRelationships.id]
                    }
                }

                // no collision (or the survivor IS this row): re-point
                else -> EltmRelationships.update({ EltmRelationships.id eq rel[EltmRelationships.id] }) {
                    it[EltmRelationships.srcId] = newSrc
                    it[EltmRelationships.dstId] = newDst
                }
            }
        }

        // re-point the loser's entity notes, fold its attributes per the
        // shared plan (the colliding rows are dropped: re-pointing them
        // would overwrite the winner's value on the composite PK; the
        // foldable rows re-point to the winner), then delete the loser row
        // (the cascade never fires: no note references the loser anymore)
        EltmNotes.update({ EltmNotes.entityId eq loserId }) {
            it[EltmNotes.entityId] = winnerId
        }
        val droppedKeys = foldPlan.droppedKeys.toList()
        if (droppedKeys.isNotEmpty()) {
            EltmEntityAttributes.deleteWhere {
                (EltmEntityAttributes.entityId eq loserId) and
                    (EltmEntityAttributes.key inList droppedKeys)
            }
        }
        val foldableKeys = foldPlan.foldableKeys.toList()
        if (foldableKeys.isNotEmpty()) {
            EltmEntityAttributes.update({
                (EltmEntityAttributes.entityId eq loserId) and
                    (EltmEntityAttributes.key inList foldableKeys)
            }) {
                it[EltmEntityAttributes.entityId] = winnerId
            }
        }
        if (winnerEmbedding != null) {
            EltmEntities.update({ EltmEntities.id eq winnerId }) {
                it[EltmEntities.embedding] = winnerEmbedding
            }
        }
        EltmEntities.deleteWhere { EltmEntities.id eq loserId }
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
        require(limit >= 1) { "limit must be >= 1, got $limit" }
        require(offset >= 0) { "offset must be >= 0, got $offset" }
        val entities = EltmEntities.selectAll()
            .orderBy(EltmEntities.id to SortOrder.ASC)
            .limit(limit)
            .offset(offset.toLong())
            .map { it.toEntity() }
        // a whole page's counts, latest notes and attributes in 3 batch
        // queries instead of the single-subject helpers' 5 per row (the
        // single-subject reads below stay per-row: one row, five queries)
        val noteSummary = noteCountsAndLatest(EltmNotes.entityId, entities.map { it.id })
        val relationshipCounts = relationshipCountsFor(entities.map { it.id })
        val attributes = attributesFor(entities.map { it.id })
        entities.map { entity ->
            EntityView(
                entity = entity,
                noteCount = noteSummary[entity.id]?.first ?: 0,
                relationshipCount = relationshipCounts[entity.id] ?: 0,
                latestNote = noteSummary[entity.id]?.second,
                attributes = attributes[entity.id] ?: emptyMap(),
            )
        }
    }

    override suspend fun listRelationships(limit: Int, offset: Int): List<RelationshipView> =
        withTransaction {
            require(limit >= 1) { "limit must be >= 1, got $limit" }
            require(offset >= 0) { "offset must be >= 0, got $offset" }
            val rels = EltmRelationships.selectAll()
                .orderBy(EltmRelationships.id to SortOrder.ASC)
                .limit(limit)
                .offset(offset.toLong())
                .map { it.toRelationship() }
            if (rels.isEmpty()) return@withTransaction emptyList()
            // a whole page's endpoint names, note counts and latest notes in
            // 2 queries instead of toRelationshipView's 4 per row
            val names = EltmEntities.selectAll()
                .where { EltmEntities.id inList rels.flatMap { listOf(it.srcId, it.dstId) } }
                .associate { it[EltmEntities.id] to it[EltmEntities.canonicalName] }
            val noteSummary = noteCountsAndLatest(EltmNotes.relationshipId, rels.map { it.id })
            rels.map { rel ->
                RelationshipView(
                    relationship = rel,
                    srcName = names[rel.srcId] ?: "<deleted entity ${rel.srcId}>",
                    dstName = names[rel.dstId] ?: "<deleted entity ${rel.dstId}>",
                    noteCount = noteSummary[rel.id]?.first ?: 0,
                    latestNote = noteSummary[rel.id]?.second,
                )
            }
        }

    override suspend fun getEntity(id: Long): EntityView? = withTransaction {
        val entity = findEntityById(id) ?: return@withTransaction null
        EntityView(
            entity = entity,
            noteCount = countNotesForEntity(id),
            relationshipCount = countRelationshipsForEntity(id),
            latestNote = latestNoteForEntity(id),
            attributes = attributesOf(id),
        )
    }

    override suspend fun getRelationship(id: Long): RelationshipView? = withTransaction {
        val rel = findRelationshipById(id) ?: return@withTransaction null
        toRelationshipView(rel)
    }

    override suspend fun entityExists(entityId: Long): Boolean =
        withTransaction { findEntityRowById(entityId) != null }

    override suspend fun relationshipExists(relationshipId: Long): Boolean =
        withTransaction { findRelationshipById(relationshipId) != null }

    override suspend fun getRelationships(
        entityId: Long,
        includeInvalid: Boolean,
    ): List<RelationshipView> = withTransaction {
        val cond: Op<Boolean> =
            (EltmRelationships.srcId eq entityId) or (EltmRelationships.dstId eq entityId)

        val filtered = if (includeInvalid) cond else cond and (EltmRelationships.valid eq true)
        EltmRelationships.selectAll().where { filtered }
            .orderBy(EltmRelationships.id to SortOrder.DESC)
            .map { row -> toRelationshipView(row.toRelationship()) }
    }

    private fun toRelationshipView(rel: EltmRelationship): RelationshipView = RelationshipView(
        relationship = rel,
        srcName = findEntityById(rel.srcId)?.canonicalName
            ?: "<deleted entity ${rel.srcId}>",
        dstName = findEntityById(rel.dstId)?.canonicalName
            ?: "<deleted entity ${rel.dstId}>",
        noteCount = countNotesForRelationship(rel.id),
        latestNote = latestNoteForRelationship(rel.id),
    )

    private suspend fun noteQuery(
        column: Column<Long?>,
        subjectId: Long,
        from: LocalDate?,
        to: LocalDate?,
        limit: Int,
        offset: Int,
    ): List<EltmNote> {
        require(limit >= 1) { "limit must be >= 1, got $limit" }
        require(offset >= 0) { "offset must be >= 0, got $offset" }
        require(from == null || to == null || !from.isAfter(to)) {
            "from must not be after to"
        }
        return withTransaction {
            EltmNotes.selectAll().where {
                (column eq subjectId)
                    .andIfNotNull(from?.let { EltmNotes.eventDate greaterEq it })
                    .andIfNotNull(to?.let { EltmNotes.eventDate lessEq it })
            }
                .orderBy(EltmNotes.eventDate to SortOrder.DESC, EltmNotes.id to SortOrder.DESC)
                .limit(limit)
                .offset(offset.toLong())
                .map { it.toNote() }
        }
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
            val dist = cosineDistance(EltmNotes.embedding, q)
            EltmNotes.selectAll().where {
                (dist lessEq 1.0 - noteSearchThreshold)
                    .and(EltmNotes.embedding.isNotNull())
                    .andIfNotNull(entityId?.let { EltmNotes.entityId eq it })
                    .andIfNotNull(relationshipId?.let { EltmNotes.relationshipId eq it })
                    .andIfNotNull(from?.let { EltmNotes.eventDate greaterEq it })
                    .andIfNotNull(to?.let { EltmNotes.eventDate lessEq it })
            }
                .orderBy(dist to SortOrder.ASC)
                .limit(limit)
                .map { it.toNote() }
        }
    }

    // ------------------------------------------------------------------
    // version
    // ------------------------------------------------------------------

    override suspend fun version(): String =
        withTransaction { readWriteVersion() }.toString()

    // ------------------------------------------------------------------
    // shared helpers (ambient transaction: only called inside withTransaction)
    // ------------------------------------------------------------------

    /**
     * Atomically bump the global ELTM write counter
     * (`memory_meta_number.eltm_version`) by one. Called inside the same
     * transaction as every visible-state write, so the bump commits with the
     * write — the digest fingerprint moves exactly when the ELTM changes.
     * The `value = value + 1` assignment is an SQL expression on the column
     * itself, so the bump is atomic (no read-modify-write race).
     */
    private fun bumpWriteVersion() {
        MemoryMetaNumber.update({ MemoryMetaNumber.key eq ELTM_VERSION_KEY }) {
            it[MemoryMetaNumber.value] = MemoryMetaNumber.value + 1L
        }
    }

    /** Read the global ELTM write counter. Ambient transaction. */
    private fun readWriteVersion(): Long =
        MemoryMetaNumber.selectAll()
            .where { MemoryMetaNumber.key eq ELTM_VERSION_KEY }
            .singleOrNull()?.get(MemoryMetaNumber.value) ?: 0L

    private fun findEntityByKey(canonicalName: String, category: String): ResultRow? =
        EltmEntities.selectAll().where {
            (EltmEntities.canonicalName eq canonicalName) and (EltmEntities.category eq category)
        }.singleOrNull()

    private fun findEntityRowById(id: Long): ResultRow? =
        EltmEntities.selectAll().where { EltmEntities.id eq id }.singleOrNull()

    /**
     * The entity row with `FOR UPDATE` — the read-modify-write lock held
     * for the whole write transaction, so a concurrent write on the same
     * entity (set/delete attribute, merge) blocks here until this commit
     * and then re-reads the fresh state (never a stale read-modify-write).
     */
    private fun findEntityRowByIdForUpdate(id: Long): ResultRow? =
        EltmEntities.selectAll().where { EltmEntities.id eq id }
            .forUpdate(ForUpdateOption.ForUpdate)
            .singleOrNull()

    private fun findEntityById(id: Long): EltmEntity? =
        findEntityRowById(id)?.toEntity()

    private fun findRelationshipById(id: Long): EltmRelationship? =
        EltmRelationships.selectAll().where { EltmRelationships.id eq id }.singleOrNull()
            ?.toRelationship()

    /** The ONE row for a triple (full unique index), whatever its validity. */
    private fun findRelationshipByTriple(srcId: Long, verb: String, dstId: Long): ResultRow? =
        EltmRelationships.selectAll().where {
            (EltmRelationships.srcId eq srcId) and
                    (EltmRelationships.dstId eq dstId) and
                    (EltmRelationships.verb eq verb)
        }.singleOrNull()

    private fun findNoteById(id: Long): EltmNote? =
        EltmNotes.selectAll().where { EltmNotes.id eq id }.singleOrNull()?.toNote()

    private fun latestNoteForEntity(entityId: Long): EltmNote? =
        EltmNotes.selectAll().where { EltmNotes.entityId eq entityId }
            .orderBy(EltmNotes.eventDate to SortOrder.DESC, EltmNotes.id to SortOrder.DESC)
            .limit(1).singleOrNull()?.toNote()

    private fun latestNoteForRelationship(relationshipId: Long): EltmNote? =
        EltmNotes.selectAll().where { EltmNotes.relationshipId eq relationshipId }
            .orderBy(EltmNotes.eventDate to SortOrder.DESC, EltmNotes.id to SortOrder.DESC)
            .limit(1).singleOrNull()?.toNote()

    private fun countNotesForEntity(entityId: Long): Int =
        EltmNotes.selectAll().where { EltmNotes.entityId eq entityId }.count().toInt()

    private fun countNotesForRelationship(relationshipId: Long): Int =
        EltmNotes.selectAll().where { EltmNotes.relationshipId eq relationshipId }.count().toInt()

    private fun countRelationshipsForEntity(entityId: Long): Int =
        EltmRelationships.selectAll().where {
            (EltmRelationships.srcId eq entityId) or (EltmRelationships.dstId eq entityId)
        }.count().toInt()

    /**
     * Per-subject note counts and latest notes (by event date, then id — the
     * same ordering as the single-subject helpers' `LIMIT 1` queries) for a
     * whole page of subjects, in ONE query. Ambient transaction.
     *
     * TODO: materializes every note of the page's subjects in memory; a
     * `GROUP BY` subject + window-function (or `DISTINCT ON`) variant would
     * scale to pages whose subjects carry heavy diaries.
     */
    private fun noteCountsAndLatest(
        column: Column<Long?>,
        subjectIds: List<Long>,
    ): Map<Long, Pair<Int, EltmNote?>> {
        if (subjectIds.isEmpty()) return emptyMap()
        return EltmNotes.selectAll().where { column inList subjectIds }
            .map { it.toNote() }
            // a note's subject is exactly one of the two columns (migration
            // CHECK), so the fallback chain only masks a broken schema
            .groupBy { it.entityId ?: it.relationshipId ?: error("note has no subject") }
            .mapValues { (_, notes) ->
                notes.size to
                    notes.maxWithOrNull(compareBy<EltmNote> { it.eventDate }.thenBy { it.id })
            }
    }

    /**
     * Per-entity relationship counts (the entity as src OR dst) for a whole
     * page of entities, in ONE query. Ambient transaction.
     */
    private fun relationshipCountsFor(entityIds: List<Long>): Map<Long, Int> {
        if (entityIds.isEmpty()) return emptyMap()
        return EltmRelationships.select(EltmRelationships.srcId, EltmRelationships.dstId)
            .where {
                (EltmRelationships.srcId inList entityIds) or
                    (EltmRelationships.dstId inList entityIds)
            }
            // a self-loop (src == dst, e.g. a merge-invalidated winner—
            // winner edge) is ONE row: count it once, like
            // countRelationshipsForEntity and the drill-down list
            .map { row ->
                val src = row[EltmRelationships.srcId]
                val dst = row[EltmRelationships.dstId]
                if (src == dst) listOf(src) else listOf(src, dst)
            }
            .flatten()
            .groupingBy { it }
            .eachCount()
    }

    /**
     * The current-state attributes of ONE entity, keys alphabetically
     * ordered. Ambient transaction.
     */
    private fun attributesOf(entityId: Long): Map<String, String> =
        EltmEntityAttributes.selectAll().where { EltmEntityAttributes.entityId eq entityId }
            .map { it[EltmEntityAttributes.key] to it[EltmEntityAttributes.value] }
            .toMap()
            .toSortedMap()

    /**
     * Per-entity current-state attributes (keys alphabetically ordered) for a
     * whole page of entities, in ONE query. Ambient transaction.
     */
    private fun attributesFor(entityIds: List<Long>): Map<Long, Map<String, String>> {
        if (entityIds.isEmpty()) return emptyMap()
        return EltmEntityAttributes.selectAll()
            .where { EltmEntityAttributes.entityId inList entityIds }
            .map { it[EltmEntityAttributes.entityId] to (it[EltmEntityAttributes.key] to it[EltmEntityAttributes.value]) }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, pairs) -> pairs.toMap().toSortedMap() }
    }

    /**
     * Cosine-similarity search over stored entity embeddings, most similar
     * first, at or above [threshold], capped at [limit]; [excludeId] (used
     * for near matches) skips the row itself. Ambient transaction.
     *
     * The count, latest-note and attribute columns of the original
     * correlated-subquery SQL come from batch queries over the candidate
     * ids (Exposed v1 has no scalar subquery in the select list); the
     * candidate set is at most [limit] rows, so the extra round trips are
     * negligible.
     */
    private fun similarEntities(
        queryVector: List<Float>,
        excludeId: Long?,
        threshold: Double,
        limit: Int,
    ): List<EntityWithScore> {
        val dist = cosineDistance(EltmEntities.embedding, queryVector)
        val candidates = EltmEntities.select(
            EltmEntities.id,
            EltmEntities.canonicalName,
            EltmEntities.category,
            dist,
        ).where {
            (dist lessEq 1.0 - threshold)
                .and(EltmEntities.embedding.isNotNull())
                .andIfNotNull(excludeId?.let { EltmEntities.id neq it })
        }
            .orderBy(dist to SortOrder.ASC)
            .limit(limit)
            .map { it[EltmEntities.id] to it }
        if (candidates.isEmpty()) return emptyList()
        val ids = candidates.map { (id, _) -> id }
        // note counts AND latest notes in ONE query over the candidate ids
        // (the same batch helper the page reads use), so each hit carries
        // its full model-visible picture without a per-hit drill-down
        val noteSummary = noteCountsAndLatest(EltmNotes.entityId, ids)
        val relationshipCounts = relationshipCountsFor(ids)
        val attributes = attributesFor(ids)
        return candidates.map { (id, row) ->
            EntityWithScore(
                entity = EltmEntity(
                    id = id,
                    canonicalName = row[EltmEntities.canonicalName],
                    category = row[EltmEntities.category],
                ),
                noteCount = noteSummary[id]?.first ?: 0,
                latestNote = noteSummary[id]?.second,
                relationshipCount = relationshipCounts[id] ?: 0,
                score = 1.0 - row[dist],
                attributes = attributes[id] ?: emptyMap(),
            )
        }
    }

    // ------------------------------------------------------------------
    // row mapping
    // ------------------------------------------------------------------

    private fun ResultRow.toEntity(): EltmEntity = EltmEntity(
        id = this[EltmEntities.id],
        canonicalName = this[EltmEntities.canonicalName],
        category = this[EltmEntities.category],
    )

    private fun ResultRow.toRelationship(): EltmRelationship = EltmRelationship(
        id = this[EltmRelationships.id],
        srcId = this[EltmRelationships.srcId],
        dstId = this[EltmRelationships.dstId],
        verb = this[EltmRelationships.verb],
        valid = this[EltmRelationships.valid],
    )

    private fun ResultRow.toNote(): EltmNote = EltmNote(
        id = this[EltmNotes.id],
        entityId = this[EltmNotes.entityId],
        relationshipId = this[EltmNotes.relationshipId],
        eventDate = this[EltmNotes.eventDate],
        note = this[EltmNotes.note],
        createdAt = this[EltmNotes.createdAt],
    )

    // ------------------------------------------------------------------
    // embedding
    // ------------------------------------------------------------------

    private suspend fun embedText(text: String): List<Float> {
        val vectors = hand.embed(embeddingModel, listOf(text), maxRetries, timeoutMs).vectors
        return padVector(vectors.single(), MAX_VECTOR_DIMENSIONS)
    }

    private fun isUniqueViolation(e: Throwable): Boolean {
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is SQLException && cause.sqlState == "23505") return true
            cause = cause.cause
        }
        return false
    }

    companion object {
        private const val NEAR_MATCH_LIMIT = 5
        private const val ELTM_VERSION_KEY = "eltm_version"

        /** The pgvector cosine distance `column <=> vector` as an Exposed expression. */
        private fun cosineDistance(
            column: Column<List<Float>?>,
            vector: List<Float>,
        ): Function<Double> = VectorDistance(
            column,
            QueryParameter<List<Float>?>(vector, VectorColumnType(MAX_VECTOR_DIMENSIONS)),
            VectorDistanceMetric.COSINE,
        )
    }
}
