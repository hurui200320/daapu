package info.skyblond.daapu.agent.pipeline.eltm

import info.skyblond.daapu.agent.chat.ChatMessage
import info.skyblond.daapu.agent.chat.ChatMessagePart
import info.skyblond.daapu.agent.chat.ChatMessageRole
import info.skyblond.daapu.agent.chat.parseImageDataUrl
import info.skyblond.daapu.agent.context.ContextInjection
import info.skyblond.daapu.agent.model.LLM
import info.skyblond.daapu.agent.pipeline.runOneShotText
import info.skyblond.daapu.hand.HandRunPolicy
import info.skyblond.daapu.hand.HandService
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.LocalDate
import java.time.ZoneId

/**
 * The memory extraction pipeline (the harness's memory architecture, see the
 * README: when messages are removed from context, extract info from the raw messages and
 * write it into the ELTM before discarding them):
 *
 * 1. **Extractor** — one hand `/v1/run` call (no tools) with the raw dropped
 *    history (attachments included, so the model needs the matching input
 *    capabilities) plus the extraction system prompt, returning a free-text
 *    list of candidate facts (or the [NOTHING_TO_REMEMBER_TEXT] sentinel).
 * 2. **ELTM writer** — one hand `/v1/run` tool loop
 *    ([EltmWriterService.writeToEltm]) that records the extracted facts into
 *    the ELTM diary directly (entities, relationships, attributes, notes).
 *    The writer deduplicates against the store, so a retried run never
 *    duplicates diary entries.
 *
 * The two public entry points pair the stages behind one call:
 * [processDiscardedMessages] (the discard pipeline) and
 * [processUserImport] (the `/api/eltm/import` path, running the same
 * extractor over caller-supplied text and/or image attachments instead of
 * a dropped history — the private [extractFactsFromImport] selects the
 * [ExtractionInput.USER_IMPORT] flavor of the extractor prompt, so the
 * writer always receives the same fact tone regardless of how the input
 * was written). Both write through [EltmWriterService.writeToEltm].
 *
 * A failure throws and fails the run:
 * - [info.skyblond.daapu.agent.model.ModelCapabilityException] when the
 *   extraction model cannot process the prompt content (e.g. images with a
 *   text-only model — possible on both paths), which is a configuration
 *   error (`memory.eltm.extractionModel`) and fails fast;
 * - a failed extraction (a classified hand error such as a truncated
 *   `length` finish) or one producing tool calls or no text;
 * - any terminal writer failure: a classified hand error, an exhausted
 *   transient-retry budget, the `round_limit` cap or an `empty_response`.
 *   A failed write fails the run — and with it the deletion when triggered
 *   from there, so a retry re-extracts from the still-existing history
 *   instead of losing memories. Whatever was already recorded sticks (the
 *   writer skips already-recorded content on retry).
 */
