package info.skyblond.daapu.memory.eltm.postgres

import info.skyblond.daapu.db.*
import info.skyblond.daapu.memory.eltm.EltmNote
import info.skyblond.daapu.memory.eltm.NoteDraft
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import java.time.LocalDate

/**
 * Ambient-transaction note queries for [PostgresEltmService] over the
 * `eltm_notes` table (`V1__init.sql`): the diary reads (single-subject
 * and batch), the vector-search SQL, the batched note insert and the
 * shared paging guards. The queries here run in the caller's ambient
 * transaction — only ever called inside `withTransaction` (by the
 * service or by the other query files in this package).
 */

/**
 * Shared paging guards for every paginated read. The HTTP boundary
 * mirrors these as 400s (`server/endpoint/Params.kt`); the service-side
 * check stays because the tools and the pipeline call the service
 * directly.
 */
internal fun requirePaging(limit: Int, offset: Int) {
    require(limit >= 1) { "limit must be >= 1, got $limit" }
    require(offset >= 0) { "offset must be >= 0, got $offset" }
}

/**
 * Paginated notes of ONE subject, newest event first. The diary ordering
 * rule is `event_date DESC, id DESC` and exists in exactly three SQL
 * spots, all in this file ([noteQuery], [latestNote], [noteCountsAndLatest])
 * — update them together or the single-subject and batch views disagree.
 */
