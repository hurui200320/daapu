package info.skyblond.daapu.agent.oneshot.sstm

import info.skyblond.daapu.agent.model.ModelCapabilityException
import info.skyblond.daapu.agent.ModelCatalog
import info.skyblond.daapu.agent.model.ModelProvider
import info.skyblond.daapu.agent.tool.ToolCallRequest
import info.skyblond.daapu.agent.chat.*
import info.skyblond.daapu.hand.FakeHand
import info.skyblond.daapu.hand.assistantMessage
import info.skyblond.daapu.hand.failedCompleteResponse
import info.skyblond.daapu.hand.okCompleteResponse
import info.skyblond.daapu.memory.sstm.MemoriesWithVersion
import info.skyblond.daapu.memory.sstm.ShortTermMemory
import info.skyblond.daapu.memory.sstm.SstmService
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

    /** A fake [SstmService] recording writes. */
    private class FakeSstmService(
        private val memories: List<ShortTermMemory> = emptyList(),
    ) : SstmService {
        val created = mutableListOf<String>()
        val updated = mutableListOf<Pair<Long, String>>()
        val deleted = mutableListOf<Long>()

        override suspend fun listMemories(): MemoriesWithVersion =
            MemoriesWithVersion(memories, "test-version")

        override suspend fun createMemory(content: String): ShortTermMemory {
            created += content
            return ShortTermMemory(created.size.toLong(), Instant.EPOCH, content)
        }

        override suspend fun updateMemory(id: Long, content: String): ShortTermMemory {
            updated += id to content
            return ShortTermMemory(id, Instant.EPOCH, content)
        }

        override suspend fun deleteMemory(id: Long): Boolean {
            deleted += id
            return true
        }
    }

    // ------------------------------------------------------------------
    // MergeMemoryToolProvider
    // ------------------------------------------------------------------

    @Test
    fun `memory tools execute against the service and track modification`() = runBlocking {
        val sstm = FakeSstmService(listOf(ShortTermMemory(1, Instant.EPOCH, "old fact")))
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
        val sstm = FakeSstmService()
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

    /** An assistant message whose only part is one add_memory tool call. */
    private fun addMemoryRound(id: String, content: String) = assistantMessage(
        parts = listOf(
            ChatMessagePart.ToolCall(
                id = id,
                tool = "add_memory",
                args = buildJsonObject { put("content", content) },
            )
        ),
        finishReason = "tool_calls",
    )

    // ------------------------------------------------------------------
    // SstmExtractionService
    // ------------------------------------------------------------------

    @Test
    fun `processDiscardedMessages runs the extractor and the merge tool loop`() = runBlocking {
        // complete 1 = extractor (fact text), complete 2 = merge round 1
        // (add_memory tool call), complete 3 = merge round 2 (done)
        var round = 0
        val hand = FakeHand(
            completeScript = {
                when (++round) {
                    1 -> okCompleteResponse(assistantMessage("likes coffee"))
                    2 -> okCompleteResponse(addMemoryRound("call_1", "likes coffee"))
                    else -> okCompleteResponse(assistantMessage("done"))
                }
            },
        )
        val sstm = FakeSstmService(listOf(ShortTermMemory(1, Instant.EPOCH, "existing fact")))
        val extractor = SstmExtractionService(
            extractModel = model("bifrost/cerebras/gemma-4-31b"),
            hand = hand,
            sstmService = sstm,
        )
        extractor.processDiscardedMessages(listOf(userMessage("u1"), userMessage("u2")))
        assertEquals(listOf("likes coffee"), sstm.created)
        assertEquals(3, hand.completeRequests.size, "extractor + merge round + final round")
        // the merge rounds advertised the memory tools
        assertTrue(hand.completeRequests[1].tools!!.map { it.name }.contains("add_memory"))
    }

    @Test
    fun `the nothing-worth-remembering sentinel skips the merge`() = runBlocking {
        val hand = FakeHand(
            completeScript = { okCompleteResponse(assistantMessage("Nothing worth remember.")) },
        )
        val sstm = FakeSstmService()
        val extractor = SstmExtractionService(
            extractModel = model("bifrost/cerebras/gemma-4-31b"),
            hand = hand,
            sstmService = sstm,
        )
        extractor.processDiscardedMessages(listOf(userMessage("u1")))
        assertEquals(1, hand.completeRequests.size, "only the extractor round ran")
        assertTrue(sstm.created.isEmpty())
    }

    @Test
    fun `extraction fails fast when the model cannot see the content`() = runBlocking {
        val hand = FakeHand()
        val sstm = FakeSstmService()
        val textOnly = model("bifrost/cerebras/gpt-oss-120b")
        val extractor = SstmExtractionService(
            extractModel = textOnly,
            hand = hand,
            sstmService = sstm,
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
        assertTrue(hand.completeRequests.isEmpty(), "no LLM call for an incapable model")
    }

    @Test
    fun `a truncated extractor round fails the extraction`() = runBlocking {
        // a truncated extractor response is a broken extraction: it fails
        // the run instead of feeding the merger
        val hand = FakeHand(
            completeScript = {
                failedCompleteResponse(
                    "output_budget_exhausted",
                    "output hit the token budget"
                )
            },
        )
        val sstm = FakeSstmService()
        val extractor = SstmExtractionService(
            extractModel = model("bifrost/cerebras/gemma-4-31b"),
            hand = hand,
            sstmService = sstm,
        )
        val e = assertFailsWith<IllegalStateException> {
            extractor.processDiscardedMessages(listOf(userMessage("u1")))
        }
        assertTrue(
            e.message!!.contains("output_budget_exhausted"),
            "the error should name the failure: ${e.message}",
        )
        assertEquals(1, hand.completeRequests.size, "only the extractor round ran")
        assertTrue(sstm.created.isEmpty(), "a truncated extraction must not feed the merger")
    }

    @Test
    fun `the merge round cap stops an endless tool loop`() = runBlocking {
        // the model keeps calling add_memory forever; the loop must stop
        var round = 0
        val hand = FakeHand(
            completeScript = {
                when (++round) {
                    1 -> okCompleteResponse(assistantMessage("x"))
                    else -> okCompleteResponse(addMemoryRound("call_$round", "x"))
                }
            },
        )
        val sstm = FakeSstmService()
        val extractor = SstmExtractionService(
            extractModel = model("bifrost/cerebras/gemma-4-31b"),
            hand = hand,
            sstmService = sstm,
            maxMergeRounds = 2,
        )
        extractor.processDiscardedMessages(listOf(userMessage("u1")))
        // extractor round + 2 capped merge rounds
        assertEquals(3, hand.completeRequests.size)
        assertEquals(listOf("x", "x"), sstm.created)
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
