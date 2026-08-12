package info.skyblond.daapu.lc4j

import info.skyblond.daapu.agent.lc4j.llm.LLMCapability
import info.skyblond.daapu.agent.lc4j.llm.ModelCatalog
import info.skyblond.daapu.agent.lc4j.provider.BifrostProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelCatalogTest {

    private val catalog = ModelCatalog(BifrostProvider("bifrost", "http://gateway.example/v1", "test-key"))

    @Test
    fun `catalog exposes the pinned models`() {
        // ids and token limits pinned by value so a catalog edit is caught by
        // tests first; the id is provider-prefixed
        assertEquals(
            listOf(
                Triple("bifrost/cerebras/gpt-oss-120b", 131000L, 40000L),
                Triple("bifrost/cerebras/gemma-4-31b", 131072L, 40000L),
                Triple("bifrost/novita/google/gemma-4-31b-it", 262144L, 131072L),
            ),
            catalog.models.map { Triple(it.id, it.contextLength, it.maxOutputTokens) },
        )
    }

    @Test
    fun `catalog capabilities pinned by value`() {
        val byId = catalog.models.associateBy { it.id }
        val gptOss = byId.getValue("bifrost/cerebras/gpt-oss-120b")
        assertFalse(gptOss.supports(LLMCapability.Input.Vision.Image), "gpt-oss-120b has no vision")
        assertTrue(gptOss.supports(LLMCapability.Output.Reasoning))
        assertTrue(gptOss.supports(LLMCapability.Output.ToolCalls))
        for (id in listOf("bifrost/cerebras/gemma-4-31b", "bifrost/novita/google/gemma-4-31b-it")) {
            val gemma = byId.getValue(id)
            assertTrue(gemma.supports(LLMCapability.Input.Vision.Image), "$id should have vision")
            assertTrue(gemma.supports(LLMCapability.Output.Reasoning))
            assertTrue(gemma.supports(LLMCapability.Output.ToolCalls))
        }
    }

    @Test
    fun `model ids are unique`() {
        assertEquals(catalog.models.size, catalog.models.map { it.id }.toSet().size)
    }

    @Test
    fun `provider id prefixes every model id`() {
        assertTrue(catalog.models.all { it.id.startsWith("bifrost/") })
        // extra providers are accepted (ids unique), but the pinned entries
        // still resolve to the bifrost provider
        val extended = ModelCatalog(
            BifrostProvider("bifrost", "http://one.example", "test-key"),
            BifrostProvider("other", "http://two.example", "test-key"),
        )
        assertEquals(
            catalog.models.map { it.id },
            extended.models.map { it.id },
            "the pinned model entries still resolve to the bifrost provider",
        )
        assertTrue(extended.models.all { it.id.startsWith("bifrost/") })
    }

    @Test
    fun `findModel finds catalog entries and misses unknown ids`() {
        assertEquals("bifrost/cerebras/gemma-4-31b", catalog.findModel("bifrost/cerebras/gemma-4-31b")?.id)
        assertNull(catalog.findModel("no/such-model"))
        assertNull(catalog.findModel("cerebras/gemma-4-31b"), "the bare model id must not match")
    }

    @Test
    fun `duplicate provider ids are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            ModelCatalog(
                BifrostProvider("bifrost", "http://one.example", "test-key"),
                BifrostProvider("bifrost", "http://two.example", "test-key"),
            )
        }
    }

    @Test
    fun `catalog without a bifrost provider fails fast`() {
        assertFailsWith<IllegalArgumentException> {
            ModelCatalog(BifrostProvider("other", "http://other.example", "test-key"))
        }
    }
}
