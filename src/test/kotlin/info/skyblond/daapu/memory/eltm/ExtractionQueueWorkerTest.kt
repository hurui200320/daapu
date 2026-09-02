package info.skyblond.daapu.memory.eltm

import info.skyblond.daapu.agent.chat.ChatMessage
import info.skyblond.daapu.agent.chat.ChatMessagePart
import info.skyblond.daapu.agent.chat.ChatMessageRole
import info.skyblond.daapu.agent.pipeline.eltm.MemoryExtractionService
import info.skyblond.daapu.hand.FakeHand
import info.skyblond.daapu.hand.HandEvent
import info.skyblond.daapu.hand.HandRunPolicy
import info.skyblond.daapu.hand.HandRunRequest
import info.skyblond.daapu.hand.assistantMessage
import info.skyblond.daapu.hand.errorRunFlow
import info.skyblond.daapu.hand.textRunFlow
import info.skyblond.daapu.memory.eltm.EltmService
import info.skyblond.daapu.testutil.DbTestBase
import info.skyblond.daapu.testutil.TestDb
import info.skyblond.daapu.testutil.testEltmWriterService
import info.skyblond.daapu.testutil.testHandService
import info.skyblond.daapu.testutil.testLlm
import info.skyblond.daapu.testutil.testPostgresEltmService
import info.skyblond.daapu.testutil.writerRunFlow
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the extraction queue worker's loop (`ExtractionQueueWorker.kt`): an
 * enqueued history snapshot is extracted and written into the ELTM off the
 * request path, a failed job survives and retries (its row re-armed through
 * the visibility window — simulated by the TestDb rewind, no real-minute
 * waits), and a stop abandons work safely (the lease re-arms it).
 */
class ExtractionQueueWorkerTest : DbTestBase() {

    private val queue = PostgresExtractionQueue(jobTimeoutMinutes = 30, retryDelayMinutes = 5)
    private var worker: ExtractionQueueWorker? = null

    @AfterTest
    fun stopWorker() {
        worker?.stop()
    }

    private fun user(text: String) =
        ChatMessage(
            ChatMessageRole.User,
            listOf(ChatMessagePart.Text(text)),
            createdAt = Instant.parse("2026-08-17T09:00:00Z"),
        )

    /**
     * A fake hand dispatching on the one-shot system prompts: the extractor
     * answers [extraction], the writer runs [writerRunFlow] against [eltm].
     * The worker never calls the chat loop, so any other request fails the
     * test.
     */
    private fun oneShotHand(
        eltm: EltmService,
        extraction: suspend (HandRunRequest) -> List<HandEvent> = { textRunFlow("likes coffee") },
        writer: suspend (HandRunRequest) -> List<HandEvent> = { writerRunFlow(eltm) },
    ) = FakeHand(
        runScript = { request ->
            when {
                request.systemPrompt?.startsWith("You're extracting") == true -> extraction(request)
                request.systemPrompt?.startsWith("You're maintaining an external long-term memory") == true -> writer(request)
                else -> error("unexpected run in the extraction pipeline: ${request.systemPrompt}")
            }
        },
    )

    /**
     * Build and start a one-worker drain loop over the shared queue. The
     * poll interval is shrunk so the tests never wait real seconds.
     */
    private fun startWorker(hand: FakeHand, eltm: EltmService) {
        val extractionService = MemoryExtractionService(
            extractModel = testLlm("bifrost/cerebras/gemma-4-31b"),
            hand = testHandService(hand),
            policy = HandRunPolicy(0, 0),
            eltmWriterService = testEltmWriterService(hand, eltm),
        )
        worker = ExtractionQueueWorker(
            queue = queue,
            memoryExtractionService = extractionService,
            workers = 1,
            pollIntervalMs = 25,
        ).also { it.start() }
    }

