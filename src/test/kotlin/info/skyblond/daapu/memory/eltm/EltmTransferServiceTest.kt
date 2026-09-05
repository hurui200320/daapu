package info.skyblond.daapu.memory.eltm

import info.skyblond.daapu.hand.EmbeddingException
import info.skyblond.daapu.hand.FakeHand
import info.skyblond.daapu.testutil.DbTestBase
import info.skyblond.daapu.testutil.TestDb
import info.skyblond.daapu.testutil.testPostgresEltmService
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * DB-backed tests for the transfer merge ([EltmTransferService]) against
 * the real [PostgresEltmService] (throwaway testcontainers PostgreSQL):
 * the export snapshot's shape, the entity/relationship matching, the note
 * dedup, the attribute overwrite flag, the `valid` rule and the fail-fast
 * partial semantics. Embeddings ride the default [FakeHand] all-ones
 * script (nothing here depends on similarity outcomes).
 */
class EltmTransferServiceTest : DbTestBase() {

    private val day = LocalDate.of(2026, 8, 17)
    private val day2 = LocalDate.of(2026, 8, 18)
    private val day3 = LocalDate.of(2026, 8, 19)

    private fun service(hand: FakeHand = FakeHand()): Pair<PostgresEltmService, EltmTransferService> {
        val eltm = testPostgresEltmService(hand)
        return eltm to EltmTransferService(eltm)
    }

    private fun entity(
        name: String,
        category: String,
        attributes: Map<String, String> = emptyMap(),
        notes: List<EltmExportNote> = emptyList(),
    ) = EltmExportEntity(name, category, attributes, notes)

    private fun rel(
        src: String,
        verb: String,
        dst: String,
        valid: Boolean = true,
        notes: List<EltmExportNote> = emptyList(),
    ) = EltmExportRelationship(src, verb, dst, valid, notes)

    /** The store's single relationship row (the seeded state holds exactly one). */
    private suspend fun relationshipRow(): EltmRelationship = TestDb.allEltmRelationships().single()

    // ------------------------------------------------------------------
    // export
    // ------------------------------------------------------------------

    @Test
    fun `exportEltm renders an empty store as an empty payload`() = runBlocking {
        val (_, transfer) = service()
        val payload = transfer.exportEltm()
        assertTrue(payload.entities.isEmpty(), "no entities")
        assertTrue(payload.relationships.isEmpty(), "no relationships")
    }

    @Test
    fun `exportEltm nests attributes and notes under fresh uuids and relationships reference them`() =
        runBlocking {
            val (eltm, transfer) = service()
            val kindle = eltm.createEntity("kindle", "device").entity
            eltm.setEntityAttribute(kindle.id, "model", "k4")
            eltm.attachNoteToEntity(kindle.id, day, "bought it")
            val alice = eltm.createEntity("alice", "person").entity
            eltm.attachNoteToEntity(alice.id, day2, "met alice")
            val works = eltm.createRelationship(kindle.id, alice.id, "belongs to")
            eltm.attachNoteToRelationship(works.id, day3, "gave it away", valid = false)

            val payload = transfer.exportEltm()
            assertEquals(2, payload.entities.size)
            assertEquals(1, payload.relationships.size)
            // the keys are uuids minted for THIS file
            payload.entities.keys.forEach { key -> UUID.fromString(key) }

            val kindleEntry = payload.entities.values.single { it.name == "kindle" }
            assertEquals("device", kindleEntry.category)
            assertEquals(mapOf("model" to "k4"), kindleEntry.attributes)
            assertEquals(
                listOf(EltmExportNote(day.toString(), "bought it")),
                kindleEntry.notes,
            )

            val relationship = payload.relationships.single()
            assertTrue(relationship.srcUuid in payload.entities, "srcUuid references an entity key")
            assertTrue(relationship.dstUuid in payload.entities, "dstUuid references an entity key")
            assertEquals("belongs_to", relationship.verb)
            assertFalse(relationship.valid, "the structural state exports as-is")
            assertEquals(
                listOf(EltmExportNote(day3.toString(), "gave it away")),
                relationship.notes,
            )

            // a second export mints FRESH uuids (file-scope join keys only)
            // while the content stays identical
            val again = transfer.exportEltm()
            assertTrue(
                again.entities.keys != payload.entities.keys,
                "the uuids are minted per export, not stable",
            )
            assertEquals(
                payload.entities.values.map { it.name }.sorted(),
                again.entities.values.map { it.name }.sorted(),
            )
        }

