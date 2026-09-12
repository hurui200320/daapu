package info.skyblond.daapu.memory.eltm

import info.skyblond.daapu.agent.chat.ChatMessage
import info.skyblond.daapu.agent.chat.ChatMessageRole
import info.skyblond.daapu.agent.chat.roundCount
import info.skyblond.daapu.agent.model.LLM
import info.skyblond.daapu.agent.pipeline.compaction.ChatCompactionResult
import info.skyblond.daapu.agent.pipeline.compaction.ChatCompactionService
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

/**
 * The in-server ELTM replay job's phase, snapshotted by
 * [EltmReplayService.status]. In-memory only: a restart starts at [Idle]
 * whatever a previous boot's job was doing (jobs already enqueued by a run
 * stay queued and drain through the worker — see [EltmReplayService]).
 */
sealed interface ReplayStatus {
    /** No replay has been started this boot. */
    data object Idle : ReplayStatus

    /**
     * A replay is running in the background: [windowsCompacted] full
     * windows have been summarized so far, [jobsQueued] extraction jobs
     * enqueued (including each window's dropped region; the residue batch
     * lands at the end).
     */
    data class Running(val windowsCompacted: Int, val jobsQueued: Int) : ReplayStatus

    /**
     * The last replay finished: every region was dropped or carried into
     * the final batch, [jobsQueued] extraction jobs enqueued in total over
     * [windowsCompacted] compactions. The extraction itself runs in the
     * background worker — this phase means the WALK is done, not that the
     * memories are recorded.
     */
    data class Finished(
        val messagesTotal: Int,
        val windowsCompacted: Int,
        val jobsQueued: Int,
    ) : ReplayStatus

    /**
     * The last replay failed at [error]'s stage (a compaction call or an
     * enqueue): [windowsCompacted]/[jobsQueued] are the at-failure values
     * (how much had already gone into the queue when it died). The regions
     * already enqueued stay queued and drain normally, and the walk is
     * safely re-runnable (see the class KDoc).
     */
    data class Failed(
        val error: String,
        val windowsCompacted: Int,
        val jobsQueued: Int,
    ) : ReplayStatus
}

/**
 * Replay a foreign chat through this system's memory pipeline:
 * `POST /api/eltm/replay` (the web UI's ELTM replay tab) uploads a chat in
 * the neutral [ChatMessage] format (what `GET /api/chats/{id}/chat` serves,
 * e.g. the SillyTavern transformer's `.messages.json` output), and this
 * service walks it window by window with the PRODUCTION compaction stage
 * ([ChatCompactionService.compactChat] with `excludeLastNRound =
 * contextRounds`), enqueueing every dropped region (the running summary
 * included — exactly the region the production compaction path queues, see
 * `PersistChatService.compactAndEnqueue`) into the background extraction
 * queue ([ExtractionQueue]): the worker's existing extractor + ELTM writer
 * pipeline turns each region into memories off the request path, with the
 * queue's retries, its maintenance-mode pause and its parallel drain. The
 * residue — the last summary plus the trailing rounds, or the whole chat
 * when it never exceeded one window (no compaction at all) — is enqueued as
 * the final job. Why not import-and-delete: an external chat may be far
 * longer than what this system's models can process at once (e.g. a
 * 1M-context LLM's export against our 256K windows), and one giant
 * extraction over the whole chat is exactly what the memory extractor
 * cannot do reliably; the replay instead feeds the extractor window-sized
 * batches. Why not direct writes: enqueueing reuses the queue's whole
 * retry/drain machinery, and "finished" means every region is queued — the
 * memories land asynchronously, exactly like a deleted chat's.
 *
 * The walk itself (the loop's semantics): [replayChat]. Every raw round is
 * extracted exactly once — each window's rounds are either dropped
 * (enqueued, never seen again) or preserved (carried into the next window
 * verbatim), and the tail beyond the window is untouched.
 *
 * Lifecycle: single-flight — [start] refuses while a walk is active (the
 * route answers 409). [start] validates synchronously BEFORE launching (the
 * knob bounds, a non-empty chat, and the up-front capability check of both
 * pipeline models over the WHOLE chat — every message lands in some region,
 * so a whole-chat check covers every window; a mismatch is a configuration
 * error that fails fast instead of mid-walk). The walk runs on this
 * service's own scope (SupervisorJob + Dispatchers.IO, the
 * [EmbeddingRefreshService] pattern); [close] (the Koin onClose callback)
 * cancels it — a walk in flight at shutdown is abandoned on purpose, no
 * join that could stall the shutdown; its already-enqueued jobs drain at
 * the next boot.
 *
 * Failure semantics: a compaction failure (per [ChatCompactionService]:
 * an [IllegalStateException] for a failed/truncated summarization) or an
 * enqueue failure fails the walk — the regions already enqueued stay
 * queued and drain normally. A re-run of the same chat re-enqueues EVERY
 * region (the walk has no memory of the failed run), which is safe but
 * costs a full re-extraction; the ELTM writer deduplicates recorded
 * content, so no duplicate diary entries. Maintenance mode flipped ON
 * mid-walk: the walk keeps enqueueing while the worker pauses — the jobs
 * pile up and drain once the mode is turned off (the same accepted limit
 * as an extraction already in flight finishing normally when the flag
 * flips). Only `Exception`s mark the run [ReplayStatus.Failed]: an `Error`
 * escaping the walk is logged loudly by the scope's handler and leaves the
 * status at [ReplayStatus.Running] until the restart.
 */