    /** Poll [condition] until it holds or the (generous) deadline lapses. */
    private suspend fun awaitUntil(timeoutMs: Long = 10_000, condition: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "condition not met within ${timeoutMs}ms" }
            delay(25)
        }
    }

    /** Minutes from now until the single job's visibility window. */
    private suspend fun singleJobMinutesAhead(): Long {
        val row = TestDb.allExtractionJobs().single()
        return Duration.between(Instant.now(), row.visibleAfter.toInstant()).toMinutes()
    }

    @Test
    fun `the worker drains an enqueued job into the ELTM`() = runBlocking {
        val eltm = testPostgresEltmService(FakeHand())
        val hand = oneShotHand(eltm)
        val jobId = queue.enqueue(listOf(user("u1"), assistantMessage("a1")))
        startWorker(hand, eltm)

        awaitUntil { TestDb.allExtractionJobs().isEmpty() }
        val notes = TestDb.allEltmNotes().map { it.note }
        assertTrue(notes.contains("likes coffee"), "the extracted fact lands in the ELTM")
        assertEquals(2, hand.requests.size, "extractor run + writer run")
        // the completed job left no row behind
        assertTrue(TestDb.allExtractionJobs().none { it.id == jobId })
    }

    @Test
    fun `a failed job survives and retries until it succeeds`() = runBlocking {
        val eltm = testPostgresEltmService(FakeHand())
        val extractionCalls = AtomicInteger(0)
        val hand = oneShotHand(
            eltm = eltm,
            extraction = {
                // the first attempt's extractor round is truncated: the
                // worker must log, reschedule and walk away — the job comes
                // back through the visibility window and succeeds
                if (extractionCalls.incrementAndGet() == 1) {
                    errorRunFlow("output_budget_exhausted", "output hit the token budget")
                } else {
                    textRunFlow("likes coffee")
                }
            },
        )
        val jobId = queue.enqueue(listOf(user("u1"), assistantMessage("a1")))
        startWorker(hand, eltm)

        // the failure path ran: the extractor was called, nothing was
        // written, and the row was RESCHEDULED — its visible_after sits at
        // the ~5min retry delay (the claim lease was ~30min), which proves
        // the worker's reschedule landed before the rewind below
        awaitUntil { extractionCalls.get() >= 1 }
        awaitUntil { singleJobMinutesAhead() in 4..6 }
        assertTrue(TestDb.allEltmNotes().isEmpty(), "a failed attempt must not write anything")

        // the visibility window lapsing re-arms the job; the retry succeeds
        TestDb.rewindExtractionJob(jobId)
        awaitUntil { TestDb.allExtractionJobs().isEmpty() }
        val notes = TestDb.allEltmNotes().map { it.note }
        assertTrue(notes.contains("likes coffee"), "the retried extraction lands in the ELTM")
        // three runs: the failed extractor attempt (a failed extraction never
        // reaches the writer) + the retry's extractor and writer runs
        assertEquals(3, hand.requests.size)
    }

    @Test
    fun `stop abandons an in-flight job and the lease re-arms it`() = runBlocking {
        val eltm = testPostgresEltmService(FakeHand())
        val extractionStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val hand = oneShotHand(
            eltm = eltm,
            extraction = {
                extractionStarted.complete(Unit)
                // the abandoned attempt: the worker is stopped mid-run
                awaitCancellation()
            },
        )
        val jobId = queue.enqueue(listOf(user("u1"), assistantMessage("a1")))
        startWorker(hand, eltm)
        awaitUntil { extractionStarted.isCompleted }

        worker?.stop()
        // the abandoned attempt never reaches complete(): the row survives
        // and nothing was written before the stop
        assertEquals(listOf(jobId), TestDb.allExtractionJobs().map { it.id })
        assertTrue(TestDb.allEltmNotes().isEmpty(), "nothing written before the stop")

        // the claim's lease re-arms the job: with the visibility window
        // lapsed (rewind), the NEXT boot's worker claims the same job and
        // finishes the extraction
        TestDb.rewindExtractionJob(jobId)
        startWorker(oneShotHand(eltm), eltm)
        awaitUntil { TestDb.allExtractionJobs().isEmpty() }
        val notes = TestDb.allEltmNotes().map { it.note }
        assertTrue(notes.contains("likes coffee"), "the re-armed job's extraction lands in the ELTM")
    }
}
