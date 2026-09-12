package info.skyblond.daapu.agent.pipeline.eltm

import info.skyblond.daapu.agent.chat.AttachmentKind
import info.skyblond.daapu.agent.chat.ChatMessage
import info.skyblond.daapu.agent.chat.ChatMessagePart
import info.skyblond.daapu.agent.chat.ChatMessageRole
import info.skyblond.daapu.agent.context.ContextInjection
import info.skyblond.daapu.agent.model.LLM
import info.skyblond.daapu.agent.pipeline.runOneShotText
import info.skyblond.daapu.hand.HandRunPolicy
import info.skyblond.daapu.hand.HandService
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.LocalDate
import java.time.ZoneId

/**
 * The EXTRACTOR stage of the memory pipeline (the two-stage whole and its
 * entry points: [MemoryExtractionService]): one stateless hand `/v1/run`
 * call (no tools) with the raw dropped history or the digest input plus
 * the extraction system prompt ([renderExtractorSystemPrompt]), returning
 * the free-text list of candidate facts (or the [NOTHING_TO_REMEMBER_TEXT]
 * sentinel). Split out of the service so consumers that only need the
 * extraction (the `script/digest/DigestLLMChat.kt` replay) can run it
 * WITHOUT the ELTM writer half and its database.
 *
 * One instance is stateless and safe to share across concurrent calls.
 *
 * A failure throws:
 * - [info.skyblond.daapu.agent.model.ModelCapabilityException] when the
 *   extraction model cannot process the prompt content (e.g. images with a
 *   text-only model) — a configuration error (`memory.eltm.extractionModel`)
 *   that fails fast;
 * - [IllegalStateException] (label "Memory extraction") for a failed
 *   extraction: a classified hand error such as a truncated `length`
 *   finish, or a run producing tool calls or no text.
 */
