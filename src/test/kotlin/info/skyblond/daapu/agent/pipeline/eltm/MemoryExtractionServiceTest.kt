package info.skyblond.daapu.agent.pipeline.eltm

import info.skyblond.daapu.agent.chat.*
import info.skyblond.daapu.agent.model.LLM
import info.skyblond.daapu.agent.model.ModelCapabilityException
import info.skyblond.daapu.agent.context.ContextInjection
import info.skyblond.daapu.memory.eltm.EltmService
import info.skyblond.daapu.memory.eltm.EltmToolProvider
import info.skyblond.daapu.agent.tool.EmptyToolProvider
import info.skyblond.daapu.hand.*
import info.skyblond.daapu.testutil.DbTestBase
import info.skyblond.daapu.testutil.TestDb
import info.skyblond.daapu.testutil.createEntityRound
import info.skyblond.daapu.testutil.testPostgresEltmService
import info.skyblond.daapu.testutil.testEltmWriterService
import info.skyblond.daapu.testutil.testHandService
import info.skyblond.daapu.testutil.testLlm
import info.skyblond.daapu.testutil.writerRunFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import kotlin.test.*

/**
 * Pins [MemoryExtractionService]'s two-stage pipeline: the extractor
 * one-shot (raw dropped history, anchored user messages, no tools) and the
 * ELTM writer tool loop that records the extracted facts into the diary
 * directly — the sentinel skip, the fail-fast capability check, and the
 * failure semantics that must fail the run instead of silently losing
 * memories (a retry re-extracts and the writer deduplicates). Also pins
 * [MemoryExtractionService.processUserImport], the `/api/eltm/import` entry
 * point running the same extractor over one synthetic anchored message
 * (caller-supplied text and/or images) before writing.
 */
class MemoryExtractionServiceTest : DbTestBase() {

    private fun model(id: String) = testLlm(id)

    private fun userMessage(text: String) = ChatMessage(
        ChatMessageRole.User,
        listOf(ChatMessagePart.Text(text)),
        createdAt = Instant.parse("2026-08-17T09:00:00Z"),
    )

    private fun imageMessage() = ChatMessage(
        ChatMessageRole.User,
        listOf(
            ChatMessagePart.Attachment(
                kind = AttachmentKind.Image,
                content = AttachmentContent.Base64("AAAA"),
                mimeType = "image/png",
            )
        ),
    )

    private fun service(
        hand: FakeHand,
        eltmService: EltmService = testPostgresEltmService(FakeHand()),
        maxWriterRounds: Int = 150,
        extractModel: LLM = model("bifrost/cerebras/gemma-4-31b"),
    ) = MemoryExtractionService(
        extractModel = extractModel,
        hand = testHandService(hand),
        policy = HandRunPolicy(0, 0),
        eltmWriterService = testEltmWriterService(hand, eltmService, maxWriterRounds),
    )

