package info.skyblond.daapu.agent.pipeline.eltm

import info.skyblond.daapu.agent.chat.ChatMessage
import info.skyblond.daapu.agent.chat.ChatMessagePart
import info.skyblond.daapu.agent.context.ContextInjection
import info.skyblond.daapu.agent.model.LLM
import info.skyblond.daapu.hand.HandRunPolicy
import info.skyblond.daapu.hand.HandService
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.LocalDate

/**
 * The memory extraction pipeline (the harness's memory architecture, see the
 * README: when messages are removed from context, extract info from the raw messages and
 * write it into the ELTM before discarding them):
 *
 * 1. **Extractor** — the stateless one-shot ([MemoryExtractor]): the raw
 *    dropped history (attachments included, so the model needs the matching
 *    input capabilities) plus the extraction system prompt, returning a
 *    free-text list of candidate facts (or the
 *    [MemoryExtractor.NOTHING_TO_REMEMBER_TEXT] sentinel).
 * 2. **ELTM writer** — one hand `/v1/run` tool loop
 *    ([EltmWriterService.writeToEltm]) that records the extracted facts into
 *    the ELTM diary directly (entities, relationships, attributes, notes).
 *    The writer deduplicates against the store, so a retried run never
 *    duplicates diary entries.
 *
 * The two public entry points pair the stages behind one call:
 * [processDiscardedMessages] (the background extraction queue's job body —
 * the worker `memory/eltm/ExtractionQueueWorker.kt` feeds it the frozen
 * history snapshots enqueued by BOTH the chat-deletion path
 * (`agent/chat/ChatService.deleteChat`) and the compaction path
 * (`agent/persist/PersistChatService.compactAndEnqueue`), so the memory
 * work never runs on the request path) and
 * [digestUserInput] (the `/api/eltm/digest` path, running the same
 * extractor over caller-supplied text/image parts — ONE synthetic
 * message, order preserved — instead of a dropped history; the
 * [MemoryExtractor.extractFactsFromDigest] input flavor keeps the fact
 * tone identical regardless of how the input was written). Both write
 * through [EltmWriterService.writeToEltm].
 *
 * A failure throws:
 * - whatever the extraction stage throws (see [MemoryExtractor]: a
 *   [info.skyblond.daapu.agent.model.ModelCapabilityException] means the
 *   extraction model cannot process the prompt content — a configuration
 *   error (`memory.eltm.extractionModel`) that fails fast — while a failed
 *   extraction, one producing tool calls or no text, throws
 *   [IllegalStateException]);
 * - any terminal writer failure: a classified hand error, an exhausted
 *   transient-retry budget, the `round_limit` cap or an `empty_response`.
 *   Neither entry point runs on the chat-run request path, so a failure
 *   never fails a run: the worker logs it and the queue's visibility
 *   timeout retries the job — re-extracting from the frozen snapshot,
 *   unlimited (whatever was already recorded sticks: the writer skips
 *   already-recorded content on retry) — while the digest route maps a
 *   terminal [IllegalStateException] onto a 502 (see `EltmRoute.kt`).
 */
class MemoryExtractionService(
    extractModel: LLM,
    hand: HandService,
    // config `hand.*` — see [HandRunPolicy]; the writer rounds are capped
    // inside EltmWriterService
    policy: HandRunPolicy,
    // the ELTM write path: the extracted facts are written into the ELTM
    // diary directly (config `memory.eltm.writerModel`, `maxWriterRounds`);
    // REQUIRED — `memory.eltm` is mandatory config
    private val eltmWriterService: EltmWriterService,
    // the harness context handed to the extractor stage (see
    // [MemoryExtractor])
    contextInjection: ContextInjection = ContextInjection(),
) {
    // the extractor stage (see its KDoc): built once, stateless, shared by
    // both entry points below
    private val extractor = MemoryExtractor(extractModel, hand, policy, contextInjection)

    /**
     * Extract memories from [droppedMessages] (the raw history snapshot a
     * background job carries: a deleted chat's history or a compaction's
     * dropped messages — the extraction queue's worker feeds this from
     * `pending_extractions`) and write them into the ELTM. Throws per the
     * class KDoc — the worker logs the failure and the queue's visibility
     * timeout retries the job. The
     * [MemoryExtractor.NOTHING_TO_REMEMBER_TEXT] sentinel (matched
     * tolerantly by [MemoryExtractor.isNothingToRemember]) is the only skip
     * path for the extraction: a blank answer is a hand `empty_response`
     * error and fails the job, it cannot silently skip the write.
     */
    suspend fun processDiscardedMessages(
        droppedMessages: List<ChatMessage>,
    ) {
        if (droppedMessages.isNotEmpty()) {
            val extraction = extractor.extractFacts(droppedMessages)
            if (!MemoryExtractor.isNothingToRemember(extraction)) {
                logger.info { "Extracted memories from ${droppedMessages.size} dropped message(s), writing into the ELTM" }
                eltmWriterService.writeToEltm(extraction, LocalDate.now())
            }
        }
    }

    /**
     * The `/api/eltm/digest` entry point (`POST /api/eltm/digest`, see
     * `server/endpoint/EltmRoute.kt`): run the two-stage pipeline over the
     * caller-supplied [parts] (ordered text and image parts, the
     * `EltmDigestRequest` wire shape) and write the extracted facts into
     * the ELTM. [referenceDate] anchors the extraction only
     * ([MemoryExtractor.extractFactsFromDigest] resolves the input's
     * relative dates against it) — the write stage always stamps the
     * extraction day (`LocalDate.now()`, the same "current date" the
     * discard pipeline writes with, keeping event dates from running ahead
     * of the write day). The route's future-`date` 400 (see `EltmRoute.kt`)
     * guards the anchor itself: a future reference date would resolve the
     * input's relative dates against a future time and bake future absolute
     * dates into the facts. No images AND a text side that is blank or
     * matches the [MemoryExtractor.NOTHING_TO_REMEMBER_TEXT] sentinel
     * (tolerantly, [MemoryExtractor.isNothingToRemember], across all text
     * parts joined) is a silent no-op WITHOUT any LLM call — attached
     * images must never be silently skipped, so their presence always runs
     * the extraction; an extraction answering the sentinel skips only the
     * write. Throws per the class KDoc — the route maps a terminal
     * [IllegalStateException] to a 502 (see `EltmRoute.kt`), while the
     * capability mismatch surfaces as a 400
     * ([info.skyblond.daapu.agent.model.ModelCapabilityException], mapped
     * by the server module's StatusPages); the wire-level part validation
     * (only text and image attachments, decodable base64) lives in the
     * route.
     */
    suspend fun digestUserInput(
        parts: List<ChatMessagePart>,
        referenceDate: LocalDate,
    ) {
        // the fast path reads the text parts joined in input order; attached
        // images must never be silently skipped, so their presence always
        // runs the extraction
        val text = parts.filterIsInstance<ChatMessagePart.Text>()
            .joinToString("\n\n") { it.text }
        val hasImages = parts.any { it is ChatMessagePart.Attachment }
        if (!hasImages && (text.isBlank() || MemoryExtractor.isNothingToRemember(text))) {
            return
        }
        val extraction = extractor.extractFactsFromDigest(parts, referenceDate)
        if (!MemoryExtractor.isNothingToRemember(extraction)) {
            eltmWriterService.writeToEltm(extraction, LocalDate.now())
        }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
