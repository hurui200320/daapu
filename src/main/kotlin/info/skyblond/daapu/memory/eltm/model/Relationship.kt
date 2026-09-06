package info.skyblond.daapu.memory.eltm.model

import info.skyblond.daapu.memory.eltm.EltmService

/**
 * One ELTM relationship: a directed edge (source entity, verb, destination
 * entity) with a structural [valid] state. Storage semantics (one row per
 * triple, the flag vs the content-bearing diary notes) in
 * [info.skyblond.daapu.db.EltmRelationships]; an ending invalidates it, and
 * a re-establishment is a diary event (a note with `valid=true`).
 */
data class EltmRelationship(
    val id: Long,
    val srcId: Long,
    val dstId: Long,
    val verb: String,
    val valid: Boolean,
)

/**
 * An app-level relationship: the [EltmRelationship] row's identity with
 * both endpoints resolved to full [EltmEntity] objects — the single
 * nesting every reader renders from (names for display, ids for
 * follow-up reads), so no consumer re-resolves numeric ids to names.
 */
data class ResolvedRelationship(
    val id: Long,
    val src: EltmEntity,
    val verb: String,
    val dst: EltmEntity,
    val valid: Boolean,
)

/**
 * A relationship read view: the resolved relationship plus its
 * diary-note count and its latest diary note inline.
 */
data class RelationshipView(
    val relationship: ResolvedRelationship,
    val noteCount: Int,
    val latestNote: EltmNote?,
)

/**
 * One relationship waiting to be created or fetched by its triple: the
 * draft for the bulk create ([EltmService.createRelationships]). The verb
 * is normalized exactly like [EltmService.createRelationship]'s argument.
 */
data class RelationshipDraft(
    val srcId: Long,
    val verb: String,
    val dstId: Long,
)