internal suspend fun noteQuery(
    column: Column<Long?>,
    subjectId: Long,
    from: LocalDate?,
    to: LocalDate?,
    limit: Int,
    offset: Int,
): List<EltmNote> {
    require(from == null || to == null || !from.isAfter(to)) {
        "from must not be after to"
    }
    requirePaging(limit, offset)
    return withTransaction {
        selectNoteContent().where {
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

/**
 * The note search's SQL over an ALREADY-EMBEDDED query vector — the
 * shared body of [PostgresEltmService.searchNotes] (its own embed) and
 * [PostgresEltmService.searchEntitiesAndNotes] (the one embed feeding
 * both halves). Ambient transaction.
 */
internal fun searchNotesByVector(
    q: List<Float>,
    threshold: Double,
    entityId: Long?,
    relationshipId: Long?,
    from: LocalDate?,
    to: LocalDate?,
    limit: Int,
): List<EltmNote> {
    val dist = VectorColumnType.cosineDistance(EltmNotes.embedding, q)
    // pgvector's HNSW index post-filters: a selective WHERE (subject
    // or date range) can end the index scan early, so this can
    // return FEWER than [limit] rows even when further matches
    // exist (pgvector <=0.7 behavior; iterative scans would fix it).
    // The exact match (similarity 1.0) always survives in practice.
    return selectNoteContent().where {
        (dist lessEq 1.0 - threshold)
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

/**
 * A content-only query over notes: every column EXCEPT the embedding.
 * The vectors dominate the row size and no read path consumes them
 * (a search compares server-side through the `<=>` expression, stored
 * vectors are only written — see [PostgresEltmService.exportAll] for the
 * same rationale); the parsed-but-discarded `vector(2000)` per row is
 * pure waste. Ambient transaction.
 */
internal fun selectNoteContent() = EltmNotes.select(
    EltmNotes.id,
    EltmNotes.entityId,
    EltmNotes.relationshipId,
    EltmNotes.eventDate,
    EltmNotes.note,
)

/**
 * The subject's newest note (event date, then id — the same ordering as
 * [noteQuery] and [noteCountsAndLatest]; the diary ordering rule lives
 * in exactly those three SQL spots).
 * The subject is ONE of the two note columns (the
 * migration CHECK), so callers pass the matching column. Ambient
 * transaction.
 */
internal fun latestNote(column: Column<Long?>, subjectId: Long): EltmNote? =
    selectNoteContent().where { column eq subjectId }
        .orderBy(EltmNotes.eventDate to SortOrder.DESC, EltmNotes.id to SortOrder.DESC)
        .limit(1).singleOrNull()?.toNote()

/** The subject's diary-note count. Ambient transaction. */
internal fun countNotes(column: Column<Long?>, subjectId: Long): Int =
    EltmNotes.selectAll().where { column eq subjectId }.count().toInt()

/**
 * Per-subject note counts and latest notes (by event date, then id — the
 * same ordering as [noteQuery]/[latestNote]; the diary ordering rule
 * lives in exactly those three SQL spots) for a whole page of subjects,
 * in TWO bounded queries — a `GROUP BY` count and a `DISTINCT ON`
 * latest-note select — each returning at most one row per subject,
 * never materializing the subjects' whole diaries in memory (a heavy
 * diary must not make its page reads heavy). Ambient transaction.
 */
internal fun noteCountsAndLatest(
    column: Column<Long?>,
    subjectIds: List<Long>,
): Map<Long, Pair<Int, EltmNote?>> {
    if (subjectIds.isEmpty()) return emptyMap()
    // the counts aggregate server-side: one output row per subject
    val countExpr = EltmNotes.id.count()
    val counts: Map<Long, Int> = EltmNotes.select(column, countExpr)
        .where { column inList subjectIds }
        .groupBy(column)
        .mapNotNull { row -> row[column]?.let { it to row[countExpr].toInt() } }
        .toMap()
    // the latest note per subject: DISTINCT ON keeps the first row per
    // subject under the ordering — exactly the newest. `withDistinctOn`
    // prepends the subject column to ORDER BY itself, satisfying
    // Postgres's leftmost-ORDER-BY requirement on DISTINCT ON.
    val latest: Map<Long, EltmNote> = selectNoteContent()
        .where { column inList subjectIds }
        .withDistinctOn(column to SortOrder.ASC)
        .orderBy(EltmNotes.eventDate to SortOrder.DESC, EltmNotes.id to SortOrder.DESC)
        .map { it.toNote() }
        // a note's subject is exactly one of the two columns (migration
        // CHECK), so the fallback chain only masks a broken schema
        .associateBy { it.entityId ?: it.relationshipId ?: error("note has no subject") }
    return subjectIds.associateWith { id -> (counts[id] ?: 0) to latest[id] }
}

/**
 * Insert a whole batch of diary notes for ONE subject (exactly one of
 * [entityId]/[relationshipId] is non-null — the migration CHECK) as ONE
 * JDBC batched INSERT (the generated ids read back aligned with the
 * input rows — no unique constraint exists on the add-only notes table,
 * so no conflict can skew the association), never one INSERT statement
 * per note. The stored notes come back in input order. Ambient
 * transaction.
 */
internal fun insertNotes(
    entityId: Long?,
    relationshipId: Long?,
    drafts: List<NoteDraft>,
    embeddings: List<List<Float>>,
): List<EltmNote> {
    // the zip below must not silently truncate a misaligned pair — fail
    // fast instead (fail-fast design)
    require(embeddings.size == drafts.size) {
        "embeddings (${embeddings.size}) must align with drafts (${drafts.size})"
    }
    return EltmNotes.batchInsert(
        drafts.zip(embeddings),
        shouldReturnGeneratedValues = true,
    ) { (draft, embedding) ->
        this[EltmNotes.entityId] = entityId
        this[EltmNotes.relationshipId] = relationshipId
        this[EltmNotes.eventDate] = draft.eventDate
        this[EltmNotes.note] = draft.note
        this[EltmNotes.embedding] = embedding
    }.mapIndexed { index, row ->
        val draft = drafts[index]
        EltmNote(
            id = row[EltmNotes.id],
            entityId = entityId,
            relationshipId = relationshipId,
            eventDate = draft.eventDate,
            note = draft.note,
        )
    }
}

/** Trim the note text and fail fast on a blank one (the stored form). */
internal fun NoteDraft.validated(): NoteDraft {
    val trimmed = note.trim()
    require(trimmed.isNotBlank()) { "note must not be blank" }
    return NoteDraft(eventDate, trimmed)
}

internal fun ResultRow.toNote(): EltmNote = EltmNote(
    id = this[EltmNotes.id],
    entityId = this[EltmNotes.entityId],
    relationshipId = this[EltmNotes.relationshipId],
    eventDate = this[EltmNotes.eventDate],
    note = this[EltmNotes.note],
)
