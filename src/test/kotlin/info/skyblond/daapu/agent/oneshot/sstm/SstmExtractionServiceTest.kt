package info.skyblond.daapu.agent.oneshot.sstm

import info.skyblond.daapu.agent.ModelCatalog
import info.skyblond.daapu.agent.chat.*
import info.skyblond.daapu.agent.model.ModelCapabilityException
import info.skyblond.daapu.agent.model.ModelProvider
import info.skyblond.daapu.agent.tool.ToolCallRequest
import info.skyblond.daapu.hand.*
import info.skyblond.daapu.memory.sstm.ShortTermMemory
import info.skyblond.daapu.memory.sstm.SstmService
import info.skyblond.daapu.testutil.RecordingSstmService
import info.skyblond.daapu.testutil.addMemoryRound
import info.skyblond.daapu.testutil.testHandService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import kotlin.test.*

class SstmExtractionServiceTest {

    private fun model(id: String) = ModelCatalog(
        mapOf("bifrost" to ModelProvider("bifrost", "http://127.0.0.1:9/v1", "test"))
    ).findModel(id)!!

    private fun userMessage(text: String) =
        ChatMessage(ChatMessageRole.User, listOf(ChatMessagePart.Text(text)))

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
        sstm: SstmService,
        maxMergeRounds: Int = 150,
    ) = SstmExtractionService(
        extractModel = model("bifrost/cerebras/gemma-4-31b"),
        hand = testHandService(hand),
        sstmService = sstm,
        maxMergeRounds = maxMergeRounds,
        maxRetries = 0,
        streamIdleTimeoutMs = 0,
    )

    // ------------------------------------------------------------------
    // MergeMemoryToolProvider
    // ------------------------------------------------------------------

    @Test
    fun `memory tools execute against the service and track modification`() = runBlocking {
        val sstm = RecordingSstmService(listOf(ShortTermMemory(1, Instant.EPOCH, "old fact")))
        val provider = MergeMemoryToolProvider(sstm)

        val listResult = provider.execute(toolCall("c1", "list_memories", JsonObject(emptyMap())))
        assertEquals(
            "## Memory 1\nold fact",
            (listResult.parts.single() as ChatMessagePart.Text).text
        )
        assertFalse(listResult.isError)

        val addResult = provider.execute(
            toolCall(
                "c2",
                "add_memory",
                buildJsonObject { put("content", "likes coffee") })
        )
        assertFalse(addResult.isError)
        assertEquals(listOf("likes coffee"), sstm.created)

        val updateResult = provider.execute(
            toolCall(
                "c3",
                "update_memory",
                buildJsonObject { put("id", "1"); put("content", "loves coffee") })
        )
        assertFalse(updateResult.isError)
        assertEquals(listOf(1L to "loves coffee"), sstm.updated)

        val deleteResult =
            provider.execute(toolCall("c4", "delete_memory", buildJsonObject { put("id", "1") }))
        assertFalse(deleteResult.isError)
        assertEquals(listOf(1L), sstm.deleted)
    }

    @Test
    fun `memory tools answer errors without modifying`() = runBlocking {
        val sstm = RecordingSstmService()
        val provider = MergeMemoryToolProvider(sstm)

        // missing required arguments (the wire format guarantees parsed
        // JSON objects, so malformed JSON cannot reach the tool anymore)
        val badArgs = provider.execute(toolCall("c1", "add_memory", JsonObject(emptyMap())))
        assertTrue(badArgs.isError)

        val unknown = provider.execute(toolCall("c2", "nope", JsonObject(emptyMap())))
        assertTrue(unknown.isError)
    }

    private fun toolCall(id: String, name: String, arguments: JsonObject) =
        ToolCallRequest(id = id, name = name, args = arguments)

    // ------------------------------------------------------------------
    // SstmExtractionService
    // ------------------------------------------------------------------

    @Test
    fun `processDiscardedMessages runs the extractor and the merge tool loop`() = runBlocking {
        // run 1 = extractor (fact text), run 2 = the merge tool loop: one
        // add_memory round (executed through the merge provider, standing
        // in for the hand's tool callback) then the final confirmation
        var calls = 0
        val sstm = RecordingSstmService(listOf(ShortTermMemory(1, Instant.EPOCH, "existing fact")))
        val mergeProvider = MergeMemoryToolProvider(sstm)
        val hand = FakeHand(
            runScript = {
                when (++calls) {
                    1 -> textRunFlow("likes coffee")
                    else -> listOf(
                        HandEvent.AssistantMessage(addMemoryRound("call_1", "likes coffee"))
                    ) + toolRoundEvents(addMemoryRound("call_1", "likes coffee"), mergeProvider) +
                            listOf(
                                HandEvent.AssistantMessage(assistantMessage("done")),
                                HandEvent.Done("stop"),
                            )
                }
            },
        )
        val extractor = service(hand, sstm)
        extractor.processDiscardedMessages(listOf(userMessage("u1"), userMessage("u2")))
        assertEquals(listOf("likes coffee"), sstm.created)
        assertEquals(2, hand.requests.size, "extractor run + one merge run")
        // no static tool list travels in the request anymore: the merge
        // run's tools are served through the per-round GET /api/hand/tools
        // listing (pinned by HandCallbackTest), from the same provider
        assertEquals(
            listOf("list_memories", "add_memory", "update_memory", "delete_memory"),
            mergeProvider.specifications().map { it.name },
        )
        // the merge run carries the round cap
        assertEquals(150, hand.requests[1].maxRounds)
    }

    @Test
    fun `the nothing-worth-remembering sentinel skips the merge`() = runBlocking {
        val hand = FakeHand(
            runScript = { textRunFlow("Nothing worth remember.") },
        )
        val sstm = RecordingSstmService()
        val extractor = service(hand, sstm)
        extractor.processDiscardedMessages(listOf(userMessage("u1")))
        assertEquals(1, hand.requests.size, "only the extractor run happened")
        assertTrue(sstm.created.isEmpty())
    }

    @Test
    fun `extraction fails fast when the model cannot see the content`() = runBlocking {
        val hand = FakeHand()
        val sstm = RecordingSstmService()
        val textOnly = model("bifrost/cerebras/gpt-oss-120b")
        val extractor = SstmExtractionService(
            extractModel = textOnly,
            hand = testHandService(hand),
            sstmService = sstm,
            maxRetries = 0,
            streamIdleTimeoutMs = 0,
        )
        // a text-only extraction model with an image in the dropped
        // history: the capability mismatch is a configuration error and
        // must fail the run, not silently skip the extraction
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
        // the run instead of feeding the merger
        val hand = FakeHand(
            runScript = {
                errorRunFlow("output_budget_exhausted", "output hit the token budget")
            },
        )
        val sstm = RecordingSstmService()
        val extractor = service(hand, sstm)
        val e = assertFailsWith<IllegalStateException> {
            extractor.processDiscardedMessages(listOf(userMessage("u1")))
        }
        // the outer message names the wrapper only; the detail lives on the cause
        assertEquals("SSTM extraction failed", e.message)
        val cause = assertIs<HandRunException>(e.cause)
        assertEquals("output_budget_exhausted", cause.type)
        assertEquals(1, hand.requests.size, "only the extractor run happened")
        assertTrue(sstm.created.isEmpty(), "a truncated extraction must not feed the merger")
    }

    @Test
    fun `a merge round with no answer fails the merge`() = runBlocking {
        // the merge run's assistant answers with neither text nor tool
        // calls: the run loop fails it as empty_response, and the failed
        // merge must fail the whole extraction pipeline
        var calls = 0
        val sstm = RecordingSstmService()
        val mergeProvider = MergeMemoryToolProvider(sstm)
        val hand = FakeHand(
            runScript = {
                when (++calls) {
                    1 -> textRunFlow("x")
                    else -> listOf(
                        HandEvent.AssistantMessage(addMemoryRound("call_1", "x"))
                    ) + toolRoundEvents(addMemoryRound("call_1", "x"), mergeProvider) +
                            listOf(
                                HandEvent.RunError(
                                    "empty_response",
                                    "assistant finished with neither text nor tool calls"
                                )
                            )
                }
            },
        )
        val extractor = service(hand, sstm)
        val e = assertFailsWith<HandRunException> {
            extractor.processDiscardedMessages(listOf(userMessage("u1")))
        }
        assertEquals("empty_response", e.type)
        // the memory applied before the failure stays (healed by the next
        // sstm-updated comparison)
        assertEquals(listOf("x"), sstm.created)
    }

    @Test
    fun `the merge round cap fails the merge instead of force-stopping`() = runBlocking {
        // the model keeps calling add_memory forever; the run loop stops at
        // maxRounds with a round_limit error, and the failed merge fails
        // the pipeline (a deletion must not lose unmerged memories)
        var calls = 0
        val sstm = RecordingSstmService()
        val mergeProvider = MergeMemoryToolProvider(sstm)
        val hand = FakeHand(
            runScript = {
                if (++calls == 1) {
                    textRunFlow("x")
                } else {
                    val round = addMemoryRound("call_$calls", "x")
                    listOf(HandEvent.AssistantMessage(round)) +
                            toolRoundEvents(round, mergeProvider) +
                            listOf(
                                HandEvent.RunError(
                                    "round_limit",
                                    "maxRounds (2) reached at round 2"
                                )
                            )
                }
            },
        )
        val extractor = service(hand, sstm, maxMergeRounds = 2)
        val e = assertFailsWith<HandRunException> {
            extractor.processDiscardedMessages(listOf(userMessage("u1")))
        }
        assertEquals("round_limit", e.type)
        // the memory applied before the cap stays
        assertEquals(listOf("x"), sstm.created)
    }

    @Test
    fun `buildMergeInput lists the current sstm and the candidates`() {
        val input = buildMergeInput(
            existing = listOf(
                ShortTermMemory(1, Instant.EPOCH, "old"),
                ShortTermMemory(2, Instant.EPOCH, "newer")
            ),
            candidates = "fact a\nfact b",
        )
        assertTrue(input.contains("## Memory 1\nold"))
        assertTrue(input.contains("## Memory 2\nnewer"))
        assertTrue(input.contains("fact a"))
        assertTrue(input.contains("fact b"))
    }
}
