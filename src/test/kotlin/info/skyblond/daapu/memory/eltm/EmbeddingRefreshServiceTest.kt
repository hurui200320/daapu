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
import info.skyblond.daapu.testutil.testPostgresEltmService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Pins the in-server re-embed job (`EmbeddingRefreshService.kt`) over the
 * real tables: every stored vector is rewritten with the model's output
 * (registered deterministic vectors, so the stored values are assertable),
 * the version counter bumps ONCE on a non-empty success and never on a
 * failure or an empty run, a failed run keeps its already-written batches
 * and is re-runnable, and the single-flight start refuses a second run.
 * The guarded entity write-back (review.md's M4): a concurrent attribute
 * write, refine or merge landing mid-run is re-embedded from the locked
 * fresh state, never overwritten by the stale capture; merged-away rows
 * are skipped; the re-embed path is observable through the embed inputs.
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

    /**
     * Rendezvous state for a refresh whose FIRST embed call parks until
     * released (the M4 regression harness): the page read precedes the
     * embed, so [entered] firing proves the page text was already
     * captured — a writer committing between [entered] and [allow] lands
     * exactly in the read-to-write gap the guarded write-back must
     * detect. Only the first call parks; later calls (the write-back's
     * re-embed) pass straight through.
     */
    private class FirstEmbedGate {
        val entered = CompletableDeferred<Unit>()
        val allow = CompletableDeferred<Unit>()
    }

    private fun gatedRefresh(
        embeddings: DeterministicEmbeddings,
        gate: FirstEmbedGate,
    ): EmbeddingRefreshService {
        val calls = AtomicInteger(0)
        return newService { request ->
            if (calls.incrementAndGet() == 1) {
                gate.entered.complete(Unit)
                gate.allow.await()
            }
            embeddings.script(request)
        }
    }

    /** The concurrent writer: a real Postgres service on the same embeddings registry. */
    private fun concurrentWriter(embeddings: DeterministicEmbeddings) =
        testPostgresEltmService(FakeHand(embedScript = embeddings.script))

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

    @Test
    fun `a concurrent attribute write committed mid-refresh is never overwritten by a stale vector`() = runBlocking {
        val embeddings = DeterministicEmbeddings()
        val preText = entityEmbeddingText("kindle", "device", emptyMap())
        val postText = entityEmbeddingText("kindle", "device", mapOf("color" to "black"))
        embeddings.register(preText, testAxisVector(0))
        val postVector = testAxisVector(1)
        embeddings.register(postText, postVector)
        val gate = FirstEmbedGate()
        val refresh = gatedRefresh(embeddings, gate)
        val writer = concurrentWriter(embeddings)

        val kindleId = TestDb.seedEltmEntity("kindle", "device", embedding = stale)
        assertTrue(refresh.start())
        // the park proves the page read captured the pre-write text; the
        // writer's commit (with its own correct re-embed) lands inside the
        // read-to-write gap
        withTimeout(30_000) { gate.entered.await() }
        assertTrue(writer.setEntityAttribute(kindleId, "color", "black"))
        gate.allow.complete(Unit)
        awaitUntil { refresh.status() !is ReembedStatus.Running }
        assertIs<ReembedStatus.Finished>(refresh.status())

        // the guard detected the changed text and re-embedded the fresh
        // one: the stored vector matches the post-write content, never the
        // stale pre-write text the page read captured
        assertEquals(
            listOf(padVector(postVector, MAX_VECTOR_DIMENSIONS)),
            TestDb.allEltmEntityEmbeddings(),
        )
    }

    @Test
    fun `a concurrent refine committed mid-refresh is never overwritten by a stale vector`() = runBlocking {
        val embeddings = DeterministicEmbeddings()
        val preText = entityEmbeddingText("kindle", "device", emptyMap())
        val postText = entityEmbeddingText("paperwhite", "device", emptyMap())
        embeddings.register(preText, testAxisVector(0))
        val postVector = testAxisVector(1)
        embeddings.register(postText, postVector)
        val gate = FirstEmbedGate()
        val refresh = gatedRefresh(embeddings, gate)
        val writer = concurrentWriter(embeddings)

        val kindleId = TestDb.seedEltmEntity("kindle", "device", embedding = stale)
        assertTrue(refresh.start())
        withTimeout(30_000) { gate.entered.await() }
        writer.refineEntity(kindleId, "paperwhite", null)
        gate.allow.complete(Unit)
        awaitUntil { refresh.status() !is ReembedStatus.Running }
        assertIs<ReembedStatus.Finished>(refresh.status())

        // the survivor's stored vector is the renamed content's, never the
        // pre-rename capture's
        assertEquals(
            listOf(padVector(postVector, MAX_VECTOR_DIMENSIONS)),
            TestDb.allEltmEntityEmbeddings(),
        )
    }

    @Test
    fun `a concurrent merge committed mid-refresh never overwrites the survivor and skips the folded row`() = runBlocking {
        val embeddings = DeterministicEmbeddings()
        // the page read captures both pre-merge texts; the merge folds the
        // loser's attribute into the survivor and re-points its note
        val winnerPre = entityEmbeddingText("apple", "company", emptyMap())
        val loserPre = entityEmbeddingText("apple inc", "company", mapOf("founded" to "1976"))
        val folded = entityEmbeddingText("apple", "company", mapOf("founded" to "1976"))
        val noteText = noteEmbeddingText("the merger note")
        embeddings.register(winnerPre, testAxisVector(0))
        embeddings.register(loserPre, testAxisVector(1))
        val foldedVector = testAxisVector(2)
        embeddings.register(folded, foldedVector)
        val noteVector = testAxisVector(3)
        embeddings.register(noteText, noteVector)
        val gate = FirstEmbedGate()
        val refresh = gatedRefresh(embeddings, gate)
        val writer = concurrentWriter(embeddings)

        val winnerId = TestDb.seedEltmEntity("apple", "company", embedding = stale)
        val loserId = TestDb.seedEltmEntity("apple inc", "company", mapOf("founded" to "1976"), stale)
        TestDb.seedEltmNote(loserId, "the merger note", stale)

        assertTrue(refresh.start())
        withTimeout(30_000) { gate.entered.await() }
        writer.mergeEntities(winnerId, loserId)
        gate.allow.complete(Unit)
        awaitUntil { refresh.status() !is ReembedStatus.Running }
        // both page rows were processed — the folded loser was silently
        // skipped at write time, never an error
        val finished = assertIs<ReembedStatus.Finished>(refresh.status())
        assertEquals(2L, finished.entities)

        // the survivor keeps the folded content's vector (the pre-merge
        // capture never overwrites the merge's write) and the re-pointed
        // note embeds its own immutable text
        assertEquals(
            listOf(padVector(foldedVector, MAX_VECTOR_DIMENSIONS)),
            TestDb.allEltmEntityEmbeddings(),
        )
        assertEquals(
            listOf(padVector(noteVector, MAX_VECTOR_DIMENSIONS)),
            TestDb.allEltmNoteEmbeddings(),
        )
        assertEquals(listOf(winnerId), TestDb.allEltmNotes().map { it.entityId })
    }

    @Test
    fun `a write-back racing a locked attribute write re-derives the fresh text under the row lock`() = runBlocking {
        val embeddings = DeterministicEmbeddings()
        val preText = entityEmbeddingText("kindle", "device", emptyMap())
        val postText = entityEmbeddingText("kindle", "device", mapOf("color" to "black"))
        embeddings.register(preText, testAxisVector(0))
        val postVector = testAxisVector(1)
        embeddings.register(postText, postVector)

        // the writer parks INSIDE its locked embed: the row is held FOR
        // UPDATE from setEntityAttributes' start and nothing has committed
        // yet, so the refresh's page read still captures the pre-write text
        val enteredWriterEmbed = CompletableDeferred<Unit>()
        val allowWriterEmbed = CompletableDeferred<Unit>()
        val writerCalls = AtomicInteger(0)
        val writer = testPostgresEltmService(FakeHand(embedScript = { request ->
            if (writerCalls.incrementAndGet() == 1) {
                enteredWriterEmbed.complete(Unit)
                allowWriterEmbed.await()
            }
            embeddings.script(request)
        }))

        // the refresh's embeds pass straight through but are recorded —
        // the first embed completing proves the page read already captured
        // the pre-write text
        val refreshEmbeds = Collections.synchronizedList(mutableListOf<String>())
        val firstRefreshEmbedDone = CompletableDeferred<Unit>()
        val refreshCalls = AtomicInteger(0)
        val refresh = newService { request ->
            refreshEmbeds.addAll(request.input)
            if (refreshCalls.incrementAndGet() == 1) firstRefreshEmbedDone.complete(Unit)
            embeddings.script(request)
        }

        val kindleId = TestDb.seedEltmEntity("kindle", "device", embedding = stale)
        val writeJob = async { writer.setEntityAttribute(kindleId, "color", "black") }
        withTimeout(30_000) { enteredWriterEmbed.await() }
        assertTrue(refresh.start())
        withTimeout(30_000) { firstRefreshEmbedDone.await() }

        // the writer commits while the refresh is between its embed and its
        // guarded write-back: the write-back either waits on the row lock
        // and re-checks, or opens after the commit — either way the guard
        // re-derives from the latest committed content
        allowWriterEmbed.complete(Unit)
        assertTrue(writeJob.await(), "the concurrent attribute write must succeed")
        awaitUntil { refresh.status() !is ReembedStatus.Running }
        assertIs<ReembedStatus.Finished>(refresh.status())

        // the refresh embedded the captured pre-write text first, then
        // re-embedded the post-write text inside the guarded write-back
        assertEquals(listOf(preText, postText), refreshEmbeds)
        assertEquals(
            listOf(padVector(postVector, MAX_VECTOR_DIMENSIONS)),
            TestDb.allEltmEntityEmbeddings(),
        )
    }
}
