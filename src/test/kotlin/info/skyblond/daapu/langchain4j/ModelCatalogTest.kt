package info.skyblond.daapu.langchain4j

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelCatalogTest {

    private val catalog = ModelCatalog("http://gateway.example/v1")

    @Test
    fun `catalog exposes the same models as the koog catalog`() {
        // ids and token limits must mirror koog/client/LLMs.kt until the
        // runtime switches over in #6; pinned by value so a catalog edit
        // without the switch is caught by tests first
        assertEquals(
            listOf(
                Triple("cerebras/gpt-oss-120b", 131000L, 40000L),
                Triple("cerebras/gemma-4-31b", 131072L, 40000L),
                Triple("novita/google/gemma-4-31b-it", 262144L, 131072L),
            ),
            catalog.models.map { Triple(it.id, it.contextLength, it.maxOutputTokens) },
        )
    }

    @Test
    fun `catalog capabilities pinned by value`() {
        val byId = catalog.models.associateBy { it.id }
        val gptOss = byId.getValue("cerebras/gpt-oss-120b")
        assertFalse(gptOss.supports(ModelCapability.VisionImage), "gpt-oss-120b has no vision")
        assertTrue(gptOss.supports(ModelCapability.Reasoning))
        assertTrue(gptOss.supports(ModelCapability.ToolCalls))
        for (id in listOf("cerebras/gemma-4-31b", "novita/google/gemma-4-31b-it")) {
            val gemma = byId.getValue(id)
            assertTrue(gemma.supports(ModelCapability.VisionImage), "$id should have vision")
            assertTrue(gemma.supports(ModelCapability.Reasoning))
            assertTrue(gemma.supports(ModelCapability.ToolCalls))
        }
    }

    @Test
    fun `model ids are unique`() {
        assertEquals(catalog.models.size, catalog.models.map { it.id }.toSet().size)
    }

    @Test
    fun `base url is stamped on every entry`() {
        assertTrue(catalog.models.all { it.baseUrl == "http://gateway.example/v1" })
        assertEquals(
            "http://other.example",
            ModelCatalog("http://other.example").models.first().baseUrl,
        )
    }

    @Test
    fun `findModel finds catalog entries and misses unknown ids`() {
        assertEquals("cerebras/gemma-4-31b", catalog.findModel("cerebras/gemma-4-31b")?.id)
        assertNull(catalog.findModel("no/such-model"))
    }
}
