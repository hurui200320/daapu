package info.skyblond.daapu.memory.eltm.postgres

import info.skyblond.daapu.db.ELTM_VERSION_KEY
import info.skyblond.daapu.db.EltmEntities
import info.skyblond.daapu.db.EltmRelationships
import info.skyblond.daapu.db.readMetaCounterTx
import info.skyblond.daapu.db.withTransaction
import info.skyblond.daapu.hand.EmbeddingException
import info.skyblond.daapu.hand.FakeHand
import info.skyblond.daapu.hand.HandEmbedRequest
import info.skyblond.daapu.hand.HandEmbedResult
import info.skyblond.daapu.hand.HandEmbedUsage
import info.skyblond.daapu.memory.eltm.*
import info.skyblond.daapu.memory.eltm.model.*
import info.skyblond.daapu.testutil.DbTestBase
import info.skyblond.daapu.testutil.DeterministicEmbeddings
import info.skyblond.daapu.testutil.TestDb
import info.skyblond.daapu.testutil.testAxisVector
import info.skyblond.daapu.testutil.testEmbeddingModel
import info.skyblond.daapu.testutil.testPostgresEltmService
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * DB-backed tests for [PostgresEltmService] against the throwaway
 * testcontainers PostgreSQL (`testutil/TestDb.kt`). These are the SQL paths the fakes
 * cannot cover: the ON CONFLICT create-or-fetch adoption, the refine
 * collision handling, the merge fold, the counter bumps, and the vector
 * search semantics (embeddings are scripted through the hand seam via
 * [DeterministicEmbeddings], so similarity outcomes are exact).
 */
class PostgresEltmServiceTest : DbTestBase() {

    private val dims = testEmbeddingModel().dimensions

    private val day = LocalDate.of(2026, 8, 17)

    /** The default FakeHand embed response: one all-ones vector per input. */
    private fun allOnesResult(request: HandEmbedRequest) = HandEmbedResult(
        vectors = request.input.map { List(request.dimensions) { 1f } },
        dimensions = request.dimensions,
        usage = HandEmbedUsage(
            promptTokens = request.input.sumOf { it.length },
            totalTokens = request.input.sumOf { it.length },
        ),
    )

    private fun service(hand: FakeHand = FakeHand()) = testPostgresEltmService(hand)

    // ------------------------------------------------------------------
    // createEntity
    // ------------------------------------------------------------------

    @Test
    fun `createEntity normalizes, bumps once, and the exact match is a pure read`() = runBlocking {
        val hand = FakeHand(embedScript = DeterministicEmbeddings().script)
        val service = service(hand)

        assertEquals("0", service.version())
        val created = service.createEntity("  Kindle ", "Device")
        assertEquals("kindle", created.entity.canonicalName)
        assertEquals("device", created.entity.category)
        assertTrue(created.nearMatches.isEmpty(), "a lone entity has no near matches")
        assertEquals("1", service.version(), "only a real insert bumps")

        // the embedding text is the composed name + category
        assertEquals(listOf(listOf("kindle device")), hand.embedRequests.map { it.input })

        val again = service.createEntity("KINDLE", " device ")
        assertEquals(created.entity.id, again.entity.id, "the exact match returns the same row")
        assertEquals("1", service.version(), "the exact match does not bump")
        assertEquals(1, hand.embedRequests.size, "the exact match embeds nothing")
    }

    @Test
    fun `createEntity adopts a concurrent insert through the ON CONFLICT path`() = runBlocking {
        // the "concurrent run": a second service with its own hand, creating
        // the same (name, category) while the service under test is between
        // its pre-check and its insert — the embed call is exactly that gap.
        // The racing insert FULLY COMMITS before the under-test insert runs,
        // so this pins the ON CONFLICT adoption + same-transaction re-select;
        // the in-flight variant (the under-test insert BLOCKS on the
        // speculative token until the winner resolves, then sees the row) is
        // the same code path with a harder-to-script interleaving.
        val racingService = service(FakeHand())
        var racingId = -1L
        val hand = FakeHand(embedScript = { request ->
            racingId = racingService.createEntity("kindle", "device").entity.id
            allOnesResult(request)
        })
        val service = service(hand)

        val adopted = service.createEntity("Kindle", "Device")
        assertTrue(racingId > 0, "the racing insert ran inside the embed call")
        assertEquals(racingId, adopted.entity.id, "the conflict adopts the committed row")
        assertEquals("1", service.version(), "only the racing insert bumped the counter")
        assertEquals(1, TestDb.allEltmEntities().size, "no duplicate row was written")
    }

    @Test
    fun `createEntity reports near matches above the threshold, self excluded`() = runBlocking {
        val embeddings = DeterministicEmbeddings()
        val v1 = testAxisVector(0)
        // cos(v1, v2) = 0.8 >= entityMatchThreshold 0.5
        val v2 = List(dims) { if (it == 0) 0.8f else if (it == 1) 0.6f else 0f }
        embeddings.register("kindle device", v1)
        embeddings.register("kindle gadget", v2)
        val service = service(FakeHand(embedScript = embeddings.script))

        val first = service.createEntity("Kindle", "Device")
        assertTrue(first.nearMatches.isEmpty(), "the first create has no other entity to match")

        val second = service.createEntity("Kindle", "Gadget")
        assertEquals(listOf(first.entity.id), second.nearMatches.map { it.view.entity.id })
        assertEquals(0.8, second.nearMatches.single().score, 1e-6)
    }

    // ------------------------------------------------------------------
    // refineEntity
    // ------------------------------------------------------------------

    @Test
    fun `refineEntity renames in place, keeps attachments, bumps once`() = runBlocking {
        val service = service()
        val created = service.createEntity("kindle", "device").entity
        val note = service.attachNoteToEntity(created.id, day, "bought it")

        val versionBefore = service.version().toLong()
        val refined = service.refineEntity(created.id, " Paperwhite  6 ", null)
        assertEquals(created.id, refined.entity.id, "the id is kept")
        assertEquals("paperwhite 6", refined.entity.canonicalName)
        assertEquals("device", refined.entity.category)
        // the returned view rides the refine's own transaction — no
        // follow-up read needed
        assertEquals(1, refined.noteCount, "the attached note counts")
        assertEquals(listOf(note.id), refined.latestNote?.let { listOf(it.id) })
        assertEquals(versionBefore + 1, service.version().toLong())
        assertEquals(listOf(note.id), service.getEntityNotes(created.id, null, null, 10, 0).map { it.id })
    }

    @Test
    fun `refineEntity with an identical identity is a pure read`() = runBlocking {
        val hand = FakeHand()
        val service = service(hand)
        val created = service.createEntity("kindle", "device").entity
        val versionBefore = service.version().toLong()
        val embedsBefore = hand.embedRequests.size

        val refined = service.refineEntity(created.id, "kindle", "device")
        assertEquals(created, refined.entity)
        assertEquals(versionBefore, service.version().toLong(), "a no-op refine never bumps")
        assertEquals(embedsBefore, hand.embedRequests.size, "a no-op refine never embeds")
    }

