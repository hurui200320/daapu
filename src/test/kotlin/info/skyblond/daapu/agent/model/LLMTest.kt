package info.skyblond.daapu.agent.model

import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Tests for [LLM]'s constructor fail-fast contract. Through the config-driven
 * path (`ModelCatalog.fromConfig`) the same rules are enforced first at config
 * load (`LlmModelEntryConfig.validate`, with the operator's entry path), so
 * these constructor checks are a defensive net for DIRECT construction only —
 * but nothing may bypass them either: a zero/negative budget would misclassify
 * every round at run time (immediate context/output exhaustion) instead of
 * failing fast at boot.
 */
class LLMTest {

    private fun model(
        contextLength: Long = 1000,
        maxOutputTokens: Long = 500,
    ) = LLM(
        provider = ModelProvider("p", "http://127.0.0.1:9/v1", "test"),
        modelId = "m",
        contextLength = contextLength,
        maxOutputTokens = maxOutputTokens,
        capabilities = emptySet(),
        compactionTriggerFraction = 0.75,
        compactionKeepRounds = 2,
    )

    @Test
    fun `a non-positive context length fails fast`() {
        assertFailsWith<IllegalArgumentException> { model(contextLength = 0) }
        assertFailsWith<IllegalArgumentException> { model(contextLength = -1) }
    }

    @Test
    fun `a non-positive output budget fails fast`() {
        assertFailsWith<IllegalArgumentException> { model(maxOutputTokens = 0) }
        assertFailsWith<IllegalArgumentException> { model(maxOutputTokens = -5) }
    }
}
