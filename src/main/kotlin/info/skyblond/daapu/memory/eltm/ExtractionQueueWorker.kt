package info.skyblond.daapu.memory.eltm

import info.skyblond.daapu.agent.pipeline.eltm.MemoryExtractionService
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

/**
 * The background worker draining the extraction queue
 * ([ExtractionQueue]): N identical poll loops ([workers], config
 * `memory.eltm.queueWorkers`), each claiming one job at a time and running
 * the full two-stage memory-extraction pipeline ([MemoryExtractionService]
 * — the extractor one-shot plus the ELTM writer tool loop) over the claimed
 * history snapshot. Built for slow LLM endpoints: neither a chat deletion
 * nor a compaction waits for the memory work — both only enqueue, and this
 * worker does the minutes-long extraction off the request path.
 *
 * The loop needs NO per-chat lock and touches no chats row: the job carries
 * a frozen history snapshot and the chats row is already gone, so nothing
 * can race the extraction; concurrent jobs' ELTM writes are safe because
 * the writer handles unique-violation races and deduplicates re-runs.
 *
 * Failure semantics — the queue's visibility timeout IS the retry mechanism
 * (see [ExtractionQueue]'s KDoc): a known failure is logged and the job
 * rescheduled to the shorter retry delay; a crash or shutdown simply leaves
 * the job to re-emerge at its claim's lease boundary. Retries are unlimited
 * — a permanently failing job (e.g. a config error after a model switch)
 * retries forever and shows up as a recurring error log line, nothing is
 * silently dropped. Only `Exception`s are recovered in-loop: an `Error`
 * escaping the loop kills that worker permanently — the scope's
 * `CoroutineExceptionHandler` logs it loudly and the remaining workers keep
 * draining (`SupervisorJob`); the dead worker's in-flight job re-emerges
 * via its lease.
 *
 * Lifecycle: [start] launches the loops (called once at startup by
 * `server/WebServer.kt`, after the graph's eager resolution); [stop]
 * cancels the scope (the Koin `onClose` callback fires it from the JVM
 * shutdown hook). A job being processed when [stop] lands is abandoned
 * mid-extraction on purpose — no join that could stall the shutdown for
 * minutes; its lease re-arms it for the next boot. Started loops run on
 * `Dispatchers.IO`: the pipeline's DB access hops dispatchers itself, the
 * hand calls are suspend, and the poll delay must not occupy an event-loop
 * thread.
 */
class ExtractionQueueWorker(
    private val queue: ExtractionQueue,
    private val memoryExtractionService: MemoryExtractionService,
    private val workers: Int,
    /** Tests shrink this; production polls every [DEFAULT_POLL_INTERVAL_MS]. */
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
) {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineName("extraction-queue-worker") +
                CoroutineExceptionHandler { context, error ->
                    logger.error(error) {
                        "Extraction queue worker ${context[CoroutineName]?.name} died — " +
                                "the loop is NOT restarted; the remaining workers keep draining"
                    }
                }
    )
    private var started = false

    /** Launch the [workers] poll loops. Refuses a second start (a bug). */
    @Synchronized
    fun start() {
        check(!started) { "ExtractionQueueWorker is already started" }
        started = true
        repeat(workers) { index ->
            scope.launch(CoroutineName("extraction-queue-worker-$index")) {
                logger.info { "Extraction queue worker $index started" }
                pollLoop()
            }
        }
    }

    /** Cancel the scope; running jobs are abandoned (their lease re-arms them). */
    fun stop() {
        scope.cancel()
    }

    private suspend fun pollLoop() {
        while (currentCoroutineContext().isActive) {
            val job = try {
                queue.claim()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // a failed claim (DB hiccup) is not a job failure: nothing
                // was claimed, just back off and poll again
                logger.warn(e) { "Extraction queue claim failed, polling again after the interval" }
                delay(pollIntervalMs)
                continue
            }
            if (job == null) {
                delay(pollIntervalMs)
                continue
            }
            process(job)
        }
    }

    /**
     * Run the extraction pipeline over the claimed job and delete it on
     * success. A pipeline failure (throws per its contract) is logged and
     * the job rescheduled to the retry delay — deliberately best-effort: if
     * the reschedule itself fails, the claim's lease is the backstop and
     * the job still comes back. A corrupt snapshot never reaches this point:
     * the queue's claim already rejects and reschedules it
     * (`ExtractionQueue.kt`).
     */
    private suspend fun process(job: ClaimedJob) {
        try {
            logger.info { "Extraction job ${job.id} claimed (${job.messages.size} message(s))" }
            memoryExtractionService.processDiscardedMessages(job.messages)
            queue.complete(job.id)
            logger.info { "Extraction job ${job.id} done" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) { "Extraction job ${job.id} failed: ${e.message}" }
            try {
                queue.reschedule(job.id)
                logger.info { "Extraction job ${job.id} rescheduled for its retry" }
            } catch (rescheduleError: CancellationException) {
                throw rescheduleError
            } catch (rescheduleError: Exception) {
                logger.warn(rescheduleError) {
                    "Extraction job ${job.id} reschedule failed — the claim lease will re-arm it"
                }
            }
        }
    }

    companion object {
        /** Production poll interval; the only wait when the queue is empty. */
        const val DEFAULT_POLL_INTERVAL_MS = 10_000L
    }
}