    // ------------------------------------------------------------------
    // import: matching, dedup, attributes, the valid rule
    // ------------------------------------------------------------------

    @Test
    fun `import then re-import round-trips - everything created once, everything skipped twice`() =
        runBlocking {
            val (eltm, transfer) = service()
            val kindle = eltm.createEntity("kindle", "device").entity
            eltm.setEntityAttribute(kindle.id, "model", "k4")
            eltm.attachNoteToEntity(kindle.id, day, "bought it")
            val alice = eltm.createEntity("alice", "person").entity
            eltm.attachNoteToEntity(alice.id, day2, "met alice")
            val works = eltm.createRelationship(kindle.id, alice.id, "belongs to")
            eltm.attachNoteToRelationship(works.id, day3, "gave it away", valid = false)
            val payload = transfer.exportEltm()

            // a fresh store: the file rebuilds everything (fresh row ids,
            // embeddings recomputed through the hand)
            TestDb.resetAll()
            val (freshEltm, freshTransfer) = service()
            val summary = freshTransfer.importEltm(payload, overwriteAttr = false)
            assertEquals(2, summary.entitiesCreated)
            assertEquals(0, summary.entitiesMatched)
            assertEquals(1, summary.relationshipsCreated)
            assertEquals(3, summary.notesInserted, "2 entity notes + 1 relationship note")
            assertEquals(0, summary.notesSkipped)
            assertEquals(1, summary.attributesWritten)
            assertEquals(0, summary.attributesKept)

            val entities = freshEltm.listEntities(100, 0)
            assertEquals(
                listOf("alice", "kindle"),
                entities.map { it.entity.canonicalName }.sorted(),
            )
            val kindleView = entities.single { it.entity.canonicalName == "kindle" }
            assertEquals(mapOf("model" to "k4"), kindleView.attributes)
            val worksView = freshEltm.listRelationships(100, 0).single()
            assertFalse(worksView.relationship.valid, "the exported state survives the round trip")
            assertEquals("belongs_to", worksView.relationship.verb)

            // re-importing the SAME file skips everything (dedup)
            val again = freshTransfer.importEltm(payload, overwriteAttr = false)
            assertEquals(0, again.entitiesCreated)
            assertEquals(2, again.entitiesMatched)
            assertEquals(0, again.relationshipsCreated)
            assertEquals(1, again.relationshipsMatched)
            assertEquals(0, again.notesInserted)
            assertEquals(3, again.notesSkipped)
            assertEquals(0, again.attributesWritten)
            assertEquals(1, again.attributesKept, "the identical attribute value is kept")
            assertEquals(3, TestDb.allEltmNotes().size, "no duplicated notes")
        }

    @Test
    fun `importEltm matches entities on (name, category) regardless of uuid`() =
        runBlocking {
            val (eltm, transfer) = service()
            val seeded = eltm.createEntity("  Kindle ", "Device").entity
            eltm.setEntityAttribute(seeded.id, "model", "k4")
            eltm.attachNoteToEntity(seeded.id, day, "seeded")

            val payload = EltmExportPayload(
                entities = mapOf(
                    "uuid-a" to entity("Kindle", "device", attributes = mapOf("model" to "k4"),
                        notes = listOf(EltmExportNote(day.toString(), "seeded"))),
                ),
                relationships = emptyList(),
            )
            val summary = transfer.importEltm(payload, overwriteAttr = false)
            // the file entry matches the seeded row through normalization —
            // the uuid is never consulted
            assertEquals(0, summary.entitiesCreated)
            assertEquals(1, summary.entitiesMatched)
            assertEquals(1, TestDb.allEltmEntities().size)
            assertEquals(1, TestDb.allEltmNotes().size, "the seeded note is not duplicated")
            val attrs = eltm.getEntity(seeded.id)!!.attributes
            assertEquals(mapOf("model" to "k4"), attrs, "the identical attribute is kept")
        }

