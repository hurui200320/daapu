package info.skyblond.daapu.agent.oneshot.investigate

import info.skyblond.daapu.agent.chat.ChatMessage
import info.skyblond.daapu.agent.chat.ChatMessagePart
import info.skyblond.daapu.agent.chat.ChatMessageRole
import info.skyblond.daapu.agent.model.LLM
import info.skyblond.daapu.agent.model.ModelProvider
import info.skyblond.daapu.agent.oneshot.eltm.EltmToolProvider
import info.skyblond.daapu.agent.tool.CombinedToolProvider
import info.skyblond.daapu.agent.tool.ToolProvider
import info.skyblond.daapu.agent.tool.WhitelistedToolProvider
import info.skyblond.daapu.hand.FakeHand
import info.skyblond.daapu.hand.HandEvent
import info.skyblond.daapu.hand.HandRunPolicy
import info.skyblond.daapu.hand.HandUpstreamException
import info.skyblond.daapu.hand.assistantMessage
import info.skyblond.daapu.hand.errorRunFlow
import info.skyblond.daapu.hand.textRunFlow
import info.skyblond.daapu.hand.toolRoundEvents
import info.skyblond.daapu.testutil.FakeEltmService
import info.skyblond.daapu.testutil.testHandService
import info.skyblond.daapu.testutil.testLlm
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.*

/**
 * Pins [InvestigatorService]'s elastic tool loop: the clean-stop report,
 * the `round_limit` recovery (a real LLM summarization of the whole partial
 * history, with a heuristic fallback), the `context_exhausted` recovery
 * (the tool-call trace only, no summary call), the classified-error passthrough,
 * and the fail-fast capability check.
 */
class InvestigatorServiceTest {

    private fun model(id: String) = testLlm(id)

    /** The whitelisted read-only ELTM tool set the service runs with. */
    private fun eltmProvider(eltm: FakeEltmService): ToolProvider {
        val eltmProvider = EltmToolProvider(eltm, readOnly = true, namespace = "eltm")
        return WhitelistedToolProvider(CombinedToolProvider(listOf(eltmProvider)), setOf("eltm"))
    }

    private fun service(
        hand: FakeHand,
        eltm: FakeEltmService = FakeEltmService(),
        maxRounds: Int = 150,
        investigateModel: LLM = model("bifrost/cerebras/gemma-4-31b"),
    ) = InvestigatorService(
        model = investigateModel,
        hand = testHandService(hand),
        toolProvider = eltmProvider(eltm),
        maxRounds = maxRounds,
        policy = HandRunPolicy(0, 0),
    )

    private fun searchRound(id: String, query: String): ChatMessage = assistantMessage(
        parts = listOf(
            ChatMessagePart.ToolCall(
                id = id,
                tool = "eltm__search_entities",
                args = buildJsonObject { put("query", query) },
            )
        ),
        finishReason = "tool_calls",
    )

    @Test
    fun `a successful investigate run returns the final report`() = runBlocking {
        val eltm = FakeEltmService()
        val provider = eltmProvider(eltm)
        val round = searchRound("c1", "alice")
        val hand = FakeHand(
            runScript = {
                listOf(HandEvent.AssistantMessage(round)) +
                        toolRoundEvents(round, provider) +
                        listOf(
                            HandEvent.AssistantMessage(assistantMessage("Report: alice is a person.")),
                            HandEvent.Done("stop"),
                        )
            },
        )
        val outcome = service(hand, eltm).runInvestigate("who is alice?")

        assertFalse(outcome.isError)
        assertEquals("Report: alice is a person.", outcome.text)
        assertEquals(
            listOf<ChatMessagePart>(ChatMessagePart.Text("Report: alice is a person.")),
            outcome.report,
            "the clean report carries the final assistant's text parts verbatim",
        )

        val request = hand.requests.single()
        assertEquals(150, request.maxRounds, "the investigate run carries its own round cap")
        assertTrue(
            request.systemPrompt!!.startsWith("You're an investigator"),
            "the investigate prompt drives the run",
        )
        val input = (request.messages.single().parts.single() as ChatMessagePart.Text).text
        assertTrue(
            Regex("^Current date and time: \\d{4}-\\d{2}-\\d{2}T").containsMatchIn(input),
            "the input must carry a current date/time anchor: $input",
        )
        assertTrue(input.contains("who is alice?"), "the merged query travels verbatim: $input")
    }

    @Test
    fun `a round-limit stop summarizes the whole partial history`() = runBlocking {
        val eltm = FakeEltmService()
        val provider = eltmProvider(eltm)
        val round = searchRound("c1", "alice")
        val hand = FakeHand(
            runScript = { request ->
                when {
                    request.systemPrompt?.startsWith("You're an investigator") == true ->
                        listOf(HandEvent.AssistantMessage(round)) +
                                toolRoundEvents(round, provider) +
                                listOf(
                                    HandEvent.AssistantMessage(assistantMessage("found: alice likes coffee")),
                                    HandEvent.RunError("round_limit", "round limit reached"),
                                )

                    request.systemPrompt?.startsWith("You're summarizing") == true ->
                        textRunFlow("alice is a person who likes coffee")

                    else -> error("unexpected run: ${request.systemPrompt}")
                }
            },
        )
        val outcome = service(hand, eltm).runInvestigate("who is alice?")

        assertFalse(outcome.isError)
        assertTrue(outcome.text.contains("maximum number of rounds (150)"), outcome.text)
        assertTrue(outcome.text.contains("alice is a person who likes coffee"), outcome.text)
        assertFalse(outcome.text.contains("eltm__search_entities"), "no tool trace in the round-limit report")

        assertEquals(2, hand.requests.size, "investigate run + summarization one-shot")
        val summary = hand.requests[1]
        assertEquals(0, summary.maxRounds, "the summarization one-shot declares no tools and no round cap")
        assertTrue(summary.systemPrompt!!.startsWith("You're summarizing"))
        // the whole partial history feeds the summarizer, in order, plus the
        // summary instruction as the final user message
        val roles = summary.messages.map { it.role }
        assertEquals(
            listOf(
                ChatMessageRole.Assistant,
                ChatMessageRole.ToolResult,
                ChatMessageRole.Assistant,
                ChatMessageRole.User,
            ),
            roles,
            "the partial history plus the summary instruction: $roles",
        )
        assertEquals(
            "Summarize this chat history according to the system prompt.",
            (summary.messages.last().parts.single() as ChatMessagePart.Text).text,
        )
    }

