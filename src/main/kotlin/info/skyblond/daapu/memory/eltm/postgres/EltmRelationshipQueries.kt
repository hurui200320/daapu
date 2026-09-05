package info.skyblond.daapu.memory.eltm.postgres

import info.skyblond.daapu.db.*
import info.skyblond.daapu.memory.eltm.EltmRelationship
import info.skyblond.daapu.memory.eltm.RelationshipView
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

/**
 * Ambient-transaction relationship queries for [PostgresEltmService] over
 * the `eltm_relationships` table (`V1__init.sql`): the row finders (single
 * triple and batched create-or-fetch lookups), the counting rule and the
 * relationship view builders. Every function here is only ever called
 * inside `withTransaction` — by the service or by the other query files
 * in this package.
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
