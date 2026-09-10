package info.skyblond.daapu.memory.eltm

import info.skyblond.daapu.config.MAX_VECTOR_DIMENSIONS
import info.skyblond.daapu.db.padVector
import info.skyblond.daapu.hand.FakeHand
import info.skyblond.daapu.hand.HandEmbedRequest
import info.skyblond.daapu.hand.HandEmbedResult
import info.skyblond.daapu.hand.HandRunPolicy
import info.skyblond.daapu.testutil.DbTestBase
import info.skyblond.daapu.testutil.DeterministicEmbeddings
import info.skyblond.daapu.testutil.TestDb
import info.skyblond.daapu.testutil.testAxisVector
import info.skyblond.daapu.testutil.testEmbeddingModel
import info.skyblond.daapu.testutil.testHandService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the in-server re-embed job (`EmbeddingRefreshService.kt`) over the
 * real tables: every stored vector is rewritten with the model's output
 * (registered deterministic vectors, so the stored values are assertable),
 * the version counter bumps ONCE on a non-empty success and never on a
 * failure or an empty run, a failed run keeps its already-written batches
 * and is re-runnable, and the single-flight start refuses a second run.
 */
class EmbeddingRefreshServiceTest : DbTestBase() {

    /** A full-width stale vector, distinct from every registered one. */
    private val stale = List(MAX_VECTOR_DIMENSIONS) { 0.5f }

    private var service: EmbeddingRefreshService? = null

    @AfterTest
    fun stopService() {
        service?.close()
    }

    private fun newService(
        embedScript: suspend (HandEmbedRequest) -> HandEmbedResult = DeterministicEmbeddings().script,
    ): EmbeddingRefreshService =
        EmbeddingRefreshService(
            hand = testHandService(FakeHand(embedScript = embedScript)),
            model = testEmbeddingModel(),
            policy = HandRunPolicy(0, 0),
        ).also { service = it }

    /** Poll [condition] until it holds or the (generous) deadline lapses. */
    private suspend fun awaitUntil(timeoutMs: Long = 10_000, condition: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "condition not met within ${timeoutMs}ms" }
            delay(25)
        }
    }

    @Test
    fun `rewrites every vector through the model and bumps the version once`() = runBlocking {
        val embeddings = DeterministicEmbeddings()
        val entityVector = testAxisVector(0)
        val bareVector = testAxisVector(1)
        val noteVector = testAxisVector(2)
        // the exact texts the refresh composes: attributes ride the entity
        // text as alphabetically ordered `key: value` lines, notes embed
        // their trimmed text
        embeddings.register(entityEmbeddingText("kindle", "device", mapOf("color" to "black")), entityVector)
        embeddings.register(entityEmbeddingText("alice", "person", emptyMap()), bareVector)
        embeddings.register(noteEmbeddingText("a note"), noteVector)
        val refresh = newService(embeddings.script)

        val kindleId = TestDb.seedEltmEntity("kindle", "device", mapOf("color" to "black"), stale)
        TestDb.seedEltmEntity("alice", "person", embedding = stale)
        TestDb.seedEltmNote(kindleId, "a note", stale)
        assertEquals(0L, TestDb.eltmVersion(), "the seeded counter starts at 0")

        assertEquals(ReembedStatus.Idle, refresh.status())
        assertTrue(refresh.start(), "the first start must be accepted")
        awaitUntil { refresh.status() !is ReembedStatus.Running }
        val finished = refresh.status()
        assertTrue(finished is ReembedStatus.Finished, "expected finished, got $finished")
        assertEquals(2L, finished.entities)
        assertEquals(1L, finished.notes)

        // the stored vectors are the model's outputs, zero-padded to the
        // column width — NOT the seeded stale vector
        assertEquals(
            listOf(padVector(entityVector, MAX_VECTOR_DIMENSIONS), padVector(bareVector, MAX_VECTOR_DIMENSIONS)),
            TestDb.allEltmEntityEmbeddings(),
        )
        assertEquals(listOf(padVector(noteVector, MAX_VECTOR_DIMENSIONS)), TestDb.allEltmNoteEmbeddings())
        assertEquals(1L, TestDb.eltmVersion(), "a non-empty success bumps the counter exactly once")
    }

    @Test
    fun `an empty ELTM finishes without a bump`() = runBlocking {
        val refresh = newService()

        assertTrue(refresh.start())
        awaitUntil { refresh.status() !is ReembedStatus.Running }
        val finished = refresh.status()
        assertTrue(finished is ReembedStatus.Finished, "expected finished, got $finished")
        assertEquals(0L, finished.entities)
        assertEquals(0L, finished.notes)
        assertEquals(0L, TestDb.eltmVersion(), "an empty run leaves the counter untouched")
    }

    @Test
    fun `a failed embed fails the run without the bump and written batches stick`() = runBlocking {
        val embeddings = DeterministicEmbeddings()
        val calls = AtomicInteger(0)
        val refresh = newService { request ->
            // the first batch (EMBED_BATCH_SIZE rows) succeeds, the second
            // fails — the entities processed before the failure keep their
            // new vectors
            if (calls.incrementAndGet() == 1) {
                embeddings.script(request)
            } else {
                error("upstream broke")
            }
        }

        repeat(EMBED_BATCH_SIZE + 8) { index ->
            TestDb.seedEltmEntity("e$index", "thing", embedding = stale)
        }
        assertTrue(refresh.start())
        awaitUntil { refresh.status() !is ReembedStatus.Running }
        val failed = refresh.status()
        assertTrue(failed is ReembedStatus.Failed, "expected failed, got $failed")
        assertEquals("upstream broke", failed.error)
        assertEquals(0L, TestDb.eltmVersion(), "a failed run must not bump the counter")

        // id-order batches: the first EMBED_BATCH_SIZE rows were written
        // back before the failure, the rest keep the stale vectors — the
        // run is safely re-runnable over exactly the unwritten remainder
        val stored = TestDb.allEltmEntityEmbeddings()
        assertEquals(EMBED_BATCH_SIZE + 8, stored.size)
        val fresh = stored.take(EMBED_BATCH_SIZE)
        assertTrue(fresh.all { it != stale }, "the written batch keeps its new vectors")
        val remaining = stored.drop(EMBED_BATCH_SIZE)
        assertTrue(remaining.all { it == stale }, "the unwritten rows keep the stale vectors")
    }

    @Test
    fun `a second start is refused while a run is active and works again after`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val refresh = newService { request ->
            gate.await()
            DeterministicEmbeddings().script(request)
        }
        val entityId = TestDb.seedEltmEntity("kindle", "device", embedding = stale)

        assertTrue(refresh.start())
        assertTrue(refresh.status() is ReembedStatus.Running, "the gated embed keeps the run active")
        assertFalse(refresh.start(), "the second start must be refused while running")

        gate.complete(Unit)
        awaitUntil { refresh.status() !is ReembedStatus.Running }
        val finished = refresh.status()
        assertTrue(finished is ReembedStatus.Finished, "expected finished, got $finished")
        assertEquals(1L, finished.entities)

        // a finished run leaves the single-flight lock: the next start is
        // accepted again (a no-op over the one entity, still a success)
        assertTrue(refresh.start())
        awaitUntil { refresh.status() !is ReembedStatus.Running }
        assertTrue(TestDb.allEltmEntityEmbeddings().single() != stale)
        assertEquals(entityId, TestDb.allEltmEntities().single().id)
    }
}
