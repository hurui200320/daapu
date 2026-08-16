package info.skyblond.daapu.hand

import info.skyblond.daapu.agent.ModelCatalog
import info.skyblond.daapu.agent.chat.ChatMessage
import info.skyblond.daapu.agent.chat.ChatMessagePart
import info.skyblond.daapu.agent.chat.ChatMessageRole
import info.skyblond.daapu.agent.model.ModelProvider
import info.skyblond.daapu.agent.tool.EmptyToolProvider
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the HandService run/callback wiring: the runId is generated per
 * `/v1/run` (internal to the run plumbing), the in-flight run is registered
 * before the request goes out and evicted when the stream ends — success,
 * terminal error, or cancellation — a duplicate runId fails fast, and the
 * callback URL is attached on every run (preserving an explicit one).
 */
class HandServiceTest {

    private fun model() = ModelCatalog(
        mapOf("bifrost" to ModelProvider("bifrost", "http://127.0.0.1:9/v1", "test"))
    ).findModel("bifrost/cerebras/gemma-4-31b")!!

    private fun runRequest(
        runId: String? = null,
        tools: List<HandToolSpec>? = null,
        toolCallbackUrl: String? = null,
    ) = HandRunRequest(
        model = model().toHandModelSpec(),
        messages = listOf(ChatMessage(ChatMessageRole.User, listOf(ChatMessagePart.Text("hi")))),
        runId = runId,
        chatId = "c1",
        tools = tools,
        toolCallbackUrl = toolCallbackUrl,
        maxTokens = 100,
        maxRounds = 4,
        maxRetries = 0,
        callbackTimeoutMs = 0,
        streamIdleTimeoutMs = 0,
    )

    private fun toolSpec() = HandToolSpec(
        name = "flag",
        description = "a flag tool",
        schema = buildJsonObject { },
        timeoutSeconds = 30,
    )

    private fun callback(runId: String) = HandToolCallbackRequest(
        runId = runId,
        chatId = "c1",
        id = "call_1",
        name = "flag",
        args = JsonObject(emptyMap()),
        timeoutSeconds = 0,
    )

    @Test
    fun `registers the in-flight run before the request and evicts it after the run`() {
        runBlocking {
            val callbackService = HandCallbackService("test-token")
            val hand = FakeHand(runScript = { request ->
                assertFalse(request.runId.isNullOrBlank(), "HandService must fill the runId")
                // while the run is in flight, its runId must resolve the provider
                val inFlight = callbackService.executeToolCall(callback(request.runId))
                assertNull(inFlight.fatal, "the in-flight run must resolve its provider")
                listOf(HandEvent.Done("stop"))
            })
            val service = HandService(hand, callbackService, "http://127.0.0.1:9/api/hand/tool")

            service.run(runRequest(), EmptyToolProvider, model()).toList()

            // after the run, the same runId must be unknown again
            val late = callbackService.executeToolCall(callback(hand.requests.single().runId!!))
            assertNotNull(late.fatal, "a finished run must be evicted")
        }
    }

    @Test
    fun `evicts the run after a terminal hand error`() {
        runBlocking {
            val callbackService = HandCallbackService("test-token")
            val hand = FakeHand(runScript = { listOf(HandEvent.RunError("upstream", "boom")) })
            val service = HandService(hand, callbackService, "http://127.0.0.1:9/api/hand/tool")

            service.run(runRequest(), EmptyToolProvider, model()).toList()

            val late = callbackService.executeToolCall(callback(hand.requests.single().runId!!))
            assertNotNull(late.fatal, "a failed run must be evicted")
        }
    }