class MemoryExtractor(
    private val extractModel: LLM,
    private val hand: HandService,
    // config `hand.*` — see [HandRunPolicy]
    private val policy: HandRunPolicy,
    // the harness context: sanitize the dropped history (it may be the chat
    // loop's injected in-loop chat) and anchor every user message with its
    // send time, so the extractor resolves relative dates per message and
    // never against the extraction time
    private val contextInjection: ContextInjection = ContextInjection(),
) {
    /**
     * The extraction stage alone: run the extractor over [droppedMessages]
     * and return the free-text fact list (or the [NOTHING_TO_REMEMBER_TEXT]
     * sentinel when nothing is worth remembering) WITHOUT writing anything
     * — [MemoryExtractionService.processDiscardedMessages] decides whether
     * to write; `script/digest/DigestLLMChat.kt` replays a foreign chat
     * through this in batches.
     *
     * Throws per the class KDoc (a capability mismatch is a configuration
     * error and fails fast; a failed extraction throws
     * [IllegalStateException]). [droppedMessages] must not be empty.
     */
    suspend fun extractFacts(droppedMessages: List<ChatMessage>): String {
        require(droppedMessages.isNotEmpty()) {
            "cannot extract memories from an empty message list"
        }
        val extraction = extract(droppedMessages, ExtractionInput.CONVERSATION)
        logger.info { "Extracted memories:\n${extraction}" }
        return extraction
    }

    /**
     * The digest path's extraction stage alone
     * ([MemoryExtractionService.digestUserInput] decides whether to write):
     * run the same extractor over caller-supplied [parts] instead of a
     * dropped history. The parts become ONE synthetic user message in the
     * given order (an interleaved email/document keeps its shape), stamped
     * with [referenceDate] (start of day in the server's current zone), so
     * the stateless extractor resolves the input's relative dates against
     * the reference date — never against the extraction time — and maps its
     * first-person pronouns to "the user" (a user message's author IS the
     * user), exactly as it does for a discarded conversation. Blank text
     * parts are dropped; at least one meaningful part is required, and
     * attachments must be images (the route 400s the rest — this guards
     * direct callers). Returns the free-text fact list or the
     * [NOTHING_TO_REMEMBER_TEXT] sentinel. Throws per the class KDoc
     * (images trip the capability check [extract] runs on every flavor).
     */
    internal suspend fun extractFactsFromDigest(
        parts: List<ChatMessagePart>,
        referenceDate: LocalDate,
    ): String {
        // blank text parts carry no content and would only pad the prompt
        val meaningful = parts.filterNot { it is ChatMessagePart.Text && it.text.isBlank() }
        require(meaningful.isNotEmpty()) { "cannot extract memories from a blank text without images" }
        require(
            meaningful.all { it !is ChatMessagePart.Attachment || it.kind == AttachmentKind.Image }
        ) { "only image attachments can be digested" }
        val message = ChatMessage(
            ChatMessageRole.User,
            meaningful,
            createdAt = referenceDate.atStartOfDay(ZoneId.systemDefault()).toInstant(),
        )
        val extraction = extract(listOf(message), ExtractionInput.USER_DIGEST)
        logger.info { "Extracted memories from digested input:\n${extraction}" }
        return extraction
    }

    /**
     * The extraction call: the raw dropped history plus the extraction
     * instruction. The history is treated as potentially injected (it may
     * come from the chat loop's injected in-loop chat): sanitize first, then
     * anchor every user message with its send time. [input] selects the
     * extractor prompt flavor (the discard pipeline always gets
     * [ExtractionInput.CONVERSATION]; the digest path's single synthetic
     * message gets [ExtractionInput.USER_DIGEST]). The extractor is
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
         * check: the discard pipeline's skip
         * ([MemoryExtractionService.processDiscardedMessages]) and the
         * digest path's input fast path plus post-extraction check
         * ([MemoryExtractionService.digestUserInput]). Trims, ignores
         * casing, trailing punctuation and whitespace runs — a near-miss
         * must not reach the writer as a fact batch. Paraphrases this
         * check cannot catch are the writer's own skip-sentinel rule's
         * job (see [EltmWriterService]).
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
         * header (framing + anchor explanation +, for [ExtractionInput.USER_DIGEST],
         * the optional user-provided-context explanation) and the few
         * conversation-specific rule lines come from the [input]'s slots.
         * [ExtractionInput.CONVERSATION] renders byte-identical to the
         * prompt the discard pipeline always used (pinned by
         * MemoryExtractionServiceTest), so the digest path's
         * [ExtractionInput.USER_DIGEST] flavor can never drift the discard
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
- When multiple distinct things share a name, include the distinguishing context (e.g. employer, project, city) whenever the name appears in a fact
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
 * [MemoryExtractor.renderExtractorSystemPrompt]): the discard pipeline's
 * dropped history ([CONVERSATION]) or the digest path's single synthetic
 * message of caller-supplied text and image parts in the given order
 * ([USER_DIGEST], see `MemoryExtractionService.digestUserInput`). The
 * fields are the prompt's per-input slots; everything else in the template
 * is shared verbatim. The [USER_DIGEST] wording covers the whole input
 * shape — interleaved text and images — so the same flavor serves
 * text-only, images-only and mixed digests.
 */
internal enum class ExtractionInput(
    /**
     * The header block: framing, the anchor/relative-date explanation, and
     * (USER_DIGEST only) the optional user-provided-context explanation.
     */
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
     * [USER_DIGEST]).
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
    USER_DIGEST(
        header = """
You're extracting memories from a submission the user provided for long-term memory.
Extract **all** important information from every part, in the order given, into a list of self-contained facts suitable for long-term memory.
If the input is already a list of facts, repeat it as-is (make sure don't lose any information).

The input opens with a <meta><sent-at>...</sent-at></meta> marker carrying the input's reference time.
Resolve every relative date or time ("today", "last week", "in two months") against that reference time.
The reference time can be much older than the moment of extraction, so never resolve against "now".

The user's content may open with a `<user-provided-context>` block to provide some context or explanation of what the input is (e.g. an email, a PDF, a contract). Its content is free-form, not strict XML. Treat everything between the tags as the explanation. Use it to interpret the rest of the input; it is user-provided information like any other part, so facts may draw on it. Do not record "the user provided context about X" as a fact.""".trimIndent(),
        languageOf = "input",
        presentIn = "input",
        coverWhole = "input",
        echoRule = "",
    ),
}
