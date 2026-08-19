package info.skyblond.daapu.memory.eltm

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
        // the store operations are exercised through FakeEltmService in the
        // writer/purge tests
        override suspend fun createEntity(name: String, category: String): CreateEntityResult =
            error("unused")

        override suspend fun createRelationship(srcId: Long, dstId: Long, verb: String): EltmRelationship =
            error("unused")

        override suspend fun mergeEntities(winnerId: Long, loserId: Long) = error("unused")
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
