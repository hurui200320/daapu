package info.skyblond.daapu.memory.eltm

import info.skyblond.daapu.db.ELTM_VERSION_KEY
import info.skyblond.daapu.db.readMetaCounterTx
import info.skyblond.daapu.hand.EmbeddingException
import info.skyblond.daapu.hand.FakeHand
import info.skyblond.daapu.hand.HandEmbedRequest
import info.skyblond.daapu.hand.HandEmbedResult
import info.skyblond.daapu.hand.HandEmbedUsage
import info.skyblond.daapu.testutil.DbTestBase
import info.skyblond.daapu.testutil.DeterministicEmbeddings
import info.skyblond.daapu.testutil.TestDb
import info.skyblond.daapu.testutil.testAxisVector
import info.skyblond.daapu.testutil.testEmbeddingModel
import info.skyblond.daapu.testutil.testPostgresEltmService
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
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
        assertEquals(listOf(first.entity.id), second.nearMatches.map { it.entity.id })
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
        assertEquals(created.id, refined.id, "the id is kept")
        assertEquals("paperwhite 6", refined.canonicalName)
        assertEquals("device", refined.category)
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
        assertEquals(created, refined)
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

        val rel = service.createRelationship(a.id, b.id, "Works At")
        assertEquals("works_at", rel.verb, "the verb is normalized")
        assertTrue(rel.valid)
        val versionAfterCreate = service.version().toLong()

        val again = service.createRelationship(a.id, b.id, "works_at")
        assertEquals(rel.id, again.id, "the triple row is the relationship")
        assertEquals(versionAfterCreate, service.version().toLong(), "a re-assert never bumps")

        // end the edge via the diary event, then re-assert: still ONE row,
        // still invalid — validity only moves with a note
        service.attachNoteToRelationship(rel.id, day, "left the company", valid = false)
        val revived = service.createRelationship(a.id, b.id, "works_at")
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
        val rel = service.createRelationship(a.id, b.id, "works_at")
        val versionBefore = service.version().toLong()

        val note = service.attachNoteToRelationship(rel.id, day, "left the company", valid = false)
        assertEquals(rel.id, note.relationshipId)
        assertEquals(versionBefore + 1, service.version().toLong(), "ONE bump for the compound event")
        assertFalse(
            service.getRelationship(rel.id)?.relationship?.valid ?: fail("relationship missing"),
            "the edge is closed by the note",
        )

        // idempotent state change: the note still attaches, still one bump
        val versionAfterClose = service.version().toLong()
        service.attachNoteToRelationship(rel.id, day, "still gone", valid = false)
        assertEquals(versionAfterClose + 1, service.version().toLong())

        try {
            service.attachNoteToRelationship(4242L, day, "x")
            fail("a missing subject must fail fast")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("does not exist"))
        }
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
        val selfLoopEdge = service.createRelationship(winner.id, loser.id, "renamed_to").id
        service.attachNoteToRelationship(selfLoopEdge, day, "merged branding")
        // a loser edge to a third entity: re-pointed to the winner
        val rePointedEdge = service.createRelationship(loser.id, third.id, "employs").id
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
            selfLoopView.relationship.srcId == winner.id && selfLoopView.relationship.dstId == winner.id,
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
    fun `mergeEntities collapses opposite-direction edges onto one invalidated row`() = runBlocking {
        val service = service()
        val winner = service.createEntity("apple", "company").entity
        val loser = service.createEntity("apple inc", "company").entity

        val forward = service.createRelationship(winner.id, loser.id, "knows").id
        val backward = service.createRelationship(loser.id, winner.id, "knows").id
        service.attachNoteToRelationship(forward, day, "forward note")
        service.attachNoteToRelationship(backward, day, "backward note")

        service.mergeEntities(winner.id, loser.id)

        // both edges re-point to the same winner—winner triple: the first
        // becomes the invalidated self-loop row, the second folds into it
        val selfLoops = service.getRelationships(winner.id, includeInvalid = true)
            .filter { it.relationship.srcId == winner.id && it.relationship.dstId == winner.id }
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
        assertEquals(created.entity.id, hit.entity.id)
        assertEquals(1.0, hit.score, 1e-6, "the query vector IS the stored vector")
        assertEquals(1, hit.attributes.size)

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

    // ------------------------------------------------------------------
    // views and paging
    // ------------------------------------------------------------------

    @Test
    fun `views carry counts, latest note, attributes and endpoint names`() = runBlocking {
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
        assertEquals("alice", rel.srcName)
        assertEquals("acme", rel.dstName)
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
    fun `getRelationships filters validity and lists both directions`() = runBlocking {
        val service = service()
        val a = service.createEntity("a", "x").entity
        val b = service.createEntity("b", "x").entity
        val c = service.createEntity("c", "x").entity
        val ab = service.createRelationship(a.id, b.id, "knows").id
        val ca = service.createRelationship(c.id, a.id, "knows").id
        val invalid = service.createRelationship(a.id, c.id, "knew").id
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
