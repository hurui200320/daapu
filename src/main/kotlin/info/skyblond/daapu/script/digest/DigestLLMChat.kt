package info.skyblond.daapu.script.digest

import info.skyblond.daapu.agent.ModelCatalog
import info.skyblond.daapu.agent.chat.ChatCodec
import info.skyblond.daapu.agent.chat.ChatMessage
import info.skyblond.daapu.agent.chat.ChatMessageRole
import info.skyblond.daapu.agent.chat.roundCount
import info.skyblond.daapu.agent.model.LLM
import info.skyblond.daapu.agent.pipeline.OneShotTracer
import info.skyblond.daapu.agent.pipeline.compaction.ChatCompactionResult
import info.skyblond.daapu.agent.pipeline.compaction.ChatCompactionService
import info.skyblond.daapu.agent.pipeline.eltm.MemoryExtractor
import info.skyblond.daapu.config.loadConfig
import info.skyblond.daapu.hand.HandCallbackService
import info.skyblond.daapu.hand.HandRunPolicy
import info.skyblond.daapu.hand.HandService
import info.skyblond.daapu.hand.HttpHandClient
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A dev-time utility, not part of the server. Invocation (through the
 * Gradle runner) and the produced files are documented in `script/README.md`.
 */
private val logger = KotlinLogging.logger {}

/**
 * Manually replay a foreign chat through this system's memory pipeline.
 *
 * The input is a chat between user and assistant from another platform,
 * already processed into the neutral [ChatMessage] format (e.g. the
 * SillyTavern transformer's `.messages.json` output). Instead of importing
 * it as a chat, the replay walks it window by window with the PRODUCTION
 * compaction and extraction stages ([ChatCompactionService],
 * [MemoryExtractor]) and writes every extracted fact batch to a text file
 * for manual review — the user reviews the file and feeds the facts into
 * the ELTM through the web UI's digest tab.
 *
 * Why not import-and-delete: an external chat may be far longer than what
 * this system's models can process at once (e.g. a 1M-context LLM's export
 * against our 256K windows), and one giant extraction over the whole chat
 * is exactly what the memory extractor cannot do reliably. The replay
 * instead feeds the extractor window-sized batches, so every bit of the
 * chat passes through it at a size the configured models handle.
 *
 * CLI contract: `<chat.messages.json> <factsOutput.txt> [compactionRounds=8]
 * [contextRounds=3]` — the chat file (decoded with [ChatCodec.decodeChat],
 * so it must satisfy the stored-chat invariants), the output text file,
 * and the replay window knobs (validated by [validateReplayKnobs]). The
 * models and the hand endpoint come from the server's own `config.jsonc`
 * (`memory.compactModel`, `memory.eltm.extractionModel`, `hand.*`): the
 * hand-pi service must be RUNNING, but no database and no HTTP server is
 * needed next to this script (the one-shot runs are tool-less, see
 * [HandService]). The output file is truncated at start and flushed per
 * batch, so a crashed run keeps its completed batches for review; a
 * re-run restarts the replay from scratch.
 */
