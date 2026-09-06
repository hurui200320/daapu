package info.skyblond.daapu.memory.eltm.model

import info.skyblond.daapu.memory.eltm.EltmService
import java.time.LocalDate

/**
 * One ELTM diary note: an add-only entry attached to exactly ONE subject (an
 * entity or a relationship), carrying the LLM-resolved absolute [eventDate]
 * of the event. A new note supersedes older information; nothing is ever
 * removed.
 */
data class EltmNote(
    val id: Long,
    val entityId: Long?,
    val relationshipId: Long?,
    val eventDate: LocalDate,
    val note: String,
)

/**
 * One diary note waiting to be attached: the absolute event date plus the
 * note text (trimmed and blank-checked by the store, like a stored note).
 * The draft for the bulk attaches ([EltmService.attachNotesToEntity] /
 * [EltmService.attachNotesToRelationship]); the stored row is [EltmNote].
 */
data class NoteDraft(
    val eventDate: LocalDate,
    val note: String,
)