class EltmReplayService(
    // the boot-resolved pipeline models (memory.compactModel /
    // memory.eltm.extractionModel) for the up-front capability check only
    // — the walk itself re-checks per compaction call
    private val compactModel: LLM,
    private val extractModel: LLM,
    private val compactionService: ChatCompactionService,
    private val queue: ExtractionQueue,
) : AutoCloseable {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineName("eltm-replay") +
                CoroutineExceptionHandler { _, error ->
                    logger.error(error) {
                        "ELTM replay died — the status stays 'running' until the restart; " +
                                "already-enqueued regions stay queued and drain normally"
                    }
                }
    )

    // TODO: status only living in current JVM instance. Fine for PoC,
    //       but will need to share the task status via db eventually.
    @Volatile
    private var current: ReplayStatus = ReplayStatus.Idle

    // the walk's progress counters, written only by the walk's own
    // coroutine and read racily by status() — a stale snapshot is benign
    private var windowsCompacted = 0
    private var jobsQueued = 0

    /**
     * The walk's current phase (a snapshot; terminal states carry the
     * summary). A [ReplayStatus.Running] answer is composed from the
     * live progress counters.
     */
    fun status(): ReplayStatus = when (val phase = current) {
        is ReplayStatus.Running -> ReplayStatus.Running(windowsCompacted, jobsQueued)
        else -> phase
    }

    /**
     * Launch a replay of [chat] in the background. Single-flight: returns
     * false (and launches nothing) while a walk is active. Validations run
     * synchronously BEFORE any launch and throw — the knob bounds
     * ([validateReplayKnobs]), the non-empty chat, the user-message
     * presence (the walk's whole arithmetic is round-based: a
     * decode-valid but userless chat — e.g. a single assistant message —
     * has nothing to walk and is refused here instead of enqueued whole),
     * and the capability check of both pipeline models over the whole
     * chat (the route maps them to 400s; a [ReplayStatus.Failed] is
     * reserved for a mid-walk failure). The maintenance-mode gate is the
     * route's job.
     */
    @Synchronized
    fun start(chat: List<ChatMessage>, compactionRounds: Int, contextRounds: Int): Boolean {
        if (current is ReplayStatus.Running) return false
        validateReplayKnobs(compactionRounds, contextRounds)
        require(chat.isNotEmpty()) { "cannot replay an empty chat" }
        require(chat.roundCount() >= 1) {
            "cannot replay a chat without user messages: the walk's window arithmetic is round-based"
        }
        // fail fast on a content/capability mismatch BEFORE any LLM spend:
        // the compaction service re-checks per call, but this catches the
        // mismatch up front instead of mid-walk after N windows
        compactModel.checkPromptContentCapabilities(chat)
        extractModel.checkPromptContentCapabilities(chat)

        windowsCompacted = 0
        jobsQueued = 0
        current = ReplayStatus.Running(0, 0)
        logger.info {
            "ELTM replay started: ${chat.size} message(s) (${chat.roundCount()} rounds), " +
                    "compactionRounds=$compactionRounds, contextRounds=$contextRounds"
        }
        scope.launch {
            try {
                replayChat(
                    chat = chat,
                    compactionRounds = compactionRounds,
                    contextRounds = contextRounds,
                    compact = { feed ->
                        compactionService.compactChat(feed, contextRounds)
                            .also { windowsCompacted++ }
                    },
                    onDropped = { region ->
                        queue.enqueue(region)
                        jobsQueued++
                    },
                )
                current = ReplayStatus.Finished(chat.size, windowsCompacted, jobsQueued)
                logger.info {
                    "ELTM replay finished: $windowsCompacted window(s) compacted, " +
                            "$jobsQueued extraction job(s) enqueued (draining in the background)"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // capture the at-failure counters before the Failed write:
                // a concurrent start() (which resets them) can only run
                // once Failed is visible, and the walk's own coroutine is
                // their only writer — locals pin the values for both the
                // status and the log line
                val windows = windowsCompacted
                val jobs = jobsQueued
                current = ReplayStatus.Failed(e.message ?: e.javaClass.simpleName, windows, jobs)
                logger.error(e) {
                    "ELTM replay failed after $windows window(s) compacted and $jobs region(s) enqueued: ${e.message}"
                }
            }
        }
        return true
    }

    /** Cancel the scope; a running walk is abandoned (enqueued jobs stay queued). */
    override fun close() {
        scope.cancel()
    }
}

