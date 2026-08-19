package info.skyblond.daapu.memory.eltm

import info.skyblond.daapu.agent.model.EmbeddingModel
import info.skyblond.daapu.config.MAX_VECTOR_DIMENSIONS
import info.skyblond.daapu.db.*
import info.skyblond.daapu.hand.HandService
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.Function
import org.jetbrains.exposed.v1.core.functions.vector.VectorDistance
import org.jetbrains.exposed.v1.core.functions.vector.VectorDistanceMetric
import org.jetbrains.exposed.v1.jdbc.*
import java.sql.SQLException
import java.time.LocalDate

/**
 * Postgres-backed [EltmService] over the `eltm_entities` /
 * `eltm_relationships` / `eltm_notes` tables (`V1__init.sql`).
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
            // exact match: create-or-fetch — pure read, nothing to touch (a
            // rename/re-categorization is a NEW entity: create it, then
            // merge; the prominence signal is the read views' note/
            // relationship counts), NO second embed call — the near matches
            // are computed from the row's stored embedding
            existing
        } else {
            val embedding = embedText("$canonical $cat")
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

    override suspend fun mergeEntities(winnerId: Long, loserId: Long) {
        withTransaction {
            require(winnerId != loserId) { "cannot merge an entity into itself" }
            val winnerExists = findEntityById(winnerId) != null
            val loserExists = findEntityById(loserId) != null
            require(winnerExists) { "entity $winnerId does not exist" }
            require(loserExists) { "entity $loserId does not exist" }

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

            // re-point the loser's entity notes, then delete the loser row
            // (the cascade never fires: no note references the loser anymore)
            EltmNotes.update({ EltmNotes.entityId eq loserId }) {
                it[EltmNotes.entityId] = winnerId
            }
            EltmEntities.deleteWhere { EltmEntities.id eq loserId }
            // one bump for the whole transactional merge (the loser delete is
            // the reliable change signal; the re-points ride the same commit)
            bumpWriteVersion()
        }
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

    override suspend fun getEntity(id: Long): EntityView? = withTransaction {
        val entity = findEntityById(id) ?: return@withTransaction null
        EntityView(
            entity = entity,
            noteCount = countNotesForEntity(id),
            relationshipCount = countRelationshipsForEntity(id),
            latestNote = latestNoteForEntity(id),
        )
    }

    override suspend fun getRelationship(id: Long): RelationshipView? = withTransaction {
        val rel = findRelationshipById(id) ?: return@withTransaction null
        RelationshipView(
            relationship = rel,
            srcName = findEntityById(rel.srcId)?.canonicalName
                ?: "<deleted entity ${rel.srcId}>",
            dstName = findEntityById(rel.dstId)?.canonicalName
                ?: "<deleted entity ${rel.dstId}>",
            noteCount = countNotesForRelationship(rel.id),
            latestNote = latestNoteForRelationship(rel.id),
        )
    }

    override suspend fun getRelationships(
        entityId: Long,
        includeInvalid: Boolean,
    ): List<RelationshipView> = withTransaction {
        val cond: Op<Boolean> =
            (EltmRelationships.srcId eq entityId) or (EltmRelationships.dstId eq entityId)

        val filtered = if (includeInvalid) cond else cond and (EltmRelationships.valid eq true)
        EltmRelationships.selectAll().where { filtered }
            .orderBy(EltmRelationships.id to SortOrder.DESC)
            .map { row ->
                val rel = row.toRelationship()
                RelationshipView(
                    relationship = rel,
                    srcName = findEntityById(rel.srcId)?.canonicalName
                        ?: "<deleted entity ${rel.srcId}>",
                    dstName = findEntityById(rel.dstId)?.canonicalName
                        ?: "<deleted entity ${rel.dstId}>",
                    noteCount = countNotesForRelationship(rel.id),
                    latestNote = latestNoteForRelationship(rel.id),
                )
            }
    }

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
     * Cosine-similarity search over stored entity embeddings, most similar
     * first, at or above [threshold], capped at [limit]; [excludeId] (used
     * for near matches) skips the row itself. Ambient transaction.
     *
     * The count columns of the original correlated-subquery SQL come from
     * two group-by queries over the candidate ids (Exposed v1 has no scalar
     * subquery in the select list); the candidate set is at most [limit]
     * rows, so the extra round trips are negligible.
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
        val noteCounts = EltmNotes.select(EltmNotes.entityId, EltmNotes.id.count())
            .where { EltmNotes.entityId inList ids }
            .groupBy(EltmNotes.entityId)
            .associate { it[EltmNotes.entityId]!! to it[EltmNotes.id.count()].toInt() }
        val relationshipCounts = EltmRelationships.select(
            EltmRelationships.srcId,
            EltmRelationships.dstId,
        ).where {
            (EltmRelationships.srcId inList ids) or (EltmRelationships.dstId inList ids)
        }
            .map { listOf(it[EltmRelationships.srcId], it[EltmRelationships.dstId]) }
            .flatten()
            .groupingBy { it }
            .eachCount()
        return candidates.map { (id, row) ->
            EntityWithScore(
                entity = EltmEntity(
                    id = id,
                    canonicalName = row[EltmEntities.canonicalName],
                    category = row[EltmEntities.category],
                ),
                noteCount = noteCounts[id] ?: 0,
                relationshipCount = relationshipCounts[id] ?: 0,
                score = 1.0 - row[dist],
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