    @Test
    fun `importEltm dedups notes on (date, trimmed text) and stores the trimmed text`() = runBlocking {
        val (eltm, transfer) = service()
        val created = eltm.createEntity("kindle", "device").entity
        eltm.attachNoteToEntity(created.id, day, "bought it")

        val payload = EltmExportPayload(
            entities = mapOf(
                "uuid-a" to entity(
                    "kindle", "device",
                    notes = listOf(
                        EltmExportNote(day.toString(), "  bought it  "),  // exact dup of the seeded note
                        EltmExportNote(day.toString(), "bought it"),      // exact dup again (same file)
                        EltmExportNote(day2.toString(), "  loved it "),   // new: trimmed and inserted
                        EltmExportNote(day3.toString(), "bought it"),     // same text, different date: inserted
                    ),
                ),
            ),
            relationships = emptyList(),
        )
        val summary = transfer.importEltm(payload, overwriteAttr = false)
        assertEquals(1, summary.entitiesMatched)
        assertEquals(2, summary.notesInserted)
        assertEquals(2, summary.notesSkipped)
        val notes = TestDb.allEltmNotes().sortedBy { it.eventDate }
        assertEquals(
            listOf(
                day to "bought it",
                day2 to "loved it",
                day3 to "bought it",
            ),
            notes.map { it.eventDate to it.note },
            "only the (date, trimmed text)-distinct notes land, stored trimmed",
        )
    }

    @Test
    fun `importEltm keeps attributes with the flag off, overwrites with it on, always sets new keys`() =
        runBlocking {
            val (eltm, transfer) = service()
            val created = eltm.createEntity("kindle", "device").entity
            eltm.setEntityAttribute(created.id, "model", "k4")
            eltm.setEntityAttribute(created.id, "owner", "me")

            val payload = EltmExportPayload(
                entities = mapOf(
                    "uuid-a" to entity(
                        "kindle", "device",
                        attributes = mapOf("model" to "k9", "owner" to "me", "color" to "black"),
                    ),
                ),
                relationships = emptyList(),
            )

            val kept = transfer.importEltm(payload, overwriteAttr = false)
            assertEquals(1, kept.attributesWritten, "only the new key is set")
            assertEquals(2, kept.attributesKept, "the existing keys keep their values")
            assertEquals(
                mapOf("color" to "black", "model" to "k4", "owner" to "me"),
                eltm.getEntity(created.id)!!.attributes,
            )

            val overwritten = transfer.importEltm(payload, overwriteAttr = true)
            assertEquals(1, overwritten.attributesWritten, "only the changed value rewrites")
            assertEquals(2, overwritten.attributesKept, "the identical values are no-ops")
            assertEquals(
                mapOf("color" to "black", "model" to "k9", "owner" to "me"),
                eltm.getEntity(created.id)!!.attributes,
            )
        }

    @Test
    fun `importEltm applies the valid rule - a newer file note wins, an older one keeps the DB state`() =
        runBlocking {
            val (eltm, transfer) = service()
            val a = eltm.createEntity("alice", "person").entity
            val b = eltm.createEntity("bob", "person").entity
            val works = eltm.createRelationship(a.id, b.id, "works with")
            eltm.attachNoteToRelationship(works.id, day2, "still going")

            assertTrue(relationshipRow().valid)

            // the file's newest note (day) is OLDER than the DB's latest
            // note (day2): the DB is the newer truth, the state stays
            val older = EltmExportPayload(
                entities = mapOf(
                    "a" to entity("alice", "person"),
                    "b" to entity("bob", "person"),
                ),
                relationships = listOf(
                    rel("a", "works with", "b", valid = false,
                        notes = listOf(EltmExportNote(day.toString(), "an older event"))),
                ),
            )
            val olderSummary = transfer.importEltm(older, overwriteAttr = false)
            assertEquals(1, olderSummary.relationshipsMatched)
            assertTrue(relationshipRow().valid, "an older file note never flips the state")

            // the file's newest note (day3) is NEWER: the file's state wins
            val newer = older.copy(
                relationships = listOf(
                    rel("a", "works with", "b", valid = false,
                        notes = listOf(EltmExportNote(day3.toString(), "ended"))),
                ),
            )
            val newerSummary = transfer.importEltm(newer, overwriteAttr = false)
            assertEquals(1, newerSummary.relationshipsMatched)
            assertFalse(relationshipRow().valid, "a newer file note flips the state")

            // equal dates are not strictly newer: the DB state stays
            val equal = newer.copy(
                relationships = listOf(
                    rel("a", "works with", "b", valid = true,
                        notes = listOf(EltmExportNote(day3.toString(), "revived? no"))),
                ),
            )
            transfer.importEltm(equal, overwriteAttr = false)
            assertFalse(relationshipRow().valid, "an equal-dated file note never flips the state")
        }