class MemoryExtractionService(
    private val extractModel: LLM,
    private val hand: HandService,
    // config `hand.*` — see [HandRunPolicy]; the writer rounds are capped
    // inside EltmWriterService
    private val policy: HandRunPolicy,
    // the ELTM write path: the extracted facts are written into the ELTM
    // diary directly (config `memory.eltm.writerModel`, `maxWriterRounds`);
    // REQUIRED — `memory.eltm` is mandatory config
    private val eltmWriterService: EltmWriterService,
    // the harness context: sanitize the dropped history (it may be the chat
    // loop's injected in-loop chat) and anchor every user message with its
    // send time, so the extractor resolves relative dates per message and
    // never against the extraction time
    private val contextInjection: ContextInjection = ContextInjection(),
) {
    /**
     * Extract memories from [droppedMessages] (the raw messages compaction is
     * about to discard) and write them into the ELTM. Throws per the class
     * KDoc (the extraction pipeline fails the run on failure). The
     * [NOTHING_TO_REMEMBER_TEXT] sentinel (matched tolerantly by
     * [isNothingToRemember]) is the only skip path for the extraction: a
     * blank answer is a hand `empty_response` error and fails the run, it
     * cannot silently skip the write.
     */
    suspend fun processDiscardedMessages(
        droppedMessages: List<ChatMessage>,
    ) {
        if (droppedMessages.isNotEmpty()) {
            val extraction = extractFacts(droppedMessages)
            if (!isNothingToRemember(extraction)) {
                logger.info { "Extracted memories from ${droppedMessages.size} dropped message(s), writing into the ELTM" }
                eltmWriterService.writeToEltm(extraction, LocalDate.now())
            }
        }
    }

    /**
     * The extraction stage alone: run the extractor over [droppedMessages]
     * and return the free-text fact list (or the [NOTHING_TO_REMEMBER_TEXT]
     * sentinel when nothing is worth remembering) WITHOUT writing anything
     * — [processDiscardedMessages] decides whether to write.
     *
     * Throws per the class KDoc (a capability mismatch is a configuration
     * error and fails fast; a failed extraction throws
     * [IllegalStateException]). [droppedMessages] must not be empty.
     */
    private suspend fun extractFacts(droppedMessages: List<ChatMessage>): String {
        require(droppedMessages.isNotEmpty()) {
            "cannot extract memories from an empty message list"
        }
        val extraction = extract(droppedMessages, ExtractionInput.CONVERSATION)
        logger.info { "Extracted memories:\n${extraction}" }
        return extraction
    }

    /**
     * The `/api/eltm/import` entry point (`POST /api/eltm/import`, see
     * `server/endpoint/EltmRoute.kt`): run the two-stage pipeline over the
     * caller-supplied [text] and/or [imageDataUrls] and write the
     * extracted facts into the ELTM. [referenceDate] anchors the
     * extraction only ([extractFactsFromImport] resolves the input's
     * relative dates against it) — the write stage always stamps the
     * extraction day (`LocalDate.now()`, the same "current date" the
     * discard pipeline writes with, keeping event dates from running ahead
     * of the write day). The route's future-`date` 400 (see
     * `EltmRoute.kt`) guards the anchor itself: a future reference date
     * would resolve the input's relative dates against a future time and
     * bake future absolute dates into the facts. A blank text with NO
     * images, or one matching the [NOTHING_TO_REMEMBER_TEXT] sentinel
     * (tolerantly, [isNothingToRemember]), is a silent no-op WITHOUT any
     * LLM call — attached images must never be silently skipped, so their
     * presence always runs the extraction; an extraction answering the
     * sentinel skips only the write. Throws per the class KDoc — the route
     * maps a terminal [IllegalStateException] to a 502 (see
     * `EltmRoute.kt`), while the capability mismatch and a malformed data
     * URL surface as 400s
     * ([info.skyblond.daapu.agent.model.ModelCapabilityException],
     * [info.skyblond.daapu.agent.chat.ChatValidationException] from
     * [parseImageDataUrl], both mapped by the server module's
     * StatusPages).
     */
    suspend fun processUserImport(
        text: String,
        imageDataUrls: List<String>,
        referenceDate: LocalDate,
    ) {
        val images = imageDataUrls.map { parseImageDataUrl(it) }
        if (images.isEmpty() && (text.isBlank() || isNothingToRemember(text))) {
            return
        }
        val extraction = extractFactsFromImport(text, images, referenceDate)
        if (!isNothingToRemember(extraction)) {
            eltmWriterService.writeToEltm(extraction, LocalDate.now())
        }
    }

    /**
     * The import path's extraction stage alone ([processUserImport]
     * decides whether to write): run the same extractor over
     * caller-supplied [text] and/or pre-parsed image [images] instead of a
     * dropped history. The input is wrapped as a single synthetic user
     * message stamped with [referenceDate] (start of day in the server's
     * current zone), so the stateless extractor resolves the input's
     * relative dates against the reference date — never against the
     * extraction time — and maps its first-person pronouns to "the user"
     * (a user message's author IS the user), exactly as it does for a
     * discarded conversation. At least one part is required. Returns the
     * free-text fact list or the [NOTHING_TO_REMEMBER_TEXT] sentinel.
     * Throws per the class KDoc (images trip the capability check
     * [extract] runs on every flavor).
     */
    private suspend fun extractFactsFromImport(
        text: String,
        images: List<ChatMessagePart.Attachment>,
        referenceDate: LocalDate,
    ): String {
        val parts = buildList {
            if (text.isNotBlank()) add(ChatMessagePart.Text(text))
            addAll(images)
        }
        require(parts.isNotEmpty()) { "cannot extract memories from a blank text without images" }
        val message = ChatMessage(
            ChatMessageRole.User,
            parts,
            createdAt = referenceDate.atStartOfDay(ZoneId.systemDefault()).toInstant(),
        )
        val extraction = extract(listOf(message), ExtractionInput.USER_IMPORT)
        logger.info { "Extracted memories from imported input:\n${extraction}" }
        return extraction
    }

    /**
     * The extraction call: the raw dropped history plus the extraction
     * instruction. The history is treated as potentially injected (it may
     * come from the chat loop's injected in-loop chat): sanitize first, then
     * anchor every user message with its send time. [input] selects the
     * extractor prompt flavor (the discard pipeline always gets
     * [ExtractionInput.CONVERSATION]; the import path's single synthetic
     * message gets [ExtractionInput.USER_IMPORT]). The extractor is
     * stateless — the input carries no "now" anywhere (no current date in
     * the prompt), every relative date resolves against the message's own
     * anchor, so extraction time never matters. Fails on anything but a
     * clean `stop` with text (the fail-fast semantics depend on
     * distinguishing `length` from `stop`).
     */
    private suspend fun extract(
        droppedMessages: List<ChatMessage>,
        input: ExtractionInput,
    ): String {
        val chat = contextInjection.injectContext(
            contextInjection.removeInjection(droppedMessages) + ChatMessage(
                role = ChatMessageRole.User,
                parts = listOf(
                    ChatMessagePart.Text(
                        "Extract memories item according to the system prompt."
                    )
                ),
            ),
            spec = null,
        )
        extractModel.checkPromptContentCapabilities(droppedMessages)

        return hand.runOneShotText(
            model = extractModel,
            messages = chat,
            systemPrompt = renderExtractorSystemPrompt(input),
            policy = policy,
            label = "Memory extraction",
        )
    }

    companion object {
        private val logger = KotlinLogging.logger {}

        internal const val NOTHING_TO_REMEMBER_TEXT = "Nothing worth remember."

        /**
         * The tolerant sentinel match, the SINGLE SOURCE for every sentinel
          * check: the discard pipeline's skip ([processDiscardedMessages])
          * and the import path's input fast path plus post-extraction check
          * ([processUserImport]). Trims, ignores casing, trailing
         * punctuation and whitespace runs — a near-miss must not reach the
         * writer as a fact batch. Paraphrases this check cannot catch are
         * the writer's own skip-sentinel rule's job (see
         * [EltmWriterService]).
         */
        // precompiled whitespace-run matcher for [isNothingToRemember]
        private val WHITESPACE_RUNS = Regex("\\s+")

        internal fun isNothingToRemember(output: String): Boolean =
            output.trim()
                .replace(WHITESPACE_RUNS, " ")
                .trimEnd('.', '!', '?')
                .equals(NOTHING_TO_REMEMBER_TEXT.trimEnd('.', '!', '?'), ignoreCase = true)

        /**
         * The extractor system prompt for one [ExtractionInput]. The
         * absolute-dates line, the focus list and the fact rules are
         * input-neutral and shared verbatim below (single source); the
         * header (framing + anchor explanation) and the few
         * conversation-specific rule lines come from the [input]'s slots.
         * [ExtractionInput.CONVERSATION] renders byte-identical to the
         * prompt the discard pipeline always used (pinned by
         * MemoryExtractionServiceTest), so the import path's
         * [ExtractionInput.USER_IMPORT] flavor can never drift the discard
         * path's extraction.
         */
        internal fun renderExtractorSystemPrompt(input: ExtractionInput): String = """
${input.header}
Write absolute dates in the facts: "User went to Paris last week" is useless 6 months later; "User went to Paris the week of May 15, 2026" is meaningful forever.

Focus on:
- The user's preferences, likes, dislikes, and personal details
- Plans, goals, pending tasks, and unresolved questions
- Decisions and constraints
- Facts about people, projects, and entities: keep names, numbers, ids, and values verbatim
- Transitions: "switched from X to Y because Z" is more valuable than "uses Y"
- Anything a future conversation would need to know

Rules:
- Each fact must be self-contained: replace pronouns with the entity name or "the user"
- Rich, not atomic: one fact may span 1-3 sentences when the context matters, but keep it under ~80 words
- Write facts in the same language as the ${input.languageOf}
- Do not invent details that are not present in the ${input.presentIn}
- Merge overlapping information into one fact
- Cover the whole ${input.coverWhole}, not just the first topic
${input.echoRule}- Extract the content of documents or code the user shared, not "the user shared a document" (meta extraction)
- When nothing is worth remembering, output sentence "$NOTHING_TO_REMEMBER_TEXT"
""".trimIndent().trim()
    }
}

