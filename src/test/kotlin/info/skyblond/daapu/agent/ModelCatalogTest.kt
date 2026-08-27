package info.skyblond.daapu.agent

import info.skyblond.daapu.agent.model.LLMCapability
import info.skyblond.daapu.config.EmbeddingModelEntryConfig
import info.skyblond.daapu.config.LlmModelEntryConfig
import info.skyblond.daapu.config.LlmProviderConfig
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for the config-driven catalog construction (`fromConfig`): the
 * capability-token parsing — including the defensive re-checks the config
 * layer cannot express — plus the structural fail-fast guarantees
 * (empty catalog, duplicate composite ids).
 */
class ModelCatalogTest {

    private fun llmEntry(
        modelId: String = "test/model",
        capabilities: List<String> = listOf("tool_calls"),
    ) = LlmModelEntryConfig(
        modelId = modelId,
        contextLength = 1000,
        maxOutputTokens = 500,
        capabilities = capabilities,
        compactionTriggerFraction = 0.75,
        compactionKeepRounds = 2,
    )

    private fun providers(
        vararg entries: Pair<String, Pair<List<LlmModelEntryConfig>, List<EmbeddingModelEntryConfig>>>,
    ) = entries.associate { (id, models) ->
        id to LlmProviderConfig(
            apiKey = "key",
            baseUrl = "http://localhost:9/v1",
            llm = models.first,
            embedding = models.second,
        )
    }

    @Test
    fun `every capability token maps onto its runtime capability`() {
        // one entry per reasoning effort: a model carries at most ONE
        // reasoning capability (see below), so the full vocabulary cannot
        // share a single entry
        val efforts = listOf("minimal", "low", "medium", "high", "xhigh", "max")
        val reasoningEntries = efforts.map { effort ->
            llmEntry(modelId = "reasoning-$effort", capabilities = listOf("reasoning:$effort"))
        }
        val singletonEntry = llmEntry(
            modelId = "singletons",
            capabilities = listOf("image", "video", "audio", "document", "tool_calls"),
        )
        val catalog = ModelCatalog.fromConfig(
            providers("p" to Pair(reasoningEntries + singletonEntry, emptyList()))
        )

        assertEquals(
            setOf(
                LLMCapability.Input.Vision.Image,
                LLMCapability.Input.Vision.Video,
                LLMCapability.Input.Audio,
                LLMCapability.Input.Document,
                LLMCapability.Output.ToolCalls,
            ),
            catalog.findModel("p/singletons")!!.capabilities,
        )
        for (effort in efforts) {
            val model = catalog.findModel("p/reasoning-$effort")!!
            assertEquals(setOf<LLMCapability>(LLMCapability.Output.Reasoning(effort)), model.capabilities)
        }

        // the whole field set must survive the mapping verbatim — the budgets
        // and compaction knobs drive exhaustion classification and proactive
        // compaction, so a dropped or shuffled value must not boot
        // (the embedding analog lives further down; see also LLM.init's own checks)
        val knobs = catalog.findModel("p/singletons")!!
        assertEquals(0.75, knobs.compactionTriggerFraction)
        assertEquals(2, knobs.compactionKeepRounds)
    }

    @Test
    fun `more than one reasoning capability fails fast at boot`() {
        // the duplicate-token check only rejects IDENTICAL strings; two
        // distinct efforts both parse — but a model runs with exactly one
        // reasoning_effort (LLM.reasoningEffort() picks one), so letting two
        // through would silently resolve by config order instead of naming
        // the ambiguity
        val e = assertFailsWith<IllegalArgumentException> {
            ModelCatalog.fromConfig(
                providers(
                    "p" to Pair(
                        listOf(llmEntry(capabilities = listOf("tool_calls", "reasoning:low", "reasoning:high"))),
                        emptyList(),
                    )
                )
            )
        }
        assertTrue(e.message!!.startsWith("p/test/model "), e.message)
        assertTrue(e.message!!.contains("2 reasoning capabilities"), e.message)
        assertTrue(e.message!!.contains("(low, high)"), e.message)
    }

    @Test
    fun `an unknown capability token fails fast at boot`() {
        val e = assertFailsWith<IllegalArgumentException> {
            ModelCatalog.fromConfig(providers("p" to (listOf(llmEntry(capabilities = listOf("vision"))) to emptyList())))
        }
        // the owner path comes first: the operator must see WHICH entry failed
        assertTrue(e.message!!.startsWith("p/test/model "), e.message)
        assertTrue(e.message!!.contains("Unknown capability token 'vision'"), e.message)
    }