    @Test
    fun `processDiscardedMessages runs the extractor and the writer tool loop`() = runBlocking {
        // run 1 = extractor (fact text), run 2 = the ELTM writer tool loop:
        // one create_entity + add_entity_note round (executed through the
        // real EltmToolProvider, standing in for the hand's tool callback)
        // then the final confirmation
        val eltm = testPostgresEltmService(FakeHand())
        val hand = FakeHand(
            runScript = { request ->
                when {
                    request.systemPrompt?.startsWith("You're extracting") == true -> textRunFlow("likes coffee")
                    request.systemPrompt?.startsWith("You're maintaining an external long-term memory") == true ->
                        writerRunFlow(eltm)

                    else -> error("unexpected run in the extraction pipeline")
                }
            },
        )
        val extractor = service(hand, eltm)
        extractor.processDiscardedMessages(listOf(userMessage("u1"), userMessage("u2")))

        // the extracted facts land in the ELTM diary (the writer run created
        // the canonical user entity and attached the note)
        assertTrue(
            TestDb.allEltmNotes().map { it.note }.contains("likes coffee"),
            "the extracted fact must be recorded into the ELTM diary",
        )
        assertEquals(2, hand.requests.size, "extractor run + one writer run")
        // the writer run carries the round cap; no static tool list travels
        // in the request anymore (the per-round GET /api/hand/tools listing
        // serves the provider's tools, pinned by HandCallbackTest)
        assertEquals(150, hand.requests[1].maxRounds)

        // the extractor is stateless: no "today" anywhere in the prompt, and
        // the dropped user messages carry their own send-time <meta> anchors
        // (anchors only — a one-shot never gets a full context injection),
        // so relative dates resolve per message instead of against the
        // extraction time
        val contextInjection = ContextInjection()
        val extractorPrompt = hand.requests[0].systemPrompt!!
        assertTrue(extractorPrompt.startsWith("You're extracting"))
        assertFalse(
            extractorPrompt.contains("Today's date"),
            "the extractor must resolve dates from the message anchors, not the extraction time: $extractorPrompt",
        )
        val extractorMessages = hand.requests[0].messages
        assertEquals(
            listOf(ChatMessageRole.User, ChatMessageRole.User, ChatMessageRole.User),
            extractorMessages.map { it.role },
            "dropped messages + the extraction instruction",
        )
        val anchor = extractorMessages[0].parts.first() as ChatMessagePart.Text
        assertTrue(contextInjection.hasMetaPart(extractorMessages[0]))
        // expected date computed from the anchor's own instant (never hard-coded)
        val anchorDate = java.time.ZonedDateTime.ofInstant(
            Instant.parse("2026-08-17T09:00:00Z"),
            java.time.ZoneId.systemDefault(),
        ).toLocalDate().toString()
        assertTrue(
            anchor.text.contains(anchorDate),
            "the anchor must render the message's own send time, got: ${anchor.text}",
        )
        assertEquals(
            listOf(ChatMessagePart.Text("u1")),
            extractorMessages[0].parts.drop(1),
            "the anchor is prepended, the original content untouched",
        )
        assertFalse(
            extractorMessages.any { message ->
                message.parts.firstOrNull() is ChatMessagePart.Text &&
                        contextInjection.isInjection(message.parts.first() as ChatMessagePart.Text)
            },
            "the extractor must not receive a full context injection",
        )

        // the writer run's user message carries the extracted facts verbatim
        // under a current date header (the only source the writer may record)
        val writerPrompt = hand.requests[1].systemPrompt!!
        assertTrue(writerPrompt.startsWith("You're maintaining an external long-term memory"))
        val writerInput = (hand.requests[1].messages.single().parts.single()
            as ChatMessagePart.Text).text
        assertTrue(
            Regex("^Current date: \\d{4}-\\d{2}-\\d{2}\n\n").containsMatchIn(writerInput),
            "the writer input must carry a current date header: $writerInput"
        )
        assertTrue(writerInput.contains("likes coffee"), "the writer input must carry the facts verbatim: $writerInput")
    }

    @Test
    fun `the nothing-worth-remembering sentinel skips the writer`() = runBlocking {
        val hand = FakeHand(
            runScript = { textRunFlow("Nothing worth remember.") },
        )
        val eltm = testPostgresEltmService(FakeHand())
        val extractor = service(hand, eltm)
        extractor.processDiscardedMessages(listOf(userMessage("u1")))
        assertEquals(1, hand.requests.size, "only the extractor run happened")
        assertTrue(TestDb.allEltmNotes().isEmpty(), "a skipped extraction must not write the ELTM")
    }

    @Test
    fun `isNothingToRemember tolerates casing punctuation and whitespace runs`() {
        // the tolerant sentinel match (see MemoryExtractionService
        // .isNothingToRemember): a near-miss must not reach the writer as a
        // fact batch
        assertTrue(MemoryExtractionService.isNothingToRemember("Nothing worth remember."))
        assertTrue(MemoryExtractionService.isNothingToRemember("nothing worth remember"))
        assertTrue(MemoryExtractionService.isNothingToRemember("  NOTHING  WORTH REMEMBER.  "))
        assertTrue(MemoryExtractionService.isNothingToRemember("Nothing worth remember!"))
        // a paraphrase is not the sentinel: the writer's skip-sentinel rule
        // is the backstop for those
        assertFalse(MemoryExtractionService.isNothingToRemember("Nothing worth remembering."))
        assertFalse(MemoryExtractionService.isNothingToRemember("User likes coffee"))
        assertFalse(MemoryExtractionService.isNothingToRemember("The note says \"Nothing worth remember.\""))
    }

