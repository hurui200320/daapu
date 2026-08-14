package info.skyblond.daapu.agent.oneshot

import dev.langchain4j.agent.tool.ToolExecutionRequest
import info.skyblond.daapu.agent.ModelCapabilityException
import info.skyblond.daapu.agent.lc4j.MockSseResponse
import info.skyblond.daapu.agent.lc4j.MockSseServer
import info.skyblond.daapu.agent.lc4j.jsonCompletion
import info.skyblond.daapu.agent.lc4j.jsonResponse
import info.skyblond.daapu.agent.lc4j.llm.ModelCatalog
import info.skyblond.daapu.agent.lc4j.provider.BifrostProvider
import info.skyblond.daapu.chat.AttachmentContent
import info.skyblond.daapu.chat.AttachmentKind
import info.skyblond.daapu.chat.ChatMessage
import info.skyblond.daapu.chat.ChatMessagePart
import info.skyblond.daapu.chat.ChatMessageRole
import info.skyblond.daapu.memory.sstm.MemoriesWithVersion
import info.skyblond.daapu.memory.sstm.ShortTermMemory
import info.skyblond.daapu.memory.sstm.SstmService
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ExtractSSTMTest {

    private fun model(server: MockSseServer, id: String) = ModelCatalog(
        BifrostProvider(id = "bifrost", baseUrl = "http://127.0.0.1:${server.port}/v1", apiKey = "test")
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
    // MemoryToolProvider
    // ------------------------------------------------------------------

    @Test
    fun `memory tools execute against the service and track modification`() = runBlocking {
        val sstm = FakeSstmService(listOf(ShortTermMemory(1, Instant.EPOCH, "old fact")))
        val provider = MemoryToolProvider(sstm)

        val listResult = provider.execute(toolCall("c1", "list_memories", "{}"))
        assertEquals("## Memory 1\nold fact", (listResult.parts.single() as ChatMessagePart.Text).text)
        assertFalse(listResult.isError)

        val addResult = provider.execute(toolCall("c2", "add_memory", """{"content": "likes coffee"}"""))
        assertFalse(addResult.isError)
        assertEquals(listOf("likes coffee"), sstm.created)

        val updateResult = provider.execute(toolCall("c3", "update_memory", """{"id": "1", "content": "loves coffee"}"""))
        assertFalse(updateResult.isError)
        assertEquals(listOf(1L to "loves coffee"), sstm.updated)

        val deleteResult = provider.execute(toolCall("c4", "delete_memory", """{"id": "1"}"""))
        assertFalse(deleteResult.isError)
        assertEquals(listOf(1L), sstm.deleted)
    }

    @Test
    fun `memory tools answer errors without modifying`() = runBlocking {
        val sstm = FakeSstmService()
        val provider = MemoryToolProvider(sstm)

        val badArgs = provider.execute(toolCall("c1", "add_memory", "not json"))
        assertTrue(badArgs.isError)

        val unknown = provider.execute(toolCall("c2", "nope", "{}"))
        assertTrue(unknown.isError)
    }

    private fun toolCall(id: String, name: String, arguments: String) =
        ToolExecutionRequest.builder().id(id).name(name).arguments(arguments).build()

    // ------------------------------------------------------------------
    // SstmExtractor
    // ------------------------------------------------------------------

    @Test
    fun `processDiscardedMessages runs the extractor and the merge tool loop`() = runBlocking {
        // attempt 1 = extractor (fact text), attempt 2 = merge round 1
        // (add_memory tool call), attempt 3 = merge round 2 (done)
        val server = MockSseServer { attempt ->
            when (attempt) {
                1 -> jsonResponse(jsonCompletion(content = "likes coffee"))
                2 -> jsonResponse(
                    jsonCompletion(
                        content = null,
                        finishReason = "tool_calls",
                        toolCalls = """[{"id":"call_1","type":"function","function":{"name":"add_memory","arguments":"{\"content\":\"likes coffee\"}"}}]""",
                    )
                )
                else -> jsonResponse(jsonCompletion(content = "done"))
            }
        }
        val sstm = FakeSstmService(listOf(ShortTermMemory(1, Instant.EPOCH, "existing fact")))
        try {
            val model = model(server, "bifrost/cerebras/gemma-4-31b")
            val extractor = SstmExtractor(
                extractModel = model,
                extractChatModel = model.toChatModel("high"),
                sstmService = sstm,
            )
            extractor.processDiscardedMessages(listOf(userMessage("u1"), userMessage("u2")))
            assertEquals(listOf("likes coffee"), sstm.created)
            assertEquals(3, server.count, "extractor + merge round + final round")
        } finally {
            server.close()
        }
    }

    @Test
    fun `the nothing-worth-remembering sentinel skips the merge`() = runBlocking {
        val server = MockSseServer { jsonResponse(jsonCompletion(content = "Nothing worth remember.")) }
        val sstm = FakeSstmService()
        try {
            val model = model(server, "bifrost/cerebras/gemma-4-31b")
            val extractor = SstmExtractor(
                extractModel = model,
                extractChatModel = model.toChatModel("high"),
                sstmService = sstm,
            )
            extractor.processDiscardedMessages(listOf(userMessage("u1")))
            assertEquals(1, server.count, "only the extractor round ran")
            assertTrue(sstm.created.isEmpty())
        } finally {
            server.close()
        }
    }

    @Test
    fun `extraction fails fast when the model cannot see the content`() = runBlocking {
        val server = MockSseServer { MockSseResponse(200, emptyList()) }
        val sstm = FakeSstmService()
        try {
            val textOnly = model(server, "bifrost/cerebras/gpt-oss-120b")
            val extractor = SstmExtractor(
                extractModel = textOnly,
                extractChatModel = textOnly.toChatModel("high"),
                sstmService = sstm,
            )
            // a text-only extraction model with an image in the dropped
            // history: the capability mismatch is a configuration error and
            // must fail the run, not silently skip the extraction
            val e = assertFailsWith<ModelCapabilityException> {
                extractor.processDiscardedMessages(listOf(imageMessage()))
            }
            assertTrue(e.message!!.contains("image"), "the error should name the unsupported kind: ${e.message}")
            assertEquals(0, server.count, "no LLM call for an incapable model")
        } finally {
            server.close()
        }
    }

    @Test
    fun `a truncated extractor round fails the extraction`() = runBlocking {
        // a truncated extractor response is a broken extraction: it fails
        // the run instead of feeding the merger
        val server = MockSseServer { jsonResponse(jsonCompletion(content = "partial", finishReason = "length")) }
        val sstm = FakeSstmService()
        try {
            val model = model(server, "bifrost/cerebras/gemma-4-31b")
            val extractor = SstmExtractor(
                extractModel = model,
                extractChatModel = model.toChatModel("high"),
                sstmService = sstm,
            )
            val e = assertFailsWith<IllegalStateException> {
                extractor.processDiscardedMessages(listOf(userMessage("u1")))
            }
            assertTrue(e.message!!.contains("finish_reason"), "the error should name the finish reason: ${e.message}")
            assertEquals(1, server.count, "only the extractor round ran")
            assertTrue(sstm.created.isEmpty(), "a truncated extraction must not feed the merger")
        } finally {
            server.close()
        }
    }

    @Test
    fun `the merge round cap stops an endless tool loop`() = runBlocking {
        // the model keeps calling add_memory forever; the loop must stop
        val endlessToolCalls = """[{"id":"call_1","type":"function","function":{"name":"add_memory","arguments":"{\"content\":\"x\"}"}}]"""
        val server = MockSseServer { attempt ->
            if (attempt == 1) jsonResponse(jsonCompletion(content = "x"))
            else jsonResponse(jsonCompletion(content = null, finishReason = "tool_calls", toolCalls = endlessToolCalls))
        }
        val sstm = FakeSstmService()
        try {
            val model = model(server, "bifrost/cerebras/gemma-4-31b")
            val extractor = SstmExtractor(
                extractModel = model,
                extractChatModel = model.toChatModel("high"),
                sstmService = sstm,
                maxMergeRounds = 2,
            )
            extractor.processDiscardedMessages(listOf(userMessage("u1")))
            // extractor round + 2 capped merge rounds
            assertEquals(3, server.count)
            assertEquals(listOf("x", "x"), sstm.created)
        } finally {
            server.close()
        }
    }

    @Test
    fun `buildMergeInput lists the current sstm and the candidates`() {
        val input = buildMergeInput(
            existing = listOf(ShortTermMemory(1, Instant.EPOCH, "old"), ShortTermMemory(2, Instant.EPOCH, "newer")),
            candidates = "fact a\nfact b",
        )
        assertTrue(input.contains("## Memory 1\nold"))
        assertTrue(input.contains("## Memory 2\nnewer"))
        assertTrue(input.contains("fact a"))
        assertTrue(input.contains("fact b"))
    }
}
