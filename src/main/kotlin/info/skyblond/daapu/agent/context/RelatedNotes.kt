package info.skyblond.daapu.agent.context

import info.skyblond.daapu.memory.eltm.EltmNote
import info.skyblond.daapu.memory.eltm.EltmService
import info.skyblond.daapu.memory.eltm.EntityWithScore

/**
 * Turn raw diary-note search hits into the injection's [RelatedNoteView]
 * list by resolving each note's subject to NAMES (the render carries no
 * ids-only references): an entity subject reuses the search's own hits
 * when the note's entity is among them, otherwise an
 * [EltmService.getEntity] fallback; a relationship subject resolves via
 * [EltmService.getRelationship] (which carries the endpoint names and the
 * verb). A note whose subject LOOKUP fails (the resolver returns null) is
 * skipped rather than rendered with partial ids; a note with NO subject at
 * all cannot happen under the notes CHECK and fails loudly via [error]
 * instead of being silently dropped.
 */
suspend fun resolveRelatedNotes(
    eltmService: EltmService,
    notes: List<EltmNote>,
    knownEntities: List<EntityWithScore>,
): List<RelatedNoteView> = notes.mapNotNull { note ->
    when {
        note.entityId != null -> {
            val entity = knownEntities.firstOrNull {
                it.entity.id == note.entityId
            }?.entity
                ?: eltmService.getEntity(note.entityId)?.entity
                ?: return@mapNotNull null
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
            val relationship = eltmService.getRelationship(note.relationshipId)
                ?: return@mapNotNull null
            RelatedNoteView(
                id = note.id,
                eventDate = note.eventDate,
                subjectType = "relationship",
                subjectAttributes = linkedMapOf(
                    "src-name" to relationship.srcName,
                    "verb" to relationship.relationship.verb,
                    "dst-name" to relationship.dstName,
                ),
                note = note.note,
            )
        }

        else -> error("Impossible: the notes CHECK enforces exactly one subject")
    }
}