/**
 * The replay walk behind [EltmReplayService.start] — the single source of
 * the replay semantics (the route's and the status' KDoc point here):
 *
 * 1. While the remaining chat has more rounds than
 *    `max(compactionRounds, contextRounds + 1)`, one iteration feeds the
 *    leading `compactionRounds + contextRounds` user rounds to [compact]
 *    (the production `ChatCompactionService.compactChat` with
 *    `excludeLastNRound = contextRounds`), hands the DROPPED region (the
 *    running summary included — exactly the region the production
 *    compaction path queues for extraction) to [onDropped], and continues
 *    with `[summary + kept context rounds] + the untouched tail`.
 * 2. The residue — the last summary plus the trailing rounds, or the whole
 *    chat when it never exceeded one window (no compaction at all) — is
 *    handed to [onDropped] as the final region.
 *
 * Every raw round is extracted exactly once: each window's rounds are
 * either dropped (enqueued, never seen again) or preserved (carried into
 * the next window verbatim), and the tail beyond the window is untouched.
 * Each full-window iteration drops `compactionRounds` rounds and re-adds
 * the summary as one, so the round count strictly decreases
 * (`compactionRounds >= 2`); `contextRounds + 1` — the smallest chat any
 * compaction can leave behind (the summary plus its kept context rounds)
 * — closes the loop bound, so the loop cannot stall however lopsided the
 * knobs are (a remaining chat too short for a full window compacts down
 * to exactly that bound and exits).
 *
 * A dropped region is empty only when [compact] returns one (the
 * production split never does for a full window); empty regions are not
 * handed to [onDropped], and an empty residue (an empty chat) is a no-op.
 * A chat without user messages has zero rounds: the loop never runs and
 * the whole chat enqueues as the residue — [EltmReplayService.start]
 * refuses that input instead (see its KDoc), so only a direct caller
 * reaches this.
 * Failures of [compact]/[onDropped] propagate and abort the walk (the
 * regions already handed to [onDropped] stay with its receiver).
 *
 * The compaction stage is a lambda seam so tests can pin the loop's
 * arithmetic without any model/hand.
 */
internal suspend fun replayChat(
    chat: List<ChatMessage>,
    compactionRounds: Int,
    contextRounds: Int,
    compact: suspend (feed: List<ChatMessage>) -> ChatCompactionResult,
    onDropped: suspend (region: List<ChatMessage>) -> Unit,
) {
    validateReplayKnobs(compactionRounds, contextRounds)
    var remaining = chat
    // loop while the remaining chat holds more than one window's worth of
    // rounds: compactionRounds bounds what one window drops, and
    // contextRounds + 1 is the smallest chat any compaction can leave
    // behind — a remaining chat at or below the max of the two is the
    // final region instead
    while (remaining.roundCount() > maxOf(compactionRounds, contextRounds + 1)) {
        // one window: the rounds this compaction will drop plus the
        // context rounds it preserves
        val feed = remaining.takeFirstNRound(compactionRounds + contextRounds)
        val result = compact(feed)
        if (result.droppedMessages.isNotEmpty()) onDropped(result.droppedMessages)
        remaining = result.newChat + remaining.drop(feed.size)
    }
    if (remaining.isNotEmpty()) onDropped(remaining)
}

/**
 * The replay's knob contract: the single validation shared by
 * [EltmReplayService.start] (fail fast, synchronously — the route maps it
 * to a 400) and [replayChat] (guarding its own direct callers, tests
 * included). The bounds' reasoning lives in the require messages and in
 * [replayChat]'s loop-bound notes.
 */
internal fun validateReplayKnobs(compactionRounds: Int, contextRounds: Int) {
    require(contextRounds >= 1) {
        "contextRounds must be >= 1: the compactor always keeps at least one reference round"
    }
    require(compactionRounds >= 2) {
        "compactionRounds must be >= 2: every compaction drops compactionRounds rounds " +
                "but re-adds the summary as one round, so 1 would not shrink the chat"
    }
}

/**
 * The leading [n] user rounds — the mirror of the production
 * `takeLastNRound` (`agent/chat/ChatExtensions.kt`, the same
 * user-message-boundary cut, from the other end): everything up to the
 * (n+1)-th user message, so tool_call/tool_result pairs stay whole. The
 * whole list when it holds [n] user rounds or fewer; empty when [n] <= 0.
 * Local to the replay because only it walks a chat forward.
 */
private fun List<ChatMessage>.takeFirstNRound(n: Int): List<ChatMessage> {
    if (n <= 0) return emptyList()
    val userIndexes = mapIndexedNotNull { index, message ->
        if (message.role == ChatMessageRole.User) index else null
    }
    if (userIndexes.size <= n) return this
    return subList(0, userIndexes[n])
}
