package info.skyblond.daapu.memory.eltm.postgres

import info.skyblond.daapu.db.*
import info.skyblond.daapu.memory.eltm.EltmService
import info.skyblond.daapu.memory.eltm.EntityView
import info.skyblond.daapu.memory.eltm.EntityWithScore
import info.skyblond.daapu.memory.eltm.EltmEntity
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.*

/**
 * Ambient-transaction entity/attribute queries for [PostgresEltmService]
 * over the `eltm_entities` / `eltm_entity_attributes` tables
 * (`V1__init.sql`): the row finders (plain and `FOR UPDATE`), the
 * create-or-fetch inserts (single and bulk), the collision check, the
 * attribute readers and writers, the cosine-similarity search and the
 * view builders. Every function here is only ever called inside
 * `withTransaction` — by the service or by the other query files in
 * this package.
 */

internal fun ResultRow.toEntity(): EltmEntity = EltmEntity(
    id = this[EltmEntities.id],
    canonicalName = this[EltmEntities.canonicalName],
    category = this[EltmEntities.category],
)

internal fun findEntityByKey(canonicalName: String, category: String): ResultRow? =
    EltmEntities.selectAll().where {
        (EltmEntities.canonicalName eq canonicalName) and (EltmEntities.category eq category)
    }.singleOrNull()

internal fun findEntityRowById(id: Long): ResultRow? =
    EltmEntities.selectAll().where { EltmEntities.id eq id }.singleOrNull()

/**
 * The entity row with `FOR UPDATE` — the read-modify-write lock held
 * for the whole write transaction, so a concurrent write on the same
 * entity (set/delete attribute, merge) blocks here until this commit
 * and then re-reads the fresh state (never a stale read-modify-write).
 */
internal fun findEntityRowByIdForUpdate(id: Long): ResultRow? =
    EltmEntities.selectAll().where { EltmEntities.id eq id }
        .forUpdate(ForUpdateOption.ForUpdate)
        .singleOrNull()

internal fun findEntityById(id: Long): EltmEntity? =
    findEntityRowById(id)?.toEntity()

/**
 * The create-or-fetch insert for ONE (name, category) key: INSERT ... ON
 * CONFLICT DO NOTHING RETURNING (`insertReturning(ignoreErrors = true)`
 * on Postgres) never aborts the transaction — a null result means a
 * concurrent run inserted the same key first, and since a same-key insert
 * blocks until the winner resolves, the conflicting row is committed and
 * visible to the caller's re-select in the SAME transaction (no nested
 * transaction, no SQLState handling). Only a real insert bumps the write
 * counter (the caller decides). Ambient transaction.
 *
 * The CONFLICT clause is untargeted (Exposed's API offers no conflict
 * target here), so it swallows a violation of ANY unique constraint on
 * the table. That is safe only while the table's unique constraints are
 * exactly the intended (name, category) key plus the BIGSERIAL PK
 * (collision effectively impossible): a future index must revisit this
 * or the violation would be misread as a same-key race (the re-select
 * then fails with the "not visible" error — misleading, but fail-fast).
 */
internal fun insertEntityRow(
    canonicalName: String,
    category: String,
    embedding: List<Float>,
): ResultRow? =
    EltmEntities.insertReturning(
        returning = listOf(
            EltmEntities.id,
            EltmEntities.canonicalName,
            EltmEntities.category,
            EltmEntities.embedding,
        ),
        ignoreErrors = true,
    ) {
        it[EltmEntities.canonicalName] = canonicalName
        it[EltmEntities.category] = category
        it[EltmEntities.embedding] = embedding
    }.singleOrNull()

/**
 * The bulk create-or-fetch body (the batch unit's semantics:
 * [EltmService.createEntities]): the batched key lookups, the per-key
 * ON CONFLICT inserts (via [insertEntityRow]) and the re-selects, all in
 * the caller's ONE transaction. Duplicate keys fold onto ONE row:
 * create-or-fetch per key. The missing keys' embeddings come from
 * [embedMissing] — the service's suspend lambda (it embeds the batched
 * texts; the connection is held across the embeds, the same stance as
 * [PostgresEltmService.setEntityAttributes]). Returns the rows for
 * [keys] in input order (duplicates repeat their row) plus whether THIS
 * call inserted anything — only a real insert bumps the write counter,
 * so a fully conflict-adopted batch is a pure read and the caller bumps
 * on the flag. Ambient transaction.
 */
