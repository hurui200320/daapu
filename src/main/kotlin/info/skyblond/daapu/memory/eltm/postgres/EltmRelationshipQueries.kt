package info.skyblond.daapu.memory.eltm.postgres

import info.skyblond.daapu.db.EltmEntities
import info.skyblond.daapu.db.EltmNotes
import info.skyblond.daapu.db.EltmRelationships
import info.skyblond.daapu.memory.eltm.EltmService
import info.skyblond.daapu.memory.eltm.TornRelationshipReadException
import info.skyblond.daapu.memory.eltm.model.EltmEntity
import info.skyblond.daapu.memory.eltm.model.EltmRelationship
import info.skyblond.daapu.memory.eltm.model.RelationshipView
import info.skyblond.daapu.memory.eltm.model.ResolvedRelationship
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.insertReturning
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll

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

/**
 * Cheap existence probe for a relationship (a single indexed PK lookup) —
 * the ambient-transaction counterpart of
 * [PostgresEltmService.relationshipExists] for callers that need nothing
 * but existence, never the full row. Ambient transaction.
 */
internal fun relationshipIdExists(id: Long): Boolean =
    EltmRelationships.select(EltmRelationships.id)
        .where { EltmRelationships.id eq id }
        .limit(1)
        .singleOrNull() != null

/** The ONE row for a triple (full unique index), whatever its validity. */
internal fun findRelationshipByTriple(srcId: Long, verb: String, dstId: Long): ResultRow? =
    EltmRelationships.selectAll().where {
        (EltmRelationships.srcId eq srcId) and
                (EltmRelationships.dstId eq dstId) and
                (EltmRelationships.verb eq verb)
    }.singleOrNull()

/**
 * The rows for a batch of (src, verb, dst) triples, in one query per
 * [BULK_QUERY_CHUNK_SIZE] chunk (an OR of per-triple conjunctions per
 * chunk — each disjunct is a point lookup on the triple's unique index),
 * the batched counterpart of [findRelationshipByTriple] for the bulk
 * create-or-fetch. Chunked so a large import never builds a
 * thousands-disjunct WHERE in one statement. Duplicate triples dedupe
 * before chunking (a duplicate disjunct is pure waste). Ambient
 * transaction.
 */