    @Test
    fun `importEltm valid rule edge cases - a noteless DB row loses, a new row takes the file state`() =
        runBlocking {
            val (eltm, transfer) = service()
            val a = eltm.createEntity("alice", "person").entity
            val b = eltm.createEntity("bob", "person").entity
            // a bare triple without diary notes (created without any note)
            eltm.createRelationship(a.id, b.id, "knows")

            val payload = EltmExportPayload(
                entities = mapOf(
                    "a" to entity("alice", "person"),
                    "b" to entity("bob", "person"),
                    "c" to entity("carol", "person"),
                ),
                relationships = listOf(
                    // the DB row has no notes to protect: the file wins
                    rel("a", "knows", "b", valid = false,
                        notes = listOf(EltmExportNote(day.toString(), "ended"))),
                    // a NEW relationship with NO notes still takes the file's
                    // state (nothing pre-exists to protect — the approved edge)
                    rel("b", "mentors", "c", valid = false),
                ),
            )
            val summary = transfer.importEltm(payload, overwriteAttr = false)
            assertEquals(1, summary.entitiesCreated)
            assertEquals(2, summary.entitiesMatched)
            assertEquals(1, summary.relationshipsCreated)
            assertEquals(1, summary.relationshipsMatched)

            val rows = TestDb.allEltmRelationships().associateBy { it.verb }
            assertFalse(rows["knows"]!!.valid, "a noteless DB row loses to the file's state")
            assertFalse(rows["mentors"]!!.valid, "a new row takes the file's state even without notes")
        }

    // ------------------------------------------------------------------
    // import: validation and fail-fast partial
    // ------------------------------------------------------------------

    @Test
    fun `importEltm validates the whole file before the first write`() = runBlocking {
        val (eltm, transfer) = service()
        val good = "uuid-a" to entity("alice", "person")
        val b = "uuid-b" to entity("bob", "person")
        val badPayloads: List<Pair<String, EltmExportPayload>> = listOf(
            "a blank name" to EltmExportPayload(
                entities = mapOf(good, "bad" to entity("   ", "person")),
                relationships = emptyList(),
            ),
            "a multi-line attribute value" to EltmExportPayload(
                entities = mapOf(good, "bad" to entity("bob", "person", attributes = mapOf("k" to "a\nb"))),
                relationships = emptyList(),
            ),
            "duplicate attribute keys (after normalization)" to EltmExportPayload(
                entities = mapOf(
                    good,
                    // one row per (entity, key): "Model" and "model" fold
                    // onto ONE row — rejected like duplicate entity keys
                    "bad" to entity("bob", "person", attributes = mapOf("Model" to "a", "model" to "b")),
                ),
                relationships = emptyList(),
            ),
            "a blank note" to EltmExportPayload(
                entities = mapOf(good, "bad" to entity("bob", "person",
                    notes = listOf(EltmExportNote(day.toString(), "   ")))),
                relationships = emptyList(),
            ),
            "a malformed note date" to EltmExportPayload(
                entities = mapOf(good, "bad" to entity("bob", "person",
                    notes = listOf(EltmExportNote("2026/08/17", "x")))),
                relationships = emptyList(),
            ),
            "a dangling relationship endpoint" to EltmExportPayload(
                entities = mapOf(good),
                relationships = listOf(rel("uuid-a", "knows", "uuid-ghost")),
            ),
            "a blank verb" to EltmExportPayload(
                entities = mapOf(good, b),
                relationships = listOf(rel("uuid-a", "  ", "uuid-b")),
            ),
            "a duplicate entity key (after normalization)" to EltmExportPayload(
                entities = mapOf(
                    good,
                    "dup-1" to entity("ALICE", "person"),
                    "dup-2" to entity("  alice  ", "Person"),
                ),
                relationships = emptyList(),
            ),
            "a duplicate relationship triple (after verb normalization)" to EltmExportPayload(
                entities = mapOf(good, b),
                relationships = listOf(
                    rel("uuid-a", "works with", "uuid-b"),
                    rel("uuid-a", "works_with", "uuid-b"),
                ),
            ),
        )
        for ((label, payload) in badPayloads) {
            val e = assertFailsWith<IllegalArgumentException>(label) {
                runBlocking { transfer.importEltm(payload, overwriteAttr = false) }
            }
            assertTrue(
                e.message!!.contains('['),
                "the 400-reason must carry the offending path, got: ${e.message}",
            )
            assertTrue(
                TestDb.allEltmEntities().isEmpty() && TestDb.allEltmNotes().isEmpty(),
                "$label: nothing is written when the file is broken",
            )
        }
    }