/**
 * Which input shape the extractor prompt describes (see
 * [MemoryExtractionService.renderExtractorSystemPrompt]): the discard
 * pipeline's dropped history ([CONVERSATION]) or the import path's single
 * synthetic message of caller-supplied text and/or image attachments
 * ([USER_IMPORT], see `MemoryExtractionService.processUserImport`). The
 * fields are the prompt's per-input slots; everything else in the template
 * is shared verbatim. The [USER_IMPORT] wording covers the whole input
 * shape — text and, when present, attached images — so the same flavor
 * serves text-only, images-only and text+images imports.
 */
internal enum class ExtractionInput(
    /** The header block: framing plus the anchor/relative-date explanation. */
    val header: String,
    /** Rules slot: what the facts' language follows. */
    val languageOf: String,
    /** Rules slot: what the facts must be present in. */
    val presentIn: String,
    /** Rules slot: what to cover whole. */
    val coverWhole: String,
    /**
     * The conversation-only echo-extraction rule, rendered as a full
     * rules-list line including its trailing newline (empty for
     * [USER_IMPORT]).
     */
    val echoRule: String,
) {
    CONVERSATION(
        header = """
You're extracting memories from a discarded conversation.
Extract **all** important information from the conversation history into a list of self-contained facts suitable for long-term memory.

Every user message opens with a <meta><sent-at>...</sent-at></meta> marker carrying that message's send time.
Resolve every relative date or time ("today", "last week", "in two months") against the send time of the message that contains it.
The messages can be much older than the moment of extraction, so never resolve against "now".
Assistant messages carry no marker: they reply immediately after the preceding user message.""".trimIndent(),
        languageOf = "conversation",
        presentIn = "history",
        coverWhole = "conversation",
        echoRule = "- Do not extract the assistant restating what the user said as a new fact (echo extraction)\n",
    ),
    USER_IMPORT(
        header = """
You're extracting memories from a submission the user provided for long-term memory: a piece of text and, when present, attached images.
Extract **all** important information from the text and the images into a list of self-contained facts suitable for long-term memory.

The input opens with a <meta><sent-at>...</sent-at></meta> marker carrying the input's reference time.
Resolve every relative date or time ("today", "last week", "in two months") against that reference time.
The reference time can be much older than the moment of extraction, so never resolve against "now".""".trimIndent(),
        languageOf = "text and images",
        presentIn = "text and images",
        coverWhole = "input",
        echoRule = "",
    ),
}
