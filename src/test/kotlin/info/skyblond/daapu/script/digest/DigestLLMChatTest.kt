package info.skyblond.daapu.script.digest

import info.skyblond.daapu.agent.chat.ChatMessage
import info.skyblond.daapu.agent.chat.ChatMessageMeta
import info.skyblond.daapu.agent.chat.ChatMessagePart
import info.skyblond.daapu.agent.chat.ChatMessageRole
import info.skyblond.daapu.agent.chat.roundCount
import info.skyblond.daapu.agent.chat.takeLastNRound
import info.skyblond.daapu.agent.pipeline.compaction.ChatCompactionResult
import info.skyblond.daapu.agent.pipeline.compaction.ChatCompactionService
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.*

/**
 * Pins [replayChatForDigest]'s window arithmetic (the semantics
 * `script/README.md` points at): the batch sequence, the exactly-once
 * round coverage, the running summary riding every batch after the first,
 * the no-compaction short-chat path, the sentinel skip, the stall guard
 * for lopsided knobs, and the leading-assistant-prologue input shape (the
 * transformer's output). The two LLM stages are lambda seams, so the
 * loop is tested without any model/hand.
 */
class DigestLLMChatTest {

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

    @Test
    fun `a long chat replays in batches and extracts every round exactly once`() = runBlocking {
        val chat = (1..20).flatMap { round(it) }
        val feeds = mutableListOf<List<ChatMessage>>()
        val batches = mutableListOf<List<ChatMessage>>()
        val facts = mutableListOf<String>()
        var summaries = 0
        replayChatForDigest(
            chat = chat,
            compactionRounds = 8,
            contextRounds = 3,
            compact = { feed ->
                feeds += feed
                fakeCompactResult(feed, 3, summary("S${++summaries}"))
            },
            extractFacts = { batch ->
                batches += batch
                "facts"
            },
            onFacts = { facts += it },
        )

        // two full windows (8 + 3 rounds each) were compacted, in order
        assertEquals(listOf(11, 11), feeds.map { it.roundCount() })
        assertEquals(chat.take(22), feeds[0], "the first window is the chat's head")
        assertEquals(
            ChatCompactionService.COMPACTION_HEADER + "S1",
            userTexts(feeds[1]).first(),
            "the second window continues after the first summary",
        )

        // three extraction batches: r1..r8 | S1 + r9..r15 | S2 + r16..r20
        assertEquals(3, batches.size)
        assertEquals(chat.take(16), batches[0], "the first batch is the first window's dropped region")
        assertEquals(15, batches[1].size)
        assertEquals(11, batches[2].size)
        // the later batches open with the running summary (the dropped
        // region the production compaction path would enqueue)
        assertEquals(ChatCompactionService.COMPACTION_HEADER + "S1", userTexts(batches[1]).first())
        assertEquals(ChatCompactionService.COMPACTION_HEADER + "S2", userTexts(batches[2]).first())

        // every raw round extracted exactly once, in order
        assertEquals(
            (1..20).map { "u$it" },
            batches.flatMap { userTexts(it) }
                .filterNot { it.startsWith(ChatCompactionService.COMPACTION_HEADER) },
        )
        assertEquals(3, facts.size, "every non-sentinel batch is written")
    }

    @Test
    fun `a leading assistant prologue rides the first window and is extracted with it`() = runBlocking {
        // the transformer's output always opens with an assistant prologue
        // before the first user round (see
        // transform/SillyTavernTransformer.kt): takeFirstNRound must carry
        // it inside the first window so it lands in the first dropped
        // region — extracted exactly once, never silently dropped
        val prologue = ChatMessage(
            role = ChatMessageRole.Assistant,
            parts = listOf(ChatMessagePart.Text("prologue")),
            meta = ChatMessageMeta(0, 0, 0, null),
            finishReason = "stop",
        )
        val chat = listOf(prologue) + (1..20).flatMap { round(it) }
        val batches = mutableListOf<List<ChatMessage>>()
        replayChatForDigest(
            chat = chat,
            compactionRounds = 8,
            contextRounds = 3,
            compact = { feed -> fakeCompactResult(feed, 3, summary("S")) },
            extractFacts = { batch ->
                batches += batch
                "facts"
            },
            onFacts = { },
        )
        assertTrue(batches.isNotEmpty())
        assertEquals(prologue, batches[0].first(), "the prologue opens the first extraction batch")
        assertEquals(1, batches.count { batch -> prologue in batch }, "the prologue is extracted exactly once")
        // every raw round still extracted exactly once, in order
        assertEquals(
            (1..20).map { "u$it" },
            batches.flatMap { userTexts(it) }
                .filterNot { it.startsWith(ChatCompactionService.COMPACTION_HEADER) },
        )
    }