    @Test
    fun `a reasoning effort outside the pi-ai union fails fast at boot`() {
        // the JSON Schema restricts the effort (documentation only) — the
        // runtime check is what actually stops a typo'd effort like this
        // from failing every request as an upstream error mid-session
        val e = assertFailsWith<IllegalArgumentException> {
            ModelCatalog.fromConfig(providers("p" to (listOf(llmEntry(capabilities = listOf("reasoning:hig"))) to emptyList())))
        }
        assertTrue(e.message!!.startsWith("p/test/model "), e.message)
        assertTrue(e.message!!.contains("Unknown reasoning effort 'hig'"), e.message)
        assertTrue(e.message!!.contains("reasoning:hig"), e.message)
    }

    @Test
    fun `the empty-catalog case names the missing config surface`() {
        val e = assertFailsWith<IllegalArgumentException> {
            ModelCatalog.fromConfig(providers("p" to (emptyList<LlmModelEntryConfig>() to emptyList())))
        }
        assertTrue(e.message!!.contains("providers.<id>.llm"), e.message)
    }

    @Test
    fun `an invalid provider key fails fast at catalog build`() {
        // the same charset rule AppConfig.validate() applies on config load,
        // repeated in fromConfig so direct construction cannot skip it (the
        // id becomes part of every wire-visible model id)
        val e = assertFailsWith<IllegalArgumentException> {
            ModelCatalog.fromConfig(
                providers("bad id" to Pair(listOf(llmEntry()), emptyList())),
            )
        }
        assertTrue(e.message!!.contains("providers key"), e.message)
        assertTrue(e.message!!.contains("'bad id'"), e.message)
    }

    @Test
    fun `duplicate composite ids fail fast across kinds`() {
        // the composite id namespaces by provider, so two providers may share
        // a raw modelId ("p/other" != "q/other") — but within one gateway the
        // kinds collide: an LLM and an embedding entry resolving to the same
        // id would break findModel/findEmbeddingModel
        val e = assertFailsWith<IllegalArgumentException> {
            ModelCatalog.fromConfig(
                providers(
                    "p" to Pair(
                        listOf(llmEntry(modelId = "shared/model"), llmEntry(modelId = "other")),
                        listOf(EmbeddingModelEntryConfig(modelId = "shared/model", dimensions = 8)),
                    ),
                    "q" to Pair(listOf(llmEntry(modelId = "other")), emptyList()),
                )
            )
        }
        assertTrue(e.message!!.contains("Duplicate model id"), e.message)
        assertTrue(e.message!!.contains("p/shared/model"), e.message)
    }

    @Test
    fun `two providers may share a raw modelId`() {
        // the duplicate detection keys on the COMPOSITE id ({provider}/{modelId}):
        // different providers namespace their raw ids independently, and this
        // must stay so (a change keying on the raw id would break multi-gateway
        // setups serving mirrored upstream models under separate gateways)
        val catalog = ModelCatalog.fromConfig(
            providers(
                "p" to Pair(listOf(llmEntry(modelId = "shared"), llmEntry(modelId = "other")), emptyList()),
                "q" to Pair(listOf(llmEntry(modelId = "shared")), emptyList()),
            )
        )
        assertNotNull(catalog.findModel("p/shared"))
        assertNotNull(catalog.findModel("q/shared"))
        assertEquals(3, catalog.models.size)
    }

    @Test
    fun `an out-of-range embedding dimensions fails fast at boot naming the composite id`() {
        // the bounds live in ONE shared check; through the catalog build the
        // runtime type is the caller, so the error labels itself with the
        // composite id (the config-load path prefixes the entry path instead)
        val e = assertFailsWith<IllegalArgumentException> {
            ModelCatalog.fromConfig(
                providers(
                    "p" to Pair(
                        listOf(llmEntry()),
                        listOf(EmbeddingModelEntryConfig(modelId = "wide/model", dimensions = 3000)),
                    ),
                )
            )
        }
        assertTrue(e.message!!.startsWith("p/wide/model.dimensions "), e.message)
        assertTrue(e.message!!.contains("must be at most 2000"), e.message)
    }

    @Test
    fun `an embedding entry maps onto the runtime model verbatim`() {
        // the whole field set must survive the mapping: modelId, dimensions,
        // and the gateway knobs (additionalProperties) the /v1/embed request
        // merges into its body — null stays absent by default
        val knobs = EmbeddingModelEntryConfig(
            modelId = "knobs/model",
            dimensions = 512,
            additionalProperties = buildJsonObject { put("service_tier", "priority") },
        )
        val catalog = ModelCatalog.fromConfig(
            providers("p" to Pair(listOf(llmEntry()), listOf(knobs, EmbeddingModelEntryConfig(modelId = "plain", dimensions = 8))))
        )
        val withKnobs = catalog.findEmbeddingModel("p/knobs/model")!!
        assertEquals("p/knobs/model", withKnobs.id)
        assertEquals(512, withKnobs.dimensions)
        assertEquals(
            buildJsonObject { put("service_tier", "priority") },
            withKnobs.additionalProperties,
        )
        assertEquals(null, catalog.findEmbeddingModel("p/plain")?.additionalProperties, "no gateway knobs by default")
    }
}
