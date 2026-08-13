package info.skyblond.daapu.memory.sstm

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Pins the version fingerprint shared by all [SstmService] implementations.
 * The fingerprint drives the per-chat `sstm-updated` injection flag (stored
 * on the chat at the last successful run, compared per run), so its semantics
 * — deterministic, order-sensitive, changing with any column — are contract,
 * not implementation detail. The digest is `protected`, so the test probes it
 * through a subclass.
 */
class SstmServiceTest {

    private class DigestProbe : AbstractSstmService() {
        fun digest(memories: List<ShortTermMemory>) = digestVersion(memories)

        override suspend fun listMemories(): MemoriesWithVersion = error("not used in digest tests")

        override suspend fun createMemory(content: String): ShortTermMemory =
            error("not used in digest tests")

        override suspend fun updateMemory(id: Long, content: String): ShortTermMemory? =
            error("not used in digest tests")

        override suspend fun deleteMemory(id: Long): Boolean = error("not used in digest tests")
    }

    private val probe = DigestProbe()

    private val timestamp = Instant.parse("2026-08-13T12:00:00Z")

    private fun memory(id: Long, content: String) = ShortTermMemory(
        id = id,
        lastUpdate = timestamp,
        content = content,
    )

    @Test
    fun `empty memory list fingerprints to a stable non-empty value`() {
        // a fresh chat stores "" as its version, so an empty table must
        // fingerprint to something non-empty: the first run always flags
        assertEquals(probe.digest(emptyList()), probe.digest(emptyList()))
        assertNotEquals("", probe.digest(emptyList()))
    }

    @Test
    fun `fingerprint is deterministic`() {
        val memories = listOf(memory(1, "a"), memory(2, "b"))
        assertEquals(probe.digest(memories), probe.digest(memories))
    }

    @Test
    fun `fingerprint is order-sensitive`() {
        val a = memory(1, "a")
        val b = memory(2, "b")
        assertNotEquals(probe.digest(listOf(a, b)), probe.digest(listOf(b, a)))
    }

    @Test
    fun `fingerprint changes with content`() {
        assertNotEquals(
            probe.digest(listOf(memory(1, "a"))),
            probe.digest(listOf(memory(1, "b"))),
        )
    }

    @Test
    fun `fingerprint changes with id`() {
        // delete + re-create with the same text is a different memory row
        assertNotEquals(
            probe.digest(listOf(memory(1, "a"))),
            probe.digest(listOf(memory(2, "a"))),
        )
    }

    @Test
    fun `fingerprint changes with lastUpdate`() {
        assertNotEquals(
            probe.digest(listOf(memory(1, "a"))),
            probe.digest(listOf(memory(1, "a").copy(lastUpdate = timestamp.plusSeconds(1)))),
        )
    }
}