    @Test
    fun `the discard skip tolerates a near-miss sentinel variant`() = runBlocking {
        val hand = FakeHand(
            runScript = { textRunFlow("NOTHING WORTH REMEMBER") },
        )
        val eltm = testPostgresEltmService(FakeHand())
        val extractor = service(hand, eltm)
        extractor.processDiscardedMessages(listOf(userMessage("u1")))
        assertEquals(1, hand.requests.size, "the near-miss sentinel still skips the writer")
        assertTrue(TestDb.allEltmNotes().isEmpty(), "a near-miss sentinel must not write the ELTM")
    }

    @Test
    fun `the conversation extractor prompt is pinned byte-identical for the discard path`() {
        // the discard pipeline's extraction must never drift with
        // import-path changes: editing this literal is a conscious change
        // to the discard path
        assertEquals(
            """
You're extracting memories from a discarded conversation.
Extract **all** important information from the conversation history into a list of self-contained facts suitable for long-term memory.

Every user message opens with a <meta><sent-at>...</sent-at></meta> marker carrying that message's send time.
Resolve every relative date or time ("today", "last week", "in two months") against the send time of the message that contains it.
The messages can be much older than the moment of extraction, so never resolve against "now".
Assistant messages carry no marker: they reply immediately after the preceding user message.
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
- Write facts in the same language as the conversation
- Do not invent details that are not present in the history
- Merge overlapping information into one fact
- Cover the whole conversation, not just the first topic
- Do not extract the assistant restating what the user said as a new fact (echo extraction)
- Extract the content of documents or code the user shared, not "the user shared a document" (meta extraction)
- When nothing is worth remembering, output sentence "Nothing worth remember."
""".trimIndent().trim(),
            MemoryExtractionService.renderExtractorSystemPrompt(ExtractionInput.CONVERSATION),
        )
    }

    @Test
    fun `the writer system prompt is pinned byte-identical for both write paths`() {
        // the writer serves BOTH write paths (the discard pipeline and the
        // import route): editing this literal is a conscious change to both,
        // the same drift guard the extractor prompt's pin above provides
        assertEquals(
            """
You're maintaining an external long-term memory (ELTM) knowledge store.
You are given candidate facts for long-term memory. Preserve their information by writing it into the ELTM.

The ELTM has four kinds of records:
- Entities: a named thing with a category (e.g. name "Apple" with category "fruit" vs "company"). A group of people can be one entity.
- Relationships: a directed edge (source entity, verb, destination entity). Use consistent, general, timeless verbs: "colleague_of", not "became_colleague_of". Create or fetch them with create_relationship; their validity (active/ended) only changes through a diary note (add_relationship_note's valid flag).
- Attributes: structured key-value facts about ONE entity: its current-state identity (e.g. a person's realname and nickname, a device's model). One value per (entity, key): setting the same key again overwrites. They are embedded with the entity, so facts are semantically searchable.
- Notes: dated diary entries attached to exactly ONE entity or ONE relationship. Dated narrative events and descriptions live here. You should ONLY attach a note to a relationship if the event is related to both the entities AND the verb.

Rules:
- Record only information explicitly present in the input. Never invent details.
- If the input is only the skip sentinel "Nothing worth remember." (any casing or trivial rewording), there is nothing to record: reply with a short confirmation and make no tool calls.
- "The user" maps to the canonical entity with name "user" (category "person").
- When new entity should be created but without a defined name, include words like "unknown", "unspecified" in the name with some description. E.g. "unknown chinese company", or "unspecified female friend", etc.
- Timeless structured facts about an entity (model, realname, nickname, serial numbers, etc.) are ATTRIBUTES (set_entity_attribute), not notes. Use notes only for dated events and narrative. Attribute values must be a single line. Before setting an attribute, check the entity's current attributes (search_entities renders them in the hits) and skip facts already recorded.
- Before creating an entity, call search_entities to find existing ones. create_entity returns near matches: if one of them is the same thing, use that id; if you discover true duplicates, call merge_entities with the better-canonical entity as winner_id. To refine an EXISTING entity's identity (e.g. a placeholder "friend" now identified as "Alice", or a re-categorization), call refine_entity with its id and the new name and/or category: the entity keeps its id, so its notes, relationships and attributes stay attached. Only merge when two entities are true duplicates.
- A note belongs to exactly one subject. An event about a relationship (met, broke up, started working together) attaches to that relationship. An event about one entity attaches to that entity. It may mention other entities by name in the text, but must NOT be duplicated under each of them.
- Notes are add-only. To correct or supersede older information, add a NEW note with the current fact and its date. If a relationship no longer holds, add a note to it explaining the ending and pass valid=false; if a previously-ended relationship holds again (e.g. rejoined the company), add a note about the new event and pass valid=true. Only change validity on genuine endings.
- Before adding a note about a subject, check its recent notes with get_entity_notes / get_relationship_notes (or search_notes to find already-recorded content) and skip content that is already recorded: a retried run must not duplicate diary entries.
- event_date is the absolute date the event happened (YYYY-MM-DD); use today's date when unknown.
- Keep notes self-contained and concise (3-5 sentences; names, numbers, and ids verbatim). If add_entity_note or add_relationship_note fails with a "too large" embedding error, split the content into several smaller notes.
- When everything is recorded, reply with a short confirmation and make no further tool calls.
""".trimIndent().trim(),
            EltmWriterService.renderWriterSystemPrompt(),
        )
    }

