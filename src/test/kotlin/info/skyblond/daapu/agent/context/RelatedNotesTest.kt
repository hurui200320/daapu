package info.skyblond.daapu.agent.context

import info.skyblond.daapu.memory.eltm.EltmEntity
import info.skyblond.daapu.memory.eltm.EltmNote
import info.skyblond.daapu.memory.eltm.EntityWithScore
import info.skyblond.daapu.testutil.FakeEltmService
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Pins [resolveRelatedNotes]: diary-note search hits become name-identified
 * [RelatedNoteView]s — an entity subject reuses the search's own hits before
 * the ELTM `getEntity` fallback, a relationship subject resolves through
 * `getRelationship` (endpoint names + verb), and a note whose subject cannot
 * be resolved is skipped (never rendered with partial ids) while an
 * impossible one (no subject at all) fails loudly.
 */
class RelatedNotesTest {

    private val eltm = FakeEltmService()

    private fun hit(entity: EltmEntity) = EntityWithScore(
        entity = entity,
        noteCount = 0,
        latestNote = null,
        relationshipCount = 0,
        score = 1.0,
        attributes = emptyMap(),
    )

    private fun note(entityId: Long?, relationshipId: Long?, id: Long, text: String) = EltmNote(
        id = id,
        entityId = entityId,
        relationshipId = relationshipId,
        eventDate = LocalDate.of(2026, 8, 1),
        note = text,
        createdAt = OffsetDateTime.parse("2026-08-01T09:00:00Z"),
    )

    @Test
    fun `an entity subject is resolved from the search hits`() = runBlocking {
        eltm.createEntity("alice", "person")
        eltm.attachNoteToEntity(1, LocalDate.of(2026, 8, 1), "Met Bob at the conference")
        val alice = eltm.entities.getValue(1)

        val views = resolveRelatedNotes(
            eltm,
            notes = listOf(note(1, null, 10, "Met Bob at the conference")),
            knownEntities = listOf(hit(alice)),
        )

        val view = views.single()
        assertEquals("entity", view.subjectType)
        assertEquals(10, view.id)
        assertEquals("Met Bob at the conference", view.note)
        assertEquals(
            linkedMapOf("name" to "alice", "category" to "person"),
            view.subjectAttributes,
        )
    }

    @Test
    fun `an entity subject outside the search hits falls back to getEntity`() = runBlocking {
        eltm.createEntity("bob", "person")
        eltm.attachNoteToEntity(1, LocalDate.of(2026, 8, 1), "Met Alice at the conference")
        val bob = eltm.entities.getValue(1)

        // bob carries the note but is NOT among the search hits
        val views = resolveRelatedNotes(
            eltm,
            notes = listOf(note(1, null, 11, "Met Alice at the conference")),
            knownEntities = emptyList(),
        )

        val view = views.single()
        assertEquals("entity", view.subjectType)
        assertEquals(
            linkedMapOf("name" to bob.canonicalName, "category" to "person"),
            view.subjectAttributes,
        )
    }

    @Test
    fun `a note whose subject cannot be resolved is skipped`() = runBlocking {
        eltm.createEntity("alice", "person")
        val alice = eltm.entities.getValue(1)

        val views = resolveRelatedNotes(
            eltm,
            notes = listOf(
                note(99, null, 12, "ghost entity note"),
                note(1, null, 13, "real note"),
                note(null, 99, 14, "ghost relationship note"),
            ),
            knownEntities = listOf(hit(alice)),
        )

        // both unresolvable subject kinds drop out; order is preserved
        assertEquals(listOf(13L), views.map { it.id })
        assertEquals("entity", views.single().subjectType)
    }

    @Test
    fun `a relationship subject resolves to the endpoint names and the verb`() = runBlocking {
        eltm.createEntity("alice", "person")
        eltm.createEntity("acme", "company")
        eltm.createRelationship(1, 2, "works at")
        eltm.attachNoteToRelationship(1, LocalDate.of(2026, 7, 15), "Joined Acme as an engineer")

        val views = resolveRelatedNotes(
            eltm,
            notes = listOf(note(null, 1, 15, "Joined Acme as an engineer")),
            knownEntities = emptyList(),
        )

        val view = views.single()
        assertEquals("relationship", view.subjectType)
        assertEquals(15, view.id)
        assertEquals(
            linkedMapOf("src-name" to "alice", "verb" to "works_at", "dst-name" to "acme"),
            view.subjectAttributes,
        )
        assertEquals("Joined Acme as an engineer", view.note)
    }

    @Test
    fun `a note with no subject at all fails loudly`() {
        // impossible under the notes CHECK (exactly one subject): never
        // silently dropped — the invariant breach must surface
        val impossible = note(null, null, 16, "no subject")
        assertFailsWith<IllegalStateException> {
            runBlocking { resolveRelatedNotes(eltm, notes = listOf(impossible), knownEntities = emptyList()) }
        }
    }
}