    @Test
    fun `importEltm is fail-fast partial and resumable`() = runBlocking {
        // the embed script fails the SECOND entity's create ("bob person"):
        // the failure is post-validation, so alice's writes stick
        val hand = FakeHand(embedScript = { request ->
            if (request.input.any { "bob" in it }) {
                throw EmbeddingException("invalid_request", "content too large for the embedding model")
            }
            FakeHand().embed(request)
        })
        val (eltm, transfer) = service(hand)
        val payload = EltmExportPayload(
            entities = mapOf(
                "a" to entity("alice", "person",
                    notes = listOf(EltmExportNote(day.toString(), "met alice"))),
                "b" to entity("bob", "person",
                    notes = listOf(EltmExportNote(day.toString(), "met bob"))),
            ),
            relationships = listOf(rel("a", "knows", "b")),
        )
        assertFailsWith<EmbeddingException> {
            transfer.importEltm(payload, overwriteAttr = false)
        }
        val entities = TestDb.allEltmEntities()
        assertEquals(listOf("alice"), entities.map { it.canonicalName }, "earlier writes stick")
        assertEquals(1, TestDb.allEltmNotes().size)
        assertTrue(TestDb.allEltmRelationships().isEmpty(), "the failing entity's pass never ran")

        // re-running the same file with a healthy hand resumes: alice
        // matches (dedup), bob and the relationship land
        val (freshEltm, freshTransfer) = service()
        val summary = freshTransfer.importEltm(payload, overwriteAttr = false)
        assertEquals(1, summary.entitiesCreated, "only bob")
        assertEquals(1, summary.entitiesMatched, "alice again")
        assertEquals(1, summary.relationshipsCreated)
        assertEquals(1, summary.notesInserted, "only bob's note; alice's is deduped")
        assertEquals(1, summary.notesSkipped)
        assertEquals(2, freshEltm.listEntities(100, 0).size)
        assertEquals(2, TestDb.allEltmNotes().size)
    }

    // ------------------------------------------------------------------
    // setRelationshipValid (the note-less validity write)
    // ------------------------------------------------------------------

    @Test
    fun `setRelationshipValid no-ops on the current state and bumps on a change`() = runBlocking {
        val (eltm, _) = service()
        val a = eltm.createEntity("alice", "person").entity
        val b = eltm.createEntity("bob", "person").entity
        val works = eltm.createRelationship(a.id, b.id, "works with")
        val versionBefore = eltm.version().toLong()

        assertFalse(eltm.setRelationshipValid(works.id, true), "already valid: a no-op")
        assertEquals(versionBefore, eltm.version().toLong(), "a no-op never bumps")

        assertTrue(eltm.setRelationshipValid(works.id, false), "a change is a write")
        assertEquals(versionBefore + 1, eltm.version().toLong(), "a change bumps once")
        assertFalse(TestDb.allEltmRelationships().single().valid)

        val missing = assertFailsWith<IllegalArgumentException> {
            eltm.setRelationshipValid(-1L, true)
        }
        assertTrue(missing.message!!.contains("does not exist"), "fail-fast on a missing row")
    }
}
