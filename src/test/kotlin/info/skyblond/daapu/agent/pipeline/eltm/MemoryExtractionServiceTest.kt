package info.skyblond.daapu.agent.pipeline.eltm

import info.skyblond.daapu.agent.chat.*
import info.skyblond.daapu.agent.model.LLM
import info.skyblond.daapu.agent.model.ModelCapabilityException
import info.skyblond.daapu.agent.context.ContextInjection
import info.skyblond.daapu.memory.eltm.EltmToolProvider
import info.skyblond.daapu.agent.tool.EmptyToolProvider
import info.skyblond.daapu.hand.*
import info.skyblond.daapu.testutil.FakeEltmService
import info.skyblond.daapu.testutil.createEntityRound
import info.skyblond.daapu.testutil.testEltmWriterService
import info.skyblond.daapu.testutil.testHandService
import info.skyblond.daapu.testutil.testLlm
import info.skyblond.daapu.testutil.writerRunFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import kotlin.test.*

/**
 * Pins [MemoryExtractionService]'s two-stage pipeline: the extractor
 * one-shot (raw dropped history, anchored user messages, no tools) and the
 * ELTM writer tool loop that records the extracted facts into the diary
 * directly — the sentinel skip, the fail-fast capability check, and the
 * failure semantics that must fail the run instead of silently losing
 * memories (a retry re-extracts and the writer deduplicates).
 */
class MemoryExtractionServiceTest {

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
        eltmService: FakeEltmService = FakeEltmService(),
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
        val eltm = FakeEltmService()
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
            eltm.notes.values.map { it.note }.contains("likes coffee"),
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
        // the anchor renders the message's own instant in the system zone,
        // so the expected date is computed from it (never hard-coded)
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
        val eltm = FakeEltmService()
        val extractor = service(hand, eltm)
        extractor.processDiscardedMessages(listOf(userMessage("u1")))
        assertEquals(1, hand.requests.size, "only the extractor run happened")
        assertTrue(eltm.notes.isEmpty(), "a skipped extraction must not write the ELTM")
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
        val eltm = FakeEltmService()
        val extractor = service(hand, eltm)
        val e = assertFailsWith<IllegalStateException> {
            extractor.processDiscardedMessages(listOf(userMessage("u1")))
        }
        // the outer message names the wrapper only; the detail lives on the cause
        assertEquals("Memory extraction failed", e.message)
        val cause = assertIs<HandRunException>(e.cause)
        assertEquals("output_budget_exhausted", cause.type)
        assertEquals(1, hand.requests.size, "only the extractor run happened")
        assertTrue(eltm.notes.isEmpty(), "a truncated extraction must not reach the writer")
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
        val eltm = FakeEltmService()
        val extractor = service(hand, eltm)
        val e = assertFailsWith<IllegalStateException> {
            extractor.processDiscardedMessages(listOf(userMessage("u1")))
        }
        val cause = assertIs<HandRunException>(e.cause)
        assertEquals("empty_response", cause.type)
        assertEquals(1, hand.requests.size, "only the extractor run happened")
        assertTrue(eltm.notes.isEmpty(), "a failed extraction must not write the ELTM")
    }

    @Test
    fun `a writer round with no answer fails the pipeline and keeps the applied write`() = runBlocking {
        // the writer run's assistant answers with neither text nor tool
        // calls after one executed round: the run loop fails it as
        // empty_response, and the failed write must fail the whole pipeline
        var calls = 0
        val eltm = FakeEltmService()
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
        assertTrue(eltm.entities.values.any { it.canonicalName == "alice" })
    }
}