    @Test
    fun `a chat within one window is extracted in a single batch without compaction`() = runBlocking {
        val chat = (1..5).flatMap { round(it) }
        val compactions = mutableListOf<List<ChatMessage>>()
        val batches = mutableListOf<List<ChatMessage>>()
        replayChatForDigest(
            chat = chat,
            compactionRounds = 8,
            contextRounds = 3,
            compact = { feed ->
                compactions += feed
                fakeCompactResult(feed, 3, summary("S"))
            },
            extractFacts = { batch ->
                batches += batch
                "facts"
            },
            onFacts = { },
        )
        assertTrue(compactions.isEmpty(), "a chat within one window is never compacted")
        assertEquals(listOf(chat), batches, "the whole chat is the one extraction batch")
    }

    @Test
    fun `degenerate knobs do not stall and still extract every round exactly once`() = runBlocking {
        // compactionRounds < contextRounds + 1 is legal but wasteful: the
        // loop still covers every round exactly once (and must terminate —
        // the loop bound's contextRounds + 1 half is the stall guard)
        val chat = (1..20).flatMap { round(it) }
        val batches = mutableListOf<List<ChatMessage>>()
        var compactions = 0
        replayChatForDigest(
            chat = chat,
            compactionRounds = 2,
            contextRounds = 8,
            compact = { feed ->
                compactions++
                fakeCompactResult(feed, 8, summary("S$compactions"))
            },
            extractFacts = { batch ->
                batches += batch
                "facts"
            },
            onFacts = { },
        )
        // the trace of 20 rounds at 2/8: eleven compactions, twelve batches
        // (r1,r2 | S1,r3 | S2,r4 | ... | S10,r12 | residue S11 + r13..r20)
        assertEquals(11, compactions)
        assertEquals(12, batches.size)
        assertEquals(
            (1..20).map { "u$it" },
            batches.flatMap { userTexts(it) }
                .filterNot { it.startsWith(ChatCompactionService.COMPACTION_HEADER) },
        )
    }

    @Test
    fun `a sentinel batch is skipped and the replay continues`() = runBlocking {
        val chat = (1..3).flatMap { round(it) }
        val facts = mutableListOf<String>()
        var calls = 0
        replayChatForDigest(
            chat = chat,
            compactionRounds = 2,
            contextRounds = 1,
            compact = { feed -> fakeCompactResult(feed, 1, summary("S")) },
            extractFacts = { _ ->
                calls++
                if (calls == 1) "Nothing worth remember." else "real facts"
            },
            onFacts = { facts += it },
        )
        assertEquals(2, calls, "both the dropped batch and the residue are extracted")
        assertEquals(listOf("real facts"), facts, "only the non-sentinel batch is written")
    }

    @Test
    fun `an empty chat is a no-op`() = runBlocking {
        replayChatForDigest(
            chat = emptyList(),
            compactionRounds = 8,
            contextRounds = 3,
            compact = { _ -> error("must not compact") },
            extractFacts = { _ -> error("must not extract") },
            onFacts = { error("must not write facts") },
        )
    }

    @Test
    fun `the knobs are validated`() = runBlocking {
        val chat = (1..4).flatMap { round(it) }
        val e1 = assertFailsWith<IllegalArgumentException> {
            replayChatForDigest(
                chat = chat,
                compactionRounds = 1,
                contextRounds = 3,
                compact = { _ -> error("must not compact") },
                extractFacts = { _ -> error("must not extract") },
                onFacts = { },
            )
        }
        assertTrue(e1.message!!.contains("compactionRounds"), e1.message)
        val e2 = assertFailsWith<IllegalArgumentException> {
            replayChatForDigest(
                chat = chat,
                compactionRounds = 8,
                contextRounds = 0,
                compact = { _ -> error("must not compact") },
                extractFacts = { _ -> error("must not extract") },
                onFacts = { },
            )
        }
        assertTrue(e2.message!!.contains("contextRounds"), e2.message)
    }
}