fun main(args: Array<String>) {
    if (args.size !in 2..4) {
        throw IllegalArgumentException(
            "Usage: <chat.messages.json> <factsOutput.txt> [compactionRounds=8] [contextRounds=3] — see script/README.md"
        )
    }
    val chatFile = File(args[0])
    val factsFile = File(args[1])
    val compactionRounds = parseIntArg("compactionRounds", args.getOrElse(2) { "8" })
    val contextRounds = parseIntArg("contextRounds", args.getOrElse(3) { "3" })
    // bad knobs fail here, before any config load or file work — the same
    // contract the replay loop enforces for its direct callers
    validateReplayKnobs(compactionRounds, contextRounds)

    // the server's own config drives the replay, so the SAME production
    // stages the server would run are used (the model ids are the
    // mandatory memory-pipeline ones, resolved like di/AppModule.kt does)
    val config = loadConfig()
    val policy = HandRunPolicy(config.hand.maxRetries, config.hand.streamIdleTimeoutMs)
    val catalog = ModelCatalog.fromConfig(config.providers)
    val compactModel = catalog.requiredModel("memory.compactModel", config.memory.compactModel)
    val extractModel = catalog.requiredModel("memory.eltm.extractionModel", config.memory.eltm.extractionModel)

    val chat = ChatCodec.decodeChat("digest-script", chatFile.readText())
    if (chat.isEmpty()) {
        // an empty chat is decodable, so this path is reachable: still
        // truncate, keeping the truncate-at-start contract — a re-run
        // against an empty chat must not leave stale facts behind
        factsFile.writeText("")
        logger.info { "The chat file is empty, nothing to replay" }
        return
    }
    // fail fast on a content/capability mismatch BEFORE any LLM spend: the
    // services re-check per call, but this catches the mismatch up front
    // instead of mid-replay after N batches (every message lands in some
    // batch, so a whole-chat check covers every batch)
    compactModel.checkPromptContentCapabilities(chat)
    extractModel.checkPromptContentCapabilities(chat)
    logger.info {
        "Replaying ${chat.size} message(s) (${chat.roundCount()} rounds), " +
                "compactionRounds=$compactionRounds, contextRounds=$contextRounds"
    }

    HandService(
        hand = HttpHandClient(config.hand.baseUrl, config.hand.token),
        handCallback = HandCallbackService(config.hand.token),
        toolCallbackUrl = config.hand.toolCallbackUrl,
        toolListUrl = config.hand.toolListUrl,
        collectObserver = if (config.observability.oneShotTrace) OneShotTracer() else null,
    ).use { hand ->
        val compactionService = ChatCompactionService(
            model = compactModel,
            hand = hand,
            policy = policy,
        )
        val memoryExtractor = MemoryExtractor(
            extractModel = extractModel,
            hand = hand,
            policy = policy,
        )
        factsFile.bufferedWriter().use { writer ->
            runBlocking {
                replayChatForDigest(
                    chat = chat,
                    compactionRounds = compactionRounds,
                    contextRounds = contextRounds,
                    compact = { feed -> compactionService.compactChat(feed, contextRounds) },
                    extractFacts = { batch -> memoryExtractor.extractFacts(batch) },
                    onFacts = { facts ->
                        withContext(Dispatchers.IO) {
                            writer.write(facts)
                            writer.write("\n\n")
                            writer.flush()
                        }
                    },
                )
            }
        }
    }
    logger.info { "Replay finished, facts written to $factsFile" }
}

/**
 * The replay loop behind [main] — the single source of the replay
 * semantics (`script/README.md` points here):
 *
 * 1. While the remaining chat has more rounds than
 *    `max(compactionRounds, contextRounds + 1)`, one iteration feeds the
 *    leading `compactionRounds + contextRounds` user rounds to [compact]
 *    (the production `ChatCompactionService.compactChat` with
 *    `excludeLastNRound = contextRounds`), extracts facts from the DROPPED
 *    region (the running summary included — exactly the region the
 *    production compaction path queues for extraction, see
 *    `PersistChatService.compactAndEnqueue`), and continues with
 *    `[summary + kept context rounds] + the untouched tail`.
 * 2. The residue — the last summary plus the trailing rounds, or the
 *    whole chat when it never exceeded one window (no compaction at all)
 *    — is extracted in one final batch.
 *
 * Every raw round is extracted exactly once: each window's rounds are
 * either dropped (extracted, never seen again) or preserved (carried into
 * the next window verbatim), and the tail beyond the window is untouched.
 * Each full-window iteration drops `compactionRounds` rounds and re-adds
 * the summary as one, so the round count strictly decreases
 * (`compactionRounds >= 2`); `contextRounds + 1` — the smallest chat any
 * compaction can leave behind (the summary plus its kept context rounds)
 * — closes the loop bound, so the loop cannot stall however lopsided the
 * knobs are (a remaining chat too short for a full window compacts down
 * to exactly that bound and exits).
 *
 * A batch whose extraction answers the nothing-worth-remembering sentinel
 * ([MemoryExtractor.isNothingToRemember]) is skipped with a log line
 * instead of written. Failures of [compact]/[extractFacts] propagate and
 * abort the replay (the batches already handed to [onFacts] stay there).
 *
 * The two LLM stages are lambda seams so tests can pin the loop's
 * arithmetic without any model/hand.
 */
