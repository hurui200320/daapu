package info.skyblond.daapu.memory.eltm

/**
 * A relationship batch read found rows whose endpoints are gone: a live
 * row's endpoints exist (the merge path re-points relationships before
 * deleting their loser endpoints, and the FK cascade deletes a
 * relationship with its endpoint), so a mismatch is either a broken state
 * or a concurrent merge landing between the row read and the endpoint read
 * (a torn read) — never silently dropped, which would punch holes in
 * limit/offset pages.
 *
 * Extends [IllegalStateException] so existing fail-fast handling keeps
 * catching it, while readers that must tolerate the millisecond-window
 * race (the context injection's related-note resolution) catch exactly
 * this type for their one retry instead of every [IllegalStateException].
 */
class TornRelationshipReadException(
    /** The ids of the rows whose endpoint was missing. */
    val relationshipIds: List<Long>,
    message: String = "relationships $relationshipIds reference entities that no longer exist",
) : IllegalStateException(message)
