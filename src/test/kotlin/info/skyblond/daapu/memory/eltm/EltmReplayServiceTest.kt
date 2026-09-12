package info.skyblond.daapu.memory.eltm

import info.skyblond.daapu.agent.chat.AttachmentContent
import info.skyblond.daapu.agent.chat.AttachmentKind
import info.skyblond.daapu.agent.chat.ChatMessage
import info.skyblond.daapu.agent.chat.ChatMessageMeta
import info.skyblond.daapu.agent.chat.ChatMessagePart
import info.skyblond.daapu.agent.chat.ChatMessageRole
import info.skyblond.daapu.agent.chat.roundCount
import info.skyblond.daapu.agent.chat.takeLastNRound
import info.skyblond.daapu.agent.model.LLM
import info.skyblond.daapu.agent.model.ModelCapabilityException
import info.skyblond.daapu.agent.pipeline.compaction.ChatCompactionResult
import info.skyblond.daapu.agent.pipeline.compaction.ChatCompactionService
import info.skyblond.daapu.hand.FakeHand
import info.skyblond.daapu.hand.HandEvent
import info.skyblond.daapu.hand.HandRunPolicy
import info.skyblond.daapu.hand.HandRunRequest
import info.skyblond.daapu.hand.HandUpstreamException
import info.skyblond.daapu.hand.textRunFlow
import info.skyblond.daapu.testutil.testHandService
import info.skyblond.daapu.testutil.testLlm
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Pins [replayChat]'s window arithmetic (the semantics the route's and the
 * status' KDoc point at): the region sequence, the exactly-once round
 * coverage, the running summary riding every region after the first, the
 * no-compaction short-chat path, the stall guard for lopsided knobs, and
 * the leading-assistant-prologue input shape (the transformer's output) —
 * plus [EltmReplayService]'s own behavior over a fake queue: the happy
 * walk's counters, the single-flight lock, the mid-walk failure's
 * already-enqueued regions, and the synchronous fail-fast validations.
 */
class EltmReplayServiceTest {

    /** One user/assistant round, the neutral format's stored shape. */
    private fun round(n: Int): List<ChatMessage> = listOf(
        ChatMessage(
            role = ChatMessageRole.User,
            parts = listOf(ChatMessagePart.Text("u$n")),
            createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        ),
        ChatMessage(
            role = ChatMessageRole.Assistant,
            parts = listOf(ChatMessagePart.Text("a$n")),
            meta = ChatMessageMeta(0, 0, 0, null),
            finishReason = "stop",
        ),
    )

    /**
     * An assistant-only message, the transformer output's leading
     * prologue — also the building block of a userless (round-less) chat.
     */
    private fun prologue(): ChatMessage = ChatMessage(
        role = ChatMessageRole.Assistant,
        parts = listOf(ChatMessagePart.Text("prologue")),
        meta = ChatMessageMeta(0, 0, 0, null),
        finishReason = "stop",
    )

    /** A compaction summary message, the shape compactChat produces. */
    private fun summary(text: String): ChatMessage = ChatMessage(
        role = ChatMessageRole.User,
        parts = listOf(
            ChatMessagePart.Text(ChatCompactionService.COMPACTION_HEADER + text)
        ),
        createdAt = Instant.now(),
    )

    /**
     * Mirrors ChatCompactionService.splitMessage's cut (drop everything
     * before the kept context rounds, continue with a fresh summary)
     * without needing a model/hand. splitMessage's keep-count clamp
     * (keep = minOf(lastNRound, roundCount - 1), for chats shorter than
     * the keep count) is unreachable through the replay — the loop only
     * compacts feeds holding more than contextRounds + 1 rounds, so keep
     * is always exactly the requested count here and the fake needs no
     * clamp.
     */
    private fun fakeCompactResult(
        feed: List<ChatMessage>,
        contextRounds: Int,
        summary: ChatMessage,
    ): ChatCompactionResult {
        val kept = feed.takeLastNRound(contextRounds)
        return ChatCompactionResult(
            droppedMessages = feed.dropLast(kept.size),
            newChat = listOf(summary) + kept,
        )
    }

    private fun userTexts(batch: List<ChatMessage>): List<String> =
        batch.filter { it.role == ChatMessageRole.User }
            .flatMap { it.parts }
            .filterIsInstance<ChatMessagePart.Text>()
            .map { it.text }

    /** An in-memory [ExtractionQueue]: records every enqueued region. */
    private class FakeQueue : ExtractionQueue {
        val regions = mutableListOf<List<ChatMessage>>()
        override suspend fun enqueue(messages: List<ChatMessage>): Long {
            regions += messages
            return regions.size.toLong()
        }

        override suspend fun claim(): ClaimedJob? = null
        override suspend fun complete(id: Long) {}
        override suspend fun reschedule(id: Long) {}
    }

    // ------------------------------------------------------------------
    // replayChat: the walk's window arithmetic (no model/hand/queue)
    // ------------------------------------------------------------------

    @Test
    fun `a long chat replays in windows and enqueues every round exactly once`() = runBlocking {
        val chat = (1..20).flatMap { round(it) }
        val feeds = mutableListOf<List<ChatMessage>>()
        val regions = mutableListOf<List<ChatMessage>>()
        var summaries = 0
        replayChat(
            chat = chat,
            compactionRounds = 8,
            contextRounds = 3,
            compact = { feed ->
                feeds += feed
                fakeCompactResult(feed, 3, summary("S${++summaries}"))
            },
            onDropped = { regions += it },
        )

        // two full windows (8 + 3 rounds each) were compacted, in order
        assertEquals(listOf(11, 11), feeds.map { it.roundCount() })
        assertEquals(chat.take(22), feeds[0], "the first window is the chat's head")
        assertEquals(
            ChatCompactionService.COMPACTION_HEADER + "S1",
            userTexts(feeds[1]).first(),
            "the second window continues after the first summary",
        )

        // three enqueued regions: r1..r8 | S1 + r9..r15 | S2 + r16..r20
        assertEquals(3, regions.size)
        assertEquals(chat.take(16), regions[0], "the first region is the first window's dropped part")
        assertEquals(15, regions[1].size)
        assertEquals(11, regions[2].size)
        // the later regions open with the running summary (the dropped
        // region the production compaction path would enqueue)
        assertEquals(ChatCompactionService.COMPACTION_HEADER + "S1", userTexts(regions[1]).first())
        assertEquals(ChatCompactionService.COMPACTION_HEADER + "S2", userTexts(regions[2]).first())

        // every raw round enqueued exactly once, in order
        assertEquals(
            (1..20).map { "u$it" },
            regions.flatMap { userTexts(it) }
                .filterNot { it.startsWith(ChatCompactionService.COMPACTION_HEADER) },
        )
    }

    @Test
    fun `a leading assistant prologue rides the first window and is enqueued with it`() = runBlocking {
        // the transformer's output always opens with an assistant prologue
        // before the first user round (see
        // transform/SillyTavernTransformer.kt): takeFirstNRound must carry
        // it inside the first window so it lands in the first dropped
        // region — enqueued exactly once, never silently dropped
        val chat = listOf(prologue()) + (1..20).flatMap { round(it) }
        val regions = mutableListOf<List<ChatMessage>>()
        replayChat(
            chat = chat,
            compactionRounds = 8,
            contextRounds = 3,
            compact = { feed -> fakeCompactResult(feed, 3, summary("S")) },
            onDropped = { regions += it },
        )
        assertTrue(regions.isNotEmpty())
        assertEquals(prologue(), regions[0].first(), "the prologue opens the first enqueued region")
        assertEquals(1, regions.count { region -> prologue() in region }, "the prologue is enqueued exactly once")
        // every raw round still enqueued exactly once, in order
        assertEquals(
            (1..20).map { "u$it" },
            regions.flatMap { userTexts(it) }
                .filterNot { it.startsWith(ChatCompactionService.COMPACTION_HEADER) },
        )
    }

    @Test
    fun `a chat within one window is enqueued as one region without compaction`() = runBlocking {
        val chat = (1..5).flatMap { round(it) }
        val compactions = mutableListOf<List<ChatMessage>>()
        val regions = mutableListOf<List<ChatMessage>>()
        replayChat(
            chat = chat,
            compactionRounds = 8,
            contextRounds = 3,
            compact = { feed ->
                compactions += feed
                fakeCompactResult(feed, 3, summary("S"))
            },
            onDropped = { regions += it },
        )
        assertTrue(compactions.isEmpty(), "a chat within one window is never compacted")
        assertEquals(listOf(chat), regions, "the whole chat is the one enqueued region")
    }

    @Test
    fun `degenerate knobs do not stall and still enqueue every round exactly once`() = runBlocking {
        // compactionRounds < contextRounds + 1 is legal but wasteful: the
        // loop still covers every round exactly once (and must terminate —
        // the loop bound's contextRounds + 1 half is the stall guard)
        val chat = (1..20).flatMap { round(it) }
        val regions = mutableListOf<List<ChatMessage>>()
        var compactions = 0
        replayChat(
            chat = chat,
            compactionRounds = 2,
            contextRounds = 8,
            compact = { feed ->
                compactions++
                fakeCompactResult(feed, 8, summary("S$compactions"))
            },
            onDropped = { regions += it },
        )
        // the trace of 20 rounds at 2/8: eleven compactions, twelve regions
        // (r1,r2 | S1,r3 | S2,r4 | ... | S10,r12 | residue S11 + r13..r20)
        assertEquals(11, compactions)
        assertEquals(12, regions.size)
        assertEquals(
            (1..20).map { "u$it" },
            regions.flatMap { userTexts(it) }
                .filterNot { it.startsWith(ChatCompactionService.COMPACTION_HEADER) },
        )
    }

    @Test
    fun `an empty dropped region from compact is not enqueued`() = runBlocking {
        // the production split never returns an empty drop for a full
        // window, but the walk stays defensive: an empty region would
        // enqueue a no-op extraction job. The fake compactions still
        // shrink the chat (summary + kept context), so the walk runs to
        // the residue — and only THAT is enqueued: the dropped rounds
        // vanish without ever reaching onDropped
        val chat = (1..20).flatMap { round(it) }
        val regions = mutableListOf<List<ChatMessage>>()
        replayChat(
            chat = chat,
            compactionRounds = 8,
            contextRounds = 3,
            compact = { feed ->
                ChatCompactionResult(
                    droppedMessages = emptyList(),
                    newChat = fakeCompactResult(feed, 3, summary("S")).newChat,
                )
            },
            onDropped = { regions += it },
        )
        // the trace of 20 rounds with keep-3 compactions: two windows eat
        // r1..r15, the residue is the last summary plus r16..r20
        assertEquals(1, regions.size, "only the residue region is enqueued")
        val residue = regions[0]
        assertEquals(ChatCompactionService.COMPACTION_HEADER + "S", userTexts(residue).first())
        assertEquals((16..20).flatMap { round(it) }, residue.drop(1))
    }

    @Test
    fun `an empty chat is a no-op`() = runBlocking {
        replayChat(
            chat = emptyList(),
            compactionRounds = 8,
            contextRounds = 3,
            compact = { _ -> error("must not compact") },
            onDropped = { error("must not enqueue") },
        )
    }

    @Test
    fun `the knobs are validated`() = runBlocking {
        val chat = (1..4).flatMap { round(it) }
        val e1 = assertFailsWith<IllegalArgumentException> {
            replayChat(
                chat = chat,
                compactionRounds = 1,
                contextRounds = 3,
                compact = { _ -> error("must not compact") },
                onDropped = { error("must not enqueue") },
            )
        }
        assertTrue(e1.message!!.contains("compactionRounds"), e1.message)
        val e2 = assertFailsWith<IllegalArgumentException> {
            replayChat(
                chat = chat,
                compactionRounds = 8,
                contextRounds = 0,
                compact = { _ -> error("must not compact") },
                onDropped = { error("must not enqueue") },
            )
        }
        assertTrue(e2.message!!.contains("contextRounds"), e2.message)
    }

    // ------------------------------------------------------------------
    // EltmReplayService: the job's behavior over a real compaction
    // service (FakeHand) and a fake queue
    // ------------------------------------------------------------------

    private var service: EltmReplayService? = null

    @AfterTest
    fun stopService() {
        service?.close()
    }

    /** The vision-capable test model the config's pipeline ids point at. */
    private val compactModel: LLM = testLlm("bifrost/cerebras/gemma-4-31b")

    private fun newService(
        runScript: suspend (HandRunRequest) -> List<HandEvent> = { textRunFlow("summary") },
        extractModel: LLM = compactModel,
        queue: FakeQueue = FakeQueue(),
    ): Pair<EltmReplayService, FakeQueue> {
        val compaction = ChatCompactionService(
            model = compactModel,
            hand = testHandService(FakeHand(runScript = runScript)),
            policy = HandRunPolicy(0, 0),
        )
        val s = EltmReplayService(
            compactModel = compactModel,
            extractModel = extractModel,
            compactionService = compaction,
            queue = queue,
        )
        service = s
        return s to queue
    }

    /** Poll [condition] until it holds or the (generous) deadline lapses. */
    private suspend fun awaitUntil(timeoutMs: Long = 10_000, condition: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "condition not met within ${timeoutMs}ms" }
            delay(25)
        }
    }

    @Test
    fun `start walks the chat and finishes with every region enqueued`() = runBlocking {
        val summaries = AtomicInteger(0)
        val (replay, queue) = newService(
            runScript = { textRunFlow("S${summaries.incrementAndGet()}") },
        )
        val chat = (1..20).flatMap { round(it) }

        assertEquals(ReplayStatus.Idle, replay.status())
        assertTrue(replay.start(chat, compactionRounds = 8, contextRounds = 3))
        awaitUntil { replay.status() !is ReplayStatus.Running }
        val finished = assertIs<ReplayStatus.Finished>(replay.status())
        assertEquals(chat.size, finished.messagesTotal)
        assertEquals(2, finished.windowsCompacted, "two full windows were compacted")
        assertEquals(3, finished.jobsQueued, "two dropped regions plus the residue were enqueued")

        // the regions mirror the walk's arithmetic (see the loop tests):
        // raw rounds | S1 + raw rounds | S2 + raw rounds, every raw round
        // enqueued exactly once
        assertEquals(3, queue.regions.size)
        assertEquals(
            (1..20).map { "u$it" },
            queue.regions.flatMap { userTexts(it) }
                .filterNot { it.startsWith(ChatCompactionService.COMPACTION_HEADER) },
        )
        assertEquals(
            ChatCompactionService.COMPACTION_HEADER + "S1",
            userTexts(queue.regions[1]).first(),
            "the second region opens with the first window's summary",
        )
    }

    @Test
    fun `a second start is refused while a walk is active and works again after`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val (replay, queue) = newService(
            runScript = {
                gate.await()
                textRunFlow("S")
            },
        )
        val chat = (1..20).flatMap { round(it) }

        assertTrue(replay.start(chat, compactionRounds = 8, contextRounds = 3))
        assertTrue(replay.status() is ReplayStatus.Running, "the gated compaction keeps the walk active")
        assertFalse(replay.start(chat, compactionRounds = 8, contextRounds = 3), "the second start must be refused while running")
        assertTrue(queue.regions.isEmpty(), "the gated first window has not dropped anything yet")

        gate.complete(Unit)
        awaitUntil { replay.status() !is ReplayStatus.Running }
        assertTrue(replay.status() is ReplayStatus.Finished, "expected finished, got ${replay.status()}")

        // a finished walk leaves the single-flight lock: the next start is
        // accepted again — its regions pile on top of the first walk's (a
        // re-run's dedup is the writer's job, not the walk's), so the
        // queue's size is the progress signal for the second walk
        assertTrue(replay.start(chat, compactionRounds = 8, contextRounds = 3))
        awaitUntil { queue.regions.size >= 6 }
        assertEquals(6, queue.regions.size, "the second walk re-enqueues every region again")
        assertTrue(replay.status() is ReplayStatus.Finished, "the second walk finished too")
    }

    @Test
    fun `a mid-walk compaction failure fails the walk and keeps the enqueued regions`() = runBlocking {
        val calls = AtomicInteger(0)
        val (replay, queue) = newService(
            runScript = { request ->
                if (calls.incrementAndGet() == 1) {
                    textRunFlow("S1")
                } else {
                    throw HandUpstreamException("hand request failed with HTTP 500")
                }
            },
        )
        val chat = (1..20).flatMap { round(it) }

        assertTrue(replay.start(chat, compactionRounds = 8, contextRounds = 3))
        awaitUntil { replay.status() !is ReplayStatus.Running }
        val failed = assertIs<ReplayStatus.Failed>(replay.status())
        assertNotNull(failed.error)
        assertTrue(failed.error.contains("Compaction summarization failed"), failed.error)
        // the at-failure counters: the first window was compacted and its
        // region enqueued before the second window's compaction failed
        assertEquals(1, failed.windowsCompacted)
        assertEquals(1, failed.jobsQueued)

        // the first window's dropped region was already enqueued and stays
        // queued — the walk is safely re-runnable over the whole chat
        assertEquals(1, queue.regions.size)
        assertEquals(chat.take(16), queue.regions[0])
    }

    @Test
    fun `start validates the knobs and the chat synchronously`() = runBlocking {
        val (replay, queue) = newService()
        val chat = (1..4).flatMap { round(it) }

        val knobs = assertFailsWith<IllegalArgumentException> {
            replay.start(chat, compactionRounds = 1, contextRounds = 3)
        }
        assertTrue(knobs.message!!.contains("compactionRounds"), knobs.message)

        val empty = assertFailsWith<IllegalArgumentException> {
            replay.start(emptyList(), compactionRounds = 8, contextRounds = 3)
        }
        assertTrue(empty.message!!.contains("empty"), empty.message)

        // decode-valid but round-less (a lone assistant message passes
        // ChatCodec.validateChat): the walk's arithmetic is round-based,
        // so start refuses it instead of enqueuing it whole
        val userless = assertFailsWith<IllegalArgumentException> {
            replay.start(listOf(prologue()), compactionRounds = 8, contextRounds = 3)
        }
        assertTrue(userless.message!!.contains("user messages"), userless.message)

        assertEquals(ReplayStatus.Idle, replay.status(), "a refused start leaves the phase at idle")
        assertTrue(queue.regions.isEmpty(), "nothing was enqueued")
    }

    @Test
    fun `start fails fast when a pipeline model cannot see the content`() = runBlocking {
        // a text-only extraction model with an image in the chat: the
        // up-front capability check is a configuration error that fails
        // the start synchronously — no window, no LLM call, no enqueue
        val textOnly = testLlm("bifrost/cerebras/gpt-oss-120b")
        val (replay, queue) = newService(extractModel = textOnly)
        val image = ChatMessagePart.Attachment(
            kind = AttachmentKind.Image,
            content = AttachmentContent.Base64("AAAA"),
            mimeType = "image/png",
        )
        val chat = (1..4).flatMap { round(it) }.mapIndexed { index, message ->
            if (index == 0) message.copy(parts = message.parts + image) else message
        }

        assertFailsWith<ModelCapabilityException> {
            replay.start(chat, compactionRounds = 8, contextRounds = 3)
        }
        assertTrue(queue.regions.isEmpty(), "nothing was enqueued")
        assertEquals(ReplayStatus.Idle, replay.status())
    }
}