internal suspend fun replayChatForDigest(
    chat: List<ChatMessage>,
    compactionRounds: Int,
    contextRounds: Int,
    compact: suspend (feed: List<ChatMessage>) -> ChatCompactionResult,
    extractFacts: suspend (batch: List<ChatMessage>) -> String,
    onFacts: suspend (facts: String) -> Unit,
) {
    validateReplayKnobs(compactionRounds, contextRounds)
    var remaining = chat
    // loop while the remaining chat holds more than one batch's worth of
    // rounds: compactionRounds bounds what one window drops, and
    // contextRounds + 1 is the smallest chat any compaction can leave
    // behind — a remaining chat at or below the max of the two is the
    // final batch instead
    while (remaining.roundCount() > maxOf(compactionRounds, contextRounds + 1)) {
        // one window: the rounds this compaction will drop plus the
        // context rounds it preserves
        val feed = remaining.takeFirstNRound(compactionRounds + contextRounds)
        val result = compact(feed)
        extractBatch(result.droppedMessages, extractFacts, onFacts)
        remaining = result.newChat + remaining.drop(feed.size)
    }
    extractBatch(remaining, extractFacts, onFacts)
}

/**
 * The replay's knob contract: the single validation shared by [main] (fail
 * fast, before any config load or file work) and [replayChatForDigest]
 * (guarding its own direct callers, tests included). The bounds' reasoning
 * lives in the require messages and in [replayChatForDigest]'s loop-bound
 * notes.
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
 * One extraction batch: run [extractFacts] over [batch] and hand the
 * result to [onFacts] — unless the extraction answered the sentinel (see
 * [replayChatForDigest]). An empty batch is a no-op.
 */
private suspend fun extractBatch(
    batch: List<ChatMessage>,
    extractFacts: suspend (List<ChatMessage>) -> String,
    onFacts: suspend (String) -> Unit,
) {
    if (batch.isEmpty()) return
    val facts = extractFacts(batch)
    if (MemoryExtractor.isNothingToRemember(facts)) {
        logger.info { "A batch of ${batch.size} message(s) produced nothing worth remembering" }
    } else {
        onFacts(facts)
    }
}

/**
 * The leading [n] user rounds — the mirror of the production
 * `takeLastNRound` (the same user-message-boundary cut, from the other
 * end): everything up to the (n+1)-th user message, so
 * tool_call/tool_result pairs stay whole. The whole list when it holds
 * [n] user rounds or fewer; empty when [n] <= 0. Local to the script
 * because only the replay walks a chat forward.
 */
private fun List<ChatMessage>.takeFirstNRound(n: Int): List<ChatMessage> {
    if (n <= 0) return emptyList()
    val userIndexes = mapIndexedNotNull { index, message ->
        if (message.role == ChatMessageRole.User) index else null
    }
    if (userIndexes.size <= n) return this
    return subList(0, userIndexes[n])
}

/** Parse a CLI integer knob, failing fast with the knob named. */
private fun parseIntArg(name: String, value: String): Int = try {
    value.toInt()
} catch (e: NumberFormatException) {
    throw IllegalArgumentException("$name must be an integer, got '$value'", e)
}

private fun ModelCatalog.requiredModel(configKey: String, id: String): LLM = findModel(id)
    ?: throw IllegalArgumentException("$configKey '$id' is not in the model catalog")