    @Test
    fun `the import extractor prompt describes the provided input without conversation-only rules`() {
        val prompt = MemoryExtractionService.renderExtractorSystemPrompt(ExtractionInput.USER_IMPORT)
        // the fake-hand dispatch prefix (see the pipeline tests) must hold
        assertTrue(
            prompt.startsWith("You're extracting memories from a submission the user provided"),
            prompt,
        )
        // the anchor mechanics stay, reworded for one submitted input
        // (text and, when present, attached images)
        assertTrue(prompt.contains("<meta><sent-at>"), prompt)
        assertTrue(prompt.contains("the input's reference time"), prompt)
        assertTrue(prompt.contains("never resolve against \"now\""), prompt)
        // the input-neutral focus list and fact rules survive verbatim
        assertTrue(prompt.contains("Focus on:"), prompt)
        assertTrue(prompt.contains("replace pronouns with the entity name or \"the user\""), prompt)
        assertTrue(prompt.contains("same language as the text and images"), prompt)
        assertTrue(prompt.contains("Cover the whole input"), prompt)
        assertTrue(
            prompt.contains(
                "When nothing is worth remembering, output sentence " +
                        "\"${MemoryExtractionService.NOTHING_TO_REMEMBER_TEXT}\""
            ),
            prompt,
        )
        // the conversation-only lines are gone
        assertFalse(prompt.contains("discarded conversation"), prompt)
        assertFalse(prompt.contains("Assistant messages"), prompt)
        assertFalse(prompt.contains("echo extraction"), prompt)
    }

    @Test
    fun `processUserImport anchors the input as one synthetic user message at the reference date`() = runBlocking {
        val eltm = testPostgresEltmService(FakeHand())
        val hand = FakeHand(
            runScript = { request ->
                when {
                    request.systemPrompt?.startsWith("You're extracting") == true -> textRunFlow("User likes coffee")
                    request.systemPrompt?.startsWith("You're maintaining an external long-term memory") == true ->
                        writerRunFlow(eltm)

                    else -> error("unexpected run: ${request.systemPrompt}")
                }
            },
        )
        val extractor = service(hand, eltm)
        extractor.processUserImport("I like coffee", emptyList(), LocalDate.parse("2026-01-01"))

        assertEquals(2, hand.requests.size, "extractor run + one writer run")
        assertTrue(
            hand.requests[0].systemPrompt!!.startsWith("You're extracting memories from a submission"),
            "the import extraction uses the user-import-flavored prompt: ${hand.requests[0].systemPrompt}",
        )
        // the extraction one-shot received the text as ONE user message
        // carrying its reference-date anchor (relative dates resolve
        // against it), followed by the extraction instruction
        val messages = hand.requests[0].messages
        assertEquals(2, messages.size, "the synthetic text message + the extraction instruction")
        val contextInjection = ContextInjection()
        assertTrue(
            contextInjection.hasMetaPart(messages[0]),
            "the synthetic message carries its reference-date anchor",
        )
        val anchor = messages[0].parts.first() as ChatMessagePart.Text
        assertTrue(
            anchor.text.contains("2026-01-01T00:00:00"),
            "the anchor renders the reference date at start of day (server zone): ${anchor.text}",
        )
        assertEquals(
            listOf(ChatMessagePart.Text("I like coffee")),
            messages[0].parts.drop(1),
            "the anchor is prepended, the text untouched",
        )
        // the extracted facts reach the writer and land in the ELTM diary
        assertTrue(
            (hand.requests[1].messages.single().parts.single() as ChatMessagePart.Text)
                .text.contains("User likes coffee"),
            "the writer receives the extractor's output",
        )
        assertTrue(
            TestDb.allEltmNotes().map { it.note }.contains("likes coffee"),
            "the extracted fact must be recorded into the ELTM diary",
        )
    }