internal suspend fun bulkCreateOrFetchEntities(
    keys: List<Pair<String, String>>,
    embedMissing: suspend (List<Pair<String, String>>) -> List<List<Float>>,
): Pair<List<ResultRow>, Boolean> {
    val distinct = keys.distinct()
    val existing = findEntitiesByKeys(distinct).associateBy {
        it[EltmEntities.canonicalName] to it[EltmEntities.category]
    }
    val missing = distinct.filter { it !in existing }
    val resolved = HashMap(existing)
    var insertedAny = false
    if (missing.isNotEmpty()) {
        val embeddings = embedMissing(missing)
        for ((key, embedding) in missing.zip(embeddings)) {
            // the single createEntity insert path, per key: ON CONFLICT
            // DO NOTHING RETURNING adopts a concurrent same-key insert
            // (a null result) — the re-select below resolves it (see
            // [insertEntityRow] for the full rationale)
            val row = insertEntityRow(key.first, key.second, embedding)
            if (row != null) {
                insertedAny = true
                resolved[key] = row
            }
        }
        // resolve the conflict-adopted keys (the rare path — usually
        // every insert above succeeded)
        val adopted = distinct.filter { it !in resolved }
        if (adopted.isNotEmpty()) {
            findEntitiesByKeys(adopted).forEach { row ->
                resolved[row[EltmEntities.canonicalName] to row[EltmEntities.category]] = row
            }
        }
    }
    val rows = keys.map { key ->
        resolved[key]
            ?: error("unique conflict but the entity (${key.first}, ${key.second}) is not visible")
    }
    return rows to insertedAny
}

/**
 * The rows for a batch of (canonical name, category) keys in ONE query
 * (an OR of per-key conjunctions — every disjunct is a point lookup on
 * the `(canonical_name, category)` unique index), the batched
 * counterpart of [findEntityByKey] for the bulk create-or-fetch.
 * Ambient transaction.
 */
internal fun findEntitiesByKeys(keys: List<Pair<String, String>>): List<ResultRow> {
    if (keys.isEmpty()) return emptyList()
    val cond = keys.map { (name, cat) ->
        (EltmEntities.canonicalName eq name) and (EltmEntities.category eq cat)
    }.reduce { a, b -> a or b }
    return EltmEntities.selectAll().where { cond }.toList()
}

/**
 * Fail with the merge-instead collision error when the target
 * (name, category) belongs to a DIFFERENT entity than [entityId] (the
 * entity's own row is not a collision —
 * [PostgresEltmService.refineEntity] may echo its current identity).
 * Ambient transaction.
 */
internal fun checkNoNameCollision(entityId: Long, name: String, category: String) {
    findEntityByKey(name, category)?.let { existing ->
        if (existing[EltmEntities.id] != entityId) {
            throw IllegalArgumentException(
                "an entity \"$name\" (category $category) already exists " +
                        "as entity ${existing[EltmEntities.id]}: merge the two instead"
            )
        }
    }
}

/**
 * The entity's canonical name, or the defensive placeholder for a gone
 * row (the merge path re-points relationships before deleting their
 * loser endpoints, so a live row's endpoints exist — the placeholder
 * only masks a broken state). Ambient transaction.
 */
internal fun entityNameOf(id: Long): String =
    findEntityRowById(id)?.get(EltmEntities.canonicalName) ?: "<deleted entity $id>"

/**
 * The current-state attributes of ONE entity, keys alphabetically
 * ordered. Ambient transaction.
 */
internal fun attributesOf(entityId: Long): Map<String, String> =
    EltmEntityAttributes.selectAll().where { EltmEntityAttributes.entityId eq entityId }
        .map { it[EltmEntityAttributes.key] to it[EltmEntityAttributes.value] }
        .toMap()
        .toSortedMap()

/**
 * Per-entity current-state attributes (keys alphabetically ordered) for a
 * whole page of entities, in ONE query. Ambient transaction.
 */
internal fun attributesFor(entityIds: List<Long>): Map<Long, Map<String, String>> {
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
 * pgvector's HNSW index post-filters: the WHERE above (threshold,
 * `excludeId`) can end the index scan early, so this can return FEWER
 * than [limit] rows even when further matches exist (pgvector <=0.7
 * behavior; iterative scans would fix it). The threshold only ever
 * DROPS candidates (distance is exact per visited row), so a returned
 * hit is always genuinely above [threshold].
 *
 * The count, latest-note and attribute columns of the original
 * correlated-subquery SQL come from batch queries over the candidate
 * ids (Exposed v1 has no scalar subquery in the select list); the
 * candidate set is at most [limit] rows, so the extra round trips are
 * negligible.
 */
