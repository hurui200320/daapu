package info.skyblond.daapu.config

import kotlinx.serialization.Serializable

/**
 * The session-title generation settings (see `agent/pipeline/TitleGenerator.kt`).
 * The model id is REQUIRED and references the catalog
 * (`agent/ModelCatalog.kt`); it is resolved once at startup by the DI
 * container (`di/AppModule.kt`), like the memory pipeline models — a
 * chat run's own model is never used for it.
 */
@Serializable
data class TitleConfig(
    /** Catalog model id for the title generator. */
    val model: String,
    /**
     * How many trailing user rounds of the history feed the title generator;
     * `0` (default) means the whole history.
     */
    val lastNRound: Int = 0,
) {
    fun validate() {
        require(model.isNotBlank()) { "title.model must not be blank" }
        require(lastNRound >= 0) { "title.lastNRound must be >= 0, got $lastNRound" }
    }
}