    @Test
    fun `processUserImport skips the writer when the extraction answers the sentinel`() = runBlocking {
        val eltm = testPostgresEltmService(FakeHand())
        val hand = FakeHand(runScript = { textRunFlow("Nothing worth remember.") })
        val extractor = service(hand, eltm)
        extractor.processUserImport("we just chatted about the weather", emptyList(), LocalDate.parse("2026-01-01"))
        assertEquals(1, hand.requests.size, "only the extractor run happens")
        assertTrue(TestDb.allEltmNotes().isEmpty(), "a sentinel extraction must not write the ELTM")
    }

    @Test
    fun `processUserImport no-ops a blank or pasted-sentinel text without an LLM call`() = runBlocking {
        val hand = FakeHand(runScript = { error("the LLM must not be called") })
        val extractor = service(hand)
        // a blank text (the route 400s first; the service guards
        // defensively) and a pasted sentinel — exact or near-miss, the
        // tolerant match (see MemoryExtractionService.isNothingToRemember)
        // — are all silent no-ops without any LLM call
        extractor.processUserImport("   ", emptyList(), LocalDate.parse("2026-01-01"))
        extractor.processUserImport("Nothing worth remember.", emptyList(), LocalDate.parse("2026-01-01"))
        extractor.processUserImport("nothing worth remember", emptyList(), LocalDate.parse("2026-01-01"))
        assertTrue(hand.requests.isEmpty(), "no LLM call for a blank text or a pasted sentinel")
        assertTrue(TestDb.allEltmNotes().isEmpty(), "a no-op import must not write the ELTM")
    }

    @Test
    fun `processUserImport passes the text and images to the extractor as one synthetic message`() = runBlocking {
        val eltm = testPostgresEltmService(FakeHand())
        val hand = FakeHand(
            runScript = { request ->
                when {
                    request.systemPrompt?.startsWith("You're extracting") == true -> textRunFlow("User likes coffee")
                    request.systemPrompt?.startsWith("You're maintaining an external long-term memory") == true ->
                        writerRunFlow(eltm)

                    else -> error("unexpected run: ${request.systemPrompt}")
                }
            },
        )
        val extractor = service(hand, eltm)
        extractor.processUserImport(
            "I like coffee",
            listOf("data:image/png;base64,AAAA"),
            LocalDate.parse("2026-01-01"),
        )

        assertEquals(2, hand.requests.size, "extractor run + one writer run")
        // the synthetic input message carries the reference-date anchor, the
        // text and the parsed image attachment (composer order: text, then
        // images), followed by the extraction instruction
        val messages = hand.requests[0].messages
        assertEquals(2, messages.size, "the synthetic input message + the extraction instruction")
        val parts = messages[0].parts
        assertTrue(ContextInjection().hasMetaPart(messages[0]), "the anchor is prepended")
        assertEquals(ChatMessagePart.Text("I like coffee"), parts[1], "the text follows the anchor untouched")
        val attachment = assertIs<ChatMessagePart.Attachment>(parts[2])
        assertEquals(AttachmentKind.Image, attachment.kind, "the data URL parses into an image attachment")
        assertEquals(AttachmentContent.Base64("AAAA"), attachment.content)
        assertEquals("image/png", attachment.mimeType)
        // the extracted facts reach the writer and land in the ELTM diary
        assertTrue(
            TestDb.allEltmNotes().map { it.note }.contains("likes coffee"),
            "the extracted fact must be recorded into the ELTM diary",
        )
    }

