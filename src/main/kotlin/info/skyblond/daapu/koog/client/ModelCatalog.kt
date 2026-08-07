package info.skyblond.daapu.koog.client

import ai.koog.prompt.llm.LLModel

/**
 * All models the web UI can pick from.
 *
 * Deliberately in its own file, NOT in `LLMs.kt`: a top-level `modelCatalog`
 * in the same class as [createModel] would create a JVM class-init cycle
 * (the catalog reads `Cerebras`/`Novita` object fields while those objects'
 * initialization calls `createModel` back into the same class), silently
 * leaving the catalog entries null.
 */
val modelCatalog: List<LLModel> = listOf(
    Cerebras.GPT_OSS_120B,
    Cerebras.Gemma4_31B,
    Novita.Gemma4_31B,
)

fun findModel(id: String): LLModel? = modelCatalog.firstOrNull { it.id == id }
