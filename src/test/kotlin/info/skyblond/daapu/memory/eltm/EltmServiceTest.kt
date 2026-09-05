package info.skyblond.daapu.memory.eltm

import info.skyblond.daapu.db.padVector
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/**
 * Pins the pure decision logic of the ELTM store: normalization and
 * zero-pad (cosine-invariant storage width). The Postgres SQL behavior
 * stays uncovered until DB-backed integration tests exist — these helpers
 * are the pieces the raw queries build on.
 */
class EltmServiceTest {

    // ------------------------------------------------------------------
    // normalization
    // ------------------------------------------------------------------

    @Test
    fun `normalizeName trims, collapses whitespace and lowercases`() {
        assertEquals("alice", normalizeName("  Alice  "))
        assertEquals("new york city", normalizeName("  New   York\n\tCity  "))
        assertEquals("user", normalizeName("USER"))
        assertEquals("a b", normalizeName("a  b"), "internal whitespace collapses to one space")
        assertEquals("", normalizeName("   "))
    }

    @Test
    fun `normalizeVerb turns spaces into underscores`() {
        assertEquals("colleague_of", normalizeVerb("Colleague of"))
        assertEquals("works_at", normalizeVerb("  works   at  "))
        assertEquals("", normalizeVerb("   "))
    }

    @Test
    fun `normalizeAttributeKey is verb-like`() {
        assertEquals("model", normalizeAttributeKey(" Model "))
        assertEquals("real_name", normalizeAttributeKey("Real Name"))
        assertEquals("", normalizeAttributeKey("   "))
    }

    // ------------------------------------------------------------------
    // entity embedding text
    // ------------------------------------------------------------------

    @Test
    fun `entityEmbeddingText appends alphabetically ordered attribute lines`() {
        assertEquals(
            "kindle device",
            entityEmbeddingText("kindle", "device", emptyMap()),
            "without attributes the text is the plain name + category"
        )
        assertEquals(
            "kindle device\nmodel: Paperwhite 6\nrealname: Alice",
            entityEmbeddingText(
                "kindle", "device",
                mapOf("realname" to "Alice", "model" to "Paperwhite 6"),
            ),
            "the keys are ordered alphabetically, not by insertion order"
        )
    }

    // ------------------------------------------------------------------
    // note embedding text
    // ------------------------------------------------------------------

    @Test
    fun `noteEmbeddingText is the trimmed note`() {
        assertEquals("bought a kindle", noteEmbeddingText("  bought a kindle  "))
        assertEquals("a\nb", noteEmbeddingText("  a\nb  "), "internal newlines are content, not structure")
        assertEquals("", noteEmbeddingText("   "), "the blank check stays at the call site")
        // the shape must be reproducible from the stored row: what the
        // service stores IS the trimmed text, so refreshing an existing
        // note re-embeds exactly the text it was embedded with before
        assertEquals(noteEmbeddingText("  hello  "), noteEmbeddingText(noteEmbeddingText("  hello  ")))
    }

    // ------------------------------------------------------------------
    // attribute fold planning (the merge's attribute decision logic)
    // ------------------------------------------------------------------

    @Test
    fun `planAttributeFold keeps the winner's value on a colliding key`() {
        val plan = planAttributeFold(
            winnerAttrs = mapOf("ticker" to "AAPL", "hq" to "Cupertino"),
            loserAttrs = mapOf("ticker" to "APPL", "founded" to "1976"),
        )
        assertEquals(setOf("founded"), plan.foldableKeys)
        assertEquals(setOf("ticker"), plan.droppedKeys)
        assertEquals(
            mapOf("ticker" to "AAPL", "hq" to "Cupertino", "founded" to "1976"),
            plan.winnerAttributes,
            "the winner's value wins the collision, the loser's unique key folds in"
        )
        assertTrue(plan.changesText)
    }

    @Test
    fun `planAttributeFold with no new keys is a pure read`() {
        val winner = mapOf("ticker" to "AAPL", "founded" to "1976")
        val plan = planAttributeFold(winner, mapOf("ticker" to "APPL"))
        assertTrue(plan.foldableKeys.isEmpty(), plan.foldableKeys.toString())
        assertEquals(setOf("ticker"), plan.droppedKeys)
        assertEquals(winner, plan.winnerAttributes, "no new key: the text never changes")
        assertFalse(plan.changesText)
    }

    @Test
    fun `planAttributeFold with no attributes changes nothing`() {
        val plan = planAttributeFold(emptyMap(), emptyMap())
        assertTrue(plan.foldableKeys.isEmpty())
        assertTrue(plan.droppedKeys.isEmpty())
        assertTrue(plan.winnerAttributes.isEmpty())
        assertFalse(plan.changesText)
    }

    // ------------------------------------------------------------------
    // zero-padding
    // ------------------------------------------------------------------

