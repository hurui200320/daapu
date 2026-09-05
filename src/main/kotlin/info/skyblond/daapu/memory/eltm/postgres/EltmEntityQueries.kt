package info.skyblond.daapu.memory.eltm.postgres

import info.skyblond.daapu.db.*
import info.skyblond.daapu.memory.eltm.EntityView
import info.skyblond.daapu.memory.eltm.EntityWithScore
import info.skyblond.daapu.memory.eltm.EltmEntity
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.*

/**
 * Ambient-transaction entity/attribute queries for [PostgresEltmService]
 * over the `eltm_entities` / `eltm_entity_attributes` tables
 * (`V1__init.sql`): the row finders (plain and `FOR UPDATE`), the batched
 * create-or-fetch lookups, the collision check, the attribute readers,
 * the cosine-similarity search and the single-subject view builder.
 * Every function here is only ever called inside `withTransaction` — by
 * the service or by the other query files in this package.
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