    @Test
    fun `refineEntity onto an existing (name, category) fails with the merge-instead error`() =
        runBlocking {
            val service = service()
            val fruit = service.createEntity("apple", "fruit").entity
            service.createEntity("apple", "company")

            val versionBefore = service.version().toLong()
            try {
                service.refineEntity(fruit.id, null, "company")
                fail("a category collision must fail fast")
            } catch (expected: IllegalArgumentException) {
                assertTrue(
                    expected.message!!.contains("merge the two instead"),
                    "the error tells the caller to merge: ${expected.message}",
                )
            }
            assertEquals(versionBefore, service.version().toLong(), "a failed refine never bumps")
            assertEquals("fruit", service.getEntity(fruit.id)?.entity?.category, "nothing moved")
        }

    @Test
    fun `refineEntity onto a concurrently created (name, category) fails with the merge-instead error`() =
        runBlocking {
            // the "concurrent run": a second service creates the colliding
            // (name, category) DURING the refine's embed call — after the
            // pre-check, so only the UPDATE's unique violation can catch it.
            // The script stays inert for the fruit create's own embed call.
            val racing = service()
            var colliding = false
            val hand = FakeHand(embedScript = { request ->
                if (colliding) racing.createEntity("apple", "company")
                allOnesResult(request)
            })
            val service = service(hand)
            val fruit = service.createEntity("apple", "fruit").entity

            val versionBefore = service.version().toLong()
            colliding = true
            try {
                service.refineEntity(fruit.id, null, "company")
                fail("a collision appearing after the pre-check must fail fast")
            } catch (expected: IllegalArgumentException) {
                assertTrue(
                    expected.message!!.contains("merge the two instead"),
                    "the error tells the caller to merge: ${expected.message}",
                )
            }
            // exactly ONE new bump: the racing createEntity's — a failed
            // refine (rolled back) must not add a second one
            assertEquals(versionBefore + 1, service.version().toLong(), "a failed refine never bumps")
            assertEquals("fruit", service.getEntity(fruit.id)?.entity?.category, "nothing moved")
            assertEquals(2, TestDb.allEltmEntities().size, "the colliding row is the racing one")
        }

    @Test
    fun `refineEntity rolls back on an embed failure`() = runBlocking {
        val good = service()
        val created = good.createEntity("kindle", "device").entity
        good.attachNoteToEntity(created.id, day, "a note")
        val versionBefore = good.version().toLong()

        val failing = service(FakeHand(embedScript = { _ ->
            throw EmbeddingException("invalid_request", "content too large")
        }))
        try {
            failing.refineEntity(created.id, "paperwhite", null)
            fail("an embed failure must propagate")
        } catch (expected: EmbeddingException) {
            assertEquals("invalid_request", expected.type)
        }
        assertEquals("kindle", good.getEntity(created.id)?.entity?.canonicalName, "rolled back")
        assertEquals(versionBefore, good.version().toLong(), "rolled back")
        assertEquals(1, good.getEntity(created.id)?.noteCount, "the note survived")
    }