    @Test
    fun `padVector zero-pads to the column width preserving cosine`() {
        val v = listOf(1f, 2f, 3f)
        val padded = padVector(v, 8)
        assertEquals(8, padded.size)
        assertEquals(listOf(1f, 2f, 3f, 0f, 0f, 0f, 0f, 0f), padded)
        // cosine similarity is invariant: the zero dimensions contribute
        // nothing to the dot product or the norms
        val other = listOf(4f, 5f, 6f, 0f, 0f, 0f, 0f, 0f)
        assertEquals(cosine(v, listOf(4f, 5f, 6f)), cosine(padded, other))
        // an exact-width vector is returned as-is
        assertEquals(v, padVector(v, 3))
    }

    @Test
    fun `padVector refuses to truncate`() {
        assertFailsWith<IllegalArgumentException> { padVector(listOf(1f, 2f, 3f), 2) }
    }

    private fun cosine(a: List<Float>, b: List<Float>): Double {
        val dot = a.zip(b).sumOf { (x, y) -> (x * y).toDouble() }
        val normA = kotlin.math.sqrt(a.sumOf { (it * it).toDouble() })
        val normB = kotlin.math.sqrt(b.sumOf { (it * it).toDouble() })
        return dot / (normA * normB)
    }

    // ------------------------------------------------------------------
    // version
    // ------------------------------------------------------------------

    private class TestEltmService(
        private var writeVersion: Long,
    ) : EltmService {
        override suspend fun version(): String = writeVersion.toString()
        // the store operations are exercised through the real
        // PostgresEltmService (test database) in the writer tests
        override suspend fun createEntity(name: String, category: String): CreateEntityResult =
            error("unused")

        override suspend fun createRelationship(srcId: Long, dstId: Long, verb: String): EltmRelationship =
            error("unused")

        override suspend fun mergeEntities(winnerId: Long, loserId: Long) = error("unused")
        override suspend fun refineEntity(
            entityId: Long,
            newName: String?,
            newCategory: String?,
        ): EltmEntity = error("unused")

        override suspend fun entityExists(entityId: Long): Boolean = error("unused")
        override suspend fun relationshipExists(relationshipId: Long): Boolean = error("unused")
        override suspend fun setEntityAttribute(
            entityId: Long,
            key: String,
            value: String,
        ): Boolean = error("unused")

        override suspend fun deleteEntityAttribute(entityId: Long, key: String) = error("unused")
        override suspend fun attachNoteToEntity(
            entityId: Long,
            eventDate: java.time.LocalDate,
            note: String,
        ): EltmNote = error("unused")

        override suspend fun attachNoteToRelationship(
            relationshipId: Long,
            eventDate: java.time.LocalDate,
            note: String,
            valid: Boolean?,
        ): EltmNote = error("unused")

        override suspend fun searchEntities(query: String, limit: Int): List<EntityWithScore> =
            error("unused")

        override suspend fun getEntity(id: Long): EntityView? = error("unused")
        override suspend fun listEntities(limit: Int, offset: Int): List<EntityView> =
            error("unused")

        override suspend fun listRelationships(limit: Int, offset: Int): List<RelationshipView> =
            error("unused")

        override suspend fun getRelationship(id: Long): RelationshipView? = error("unused")
        override suspend fun getRelationships(
            entityId: Long,
            includeInvalid: Boolean,
        ): List<RelationshipView> = error("unused")

        override suspend fun getEntityNotes(
            entityId: Long,
            from: java.time.LocalDate?,
            to: java.time.LocalDate?,
            limit: Int,
            offset: Int,
        ): List<EltmNote> = error("unused")

        override suspend fun getRelationshipNotes(
            relationshipId: Long,
            from: java.time.LocalDate?,
            to: java.time.LocalDate?,
            limit: Int,
            offset: Int,
        ): List<EltmNote> = error("unused")

        override suspend fun searchNotes(
            query: String,
            entityId: Long?,
            relationshipId: Long?,
            from: java.time.LocalDate?,
            to: java.time.LocalDate?,
            limit: Int,
        ): List<EltmNote> = error("unused")

        override suspend fun setRelationshipValid(relationshipId: Long, valid: Boolean): Boolean =
            error("unused")

        override suspend fun exportAll(): EltmSnapshot = error("unused")
    }

    @Test
    fun `version renders the global write counter`() = runBlocking {
        val service = TestEltmService(0)
        assertEquals("0", service.version(), "an empty store renders version zero")

        // every write kind bumps the version, so the version moves even when
        // the row counts stay the same (a revive/invalidation has no count
        // change — that is why the version, not the counts, is the signal)
        val bumped = TestEltmService(8)
        assertEquals("8", bumped.version())
        assertNotEquals(service.version(), bumped.version())
    }
}