    @Test
    fun `a failed partial summarization falls back to the raw assistant texts`() = runBlocking {
        val hand = FakeHand(
            runScript = { request ->
                when {
                    request.systemPrompt?.startsWith("You're an investigator") == true ->
                        listOf(
                            HandEvent.AssistantMessage(assistantMessage("found: alice likes coffee")),
                            HandEvent.RunError("round_limit", "round limit reached"),
                        )

                    request.systemPrompt?.startsWith("You're summarizing") == true ->
                        errorRunFlow("upstream", "provider unavailable")

                    else -> error("unexpected run: ${request.systemPrompt}")
                }
            },
        )
        val outcome = service(hand).runInvestigate("who is alice?")

        // the graceful degradation holds even when the summarizer fails
        assertFalse(outcome.isError)
        assertTrue(outcome.text.contains("found: alice likes coffee"), outcome.text)
        assertTrue(outcome.text.contains("forced to stop"), outcome.text)
    }

    @Test
    fun `a context-exhausted stop reports the tool-call trace without a summary`() = runBlocking {
        val eltm = FakeEltmService()
        val provider = eltmProvider(eltm)
        val round = searchRound("c1", "alice")
        val hand = FakeHand(
            runScript = { request ->
                when {
                    request.systemPrompt?.startsWith("You're an investigator") == true ->
                        listOf(HandEvent.AssistantMessage(round)) +
                                toolRoundEvents(round, provider) +
                                listOf(HandEvent.RunError("context_exhausted", "context window full"))

                    else -> error("the summarization must not run for a context-exhausted stop")
                }
            },
        )
        val outcome = service(hand, eltm).runInvestigate("who is alice?")

        assertEquals(1, hand.requests.size, "no summary call after a context-exhausted stop")
        assertFalse(outcome.isError)
        assertTrue(outcome.text.contains("context window"), outcome.text)
        assertTrue(outcome.text.contains("eltm__search_entities"), outcome.text)
        assertTrue(outcome.text.contains("\"query\":\"alice\""), "the trace carries the call args: ${outcome.text}")
        assertTrue(outcome.text.contains("Refine the query"), outcome.text)
    }

    @Test
    fun `a classified hand error is returned as an error result`() = runBlocking {
        val hand = FakeHand(
            runScript = { errorRunFlow("upstream", "provider unavailable") },
        )
        val outcome = service(hand).runInvestigate("who is alice?")
        assertTrue(outcome.isError)
        assertTrue(outcome.text.contains("upstream"), outcome.text)
        assertTrue(outcome.text.contains("provider unavailable"), outcome.text)
    }

    @Test
    fun `a dropped transport returns an error result instead of throwing`() = runBlocking {
        val hand = FakeHand(
            runScript = { throw HandUpstreamException("connection refused") },
        )
        val outcome = service(hand).runInvestigate("who is alice?")
        assertTrue(outcome.isError)
        assertTrue(outcome.text.contains("upstream"), outcome.text)
        assertTrue(outcome.text.contains("connection refused"), outcome.text)
    }

    @Test
    fun `a clean run without a final report text is an error carrying the trace`() = runBlocking {
        // the hand fails a blank stop as `empty_response` before `done`, so a
        // "clean" run that never produced a report text is as broken as a
        // context-exhausted one: the defensive backstop hands the caller the
        // tool-call trace instead of a blank report
        val eltm = FakeEltmService()
        val provider = eltmProvider(eltm)
        val round = searchRound("c1", "alice")
        val hand = FakeHand(
            runScript = {
                listOf(HandEvent.AssistantMessage(round)) +
                        toolRoundEvents(round, provider) +
                        listOf(HandEvent.Done("stop"))
            },
        )
        val outcome = service(hand, eltm).runInvestigate("who is alice?")

        assertTrue(outcome.isError)
        assertTrue(outcome.text.contains("without producing a report"), outcome.text)
        assertTrue(outcome.text.contains("eltm__search_entities"), outcome.text)
        assertTrue(outcome.text.contains("Refine the query"), outcome.text)
    }

    @Test
    fun `the investigator fails fast when the model cannot call tools`() {
        val textOnly = LLM(
            provider = ModelProvider("bifrost", "http://127.0.0.1:9/v1", "test"),
            modelId = "text-only",
            contextLength = 131000,
            maxOutputTokens = 40000,
            capabilities = emptySet(),
            compactionTriggerFraction = 0.75,
            compactionKeepRounds = 2,
        )
        val e = assertFailsWith<IllegalArgumentException> {
            runBlocking { service(FakeHand(), investigateModel = textOnly).runInvestigate("q") }
        }
        assertTrue(e.message!!.contains("tool calls"), e.message)
    }
}
