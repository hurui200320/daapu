package info.skyblond.daapu.testutil

import info.skyblond.daapu.agent.ModelCatalog
import info.skyblond.daapu.agent.model.LLM
import info.skyblond.daapu.config.testAppConfig

/**
 * The model fixtures every catalog-consuming test resolves through
 * [testLlm]. There is exactly ONE source of the metadata:
 * `TestConfig.kt`'s provider entries, built here via the SAME production
 * code path as the DI container ([ModelCatalog.fromConfig] over
 * [testAppConfig]) — so raw-model tests and config-driven DI tests always
 * see the same ids with the same metadata, and an edit to one cannot drift
 * from the other. A typo'd composite id fails fast.
 */
private val CATALOG = ModelCatalog.fromConfig(testAppConfig().providers)

/** The test chat models keyed by their composite `{provider}/{modelId}` id. */
val TEST_MODELS_BY_ID: Map<String, LLM> = CATALOG.models.associateBy { it.id }

/** A test chat model by composite id (see [TEST_MODELS_BY_ID]); fails fast on a typo. */
fun testLlm(id: String): LLM = TEST_MODELS_BY_ID.getValue(id)
