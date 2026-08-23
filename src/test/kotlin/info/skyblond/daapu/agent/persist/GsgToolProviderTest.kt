package info.skyblond.daapu.agent.persist

import info.skyblond.daapu.agent.ModelCatalog
import info.skyblond.daapu.agent.chat.ChatMessagePart
import info.skyblond.daapu.agent.model.LLM
import info.skyblond.daapu.agent.model.ModelProvider
import info.skyblond.daapu.agent.oneshot.eltm.EltmToolProvider
import info.skyblond.daapu.agent.oneshot.investigate.InvestigatorService
import info.skyblond.daapu.agent.tool.CombinedToolProvider
import info.skyblond.daapu.agent.tool.ToolCallRequest
import info.skyblond.daapu.agent.tool.ToolProvider
import info.skyblond.daapu.agent.tool.WhitelistedToolProvider
import info.skyblond.daapu.hand.FakeHand
import info.skyblond.daapu.hand.errorRunFlow
import info.skyblond.daapu.hand.textRunFlow
import info.skyblond.daapu.testutil.FakeEltmService
import info.skyblond.daapu.testutil.testHandService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.*

/**
 * Pins [GsgToolProvider]'s packaging contract: the single `gsg__investigate`
 * advertisement, the `execute` → `InvestigatorService.runInvestigate` →
 * `ToolResult` mapping (the clean report's parts travel verbatim, the
 * outcome's `isError` passes through), and the argument validation.
 */
class GsgToolProviderTest {

    private fun model(id: String) = ModelCatalog(
        mapOf(
            "bifrost" to ModelProvider("bifrost", "http://127.0.0.1:9/v1", "test"),
            "deepinfra" to ModelProvider("deepinfra", "http://127.0.0.1:9/v1", "test"),
        )
    ).findModel(id)!!

    /** The whitelisted read-only ELTM tool set the service runs with. */
    private fun eltmProvider(eltm: FakeEltmService): ToolProvider {
        val eltmProvider = EltmToolProvider(eltm, readOnly = true, namespace = "eltm")
        return WhitelistedToolProvider(CombinedToolProvider(listOf(eltmProvider)), setOf("eltm"))
    }

    private fun provider(
        hand: FakeHand,
        eltm: FakeEltmService = FakeEltmService(),
        investigateModel: LLM = model("bifrost/cerebras/gemma-4-31b"),
    ) = GsgToolProvider(
        InvestigatorService(
            model = investigateModel,
            hand = testHandService(hand),
            toolProvider = eltmProvider(eltm),
            maxRounds = 150,
            maxRetries = 0,
            streamIdleTimeoutMs = 0,
        )
    )

    private fun investigateCall(query: String) =
        ToolCallRequest("call_1", "gsg__investigate", buildJsonObject { put("query", query) })

    @Test
    fun `specifications advertise exactly the gsg investigate tool`() = runBlocking {
        val provider = provider(FakeHand())
        assertEquals(setOf("gsg"), provider.namespaces())
        val spec = provider.specifications().single()
        assertEquals("gsg__investigate", spec.name)
        assertTrue(
            spec.schema.toString().contains("\"query\""),
            "the schema declares the query field: ${spec.schema}",
        )
        assertTrue(
            spec.schema["required"].toString().contains("query"),
            "the query is required: ${spec.schema}",
        )
        assertEquals(
            0L,
            provider.executionTimeoutSeconds("gsg__investigate"),
            "the investigate run has its own round cap; no execution budget"
        )
        assertEquals(0L, provider.executionTimeoutSeconds("no__such"), "unknown names have no budget")
    }

    @Test
    fun `a clean outcome packages the report parts verbatim as a non-error result`() = runBlocking {
        val hand = FakeHand(runScript = { textRunFlow("Report: alice is a person.") })
        val provider = provider(hand)
        val result = provider.execute(investigateCall("who is alice?"))

        assertFalse(result.isError)
        assertEquals("call_1", result.id)
        assertEquals("gsg__investigate", result.tool)
        assertEquals(
            listOf<ChatMessagePart>(ChatMessagePart.Text("Report: alice is a person.")),
            result.parts,
            "the clean report's text parts travel verbatim, no lossy round-trip",
        )
        val request = hand.requests.single()
        val input = (request.messages.single().parts.single() as ChatMessagePart.Text).text
        assertTrue(input.contains("who is alice?"), "the query travels verbatim: $input")
    }

    @Test
    fun `an error outcome passes isError through`() = runBlocking {
        val hand = FakeHand(runScript = { errorRunFlow("upstream", "provider unavailable") })
        val provider = provider(hand)
        val result = provider.execute(investigateCall("who is alice?"))

        assertTrue(result.isError)
        assertEquals("call_1", result.id)
        assertEquals("gsg__investigate", result.tool)
        val text = (result.parts.single() as ChatMessagePart.Text).text
        assertTrue(text.contains("upstream"), text)
        assertTrue(text.contains("provider unavailable"), text)
    }

    @Test
    fun `a blank or missing query answers an error result without calling the model`() = runBlocking {
        val hand = FakeHand()
        val provider = provider(hand)
        val missing = provider.execute(
            ToolCallRequest("call_1", "gsg__investigate", buildJsonObject { })
        )
        val blank = provider.execute(
            ToolCallRequest("call_2", "gsg__investigate", buildJsonObject { put("query", "   ") })
        )
        assertTrue(missing.isError)
        assertTrue(blank.isError)
        assertTrue(hand.requests.isEmpty(), "no investigate run for an invalid query")
    }
}
