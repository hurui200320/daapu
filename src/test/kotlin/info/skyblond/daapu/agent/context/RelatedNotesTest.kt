package info.skyblond.daapu.agent.context

import info.skyblond.daapu.hand.FakeHand
import info.skyblond.daapu.memory.eltm.EltmService
import info.skyblond.daapu.memory.eltm.TornRelationshipReadException
import info.skyblond.daapu.memory.eltm.model.EltmEntity
import info.skyblond.daapu.memory.eltm.model.EltmNote
import info.skyblond.daapu.memory.eltm.model.EntityView
import info.skyblond.daapu.memory.eltm.model.EntityWithScore
import info.skyblond.daapu.memory.eltm.model.ResolvedRelationship
import info.skyblond.daapu.testutil.DbTestBase
import info.skyblond.daapu.testutil.testPostgresEltmService
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Pins [resolveRelatedNotes]: diary-note search hits become name-identified
 * [RelatedNoteView]s — an entity subject reuses the search's own hits before
 * the batched ELTM fallback, a relationship subject resolves through the
 * batched endpoints read (endpoint names + verb), and a note whose subject cannot
 * be resolved is skipped (never rendered with partial ids) while an
 * impossible one (no subject at all) fails loudly. Runs against the real
 * test PostgreSQL; the notes themselves stay synthetic (the function reads
 * only the subjects through the service).
 */
class RelatedNotesTest : DbTestBase() {

    private val eltm = testPostgresEltmService(FakeHand())

    private fun hit(entity: EltmEntity) = EntityWithScore(
        view = EntityView(
            entity = entity,
            noteCount = 0,
            latestNote = null,
            relationshipCount = 0,
            attributes = emptyMap(),
        ),
        score = 1.0,
    )

    private fun note(entityId: Long?, relationshipId: Long?, id: Long, text: String) = EltmNote(
        id = id,
        entityId = entityId,
        relationshipId = relationshipId,
        eventDate = LocalDate.of(2026, 8, 1),
        note = text,
    )