    @Test
    fun `processUserImport runs the extraction for a blank text when images are attached`() = runBlocking {
        // images must never be silently skipped: an images-only import (no
        // text) runs the pipeline exactly like a text import
        val eltm = testPostgresEltmService(FakeHand())
        val hand = FakeHand(
            runScript = { request ->
                when {
                    request.systemPrompt?.startsWith("You're extracting") == true -> textRunFlow("User likes coffee")
                    request.systemPrompt?.startsWith("You're maintaining an external long-term memory") == true ->
                        writerRunFlow(eltm)

                    else -> error("unexpected run: ${request.systemPrompt}")
                }
            },
        )
        val extractor = service(hand, eltm)
        extractor.processUserImport("   ", listOf("data:image/png;base64,AAAA"), LocalDate.parse("2026-01-01"))
        assertEquals(2, hand.requests.size, "the images force the extractor + writer runs")
        // no text part: the synthetic message carries only the anchor + the attachment
        val parts = hand.requests[0].messages[0].parts
        assertTrue(ContextInjection().hasMetaPart(hand.requests[0].messages[0]), "the anchor is prepended")
        assertIs<ChatMessagePart.Attachment>(parts[1])
        assertTrue(
            TestDb.allEltmNotes().map { it.note }.contains("likes coffee"),
            "the extracted fact must be recorded into the ELTM diary",
        )
    }

    @Test
    fun `processUserImport runs the extraction for a pasted sentinel when images are attached`() = runBlocking {
        val eltm = testPostgresEltmService(FakeHand())
        val hand = FakeHand(runScript = { textRunFlow("Nothing worth remember.") })
        val extractor = service(hand, eltm)
        // the sentinel skips the WRITE only: with images attached, the
        // extraction must still run (the images may be worth remembering)
        extractor.processUserImport(
            "Nothing worth remember.",
            listOf("data:image/png;base64,AAAA"),
            LocalDate.parse("2026-01-01"),
        )
        assertEquals(1, hand.requests.size, "the extraction runs despite the sentinel text")
        assertTrue(TestDb.allEltmNotes().isEmpty(), "a sentinel extraction must not write the ELTM")
    }

    @Test
    fun `processUserImport fails fast when the model cannot see the images`() = runBlocking {
        val hand = FakeHand(runScript = { error("the LLM must not be called") })
        val textOnly = model("bifrost/cerebras/gpt-oss-120b")
        val extractor = service(hand, extractModel = textOnly)
        // a text-only extraction model with an attached image: the
        // capability mismatch is a configuration error
        // (memory.eltm.extractionModel) and must fail the import, not
        // silently skip the images
        val e = assertFailsWith<ModelCapabilityException> {
            extractor.processUserImport(
                "look at this",
                listOf("data:image/png;base64,AAAA"),
                LocalDate.parse("2026-01-01"),
            )
        }
        assertTrue(
            e.message!!.contains("image"),
            "the error should name the unsupported kind: ${e.message}"
        )
        assertTrue(hand.requests.isEmpty(), "no LLM call for an incapable model")
    }

    @Test
    fun `processUserImport rejects a malformed image data URL without an LLM call`() = runBlocking {
        val hand = FakeHand(runScript = { error("the LLM must not be called") })
        val extractor = service(hand)
        // the same parseImageDataUrl contract ChatService.prepareRun uses:
        // a malformed data URL is a client error before any LLM call
        listOf("not-a-data-url", "data:text/plain;base64,AAAA", "data:image/png;base64,!!!").forEach { url ->
            assertFailsWith<ChatValidationException> {
                extractor.processUserImport("text", listOf(url), LocalDate.parse("2026-01-01"))
            }
        }
        assertTrue(hand.requests.isEmpty(), "no LLM call for a malformed data URL")
    }

