package info.skyblond.daapu.server

import info.skyblond.daapu.memory.eltm.EltmEntity
import info.skyblond.daapu.memory.eltm.EltmNote
import info.skyblond.daapu.memory.eltm.EltmRelationship
import info.skyblond.daapu.memory.eltm.EntityView
import info.skyblond.daapu.memory.eltm.RelationshipView
import info.skyblond.daapu.server.EltmEntityDto.Companion.toDto
import info.skyblond.daapu.server.EltmNoteDto.Companion.toDto
import info.skyblond.daapu.server.EltmRelationshipDto.Companion.toDto
import kotlinx.serialization.Serializable

/**
 * Request body of `POST /api/chats/{id}/messages`.
 *
 * At least one of [text] or [images] must be present. [model] and
 * [personaId] are required (the web UI picks both per message; there is no
 * server-side default — a chat's `persona_id` column is only a record, the
 * run's persona always travels with the request). The reserved id 0 is the
 * code-only default persona.
 */
@Serializable
data class SendMessageRequest(
    val text: String? = null,
    val images: List<ImagePart> = emptyList(),
    val model: String? = null,
    val personaId: Long? = null,
)

/**
 * Request body of `POST /api/personas` and `PUT /api/personas/{id}`.
 * [allowedNamespaces] is a tool-namespace whitelist over the chat loop's
 * tool set; an empty list means ALL namespaces the loop serves.
 */
@Serializable
data class PersonaSaveRequest(
    val name: String,
    val systemPrompt: String,
    val allowedNamespaces: List<String> = emptyList(),
)

/**
 * One image attached to a message, as a `data:image/<format>;base64,<data>`
 * data URL (the same format `FileReader.readAsDataURL` produces).
 */
@Serializable
data class ImagePart(val dataUrl: String)

@Serializable
data class ChatIdResponse(val id: String)

/**
 * Request body of `PUT /api/chats/{id}`. [title] must be non-blank.
 */
@Serializable
data class RenameChatRequest(val title: String)

@Serializable
data class ModelInfo(
    val id: String,
    val vision: Boolean,
    val contextLength: Long,
    val maxOutputTokens: Long,
)

// ----------------------------------------------------------------------
// ELTM views (the `#/eltm` frontend tab): read views for the browse tabs,
// plus the manual import write's request body (the ONLY endpoint where a
// caller, not the extraction pipeline, feeds the ELTM writer)
// ----------------------------------------------------------------------

/**
 * Request body of `POST /api/eltm/import`: a manual fact batch fed straight
 * into `EltmWriterService.writeToEltm` (the same writer the extraction
 * pipeline uses). [facts] must already be written in the memory extractor's
 * output tone — self-contained facts, absolute dates; the writer records
 * verbatim, it never rewrites messy prose (the frontend's Import tab carries
 * the full notice). [date] is the optional fallback event date
 * (`YYYY-MM-DD`); the server's today when absent, and never a future day.
 */
@Serializable
data class EltmImportRequest(
    val facts: String,
    val date: String? = null,
)

@Serializable
data class EltmEntityDto(
    val id: Long,
    val canonicalName: String,
    val category: String,
) {
    companion object {
        fun EltmEntity.toDto() = EltmEntityDto(
            id = id,
            canonicalName = canonicalName,
            category = category,
        )
    }
}

@Serializable
data class EltmRelationshipDto(
    val id: Long,
    val srcId: Long,
    val dstId: Long,
    val verb: String,
    val valid: Boolean,
) {
    companion object {
        fun EltmRelationship.toDto() = EltmRelationshipDto(
            id = id,
            srcId = srcId,
            dstId = dstId,
            verb = verb,
            valid = valid,
        )
    }
}

@Serializable
data class EltmNoteDto(
    val id: Long,
    val entityId: Long?,
    val relationshipId: Long?,
    val eventDate: String,
    val note: String,
    val createdAt: String,
) {
    companion object {
        fun EltmNote.toDto() = EltmNoteDto(
            id = id,
            entityId = entityId,
            relationshipId = relationshipId,
            eventDate = eventDate.toString(),
            note = note,
            createdAt = createdAt.toString(),
        )
    }
}

@Serializable
data class EntityViewDto(
    val entity: EltmEntityDto,
    val noteCount: Int,
    val relationshipCount: Int,
    val latestNote: EltmNoteDto?,
    /** Current-state key-value facts, keys alphabetically ordered. */
    val attributes: Map<String, String>,
) {
    companion object {
        fun EntityView.toDto() = EntityViewDto(
            entity = entity.toDto(),
            noteCount = noteCount,
            relationshipCount = relationshipCount,
            latestNote = latestNote?.toDto(),
            attributes = attributes.toSortedMap(),
        )
    }
}

@Serializable
data class RelationshipViewDto(
    val relationship: EltmRelationshipDto,
    val srcName: String,
    val dstName: String,
    val noteCount: Int,
    val latestNote: EltmNoteDto?,
) {
    companion object {
        fun RelationshipView.toDto() = RelationshipViewDto(
            relationship = relationship.toDto(),
            srcName = srcName,
            dstName = dstName,
            noteCount = noteCount,
            latestNote = latestNote?.toDto(),
        )
    }
}