internal fun similarEntities(
    queryVector: List<Float>,
    excludeId: Long?,
    threshold: Double,
    limit: Int,
): List<EntityWithScore> {
    val dist = VectorColumnType.cosineDistance(EltmEntities.embedding, queryVector)
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

/**
 * The single-subject entity view (counts, latest note, attributes) from
 * the cheap single-subject helpers — the shared builder behind
 * [PostgresEltmService.getEntity] and the write paths' returned views
 * ([PostgresEltmService.createEntity], [PostgresEltmService.refineEntity]),
 * so a write's result never needs a follow-up read transaction.
 * Ambient transaction.
 */
internal fun entityViewOf(entity: EltmEntity): EntityView = EntityView(
    entity = entity,
    noteCount = countNotes(EltmNotes.entityId, entity.id),
    relationshipCount = countRelationshipsForEntity(entity.id),
    latestNote = latestNote(EltmNotes.entityId, entity.id),
    attributes = attributesOf(entity.id),
)

/**
 * Overwrite the entity's attribute rows in place — the whole batch rides
 * ONE JDBC batched UPSERT (ON CONFLICT (entity_id, key) DO UPDATE SET
 * value = excluded.value), never one UPSERT statement per key — then
 * store the re-embedded entity vector, so the embedding text and the
 * attribute rows can never diverge. Ambient transaction.
 */
internal fun writeEntityAttributes(
    entityId: Long,
    changed: Map<String, String>,
    embedding: List<Float>,
) {
    EltmEntityAttributes.batchUpsert(
        changed.entries,
        keys = arrayOf(EltmEntityAttributes.entityId, EltmEntityAttributes.key),
        shouldReturnGeneratedValues = false,
    ) { (k, v) ->
        this[EltmEntityAttributes.entityId] = entityId
        this[EltmEntityAttributes.key] = k
        this[EltmEntityAttributes.value] = v
    }
    updateEntityEmbedding(entityId, embedding)
}

/** Delete ONE attribute row. Ambient transaction. */
internal fun deleteEntityAttributeRow(entityId: Long, key: String) {
    EltmEntityAttributes.deleteWhere {
        (EltmEntityAttributes.entityId eq entityId) and
            (EltmEntityAttributes.key eq key)
    }
}

/** Store the entity's re-embedded vector (after an attribute change). */
internal fun updateEntityEmbedding(entityId: Long, embedding: List<Float>) {
    EltmEntities.update({ EltmEntities.id eq entityId }) {
        it[EltmEntities.embedding] = embedding
    }
}

/**
 * One page of full entity views (id order), a whole page's counts,
 * latest notes and attributes in bounded batch queries (see
 * [noteCountsAndLatest]) instead of [entityViewOf]'s 5 per-row queries
 * (the single-subject reads stay per-row: one row, five queries).
 * Ambient transaction.
 */
internal fun selectEntityViews(limit: Int, offset: Int): List<EntityView> {
    val entities = EltmEntities.selectAll()
        .orderBy(EltmEntities.id to SortOrder.ASC)
        .limit(limit)
        .offset(offset.toLong())
        .map { it.toEntity() }
    val noteSummary = noteCountsAndLatest(EltmNotes.entityId, entities.map { it.id })
    val relationshipCounts = relationshipCountsFor(entities.map { it.id })
    val attributes = attributesFor(entities.map { it.id })
    return entities.map { entity ->
        EntityView(
            entity = entity,
            noteCount = noteSummary[entity.id]?.first ?: 0,
            relationshipCount = relationshipCounts[entity.id] ?: 0,
            latestNote = noteSummary[entity.id]?.second,
            attributes = attributes[entity.id] ?: emptyMap(),
        )
    }
}

/**
 * Every entity's content columns, id order — the export's stable
 * ordering. Only the content columns: the embedding vectors (2000 dims
 * per row) never travel (the same rationale as selectNoteContent).
 * Ambient transaction.
 */
internal fun selectAllEntityContent(): List<EltmEntity> =
    EltmEntities
        .select(EltmEntities.id, EltmEntities.canonicalName, EltmEntities.category)
        .orderBy(EltmEntities.id to SortOrder.ASC)
        .map { it.toEntity() }