    @Test
    fun `extraction fails fast when the model cannot see the content`() = runBlocking {
        val hand = FakeHand(runScript = { error("the LLM must not be called") })
        val textOnly = model("bifrost/cerebras/gpt-oss-120b")
        val extractor = service(hand, extractModel = textOnly)
        // a text-only extraction model with an image in the dropped
        // history: the capability mismatch is a configuration error
        // (memory.eltm.extractionModel) and must fail the run, not silently
        // skip the extraction
        val e = assertFailsWith<ModelCapabilityException> {
            extractor.processDiscardedMessages(listOf(imageMessage()))
        }
        assertTrue(
            e.message!!.contains("image"),
            "the error should name the unsupported kind: ${e.message}"
        )
        assertTrue(hand.requests.isEmpty(), "no LLM call for an incapable model")
    }

    @Test
    fun `a truncated extractor round fails the extraction`() = runBlocking {
        // a truncated extractor response is a broken extraction: it fails
        // the run instead of feeding the writer
        val hand = FakeHand(
            runScript = {
                errorRunFlow("output_budget_exhausted", "output hit the token budget")
            },
        )
        val eltm = testPostgresEltmService(FakeHand())
        val extractor = service(hand, eltm)
        val e = assertFailsWith<IllegalStateException> {
            extractor.processDiscardedMessages(listOf(userMessage("u1")))
        }
        assertEquals("Memory extraction failed", e.message)
        val cause = assertIs<HandRunException>(e.cause)
        assertEquals("output_budget_exhausted", cause.type)
        assertEquals(1, hand.requests.size, "only the extractor run happened")
        assertTrue(TestDb.allEltmNotes().isEmpty(), "a truncated extraction must not reach the writer")
    }

    @Test
    fun `an extraction that produces a tool call fails instead of skipping`() = runBlocking {
        // the extractor declares no tools [EmptyToolProvider]: a stray tool
        // call is answered with an isError result, and the run ends with
        // empty_response (a blank/skipped extraction must never silently
        // skip the write — only the sentinel may)
        val round = assistantMessage(
            parts = listOf(
                ChatMessagePart.ToolCall(
                    id = "call_1",
                    tool = "search_entities",
                    args = buildJsonObject { put("query", "x") },
                )
            ),
            finishReason = "tool_calls",
        )
        val hand = FakeHand(
            runScript = {
                listOf(HandEvent.AssistantMessage(round)) +
                        toolRoundEvents(round, EmptyToolProvider) +
                        listOf(
                            HandEvent.RunError(
                                "empty_response",
                                "assistant finished with neither text nor tool calls"
                            )
                        )
            },
        )
        val eltm = testPostgresEltmService(FakeHand())
        val extractor = service(hand, eltm)
        val e = assertFailsWith<IllegalStateException> {
            extractor.processDiscardedMessages(listOf(userMessage("u1")))
        }
        val cause = assertIs<HandRunException>(e.cause)
        assertEquals("empty_response", cause.type)
        assertEquals(1, hand.requests.size, "only the extractor run happened")
        assertTrue(TestDb.allEltmNotes().isEmpty(), "a failed extraction must not write the ELTM")
    }

    @Test
    fun `a writer round with no answer fails the pipeline and keeps the applied write`() = runBlocking {
        // the writer run's assistant answers with neither text nor tool
        // calls after one executed round: the run loop fails it as
        // empty_response, and the failed write must fail the whole pipeline
        var calls = 0
        val eltm = testPostgresEltmService(FakeHand())
        val hand = FakeHand(
            runScript = {
                when (++calls) {
                    1 -> textRunFlow("likes coffee")
                    else -> {
                        val round = createEntityRound("call_1", "alice")
                        listOf(HandEvent.AssistantMessage(round)) +
                                toolRoundEvents(round, EltmToolProvider(eltm)) +
                                listOf(
                                    HandEvent.RunError(
                                        "empty_response",
                                        "assistant finished with neither text nor tool calls"
                                    )
                                )
                    }
                }
            },
        )
        val extractor = service(hand, eltm)
        val e = assertFailsWith<IllegalStateException> {
            extractor.processDiscardedMessages(listOf(userMessage("u1")))
        }
        assertEquals("ELTM write failed", e.message)
        val cause = assertIs<HandRunException>(e.cause)
        assertEquals("empty_response", cause.type)
        // the write applied before the failure stays; a retry re-extracts
        // and the writer deduplicates against the store
        assertTrue(TestDb.allEltmEntities().any { it.canonicalName == "alice" })
    }
}