    @Test
    fun `evicts the run when the stream is cancelled`() {
        runBlocking {
            val callbackService = HandCallbackService("test-token")
            val hand = FakeHand(runScript = { kotlinx.coroutines.awaitCancellation() })
            val service = HandService(hand, callbackService, "http://127.0.0.1:9/api/hand/tool")

            val job = launch {
                service.run(runRequest(), EmptyToolProvider, model()).toList()
            }
            // wait until the run is registered (the request is only recorded
            // after the registration), then abort the stream
            withTimeout(5_000) {
                while (hand.requests.isEmpty()) delay(10)
            }
            job.cancelAndJoin()

            val late = callbackService.executeToolCall(callback(hand.requests.single().runId!!))
            assertNotNull(late.fatal, "a cancelled run must be evicted")
        }
    }

    @Test
    fun `generates a fresh runId per run and preserves an explicit one`() = runBlocking {
        val callbackService = HandCallbackService("test-token")
        val hand = FakeHand()
        val service = HandService(hand, callbackService, "http://127.0.0.1:9/api/hand/tool")

        service.run(runRequest(), EmptyToolProvider, model()).toList()
        service.run(runRequest(), EmptyToolProvider, model()).toList()
        service.run(runRequest(runId = "explicit-1"), EmptyToolProvider, model()).toList()

        val ids = hand.requests.map { it.runId!! }
        assertTrue(ids[0].isNotBlank(), "a runId must be generated when absent")
        assertTrue(ids[0] != ids[1], "each hand run gets its own runId")
        assertEquals("explicit-1", ids[2], "an explicit runId is preserved")
    }

    @Test
    fun `attaches the callback URL on every run and preserves an explicit one`() = runBlocking {
        val callbackService = HandCallbackService("test-token")
        val hand = FakeHand()
        val service = HandService(hand, callbackService, "http://127.0.0.1:9/api/hand/tool")

        service.run(runRequest(tools = listOf(toolSpec())), EmptyToolProvider, model()).toList()
        service.run(runRequest(), EmptyToolProvider, model()).toList()
        service.run(
            runRequest(tools = listOf(toolSpec()), toolCallbackUrl = "http://custom"),
            EmptyToolProvider,
            model(),
        ).toList()

        assertEquals("http://127.0.0.1:9/api/hand/tool", hand.requests[0].toolCallbackUrl)
        assertEquals(
            "http://127.0.0.1:9/api/hand/tool",
            hand.requests[1].toolCallbackUrl,
            "the callback URL is attached even without tools (the hand only POSTs it on a tool call)",
        )
        assertEquals("http://custom", hand.requests[2].toolCallbackUrl, "an explicit URL is preserved")
    }

    @Test
    fun `a reused explicit runId fails fast instead of overriding the in-flight run`() {
        runBlocking {
            val callbackService = HandCallbackService("test-token")
            val hand = FakeHand(runScript = { kotlinx.coroutines.awaitCancellation() })
            val service = HandService(hand, callbackService, "http://127.0.0.1:9/api/hand/tool")

            val first = launch {
                runCatching { service.run(runRequest(runId = "dup"), EmptyToolProvider, model()).toList() }
            }
            // wait until the first run is in flight, then reuse its runId
            withTimeout(5_000) {
                while (hand.requests.isEmpty()) delay(10)
            }
            val error = assertFailsWith<IllegalStateException> {
                service.run(runRequest(runId = "dup"), EmptyToolProvider, model()).toList()
            }
            assertTrue(error.message!!.contains("dup"), "the error must name the runId: ${error.message}")
            assertEquals(
                1,
                hand.requests.size,
                "the duplicate must fail before the hand is contacted",
            )
            first.cancelAndJoin()
        }
    }

    @Test
    fun `complete delegates to the client`() = runBlocking {
        val hand = FakeHand(
            completeScript = { okCompleteResponse(assistantMessage("summary")) }
        )
        val service = HandService(hand, HandCallbackService("test-token"), "http://127.0.0.1:9/api/hand/tool")

        val response = service.complete(
            HandCompleteRequest(
                model = model().toHandModelSpec(),
                messages = emptyList(),
                maxTokens = 10,
            )
        )

        assertTrue(response.ok)
        assertEquals("summary", (response.message?.parts?.single() as ChatMessagePart.Text).text)
        assertEquals(1, hand.completeRequests.size)
    }
}
