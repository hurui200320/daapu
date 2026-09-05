package info.skyblond.daapu.memory.eltm.postgres

import info.skyblond.daapu.db.*
import info.skyblond.daapu.memory.eltm.EltmRelationship
import info.skyblond.daapu.memory.eltm.EltmService
import info.skyblond.daapu.memory.eltm.RelationshipView
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

/**
 * Ambient-transaction relationship queries for [PostgresEltmService] over
 * the `eltm_relationships` table (`V1__init.sql`): the row finders (single
 * triple and batched create-or-fetch lookups), the create-or-fetch inserts
 * (single and bulk), the counting rule and the relationship view builders.
 * Every function here is only ever called inside `withTransaction` — by
 * the service or by the other query files in this package.
 */

internal fun ResultRow.toRelationship(): EltmRelationship = EltmRelationship(
    id = this[EltmRelationships.id],
    srcId = this[EltmRelationships.srcId],
    dstId = this[EltmRelationships.dstId],
    verb = this[EltmRelationships.verb],
    valid = this[EltmRelationships.valid],
)

internal fun findRelationshipById(id: Long): EltmRelationship? =
    EltmRelationships.selectAll().where { EltmRelationships.id eq id }.singleOrNull()
        ?.toRelationship()

/** The ONE row for a triple (full unique index), whatever its validity. */
internal fun findRelationshipByTriple(srcId: Long, verb: String, dstId: Long): ResultRow? =
    EltmRelationships.selectAll().where {
        (EltmRelationships.srcId eq srcId) and
                (EltmRelationships.dstId eq dstId) and
                (EltmRelationships.verb eq verb)
    }.singleOrNull()

/**
 * The rows for a batch of (src, verb, dst) triples in ONE query (an OR
 * of per-triple conjunctions — each disjunct is a point lookup on the
 * triple's unique index), the batched counterpart of
 * [findRelationshipByTriple] for the bulk create-or-fetch. Ambient
 * transaction.
 */
internal fun findRelationshipsByTriples(triples: List<Triple<Long, String, Long>>): List<ResultRow> {
    if (triples.isEmpty()) return emptyList()
    val cond = triples.map { (srcId, verb, dstId) ->
        (EltmRelationships.srcId eq srcId) and
                (EltmRelationships.dstId eq dstId) and
                (EltmRelationships.verb eq verb)
    }.reduce { a, b -> a or b }
    return EltmRelationships.selectAll().where { cond }.toList()
}

/**
 * The create-or-fetch insert for ONE triple: INSERT ... ON CONFLICT DO
 * NOTHING RETURNING the id — a null result adopts a concurrent
 * same-triple insert, which the caller re-selects in the SAME
 * transaction (the insert semantics' full rationale AND the
 * untargeted-clause caveat live on [insertEntityRow] in
 * EltmEntityQueries.kt; here the triple is the table's only unique index
 * besides the PK, so the swallowed violation can only be a same-key
 * race). Only a real insert bumps the write counter (the caller
 * decides). Ambient transaction.
 */
internal fun insertRelationshipRow(srcId: Long, dstId: Long, verb: String): Long? =
    EltmRelationships.insertReturning(
        returning = listOf(EltmRelationships.id),
        ignoreErrors = true,
    ) {
        it[EltmRelationships.srcId] = srcId
        it[EltmRelationships.dstId] = dstId
        it[EltmRelationships.verb] = verb
    }.singleOrNull()?.get(EltmRelationships.id)

/**
 * The bulk create-or-fetch body (the batch unit's semantics:
 * [EltmService.createRelationships]): ONE endpoint existence check for
 * the whole batch (a single inList query; the first missing id in input
 * order fails the call before any insert — the FK would catch them later
 * with a raw SQL error), the batched triple lookups, the per-triple ON
 * CONFLICT inserts (via [insertRelationshipRow]) and the re-selects, all
 * in the caller's ONE transaction. Duplicate triples fold onto ONE row:
 * create-or-fetch per triple. No embed exists on this path. Returns the
 * relationships in input order (duplicates repeat their row) plus
 * whether THIS call inserted anything — only a real insert bumps the
 * write counter, so a fully conflict-adopted batch is a pure read and
 * the caller bumps on the flag. Ambient transaction.
 */