    @Test
    fun `refineEntity on a missing entity fails fast`() = runBlocking {
        val service = service()
        try {
            service.refineEntity(999L, "x", null)
            fail("refining a missing entity must fail")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("does not exist"))
        }
    }

    // ------------------------------------------------------------------
    // createRelationship
    // ------------------------------------------------------------------

    @Test
    fun `createRelationship is a triple create-or-fetch with revive semantics`() = runBlocking {
        val service = service()
        val a = service.createEntity("alice", "person").entity
        val b = service.createEntity("acme", "company").entity

        val view = service.createRelationship(a.id, b.id, "Works At")
        val rel = view.relationship
        assertEquals("works_at", rel.verb, "the verb is normalized")
        assertTrue(rel.valid)
        assertEquals(a, rel.src, "the view's endpoints ride the create's transaction")
        assertEquals(b, rel.dst)
        val versionAfterCreate = service.version().toLong()

        val again = service.createRelationship(a.id, b.id, "works_at").relationship
        assertEquals(rel.id, again.id, "the triple row is the relationship")
        assertEquals(versionAfterCreate, service.version().toLong(), "a re-assert never bumps")

        // end the edge via the diary event, then re-assert: still ONE row,
        // still invalid — validity only moves with a note
        service.attachNoteToRelationship(rel.id, day, "left the company", valid = false)
        val revived = service.createRelationship(a.id, b.id, "works_at").relationship
        assertEquals(rel.id, revived.id)
        assertFalse(revived.valid, "createRelationship never flips validity")
    }

    @Test
    fun `createRelationship fails fast on missing endpoints without embedding`() = runBlocking {
        val hand = FakeHand()
        val service = service(hand)
        val a = service.createEntity("alice", "person").entity
        val versionBefore = service.version().toLong()

        try {
            service.createRelationship(a.id, 4242L, "knows")
            fail("a missing endpoint must fail fast")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("4242"))
        }
        assertEquals(versionBefore, service.version().toLong())
        assertEquals(1, hand.embedRequests.size, "the failure embeds nothing new")
    }

    // ------------------------------------------------------------------
    // bulk creates (the transfer import's create-or-fetch units)
    // ------------------------------------------------------------------

    @Test
    fun `createEntities batches the whole batch into ONE embed call and ONE bump`() = runBlocking {
        val hand = FakeHand()
        val service = service(hand)
        // pre-existing key: the bulk's exact-match path
        val existing = service.createEntity("kindle", "device").entity
        val versionBefore = service.version().toLong()
        val embedsBefore = hand.embedRequests.size

        val created = service.createEntities(
            listOf(
                EntityDraft("  Alice ", "Person"),
                EntityDraft("Kindle", "DEVICE"), // exact match: never re-embedded
                EntityDraft("acme", "company"),
            )
        )
        assertEquals(listOf("alice", "kindle", "acme"), created.map { it.canonicalName })
        assertEquals(listOf("person", "device", "company"), created.map { it.category })
        assertEquals(existing.id, created[1].id, "the exact match folds onto the existing row")
        assertEquals(versionBefore + 1, service.version().toLong(), "ONE bump for the whole batch (2 real inserts)")
        assertEquals(
            embedsBefore + 1,
            hand.embedRequests.size,
            "the missing keys ride ONE batched embed call, never one per entity",
        )
        assertEquals(
            listOf("alice person", "acme company"),
            hand.embedRequests.last().input,
            "only the missing keys are embedded",
        )

        // an all-existing batch is a pure read: no embed, no bump
        val embedsAfter = hand.embedRequests.size
        val again = service.createEntities(
            listOf(EntityDraft("alice", "person"), EntityDraft("kindle", "device"))
        )
        assertEquals(listOf("alice", "kindle"), again.map { it.canonicalName })
        assertEquals(versionBefore + 1, service.version().toLong())
        assertEquals(embedsAfter, hand.embedRequests.size)

        // an empty batch is a no-op
        assertEquals(emptyList<EltmEntity>(), service.createEntities(emptyList()))

        // a blank entry fails the whole call before any work, naming the entry
        try {
            service.createEntities(listOf(EntityDraft("x", "y"), EntityDraft("  ", "z")))
            fail("a blank name must fail fast")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("entry 1"), expected.message)
        }
    }

    @Test
    fun `createEntities folds duplicate keys onto one row`() = runBlocking {
        val service = service()
        val created = service.createEntities(
            listOf(EntityDraft("Alice", "person"), EntityDraft("  ALICE ", "Person"))
        )
        assertEquals(created[0].id, created[1].id, "duplicate keys fold onto ONE row")
        assertEquals(1, TestDb.allEltmEntities().size)
    }

    @Test
    fun `bulk lookups chunk past BULK_QUERY_CHUNK_SIZE without losing rows`() = runBlocking {
        val service = service()
        // an over-chunk batch exercises the chunked OR-query: every key
        // must resolve in input order
        val drafts = (1..(BULK_QUERY_CHUNK_SIZE + 37)).map { EntityDraft("bulk-entity-$it", "bulk") }
        val created = service.createEntities(drafts)
        assertEquals(drafts.size, created.size)
        assertEquals(drafts.size, created.map { it.id }.toSet().size)
        val again = service.createEntities(drafts)
        assertEquals(created.map { it.id }, again.map { it.id }, "re-fetch resolves identically")

        // the relationship half chunks the same way
        val triples = created.take(BULK_QUERY_CHUNK_SIZE + 11).windowed(2, 1) { (a, b) ->
            RelationshipDraft(a.id, "bulk_rel", b.id)
        }
        val rels = service.createRelationships(triples)
        assertEquals(triples.size, rels.size)
        assertEquals(triples.size, rels.map { it.id }.toSet().size)
    }

    @Test
    fun `createRelationships creates the missing triples with ONE bump and validates`() = runBlocking {
        val service = service()
        val a = service.createEntity("alice", "person").entity
        val b = service.createEntity("acme", "company").entity
        val c = service.createEntity("carol", "person").entity
        // pre-existing triple: the bulk's fetch path
        val existing = service.createRelationship(a.id, b.id, "works_at").relationship
        val versionBefore = service.version().toLong()

        val created = service.createRelationships(
            listOf(
                RelationshipDraft(a.id, "knows", c.id),
                RelationshipDraft(a.id, "works at", b.id), // fetch (the verb normalizes)
                RelationshipDraft(c.id, "mentors", b.id),
            )
        )
        assertEquals(listOf("knows", "works_at", "mentors"), created.map { it.verb })
        assertEquals(existing.id, created[1].id, "the existing triple folds onto its row")
        assertTrue(created.all { it.valid })
        assertEquals(versionBefore + 1, service.version().toLong(), "ONE bump for the whole batch (2 real inserts)")
        assertEquals(3, TestDb.allEltmRelationships().size)

        // an all-existing batch is a pure read: no bump
        val again = service.createRelationships(listOf(RelationshipDraft(a.id, "knows", c.id)))
        assertEquals(created[0].id, again[0].id)
        assertEquals(versionBefore + 1, service.version().toLong())

        // a missing endpoint fails the whole call before any insert
        try {
            service.createRelationships(listOf(RelationshipDraft(a.id, "knows", 4242L)))
            fail("a missing endpoint must fail fast")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("4242"))
        }

        // an empty batch is a no-op
        assertEquals(emptyList<EltmRelationship>(), service.createRelationships(emptyList()))
    }

    // ------------------------------------------------------------------
    // notes
    // ------------------------------------------------------------------

    @Test
    fun `attachNoteToEntity trims, bumps, and lists newest-event-first`() = runBlocking {
        val service = service()
        val entity = service.createEntity("kindle", "device").entity
        val versionBefore = service.version().toLong()

        val first = service.attachNoteToEntity(entity.id, day, "  bought it  ")
        assertEquals("bought it", first.note, "the note is trimmed")
        assertEquals(entity.id, first.entityId)
        assertEquals(versionBefore + 1, service.version().toLong())

        val second = service.attachNoteToEntity(entity.id, day, "dropped it")
        val notes = service.getEntityNotes(entity.id, null, null, 10, 0)
        assertEquals(listOf(second.id, first.id), notes.map { it.id }, "same date: id DESC breaks the tie")

        try {
            service.attachNoteToEntity(4242L, day, "x")
            fail("a missing subject must fail fast")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("does not exist"))
        }
    }

    @Test
    fun `attachNoteToRelationship applies the validity change in the SAME bump`() = runBlocking {
        val service = service()
        val a = service.createEntity("alice", "person").entity
        val b = service.createEntity("acme", "company").entity
        val rel = service.createRelationship(a.id, b.id, "works_at").relationship
        val versionBefore = service.version().toLong()

        val note = service.attachNoteToRelationship(rel.id, day, "left the company", valid = false)
        assertEquals(rel.id, note.notes.single().relationshipId)
        assertFalse(note.valid, "the result reports the post-attach state")
        assertEquals(versionBefore + 1, service.version().toLong(), "ONE bump for the compound event")
        assertFalse(
            service.getRelationship(rel.id)?.relationship?.valid ?: fail("relationship missing"),
            "the edge is closed by the note",
        )

        // idempotent state change: the note still attaches, still one bump;
        // the unchanged state still reports back
        val versionAfterClose = service.version().toLong()
        val stillGone = service.attachNoteToRelationship(rel.id, day, "still gone", valid = false)
        assertFalse(stillGone.valid)
        assertEquals(versionAfterClose + 1, service.version().toLong())

        try {
            service.attachNoteToRelationship(4242L, day, "x")
            fail("a missing subject must fail fast")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("does not exist"))
        }
    }

    @Test
    fun `attachNotesToEntity appends the batch with ONE embed call and ONE bump`() = runBlocking {
        val hand = FakeHand()
        val service = service(hand)
        val entity = service.createEntity("kindle", "device").entity
        val versionBefore = service.version().toLong()

        val notes = service.attachNotesToEntity(
            entity.id,
            listOf(NoteDraft(day, "  bought it  "), NoteDraft(day.plusDays(1), "dropped it")),
        )
        assertEquals(listOf("bought it", "dropped it"), notes.map { it.note }, "the notes are trimmed")
        assertTrue(notes.all { it.entityId == entity.id })
        assertEquals(versionBefore + 1, service.version().toLong(), "ONE bump for the whole batch")
        assertEquals(
            2,
            hand.embedRequests.size,
            "create + the batch: the whole batch rides ONE /v1/embed call",
        )
        assertEquals(
            listOf("bought it", "dropped it"),
            hand.embedRequests.last().input,
            "both notes in the same embed request",
        )
        // the diary reads back newest-event-first through the returned rows
        assertEquals(
            listOf("dropped it", "bought it"),
            service.getEntityNotes(entity.id, null, null, 10, 0).map { it.note },
        )

        // an empty batch is a no-op: no embed, no bump
        val embedsAfterBatch = hand.embedRequests.size
        assertEquals(emptyList<EltmNote>(), service.attachNotesToEntity(entity.id, emptyList()))
        assertEquals(versionBefore + 1, service.version().toLong())
        assertEquals(embedsAfterBatch, hand.embedRequests.size)

        // a blank note fails fast before any embed call
        try {
            service.attachNotesToEntity(entity.id, listOf(NoteDraft(day, "   ")))
            fail("a blank note must be refused")
        } catch (expected: IllegalArgumentException) {
            assertEquals(embedsAfterBatch, hand.embedRequests.size)
        }
        // a missing subject fails fast before any embed call
        try {
            service.attachNotesToEntity(4242L, listOf(NoteDraft(day, "x")))
            fail("a missing subject must fail fast")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("does not exist"))
        }
        assertEquals(embedsAfterBatch, hand.embedRequests.size)
    }

    @Test
    fun `attachNotesToEntity splits a batch over the embed cap but keeps ONE bump`() = runBlocking {
        val hand = FakeHand()
        val service = service(hand)
        val entity = service.createEntity("kindle", "device").entity
        val versionBefore = service.version().toLong()

        val count = EMBED_BATCH_SIZE + 1
        val drafts = (1..count).map { NoteDraft(day.plusDays(it.toLong()), "note $it") }
        val embedsBefore = hand.embedRequests.size
        val notes = service.attachNotesToEntity(entity.id, drafts)

        assertEquals(count, notes.size)
        assertEquals(
            2,
            hand.embedRequests.size - embedsBefore,
            "ceil((cap + 1) / cap) = 2 embed calls for one batch",
        )
        assertEquals(
            (1..count).map { "note $it" },
            hand.embedRequests.takeLast(2).flatMap { it.input },
            "every note is embedded exactly once, chunk by chunk",
        )
        assertEquals(versionBefore + 1, service.version().toLong(), "the chunks share ONE bump")
        assertEquals(
            count,
            service.getEntityNotes(entity.id, null, null, count, 0).size,
            "every row of the over-cap batch landed",
        )
    }

    @Test
    fun `attachNotesToRelationship batches the notes with the validity change and ONE bump`() =
        runBlocking {
            val service = service()
            val a = service.createEntity("alice", "person").entity
            val b = service.createEntity("acme", "company").entity
            val rel = service.createRelationship(a.id, b.id, "works_at").relationship
            val versionBefore = service.version().toLong()

            val notes = service.attachNotesToRelationship(
                rel.id,
                listOf(
                    NoteDraft(day, "joined the company"),
                    NoteDraft(day.plusDays(1), "left the company"),
                ),
                valid = false,
            )
            assertEquals(2, notes.notes.size)
            assertTrue(notes.notes.all { it.relationshipId == rel.id })
            assertFalse(notes.valid, "the result reports the post-attach state")
            assertEquals(versionBefore + 1, service.version().toLong(), "ONE bump for the compound event")
            assertFalse(
                service.getRelationship(rel.id)?.relationship?.valid ?: fail("relationship missing"),
                "the edge is closed by the batch",
            )
            assertEquals(
                listOf("left the company", "joined the company"),
                service.getRelationshipNotes(rel.id, null, null, 10, 0).map { it.note },
            )
        }

    @Test
    fun `attachNotesToRelationship refuses a bare structural change without a note`() = runBlocking {
        val service = service()
        val a = service.createEntity("alice", "person").entity
        val b = service.createEntity("acme", "company").entity
        val rel = service.createRelationship(a.id, b.id, "works_at").relationship
        val versionBefore = service.version().toLong()

        try {
            service.attachNotesToRelationship(rel.id, emptyList(), valid = false)
            fail("a bare structural change must be refused")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("no reason"))
        }
        assertTrue(
            service.getRelationship(rel.id)?.relationship?.valid ?: fail("relationship missing"),
            "nothing moved",
        )
        assertEquals(versionBefore, service.version().toLong(), "refused writes never bump")
    }

    // ------------------------------------------------------------------
    // attributes
    // ------------------------------------------------------------------

    @Test
    fun `setEntityAttribute overwrites, re-embeds and bumps - identical set is a pure read`() =
        runBlocking {
            val hand = FakeHand()
            val service = service(hand)
            val entity = service.createEntity("kindle", "device").entity
            val versionBefore = service.version().toLong()

            assertTrue(service.setEntityAttribute(entity.id, "Model", "Paperwhite 6"))
            assertEquals(versionBefore + 1, service.version().toLong())
            assertEquals(
                mapOf("model" to "Paperwhite 6"),
                service.getEntity(entity.id)?.attributes,
                "the key is normalized",
            )
            // the re-embed text carries the attribute line, alphabetically last
            assertEquals(
                "kindle device\nmodel: Paperwhite 6",
                hand.embedRequests.last().input.single(),
            )

            val embedsAfterSet = hand.embedRequests.size
            val changed = service.setEntityAttribute(entity.id, "model", "Paperwhite 6")
            assertFalse(changed, "an identical set is a no-op")
            assertEquals(versionBefore + 1, service.version().toLong(), "a no-op never bumps")
            assertEquals(embedsAfterSet, hand.embedRequests.size, "a no-op never embeds")
        }

    @Test
    fun `setEntityAttribute validates the value shape`() = runBlocking {
        val service = service()
        val entity = service.createEntity("kindle", "device").entity
        val versionBefore = service.version().toLong()

        try {
            service.setEntityAttribute(entity.id, "model", "two\nlines")
            fail("a multi-line value must be refused")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("single line"))
        }
        try {
            service.setEntityAttribute(entity.id, " ", "v")
            fail("a blank key must be refused")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
        try {
            service.setEntityAttribute(4242L, "model", "v")
            fail("a missing entity must fail fast")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("does not exist"))
        }
        assertEquals(versionBefore, service.version().toLong(), "refused writes never bump")
    }

    @Test
    fun `setEntityAttributes writes the whole batch with one embed and one bump`() = runBlocking {
        val hand = FakeHand()
        val service = service(hand)
        val entity = service.createEntity("kindle", "device").entity
        val versionBefore = service.version().toLong()

        val changed = service.setEntityAttributes(
            entity.id,
            mapOf("Model" to "Paperwhite 6", "owner" to " me "),
        )
        assertEquals(2, changed)
        assertEquals(versionBefore + 1, service.version().toLong(), "ONE bump for the whole batch")
        assertEquals(
            mapOf("model" to "Paperwhite 6", "owner" to "me"),
            service.getEntity(entity.id)?.attributes,
            "the keys are normalized, the values trimmed",
        )
        assertEquals(
            2,
            hand.embedRequests.size,
            "create + the batch: the whole batch rides ONE /v1/embed call",
        )
        assertEquals(
            listOf("kindle device\nmodel: Paperwhite 6\nowner: me"),
            hand.embedRequests.last().input,
            "the FINAL composed text is embedded once, keys alphabetically ordered",
        )

        // an identical key plus a new one: only the new one writes
        val embedsAfterSet = hand.embedRequests.size
        val partial = service.setEntityAttributes(
            entity.id,
            mapOf("model" to "Paperwhite 6", "color" to "black"),
        )
        assertEquals(1, partial)
        assertEquals(embedsAfterSet + 1, hand.embedRequests.size)
        assertEquals(
            listOf("kindle device\ncolor: black\nmodel: Paperwhite 6\nowner: me"),
            hand.embedRequests.last().input,
        )

        // an all-identical batch is a pure read: no embed, no bump
        val versionAfter = service.version().toLong()
        val unchanged = service.setEntityAttributes(
            entity.id,
            mapOf("model" to "Paperwhite 6", "owner" to "me"),
        )
        assertEquals(0, unchanged)
        assertEquals(versionAfter, service.version().toLong(), "a no-op batch never bumps")
        assertEquals(embedsAfterSet + 1, hand.embedRequests.size, "a no-op batch never embeds")

        // two raw keys that canonicalize alike fold onto ONE entry: the
        // later value wins, one changed key
        val folded = service.setEntityAttributes(
            entity.id,
            linkedMapOf("Real Name" to "Alice", "real_name" to "Bob"),
        )
        assertEquals(1, folded, "the two raw keys fold onto one (entity, key)")
        assertEquals(
            "Bob",
            service.getEntity(entity.id)?.attributes?.get("real_name"),
            "the later value wins the fold",
        )
    }

    @Test
    fun `setEntityAttributes validates every entry and fails without writing`() = runBlocking {
        val service = service()
        val entity = service.createEntity("kindle", "device").entity
        val versionBefore = service.version().toLong()

        try {
            service.setEntityAttributes(
                entity.id,
                mapOf("model" to "Paperwhite", "bad" to "two\nlines"),
            )
            fail("a multi-line value must be refused")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("single line"))
        }
        try {
            service.setEntityAttributes(entity.id, mapOf(" " to "v"))
            fail("a blank key must be refused")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
        try {
            service.setEntityAttributes(4242L, mapOf("model" to "v"))
            fail("a missing entity must fail fast")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("does not exist"))
        }
        assertEquals(versionBefore, service.version().toLong(), "refused batches never bump")
        assertEquals(emptyMap(), service.getEntity(entity.id)?.attributes, "refused batches never write")
    }

    @Test
    fun `setEntityAttributes rolls back the whole batch on an embed failure`() = runBlocking {
        val good = service()
        val created = good.createEntity("kindle", "device").entity
        good.setEntityAttribute(created.id, "model", "Paperwhite")
        val versionBefore = good.version().toLong()

        val failing = service(FakeHand(embedScript = { _ ->
            throw EmbeddingException("invalid_request", "content too large")
        }))
        try {
            failing.setEntityAttributes(
                created.id,
                mapOf("model" to "k9", "color" to "black"),
            )
            fail("an embed failure must propagate")
        } catch (expected: EmbeddingException) {
            assertEquals("invalid_request", expected.type)
        }
        assertEquals(
            mapOf("model" to "Paperwhite"),
            good.getEntity(created.id)?.attributes,
            "the whole batch rolled back: nothing moved",
        )
        assertEquals(versionBefore, good.version().toLong(), "the counter is untouched")
    }

    @Test
    fun `deleteEntityAttribute removes the row and re-embeds without it`() = runBlocking {
        val hand = FakeHand()
        val service = service(hand)
        val entity = service.createEntity("kindle", "device").entity
        service.setEntityAttribute(entity.id, "model", "Paperwhite")
        val versionBefore = service.version().toLong()

        service.deleteEntityAttribute(entity.id, "model")
        assertEquals(versionBefore + 1, service.version().toLong())
        assertEquals(emptyMap(), service.getEntity(entity.id)?.attributes)
        assertEquals(
            "kindle device",
            hand.embedRequests.last().input.single(),
            "the re-embed no longer carries the attribute line",
        )

        try {
            service.deleteEntityAttribute(entity.id, "model")
            fail("deleting a missing key must fail")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("does not exist"))
        }
    }

    // ------------------------------------------------------------------
    // mergeEntities
    // ------------------------------------------------------------------

    @Test
    fun `mergeEntities folds attributes winner-wins, re-points rows, and bumps once`() = runBlocking {
        val service = service()
        val winner = service.createEntity("apple", "company").entity
        val loser = service.createEntity("apple inc", "company").entity
        val third = service.createEntity("tim cook", "person").entity

        service.setEntityAttribute(winner.id, "ticker", "AAPL")
        service.setEntityAttribute(loser.id, "ticker", "APPL")
        service.setEntityAttribute(loser.id, "founded", "1976")
        // an edge between winner and loser: re-pointing makes it a self-loop
        val selfLoopEdge = service.createRelationship(winner.id, loser.id, "renamed_to").relationship.id
        service.attachNoteToRelationship(selfLoopEdge, day, "merged branding")
        // a loser edge to a third entity: re-pointed to the winner
        val rePointedEdge = service.createRelationship(loser.id, third.id, "employs").relationship.id
        service.attachNoteToEntity(loser.id, day, "founded in a garage")

        val versionBefore = service.version().toLong()
        service.mergeEntities(winner.id, loser.id)
        assertEquals(versionBefore + 1, service.version().toLong(), "ONE bump for the whole merge")

        assertFalse(service.entityExists(loser.id), "the loser row is gone")
        val view = assertIs<EntityView>(service.getEntity(winner.id))
        assertEquals(
            mapOf("founded" to "1976", "ticker" to "AAPL"),
            view.attributes,
            "the winner's value wins the colliding key, the unique key folds in",
        )
        assertEquals(1, view.noteCount, "the loser's entity note was re-pointed, not destroyed")

        // the re-pointed edge survived as one winner—third row
        assertTrue(service.relationshipExists(rePointedEdge))
        // the self-loop edge was invalidated in place, its note survived
        val selfLoopView = assertIs<RelationshipView>(service.getRelationship(selfLoopEdge))
        assertTrue(
            selfLoopView.relationship.src == winner && selfLoopView.relationship.dst == winner,
            "the winner—loser edge became a winner—winner self-loop",
        )
        assertFalse(selfLoopView.relationship.valid, "a self-loop is invalidated, not kept")
        assertEquals(1, selfLoopView.noteCount, "the self-loop's diary note survived the fold")
        // the re-pointed note now points at the winner
        assertEquals(
            listOf(winner.id),
            service.getEntityNotes(winner.id, null, null, 10, 0).map { it.entityId },
        )
    }

    @Test
    fun `attaching a note to a merged-away subject fails with a clear message, never raw SQL`() = runBlocking {
        val service = service()
        val winner = service.createEntity("apple", "company").entity
        val loser = service.createEntity("apple inc", "company").entity
        val third = service.createEntity("tim cook", "person").entity
        // colliding edges: the loser's row folds into the survivor and its
        // id is gone after the merge
        service.createRelationship(winner.id, third.id, "employs")
        val folded = service.createRelationship(loser.id, third.id, "employs").relationship
        service.mergeEntities(winner.id, loser.id)
        // the loser entity row is gone: the pre-check fails fast, and the
        // FK-violation catch guarantees the same contract under a race
        // (subject deleted between the check and the insert)
        try {
            service.attachNoteToEntity(loser.id, day, "late note")
            fail("a merged-away entity must fail fast")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains(loser.id.toString()), expected.message)
        }
        try {
            service.attachNoteToRelationship(folded.id, day, "late note")
            fail("a folded-away relationship must fail fast")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains(folded.id.toString()), expected.message)
        }
    }

    @Test
    fun `attaching a note to a subject deleted mid-embed fails with a clear message, never raw SQL`() = runBlocking {
        // the FK-violation catch, not the pre-check: the existence check
        // passes, then the subject row is deleted while the note
        // embeddings are in flight (the embed call is the gap between the
        // check and the insert — the same seam the concurrent-insert test
        // uses), so the insert hits the FK and must convert to
        // IllegalArgumentException (a raw SQLException escaping would make
        // Exposed retry the block and risk inserting the batch twice).
        val enteredEmbed = kotlinx.coroutines.CompletableDeferred<Unit>()
        val allowEmbed = kotlinx.coroutines.CompletableDeferred<Unit>()
        val hand = FakeHand(embedScript = { request ->
            enteredEmbed.complete(Unit)
            allowEmbed.await()
            allOnesResult(request)
        })
        // the setup writes ride a plain hand: the gated hand above must
        // serve ONLY the attach under test — a setup embed would trip the
        // gate and deadlock the test body itself
        val setup = service(FakeHand())
        val service = service(hand)
        val entity = setup.createEntity("kindle", "device").entity
        val a = setup.createEntity("a", "x").entity
        val b = setup.createEntity("b", "x").entity
        val rel = setup.createRelationship(a.id, b.id, "knows").relationship

        val entityJob = async {
            runCatching { service.attachNoteToEntity(entity.id, day, "late note") }
        }
        // bounded rendezvous: a regression in the interleaving must fail
        // the test, never hang the suite (the suite has no global timeout)
        withTimeout(30_000) { enteredEmbed.await() }
        withTransaction { EltmEntities.deleteWhere { EltmEntities.id eq entity.id } }
        allowEmbed.complete(Unit)
        val entityError = assertIs<IllegalArgumentException>(
            entityJob.await().exceptionOrNull(),
            "an entity deleted mid-embed must fail with IllegalArgumentException",
        )
        assertTrue(entityError.message!!.contains(entity.id.toString()), entityError.message)

        val enteredRelEmbed = kotlinx.coroutines.CompletableDeferred<Unit>()
        val allowRelEmbed = kotlinx.coroutines.CompletableDeferred<Unit>()
        val relHand = FakeHand(embedScript = { request ->
            enteredRelEmbed.complete(Unit)
            allowRelEmbed.await()
            allOnesResult(request)
        })
        val relService = service(relHand)
        val relJob = async {
            runCatching { relService.attachNoteToRelationship(rel.id, day, "late note") }
        }
        withTimeout(30_000) { enteredRelEmbed.await() }
        withTransaction { EltmRelationships.deleteWhere { EltmRelationships.id eq rel.id } }
        allowRelEmbed.complete(Unit)
        val relError = assertIs<IllegalArgumentException>(
            relJob.await().exceptionOrNull(),
            "a relationship deleted mid-embed must fail with IllegalArgumentException",
        )
        assertTrue(relError.message!!.contains(rel.id.toString()), relError.message)
    }

    @Test
    fun `batched identity reads resolve present rows and skip missing ones`() = runBlocking {
        val service = service()
        val alice = service.createEntity("alice", "person").entity
        val acme = service.createEntity("acme", "company").entity
        val rel = service.createRelationship(alice.id, acme.id, "works at").relationship

        assertEquals(emptyMap(), service.getEntitiesByIds(emptyList()), "empty input: no query, empty map")
        assertEquals(emptyMap(), service.getResolvedRelationships(emptyList()))

        val entities = service.getEntitiesByIds(listOf(alice.id, 4242L, alice.id))
        assertEquals(mapOf(alice.id to alice), entities, "present rows resolve, missing and duplicate ids collapse")
        val resolved = service.getResolvedRelationships(listOf(rel.id, 4242L))
        assertEquals(mapOf(rel.id to rel), resolved, "missing ids drop out")
    }

    @Test
    fun `batched identity reads chunk past BULK_QUERY_CHUNK_SIZE without losing rows`() = runBlocking {
        val service = service()
        // an over-chunk batch exercises the chunked inList reads in
        // selectEntitiesByIds/selectResolvedRelationships plus the
        // endpoint inList over 2*N ids: every row must resolve in full.
        // The creates ride the bulk path (one embed series for the whole
        // batch): 537 single creates would embed 537 times.
        val created = service.createEntities(
            (1..(BULK_QUERY_CHUNK_SIZE + 37)).map { EntityDraft("chunk-entity-$it", "bulk") }
        )
        val gotEntities = service.getEntitiesByIds(created.map { it.id } + listOf(4242L))
        assertEquals(
            created.associateBy { it.id },
            gotEntities,
            "every present row resolves, the missing id drops out",
        )

        val rels = service.createRelationships(
            created.windowed(2, 1) { (a, b) -> RelationshipDraft(a.id, "chunk_rel", b.id) }
        )
        val expected = rels.mapIndexed { index, rel ->
            rel.id to ResolvedRelationship(rel.id, created[index], "chunk_rel", created[index + 1], true)
        }.toMap()
        val gotResolved = service.getResolvedRelationships(rels.map { it.id } + listOf(4242L))
        assertEquals(
            expected,
            gotResolved,
            "every present relationship resolves, the missing id drops out",
        )
    }

    @Test
    fun `batch relationship reads fail fast on a row whose endpoint is gone`() {
        // pins checkAllResolved at the builder level: a torn read (a
        // concurrent merge landing between the relationship-row read and
        // the endpoint read) must fail loudly, never silently drop the row
        // and punch holes in limit/offset pages. No service path is used:
        // through the service a dangling endpoint is unrepresentable (the
        // FK cascade deletes the relationship with its endpoint).
        val a = EltmEntity(1, "alive", "x")
        val rel = EltmRelationship(7, srcId = 1, dstId = 2, verb = "knows", valid = true)
        val resolved = resolveRelationships(listOf(rel), mapOf(1L to a))
        assertTrue(resolved.isEmpty(), "the endpoint-missing row resolves to nothing")
        val torn = assertFailsWith<TornRelationshipReadException> {
            checkAllResolved(listOf(rel), resolved)
        }
        assertEquals(listOf(7L), torn.relationshipIds)
        assertTrue(torn.message!!.contains("7"), torn.message)
    }

    @Test
    fun `mergeEntities collapses opposite-direction edges onto one invalidated row`() = runBlocking {
        val service = service()
        val winner = service.createEntity("apple", "company").entity
        val loser = service.createEntity("apple inc", "company").entity

        val forward = service.createRelationship(winner.id, loser.id, "knows").relationship.id
        val backward = service.createRelationship(loser.id, winner.id, "knows").relationship.id
        service.attachNoteToRelationship(forward, day, "forward note")
        service.attachNoteToRelationship(backward, day, "backward note")

        service.mergeEntities(winner.id, loser.id)

        // both edges re-point to the same winner—winner triple: the first
        // becomes the invalidated self-loop row, the second folds into it
        val selfLoops = service.getRelationships(winner.id, includeInvalid = true)
            .filter { it.relationship.src == winner && it.relationship.dst == winner }
        assertEquals(1, selfLoops.size, "exactly ONE row per triple, even after the collapse")
        assertEquals(2, selfLoops.single().noteCount, "both diary notes survive on the survivor")
        assertFalse(selfLoops.single().relationship.valid)
    }

    @Test
    fun `mergeEntities rolls back on an embed failure`() = runBlocking {
        val good = service()
        val winner = good.createEntity("apple", "company").entity
        val loser = good.createEntity("apple inc", "company").entity
        good.setEntityAttribute(loser.id, "founded", "1976") // the fold changes the text
        val versionBefore = good.version().toLong()

        val failing = service(FakeHand(embedScript = { _ ->
            throw EmbeddingException("invalid_request", "content too large")
        }))
        try {
            failing.mergeEntities(winner.id, loser.id)
            fail("the fold's embed failure must propagate")
        } catch (expected: EmbeddingException) {
            // expected
        }
        assertTrue(good.entityExists(loser.id), "the loser survived: nothing moved")
        assertEquals(
            mapOf("founded" to "1976"),
            good.getEntity(loser.id)?.attributes,
            "the loser's attributes are untouched",
        )
        assertEquals(versionBefore, good.version().toLong(), "the counter is untouched")
    }

    @Test
    fun `mergeEntities validates its subjects`() = runBlocking {
        val service = service()
        val a = service.createEntity("a", "x").entity
        try {
            service.mergeEntities(a.id, a.id)
            fail("a self-merge must fail")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("itself"))
        }
        try {
            service.mergeEntities(a.id, 4242L)
            fail("a missing loser must fail")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("does not exist"))
        }
    }

    // ------------------------------------------------------------------
    // searches
    // ------------------------------------------------------------------

    @Test
    fun `searchEntities finds exact embedding texts with full prominence`() = runBlocking {
        val embeddings = DeterministicEmbeddings()
        val storedText = entityEmbeddingText("kindle", "device", mapOf("model" to "Paperwhite"))
        embeddings.register(storedText, testAxisVector(0))
        val service = service(FakeHand(embedScript = embeddings.script))

        val created = service.createEntity("Kindle", "Device")
        service.setEntityAttribute(created.entity.id, "model", "Paperwhite")

        val hits = service.searchEntities(storedText, 5)
        assertEquals(1, hits.size)
        val hit = hits.single()
        assertEquals(created.entity.id, hit.view.entity.id)
        assertEquals(1.0, hit.score, 1e-6, "the query vector IS the stored vector")
        assertEquals(1, hit.view.attributes.size)

        // a different text hashes to a near-orthogonal vector: no hit
        assertTrue(service.searchEntities("completely unrelated", 5).isEmpty())
    }

    @Test
    fun `searchEntities validates its arguments`() = runBlocking {
        val service = service()
        try {
            service.searchEntities("  ", 5)
            fail("a blank query must fail")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
        try {
            service.searchEntities("x", 0)
            fail("limit 0 must fail")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("limit"))
        }
    }

    @Test
    fun `findEntities filters by regex on name, category and any attribute, AND-combined`() =
        runBlocking {
            val service = service()
            val alice = service.createEntity("alice", "person").entity
            service.setEntityAttribute(alice.id, "model", "kindle paperwhite")
            service.setEntityAttribute(alice.id, "nickname", "ally")
            val acme = service.createEntity("acme", "company").entity
            service.setEntityAttribute(acme.id, "model", "thinkpad")
            service.attachNoteToEntity(acme.id, LocalDate.of(2026, 8, 10), "shipped the thing")

            // the attr filter matches when ANY attribute's `key=value` line
            // matches — the key-only pattern hits both model holders
            assertEquals(
                setOf(alice.id, acme.id),
                service.findEntities(name = null, category = null, attr = "^model=", limit = 10, offset = 0)
                    .map { it.entity.id }.toSet(),
            )
            assertEquals(
                listOf(alice.id),
                service.findEntities(name = null, category = null, attr = "paperwhite", limit = 10, offset = 0)
                    .map { it.entity.id },
            )
            // the nickname line matches too — not just the model line
            assertEquals(
                listOf(alice.id),
                service.findEntities(name = null, category = null, attr = "^nickname=ally$", limit = 10, offset = 0)
                    .map { it.entity.id },
            )

            // name filter, case-insensitive (`~*`): the pattern is uppercase
            // against the lowercase canonical name
            assertEquals(
                listOf(alice.id),
                service.findEntities(name = "^ALICE$", category = null, attr = null, limit = 10, offset = 0)
                    .map { it.entity.id },
            )

            // filters AND together
            assertEquals(
                listOf(alice.id),
                service.findEntities(name = "a", category = "person", attr = "kindle", limit = 10, offset = 0)
                    .map { it.entity.id },
            )
            assertTrue(
                service.findEntities(name = "a", category = "^company$", attr = "kindle", limit = 10, offset = 0)
                    .isEmpty(),
                "the person-only attribute matches no company",
            )

            // paging applies WITH the filter: name "a" matches both entities
            // (id order), so the second page of one is acme
            assertEquals(
                listOf(acme.id),
                service.findEntities(name = "a", category = null, attr = null, limit = 1, offset = 1)
                    .map { it.entity.id },
            )

            // the view is full: counts, attributes, latest note
            val view = service.findEntities(name = "^acme$", category = null, attr = null, limit = 10, offset = 0)
                .single()
            assertEquals(1, view.noteCount)
            assertEquals(mapOf("model" to "thinkpad"), view.attributes)
            assertEquals("shipped the thing", view.latestNote?.note)

            assertTrue(
                service.findEntities(name = "^nonexistent$", category = null, attr = null, limit = 10, offset = 0)
                    .isEmpty(),
            )
        }

    @Test
    fun `findEntities without filters browses in id order with paging`() = runBlocking {
        val service = service()
        val first = service.createEntity("alice", "person").entity
        val second = service.createEntity("bob", "person").entity
        val third = service.createEntity("acme", "company").entity

        assertEquals(
            listOf(first.id, second.id, third.id),
            service.findEntities(name = null, category = null, attr = null, limit = 10, offset = 0)
                .map { it.entity.id },
            "all-null filters are the plain browse, id ascending",
        )
        // the browse is listEntities' page, not a degenerate WHERE
        assertEquals(
            service.listEntities(2, 1).map { it.entity.id },
            service.findEntities(name = null, category = null, attr = null, limit = 2, offset = 1)
                .map { it.entity.id },
        )
    }

    @Test
    fun `findEntities validates its paging arguments`() = runBlocking {
        val service = service()
        try {
            service.findEntities(null, null, null, 0, 0)
            fail("limit 0 must fail")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("limit"))
        }
        try {
            service.findEntities(null, null, null, 5, -1)
            fail("a negative offset must fail")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("offset"))
        }
    }

    @Test
    fun `findEntities converts a postgres-invalid regex to an IllegalArgumentException`() = runBlocking {
        val service = service()
        // \Q...\E literal quoting: Java-valid (the tool layer's pre-check
        // passes it) but rejected by PostgreSQL's ~* — converted INSIDE the
        // transaction to a non-SQL exception so withTransaction does not
        // retry the deterministically failing read (see db/Database.kt and
        // db/SqlErrors.isInvalidRegex). The error fires on an empty table
        // too: PostgreSQL validates the pattern even when no row is read.
        val e = assertFailsWith<IllegalArgumentException> {
            service.findEntities("\\Qalice\\E", null, null, 10, 0)
        }
        assertTrue(e.message!!.contains("regular expression"), e.message)
        // the server's complaint rides the message — the model needs it
        assertTrue(e.message!!.contains("invalid"), e.message)
    }

    @Test
    fun `searchNotes honors subject and date filters`() = runBlocking {
        val embeddings = DeterministicEmbeddings()
        val service = service(FakeHand(embedScript = embeddings.script))
        val entity = service.createEntity("kindle", "device").entity

        service.attachNoteToEntity(entity.id, LocalDate.of(2026, 8, 10), "battery dies fast")
        // register BEFORE the note is embedded: the stored vector must be
        // the registered one for the query below to hit it
        embeddings.register("firmware fixed it", testAxisVector(1))
        val newerNote = service.attachNoteToEntity(entity.id, LocalDate.of(2026, 8, 20), "firmware fixed it")

        val hits = service.searchNotes("firmware fixed it", null, null, null, null, 10)
        assertEquals(listOf(newerNote.id), hits.map { it.id }, "the exact text is similarity 1.0")

        val narrowed = service.searchNotes(
            "firmware fixed it", null, null,
            from = LocalDate.of(2026, 8, 15), to = null, limit = 10,
        )
        assertEquals(listOf(newerNote.id), narrowed.map { it.id }, "the range keeps the newer note")

        val excluded = service.searchNotes(
            "firmware fixed it", null, null,
            from = null, to = LocalDate.of(2026, 8, 15), limit = 10,
        )
        assertTrue(excluded.isEmpty(), "the older note's vector is orthogonal: no hit in range")

        try {
            service.searchNotes("x", entity.id, 99L, null, null, 10)
            fail("two subjects must fail")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("at most one subject"))
        }
    }

    @Test
    fun `searchEntitiesAndNotes embeds the query once and feeds both halves`() = runBlocking {
        val embeddings = DeterministicEmbeddings()
        embeddings.register("alice person", testAxisVector(0))
        embeddings.register("ali", testAxisVector(0))
        embeddings.register("met alice", testAxisVector(0))
        val hand = FakeHand(embedScript = embeddings.script)
        val service = service(hand)
        val alice = service.createEntity("alice", "person").entity
        service.attachNoteToEntity(alice.id, day, "met alice")
        val embedsBefore = hand.embedRequests.size

        val hits = service.searchEntitiesAndNotes("ali", entityLimit = 5, noteLimit = 5)
        assertEquals(listOf(alice.id), hits.entities.map { it.view.entity.id })
        assertEquals(1, hits.notes.size)
        assertEquals("met alice", hits.notes.single().note)
        assertEquals(
            embedsBefore + 1,
            hand.embedRequests.size,
            "ONE embed call feeds both halves, never one per search",
        )

        // a zero half is skipped; both zero short-circuits without embedding
        val embedsAfterSearch = hand.embedRequests.size
        val entitiesOnly = service.searchEntitiesAndNotes("ali", entityLimit = 5, noteLimit = 0)
        assertTrue(entitiesOnly.entities.isNotEmpty())
        assertTrue(entitiesOnly.notes.isEmpty())
        val none = service.searchEntitiesAndNotes("ali", entityLimit = 0, noteLimit = 0)
        assertTrue(none.entities.isEmpty() && none.notes.isEmpty())
        assertEquals(
            embedsAfterSearch + 1,
            hand.embedRequests.size,
            "only the entitiesOnly search embedded; the both-zero call did not",
        )

        try {
            service.searchEntitiesAndNotes("  ", 1, 1)
            fail("a blank query must fail")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
        try {
            service.searchEntitiesAndNotes("x", -1, 1)
            fail("a negative limit must fail")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    // ------------------------------------------------------------------
    // views and paging
    // ------------------------------------------------------------------

    @Test
    fun `views carry counts, latest note, attributes and resolved endpoints`() = runBlocking {
        val service = service()
        val entity = service.createEntity("alice", "person").entity
        val other = service.createEntity("acme", "company").entity
        service.createRelationship(entity.id, other.id, "works_at")
        service.attachNoteToEntity(entity.id, LocalDate.of(2026, 1, 1), "old")
        val newNote = service.attachNoteToEntity(entity.id, LocalDate.of(2026, 6, 1), "new")
        service.setEntityAttribute(entity.id, "city", "berlin")

        val view = assertIs<EntityView>(service.getEntity(entity.id))
        assertEquals(2, view.noteCount)
        assertEquals(1, view.relationshipCount)
        assertEquals(newNote.id, view.latestNote?.id, "latest by event_date DESC, id DESC")
        assertEquals(mapOf("city" to "berlin"), view.attributes)

        val rel = service.listRelationships(10, 0).single()
        assertEquals(entity, rel.relationship.src)
        assertEquals(other, rel.relationship.dst)
        assertEquals(0, rel.noteCount)

        // the batch page view agrees with the single-subject view
        val paged = service.listEntities(10, 0).first { it.entity.id == entity.id }
        assertEquals(view, paged)
    }

    @Test
    fun `listEntities pages by id ascending`() = runBlocking {
        val service = service()
        val ids = (1..3).map { service.createEntity("entity$it", "x").entity.id }.sorted()
        assertEquals(ids.drop(1), service.listEntities(2, 1).map { it.entity.id })
        assertTrue(service.listEntities(10, 3).isEmpty(), "an offset past the end is empty")
    }

    @Test
    fun `page batch helpers chunk past BULK_QUERY_CHUNK_SIZE without losing rows`() = runBlocking {
        val service = service()
        // an over-chunk population where EVERY entity carries content, so
        // listEntities' three batch helpers (attributesFor,
        // relationshipCountsFor, noteCountsAndLatest) all run over-chunk:
        // one attribute, one note, and (except the last) one outgoing
        // chain edge per entity. One row over the boundary is enough to
        // span two chunks — the bulk-create test above keeps the wider
        // margin for the OR-query path.
        val created = service.createEntities(
            (1..(BULK_QUERY_CHUNK_SIZE + 1)).map { EntityDraft("page-entity-$it", "bulk") }
        )
        created.forEachIndexed { index, entity ->
            service.setEntityAttribute(entity.id, "seq", "$index")
            service.attachNoteToEntity(entity.id, day, "note $index")
        }
        service.createRelationships(
            created.windowed(2, 1) { (a, b) -> RelationshipDraft(a.id, "page_rel", b.id) }
        )
        val page = service.listEntities(created.size, 0)
        assertEquals(created.map { it.id }, page.map { it.entity.id }, "id order, no row lost")
        page.forEachIndexed { index, view ->
            assertEquals(mapOf("seq" to "$index"), view.attributes, "attributes of entity $index")
            assertEquals(1, view.noteCount, "note count of entity $index")
            assertEquals("note $index", view.latestNote?.note, "latest note of entity $index")
            val expectedRels = when (index) {
                0, created.lastIndex -> 1
                else -> 2
            }
            assertEquals(expectedRels, view.relationshipCount, "relationship count of entity $index")
        }
    }

    @Test
    fun `getRelationships filters validity and lists both directions`() = runBlocking {
        val service = service()
        val a = service.createEntity("a", "x").entity
        val b = service.createEntity("b", "x").entity
        val c = service.createEntity("c", "x").entity
        val ab = service.createRelationship(a.id, b.id, "knows").relationship.id
        val ca = service.createRelationship(c.id, a.id, "knows").relationship.id
        val invalid = service.createRelationship(a.id, c.id, "knew").relationship.id
        service.attachNoteToRelationship(invalid, day, "ended", valid = false)

        val active = service.getRelationships(a.id, includeInvalid = false)
        assertEquals(setOf(ab, ca), active.map { it.relationship.id }.toSet())

        val all = service.getRelationships(a.id, includeInvalid = true)
        assertEquals(setOf(ab, ca, invalid), all.map { it.relationship.id }.toSet())
    }

    @Test
    fun `existence probes and version mirror the store`() = runBlocking {
        val service = service()
        val entity = service.createEntity("a", "x").entity
        assertTrue(service.entityExists(entity.id))
        assertFalse(service.entityExists(4242L))
        assertFalse(service.relationshipExists(4242L))
        assertNull(service.getEntity(4242L))
        assertNull(service.getRelationship(4242L))

        assertEquals(service.version().toLong(), readMetaCounterTx(ELTM_VERSION_KEY))
    }

    @Test
    fun `paging guards reject bad windows`() = runBlocking {
        val service = service()
        val entity = service.createEntity("a", "x").entity
        service.attachNoteToEntity(entity.id, day, "n")
        val cases = listOf(
            suspend { service.listEntities(0, 0) },
            suspend { service.listEntities(1, -1) },
            suspend { service.listRelationships(0, 0) },
            suspend { service.getEntityNotes(entity.id, null, null, 0, 0) },
            suspend { service.getEntityNotes(entity.id, null, null, 1, -1) },
        )
        for (case in cases) {
            try {
                case()
                fail("expected an IllegalArgumentException for a bad paging window")
            } catch (expected: IllegalArgumentException) {
                // expected
            }
        }
    }
}