internal fun findRelationshipsByTriples(triples: List<Triple<Long, String, Long>>): List<ResultRow> {
    val distinct = triples.distinct()
    if (distinct.isEmpty()) return emptyList()
    return distinct.chunked(BULK_QUERY_CHUNK_SIZE).flatMap { chunk ->
        val cond = chunk.map { (srcId, verb, dstId) ->
            (EltmRelationships.srcId eq srcId) and
                    (EltmRelationships.dstId eq dstId) and
                    (EltmRelationships.verb eq verb)
        }.reduce { a, b -> a or b }
        EltmRelationships.selectAll().where { cond }.toList()
    }
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
 * [EltmService.createRelationships]): the endpoint existence check for
 * the whole batch (one `inList` read per [BULK_QUERY_CHUNK_SIZE] chunk —
 * an unchunked check would breach the parameter limit on a whole-store
 * import's endpoint set; the first missing id in input order fails the
 * call before any insert — the FK would catch them later
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
    // toList first: the set's iteration order is undefined, and chunk
    // boundaries should be deterministic across runs
    val endpointIds = triples.flatMapTo(HashSet()) { listOf(it.first, it.third) }.toList()
    val present = endpointIds.chunked(BULK_QUERY_CHUNK_SIZE).flatMap { chunk ->
        EltmEntities.select(EltmEntities.id)
            .where { EltmEntities.id inList chunk }
            .map { it[EltmEntities.id] }
    }.toHashSet()
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
 * Per-entity relationship counts (the entity as src OR dst) for a batch
 * of entities, in one query per [BULK_QUERY_CHUNK_SIZE] chunk — a
 * whole-store export's caller can pass every id at once. Each chunk
 * counts only its OWN endpoints (chunks partition the input, so each
 * requested id is counted exactly once, in its chunk — an edge spanning
 * two chunks is selected by both chunk queries but contributes to each
 * endpoint only in that endpoint's chunk). Ambient transaction.
 */
internal fun relationshipCountsFor(entityIds: List<Long>): Map<Long, Int> {
    if (entityIds.isEmpty()) return emptyMap()
    return entityIds.distinct().chunked(BULK_QUERY_CHUNK_SIZE).flatMap { chunk ->
        val inChunk = chunk.toHashSet()
        EltmRelationships.select(EltmRelationships.srcId, EltmRelationships.dstId)
            .where {
                (EltmRelationships.srcId inList chunk) or
                        (EltmRelationships.dstId inList chunk)
            }
            // a self-loop (src == dst, e.g. a merge-invalidated winner—
            // winner edge) is ONE row: count it once, like
            // countRelationshipsForEntity and the drill-down list
            .map { row ->
                val src = row[EltmRelationships.srcId]
                val dst = row[EltmRelationships.dstId]
                if (src == dst) listOf(src).filter { it in inChunk }
                else listOf(src, dst).filter { it in inChunk }
            }
            .flatten()
    }
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
 * The single-subject relationship view (resolved relationship, note
 * count, latest note) from the cheap single-subject helpers — a `COUNT`,
 * a `LIMIT 1` latest-note read and two endpoint lookups, never the page
 * builder's batch queries (one row must not pay for a page). Null when the
 * row itself is gone or an endpoint is gone (a concurrent merge folding
 * the row away mid-read — the merge path re-points relationships before
 * deleting their loser endpoints, so a live row's endpoints exist). The
 * row is re-validated LAST: the endpoint and note reads straddle a
 * concurrent merge commit (endpoints pre-merge, notes post-merge), and
 * without the trailing check the builder would return a view of a deleted
 * row with stale endpoints. The shared builder behind
 * [PostgresEltmService.getRelationship] (which maps the null to its own
 * null — a 404) and [PostgresEltmService.createRelationship] (which
 * distinguishes the folded-row case from a gone endpoint for its
 * IllegalArgumentException — a row checked present in the same
 * transaction must still be there, so a concurrent merge took it
 * mid-call). Ambient transaction.
 */
internal fun relationshipViewOf(rel: EltmRelationship): RelationshipView? {
    val resolved = resolveRelationships(
        listOf(rel),
        selectEntitiesByIds(listOf(rel.srcId, rel.dstId)),
    ).singleOrNull() ?: return null
    val view = RelationshipView(
        relationship = resolved,
        noteCount = countNotes(EltmNotes.relationshipId, rel.id),
        latestNote = latestNote(EltmNotes.relationshipId, rel.id),
    )
    // the row itself, re-read after the note reads (see the KDoc): a
    // concurrent merge folding this row away between the reads above must
    // map to null, never a stale view of a deleted row
    if (findRelationshipById(rel.id) == null) return null
    return view
}

internal fun toRelationshipViews(rels: List<EltmRelationship>): List<RelationshipView> {
    if (rels.isEmpty()) return emptyList()
    // the whole list's resolved endpoints plus note counts and latest
    // notes in 3 bounded queries instead of the single-subject
    // helpers' 4 per row
    val entities = selectEntitiesByIds(rels.flatMap { listOf(it.srcId, it.dstId) })
    val resolved = resolveRelationships(rels, entities)
    checkAllResolved(rels, resolved)
    val noteSummary = noteCountsAndLatest(EltmNotes.relationshipId, rels.map { it.id })
    return resolved.map { r ->
        RelationshipView(
            relationship = r,
            noteCount = noteSummary[r.id]?.first ?: 0,
            latestNote = noteSummary[r.id]?.second,
        )
    }
}

/**
 * Resolve a batch of relationship rows against a batch of entities: rows
 * whose endpoint is missing resolve to null (a live row's endpoints
 * exist — the merge path re-points relationships before deleting their
 * loser endpoints, so a null is either a broken state or a concurrent
 * merge landing between the row read and the endpoint read). The single
 * nesting rule for [ResolvedRelationship], shared by every relationship
 * read — exactly one place maps numeric ids to endpoint objects.
 *
 * The null is NEVER silently dropped: the single-subject
 * [relationshipViewOf] maps it to its own null (whose callers handle it
 * explicitly — a 404 or a fail-fast error), and the batch builders
 * ([toRelationshipViews], [selectResolvedRelationships]) check sizes and
 * fail fast instead.
 */
internal fun resolveRelationships(
    rels: List<EltmRelationship>,
    entitiesById: Map<Long, EltmEntity>,
): List<ResolvedRelationship> = rels.mapNotNull { rel ->
    val src = entitiesById[rel.srcId] ?: return@mapNotNull null
    val dst = entitiesById[rel.dstId] ?: return@mapNotNull null
    ResolvedRelationship(rel.id, src, rel.verb, dst, rel.valid)
}

/**
 * Fail fast when a found relationship row lost its endpoint: a live row's
 * endpoints exist (the merge path re-points relationships before deleting
 * their loser endpoints, and the FK cascade deletes a relationship with
 * its endpoint), so a mismatch is either a broken state or a concurrent
 * merge landing between the row read and the endpoint read — never
 * silently dropped, which would punch holes in limit/offset pages. Ids
 * with NO row at all are not a mismatch (the caller maps them to absent).
 */
internal fun checkAllResolved(
    rels: List<EltmRelationship>,
    resolved: List<ResolvedRelationship>,
) {
    if (resolved.size == rels.size) return
    val resolvedIds = resolved.mapTo(HashSet()) { it.id }
    val broken = rels.filter { it.id !in resolvedIds }.map { it.id }
    throw TornRelationshipReadException(broken)
}

/**
 * The [ResolvedRelationship]s for a batch of relationship ids, in one
 * bounded `inList` read per [BULK_QUERY_CHUNK_SIZE] chunk plus the
 * endpoint `inList` reads (likewise chunked) — the identity-only
 * counterpart of [toRelationshipViews], without the note counts and
 * latest notes the injection never renders. Ids with no row are absent
 * from the map; a row whose endpoint is gone fails fast (see
 * [toRelationshipViews] — a found row must never silently drop). An
 * empty input answers an empty map with no query. Ambient transaction.
 */
internal fun selectResolvedRelationships(ids: List<Long>): Map<Long, ResolvedRelationship> {
    val distinct = ids.distinct()
    if (distinct.isEmpty()) return emptyMap()
    val rels = distinct.chunked(BULK_QUERY_CHUNK_SIZE).flatMap { chunk ->
        EltmRelationships.selectAll()
            .where { EltmRelationships.id inList chunk }
            .map { it.toRelationship() }
    }
    if (rels.isEmpty()) return emptyMap()
    val entities = selectEntitiesByIds(rels.flatMap { listOf(it.srcId, it.dstId) })
    val resolved = resolveRelationships(rels, entities)
    checkAllResolved(rels, resolved)
    return resolved.associateBy { it.id }
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