internal fun bulkCreateOrFetchRelationships(
    triples: List<Triple<Long, String, Long>>,
): Pair<List<EltmRelationship>, Boolean> {
    val endpointIds = triples.flatMapTo(HashSet()) { listOf(it.first, it.third) }
    val present = EltmEntities.select(EltmEntities.id)
        .where { EltmEntities.id inList endpointIds }
        .mapTo(HashSet()) { it[EltmEntities.id] }
    val missingEntityId = triples.firstNotNullOfOrNull { (srcId, _, dstId) ->
        when {
            srcId !in present -> srcId
            dstId !in present -> dstId
            else -> null
        }
    }
    require(missingEntityId == null) { "entity $missingEntityId does not exist" }

    val distinct = triples.distinct()
    val existing = findRelationshipsByTriples(distinct).associateBy {
        Triple(it[EltmRelationships.srcId], it[EltmRelationships.verb], it[EltmRelationships.dstId])
    }.mapValues { it.value.toRelationship() }
    val missing = distinct.filter { it !in existing }
    val resolved = HashMap(existing)
    var insertedAny = false
    for ((srcId, v, dstId) in missing) {
        // the single createRelationship insert path, per triple: ON
        // CONFLICT DO NOTHING RETURNING adopts a concurrent same-triple
        // insert (a null result) — the re-select below resolves it (see
        // [insertRelationshipRow] for the full rationale)
        val insertedId = insertRelationshipRow(srcId, dstId, v)
        if (insertedId != null) {
            insertedAny = true
            resolved[Triple(srcId, v, dstId)] =
                EltmRelationship(insertedId, srcId, dstId, v, valid = true)
        }
    }
    // resolve the conflict-adopted triples (the rare path)
    val adopted = distinct.filter { it !in resolved }
    if (adopted.isNotEmpty()) {
        findRelationshipsByTriples(adopted).forEach { row ->
            resolved[
                Triple(
                    row[EltmRelationships.srcId],
                    row[EltmRelationships.verb],
                    row[EltmRelationships.dstId],
                )
            ] = row.toRelationship()
        }
    }
    val rels = triples.map { triple ->
        resolved[triple]
            ?: error(
                "unique conflict but the relationship " +
                        "(${triple.first} -[${triple.second}]-> ${triple.third}) is not visible"
            )
    }
    return rels to insertedAny
}

/**
 * Per-entity relationship counts (the entity as src OR dst) for a whole
 * page of entities, in ONE query. Ambient transaction.
 */
internal fun relationshipCountsFor(entityIds: List<Long>): Map<Long, Int> {
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
 * The relationships of ONE entity (src OR dst, self-loops counted once —
 * [relationshipCountsFor]'s rule), delegated to the batch helper so the
 * counting rule exists in exactly one place. Ambient transaction.
 */
internal fun countRelationshipsForEntity(entityId: Long): Int =
    relationshipCountsFor(listOf(entityId))[entityId] ?: 0

/**
 * The single-subject relationship view (endpoint names, note count,
 * latest note) from the cheap single-subject helpers — a `COUNT`, a
 * `LIMIT 1` latest-note read and two endpoint name lookups, never the
 * page builder's batch queries (one row must not pay for a page). The
 * shared builder behind [PostgresEltmService.getRelationship] and
 * [PostgresEltmService.createRelationship]'s returned view. Ambient
 * transaction.
 */
internal fun relationshipViewOf(rel: EltmRelationship): RelationshipView = RelationshipView(
    relationship = rel,
    srcName = entityNameOf(rel.srcId),
    dstName = entityNameOf(rel.dstId),
    noteCount = countNotes(EltmNotes.relationshipId, rel.id),
    latestNote = latestNote(EltmNotes.relationshipId, rel.id),
)

internal fun toRelationshipViews(rels: List<EltmRelationship>): List<RelationshipView> {
    if (rels.isEmpty()) return emptyList()
    // a whole list's endpoint names, note counts and latest notes in
    // 2 queries instead of the single-subject helpers' 4 per row
    val names = EltmEntities.selectAll()
        .where { EltmEntities.id inList rels.flatMap { listOf(it.srcId, it.dstId) } }
        .associate { it[EltmEntities.id] to it[EltmEntities.canonicalName] }
    val noteSummary = noteCountsAndLatest(EltmNotes.relationshipId, rels.map { it.id })
    return rels.map { rel ->
        RelationshipView(
            relationship = rel,
            srcName = names[rel.srcId] ?: "<deleted entity ${rel.srcId}>",
            dstName = names[rel.dstId] ?: "<deleted entity ${rel.dstId}>",
            noteCount = noteSummary[rel.id]?.first ?: 0,
            latestNote = noteSummary[rel.id]?.second,
        )
    }
}

/**
 * One page of full relationship views (id order) via the shared batch
 * builder. Ambient transaction.
 */
internal fun selectRelationshipViews(limit: Int, offset: Int): List<RelationshipView> {
    val rels = EltmRelationships.selectAll()
        .orderBy(EltmRelationships.id to SortOrder.ASC)
        .limit(limit)
        .offset(offset.toLong())
        .map { it.toRelationship() }
    if (rels.isEmpty()) return emptyList()
    return toRelationshipViews(rels)
}

/**
 * ONE entity's relationships (src OR dst, newest id first), optionally
 * filtered to the valid ones, as full views via the shared batch
 * builder. Ambient transaction.
 */
internal fun selectEntityRelationshipViews(
    entityId: Long,
    includeInvalid: Boolean,
): List<RelationshipView> {
    val cond: Op<Boolean> =
        (EltmRelationships.srcId eq entityId) or (EltmRelationships.dstId eq entityId)
    val filtered = if (includeInvalid) cond else cond and (EltmRelationships.valid eq true)
    val rels = EltmRelationships.selectAll().where { filtered }
        .orderBy(EltmRelationships.id to SortOrder.DESC)
        .map { it.toRelationship() }
    return toRelationshipViews(rels)
}

/** Every relationship row, id order (the export's stable ordering). */
internal fun selectAllRelationshipContent(): List<EltmRelationship> =
    EltmRelationships.selectAll()
        .orderBy(EltmRelationships.id to SortOrder.ASC)
        .map { it.toRelationship() }