    @Test
    fun `an entity subject is resolved from the search hits`() = runBlocking {
        val alice = eltm.createEntity("alice", "person").entity
        eltm.attachNoteToEntity(alice.id, LocalDate.of(2026, 8, 1), "Met Bob at the conference")

        val views = resolveRelatedNotes(
            eltm,
            notes = listOf(note(alice.id, null, 10, "Met Bob at the conference")),
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
        val bob = eltm.createEntity("bob", "person").entity
        eltm.attachNoteToEntity(bob.id, LocalDate.of(2026, 8, 1), "Met Alice at the conference")

        // bob carries the note but is NOT among the search hits
        val views = resolveRelatedNotes(
            eltm,
            notes = listOf(note(bob.id, null, 11, "Met Alice at the conference")),
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
        val alice = eltm.createEntity("alice", "person").entity

        val views = resolveRelatedNotes(
            eltm,
            notes = listOf(
                note(99, null, 12, "ghost entity note"),
                note(alice.id, null, 13, "real note"),
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
        val alice = eltm.createEntity("alice", "person").entity
        val acme = eltm.createEntity("acme", "company").entity
        val rel = eltm.createRelationship(alice.id, acme.id, "works at").relationship
        eltm.attachNoteToRelationship(rel.id, LocalDate.of(2026, 7, 15), "Joined Acme as an engineer")

        val views = resolveRelatedNotes(
            eltm,
            notes = listOf(note(null, rel.id, 15, "Joined Acme as an engineer")),
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
    fun `a mixed batch resolves in one pass, preserving order and skipping ghosts`() = runBlocking {
        val alice = eltm.createEntity("alice", "person").entity
        val acme = eltm.createEntity("acme", "company").entity
        val rel = eltm.createRelationship(alice.id, acme.id, "works at").relationship

        // alice reuses the search hits, acme falls back to the batched
        // getEntitiesByIds, the relationship to getResolvedRelationships;
        // both ghost kinds drop out, order preserved
        val views = resolveRelatedNotes(
            eltm,
            notes = listOf(
                note(acme.id, null, 21, "acme note"),
                note(4242L, null, 22, "ghost entity note"),
                note(null, rel.id, 23, "rel note"),
                note(alice.id, null, 24, "alice note"),
                note(null, 4242L, 25, "ghost relationship note"),
            ),
            knownEntities = listOf(hit(alice)),
        )

        assertEquals(listOf(21L, 23L, 24L), views.map { it.id })
        assertEquals(
            linkedMapOf("name" to "acme", "category" to "company"),
            views[0].subjectAttributes,
        )
        assertEquals(
            linkedMapOf("src-name" to "alice", "verb" to "works_at", "dst-name" to "acme"),
            views[1].subjectAttributes,
        )
        assertEquals(
            linkedMapOf("name" to "alice", "category" to "person"),
            views[2].subjectAttributes,
        )
    }

    @Test
    fun `an empty batch resolves nothing without touching the store`() = runBlocking {
        assertEquals(
            emptyList(),
            resolveRelatedNotes(eltm, notes = emptyList(), knownEntities = emptyList()),
        )
    }

    @Test
    fun `a torn relationship read is retried once, then fails loudly`() = runBlocking {
        val alice = eltm.createEntity("alice", "person").entity
        val acme = eltm.createEntity("acme", "company").entity
        val rel = eltm.createRelationship(alice.id, acme.id, "works at").relationship

        // a wrapper whose FIRST getResolvedRelationships throws the
        // torn-read fail-fast (a concurrent merge landing mid-read) and
        // whose second answers normally: the injection retries once and
        // resolves. Every other call delegates to the real service.
        val attempts = AtomicInteger(0)
        val flaky = object : EltmService by eltm {
            override suspend fun getResolvedRelationships(ids: List<Long>): Map<Long, ResolvedRelationship> {
                if (attempts.getAndIncrement() == 0) {
                    throw TornRelationshipReadException(listOf(rel.id))
                }
                return eltm.getResolvedRelationships(ids)
            }
        }
        val views = resolveRelatedNotes(
            flaky,
            notes = listOf(note(null, rel.id, 31, "rel note")),
            knownEntities = emptyList(),
        )
        assertEquals(listOf(31L), views.map { it.id })
        assertEquals(
            linkedMapOf("src-name" to "alice", "verb" to "works_at", "dst-name" to "acme"),
            views.single().subjectAttributes,
        )
        assertEquals(2, attempts.get(), "exactly one retry")

        // a PERSISTENT break (not a race) still fails loudly after the one
        // retry instead of silently dropping the note
        val alwaysBroken = object : EltmService by eltm {
            override suspend fun getResolvedRelationships(ids: List<Long>): Map<Long, ResolvedRelationship> {
                throw TornRelationshipReadException(listOf(rel.id))
            }
        }
        val persistent = assertFailsWith<TornRelationshipReadException> {
            resolveRelatedNotes(
                alwaysBroken,
                notes = listOf(note(null, rel.id, 32, "rel note")),
                knownEntities = emptyList(),
            )
        }
        assertTrue(persistent.message!!.contains(rel.id.toString()), persistent.message)

        // an UNRELATED failure is never retried: only the torn-read
        // fail-fast gets the one retry (see resolveRelationshipsRetrying)
        val explosion = object : EltmService by eltm {
            override suspend fun getResolvedRelationships(ids: List<Long>): Map<Long, ResolvedRelationship> {
                throw IllegalStateException("boom")
            }
        }
        val unrelated = assertFailsWith<IllegalStateException> {
            resolveRelatedNotes(
                explosion,
                notes = listOf(note(null, rel.id, 33, "rel note")),
                knownEntities = emptyList(),
            )
        }
        assertEquals("boom", unrelated.message)
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
