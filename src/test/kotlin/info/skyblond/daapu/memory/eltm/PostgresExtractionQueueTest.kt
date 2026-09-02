package info.skyblond.daapu.memory.eltm

import info.skyblond.daapu.agent.chat.ChatMessage
import info.skyblond.daapu.agent.chat.ChatMessagePart
import info.skyblond.daapu.agent.chat.ChatMessageRole
import info.skyblond.daapu.hand.assistantMessage
import info.skyblond.daapu.testutil.DbTestBase
import info.skyblond.daapu.testutil.TestDb
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import kotlin.test.*

/**
 * Pins the extraction queue's visibility-timeout semantics against the real
 * testcontainers PostgreSQL ([DbTestBase]): FIFO claims, claim exclusivity
 * (`FOR UPDATE SKIP LOCKED`), the invisibility window a claim opens, the
 * complete/reschedule outcomes (see `ExtractionQueue.kt`'s KDoc — the single
 * `visible_after` column carries lease, retry schedule and crash recovery),
 * and the snapshot round trip in [ChatMessage]s (corruption rejected at
 * claim time, never blocking the queue).
 */
class PostgresExtractionQueueTest : DbTestBase() {

    // the knobs themselves don't matter here beyond being >= 1: the tests
    // drive the window by SQL-rewriting visible_after (TestDb)
    private val queue = PostgresExtractionQueue(jobTimeoutMinutes = 30, retryDelayMinutes = 5)

    private fun history(): List<ChatMessage> = listOf(
        ChatMessage(
            ChatMessageRole.User,
            listOf(ChatMessagePart.Text("u1")),
            createdAt = Instant.parse("2026-08-17T09:00:00Z"),
        ),
        assistantMessage("a1"),
    )

    @Test
    fun `an enqueued job is immediately claimable and round-trips its snapshot`() = runBlocking {
        val id = queue.enqueue(history())

        val job = queue.claim()
        assertNotNull(job, "a fresh job (visible_after = now) is claimable right away")
        assertEquals(id, job.id)
        assertEquals(history(), job.messages)
    }

    @Test
    fun `a mid-turn fragment snapshot round-trips`() = runBlocking {
        // a compaction drop region is NOT necessarily a complete chat: a
        // fresh chat's full-body reactive compaction drops the whole chat
        // ending with the run's user message (see
        // ChatCompactionService.splitMessage). The claim must validate the
        // snapshot with ChatCodec.validateSnapshot — the stored-chat
        // completeness rule (trailing assistant stop) would reject this
        // fragment forever, poisoning the queue head.
        val fragment = listOf(
            ChatMessage(
                ChatMessageRole.User,
                listOf(ChatMessagePart.Text("u1")),
                createdAt = Instant.parse("2026-08-17T09:00:00Z"),
            ),
        )
        val id = queue.enqueue(fragment)

        val job = queue.claim()
        assertNotNull(job, "a valid fragment snapshot is claimable")
        assertEquals(id, job.id)
        assertEquals(fragment, job.messages)
    }

    @Test
    fun `claim on an empty queue returns null`() = runBlocking {
        assertNull(queue.claim())
    }

    @Test
    fun `claims are FIFO by id`() = runBlocking {
        val first = queue.enqueue(history())
        val second = queue.enqueue(history())

        assertEquals(first, queue.claim()?.id, "the oldest visible job is claimed first")
        assertEquals(second, queue.claim()?.id)
        assertNull(queue.claim(), "both claimed jobs are now invisible")
    }

    @Test
    fun `a rescheduled job keeps its FIFO slot by id`() = runBlocking {
        val first = queue.enqueue(history())
        val second = queue.enqueue(history())

        // the oldest job fails once and is rescheduled; once both jobs are
        // visible again the id ordering still claims the OLDER job first —
        // a retry does not lose its place to newer work
        assertEquals(first, queue.claim()?.id)
        queue.reschedule(first)
        TestDb.rewindExtractionJob(first)
        assertEquals(first, queue.claim()?.id, "the retried job stays ahead of the newer one")
        assertEquals(second, queue.claim()?.id)
        assertNull(queue.claim())
    }

    @Test
    fun `a claimed job is invisible for the job timeout and re-emerges after`() = runBlocking {
        val id = queue.enqueue(history())
        assertNotNull(queue.claim())

        val claimed = TestDb.allExtractionJobs().single { it.id == id }
        val minutesAhead = Duration.between(Instant.now(), claimed.visibleAfter.toInstant()).toMinutes()
        assertTrue(
            minutesAhead in 28..31,
            "the claim must push visible_after about the job timeout into the future, got +$minutesAhead min",
        )
        assertNull(queue.claim(), "the claimed job is invisible inside its window")

        // the window lapsing (the crash/lease path) makes the job claimable
        // again — simulated by rewriting visible_after instead of waiting
        TestDb.rewindExtractionJob(id)
        assertEquals(id, queue.claim()?.id)
    }

    @Test
    fun `two concurrent claims never take the same job`() = runBlocking {
        queue.enqueue(history())
        queue.enqueue(history())

        val (a, b) = coroutineScope {
            val first = async { queue.claim() }
            val second = async { queue.claim() }
            first.await() to second.await()
        }
        assertNotNull(a)
        assertNotNull(b)
        assertNotEquals(a.id, b.id, "SKIP LOCKED must keep concurrent claimers on distinct rows")
    }

    @Test
    fun `complete deletes the job`() = runBlocking {
        val id = queue.enqueue(history())
        assertNotNull(queue.claim())

        queue.complete(id)
        assertTrue(TestDb.allExtractionJobs().isEmpty(), "a successful job leaves no row behind")
        assertNull(queue.claim())
    }

    @Test
    fun `reschedule keeps the row for a retry at the shorter delay`() = runBlocking {
        val id = queue.enqueue(history())
        assertNotNull(queue.claim())

        queue.reschedule(id)

        val rescheduled = TestDb.allExtractionJobs().single { it.id == id }
        val minutesAhead = Duration.between(Instant.now(), rescheduled.visibleAfter.toInstant()).toMinutes()
        assertTrue(
            minutesAhead in 3..6,
            "a failed job re-emerges at the retry delay, got +$minutesAhead min",
        )
        assertNull(queue.claim(), "the rescheduled job is still invisible until its delay lapses")

        TestDb.rewindExtractionJob(id)
        assertEquals(id, queue.claim()?.id, "the retried job is claimable once visible again")
    }

    @Test
    fun `a corrupt snapshot is rejected at claim time and never blocks the queue`() = runBlocking {
        val corruptId = TestDb.seedExtractionJob("this is not a chat snapshot")
        val goodId = queue.enqueue(history())

        assertNull(queue.claim(), "a corrupt snapshot is not handed to the worker")
        assertEquals(goodId, queue.claim()?.id, "the rejected job no longer blocks the queue head")

        // the known-failure treatment: the retry delay, not the claim lease
        val rescheduled = TestDb.allExtractionJobs().single { it.id == corruptId }
        val minutesAhead = Duration.between(Instant.now(), rescheduled.visibleAfter.toInstant()).toMinutes()
        assertTrue(
            minutesAhead in 3..6,
            "a corrupt job re-emerges at the retry delay, got +$minutesAhead min",
        )

        // and it keeps being rejected on every retry (retries are unlimited)
        TestDb.rewindExtractionJob(corruptId)
        assertNull(queue.claim(), "the corrupt job is rejected again once it re-emerges")
    }
}
