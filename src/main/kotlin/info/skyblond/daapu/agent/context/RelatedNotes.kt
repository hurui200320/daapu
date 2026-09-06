package info.skyblond.daapu.agent.context

import info.skyblond.daapu.memory.eltm.EltmService
import info.skyblond.daapu.memory.eltm.TornRelationshipReadException
import info.skyblond.daapu.memory.eltm.model.*
import kotlinx.coroutines.CancellationException

/**
 * Turn raw diary-note search hits into the injection's [RelatedNoteView]
 * list by resolving each note's subject to NAMES (the render carries no
 * ids-only references): an entity subject reuses the search's own hits
 * when the note's entity is among them, otherwise the batched
 * [EltmService.getEntitiesByIds] fallback; a relationship subject resolves
 * via the batched [EltmService.getResolvedRelationships] (which carries
 * the endpoint names and the verb). A note whose subject LOOKUP fails (the
 * resolver returns null) is skipped rather than rendered with partial ids;
 * a note with NO subject at all cannot happen under the notes CHECK and
 * fails loudly via [error] instead of being silently dropped.
 *
 * The subjects resolve in TWO batched reads (one `getEntitiesByIds`, one
 * `getResolvedRelationships` — each ONE short transaction), never one
 * transaction per note: the chat loop calls this with up to
 * `memory.eltm.relatedNotesLimit` hits on the request path, and the
 * per-note form paid a full view read per note (counts + latest note +
 * attributes each) plus a pooled connection per concurrent child. Only
 * the rendered identity is read. Order is preserved (input order).
 *
 * Torn-read retry: the relationship batch read fails fast when a found row
 * lost its endpoint mid-read (a concurrent merge landing between the
 * row read and the endpoint read — see `checkAllResolved` in
 * `EltmRelationshipQueries.kt`). The service keeps that fail-fast stance
 * (browse pages must never silently drop rows and punch holes in
 * limit/offset walks), but on this request path a millisecond-window race
 * must not 500 the chat run: the batch is retried ONCE, and only then does
 * the failure propagate (a persistent break is a broken state worth
 * failing loudly, and the retry succeeds against a settled merge).
 */
suspend fun resolveRelatedNotes(
    eltmService: EltmService,
    notes: List<EltmNote>,
    knownEntities: List<EntityWithScore>,
): List<RelatedNoteView> {
    if (notes.isEmpty()) return emptyList()
    val knownById: Map<Long, EltmEntity> =
        knownEntities.associate { it.view.entity.id to it.view.entity }
    val missingEntityIds = notes.mapNotNull { it.entityId }
        .filter { it !in knownById }
        .distinct()
    val entityMap: Map<Long, EltmEntity> = knownById +
            if (missingEntityIds.isEmpty()) emptyMap()
            else eltmService.getEntitiesByIds(missingEntityIds)
    val relIds = notes.mapNotNull { it.relationshipId }.distinct()
    val relMap: Map<Long, ResolvedRelationship> =
        if (relIds.isEmpty()) emptyMap()
        else resolveRelationshipsRetrying(eltmService, relIds)
    return notes.mapNotNull { note ->
        resolveOne(note, entityMap, relMap)
    }
}

/**
 * The `getResolvedRelationships` batch with ONE retry on the torn-read
 * fail-fast ([TornRelationshipReadException] from `checkAllResolved`): a
 * concurrent merge landing between the row read and the endpoint read
 * settles by the second attempt. Cancellation is never retried; any other
 * failure (and a second torn read — a persistent break, not a race)
 * propagates.
 */
private suspend fun resolveRelationshipsRetrying(
    eltmService: EltmService,
    relIds: List<Long>,
): Map<Long, ResolvedRelationship> {
    try {
        return eltmService.getResolvedRelationships(relIds)
    } catch (e: CancellationException) {
        throw e
    } catch (e: TornRelationshipReadException) {
        return eltmService.getResolvedRelationships(relIds)
    }
}

private fun resolveOne(
    note: EltmNote,
    entityMap: Map<Long, EltmEntity>,
    relMap: Map<Long, ResolvedRelationship>,
): RelatedNoteView? {
    return when {
        note.entityId != null -> {
            val entity = entityMap[note.entityId]
                ?: return null
            RelatedNoteView(
                id = note.id,
                eventDate = note.eventDate,
                subjectType = "entity",
                subjectAttributes = linkedMapOf(
                    "name" to entity.canonicalName,
                    "category" to entity.category,
                ),
                note = note.note,
            )
        }

        note.relationshipId != null -> {
            val resolved = relMap[note.relationshipId]
                ?: return null
            RelatedNoteView(
                id = note.id,
                eventDate = note.eventDate,
                subjectType = "relationship",
                subjectAttributes = linkedMapOf(
                    "src-name" to resolved.src.canonicalName,
                    "verb" to resolved.verb,
                    "dst-name" to resolved.dst.canonicalName,
                ),
                note = note.note,
            )
        }

        else -> error("Impossible: the notes CHECK enforces exactly one subject")
    }
}
