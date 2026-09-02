package info.skyblond.daapu.config

import kotlinx.serialization.Serializable

/**
 * The memory pipeline settings: compaction (`agent/pipeline/compaction/`)
 * and the ELTM (external long-term memory, `memory/eltm/`). The compaction
 * trigger fraction and keep rounds are per-model (`agent/model/LLM.kt`),
 * since they depend on the model's context size. All model ids are REQUIRED
 * and reference the catalog (`agent/ModelCatalog.kt`); they are resolved
 * once at startup by the DI container (`di/AppModule.kt`) and reused for
 * every run — a chat run's own model is never used for the one-shot
 * pipeline. Catalog membership is validated at startup (the config layer
 * does not know the catalog).
 */
@Serializable
data class MemoryConfig(
    /** Catalog model id for the compaction summarizer. */
    val compactModel: String,
    /**
     * The external long-term memory (ELTM) settings.
     */
    val eltm: EltmConfig,
) {
    fun validate() {
        require(compactModel.isNotBlank()) { "memory.compactModel must not be blank" }
        eltm.validate()
    }
}

/**
 * The ELTM (external long-term memory) settings: the diary model
 * (entities/relationships/notes, see `memory/eltm/`), the models behind it,
 * and the writer's knobs. The model ids are
 * REQUIRED and reference the catalog (`agent/ModelCatalog.kt`); the
 * extraction, embedding, writer, and rewrite ids are resolved once at
 * startup by the DI container (`di/AppModule.kt`; unknown ids and a
 * writer model without tool-call support fail fast). The embedding
 * model's output dimensions must be at most
 * [MAX_VECTOR_DIMENSIONS] (pgvector's HNSW indexing limit): the ELTM
 * columns are fixed at that width and shorter vectors are zero-padded on
 * write, so switching embedding models never needs a schema change.
 */
@Serializable
data class EltmConfig(
    /**
     * Catalog model id for the memory extractor (sees the raw dropped
     * history, images included); no tool call support required. REQUIRED.
     */
    val extractionModel: String,
    /**
     * Catalog id of the embedding model (`agent/ModelCatalog.kt`), whose
     * entry carries the output dimensions. REQUIRED.
     */
    val embeddingModel: String,
    /** Catalog LLM id of the ELTM writer (a tool loop); REQUIRED. */
    val writerModel: String,
    /**
     * Catalog LLM id of the query rewrite one-shot (a no-tools `/v1/run`,
     * see `agent/pipeline/rewrite/QueryRewriteService.kt`): rewrites the
     * run's latest input into standalone retrieval queries before the chat
     * round; REQUIRED.
     */
    val rewriteModel: String,
    /**
     * How many trailing user rounds of the chat feed the query rewrite
     * one-shot; must be at least 1. REQUIRED: the round limit is related to
     * the rewrite model's context size, so it must be explicit.
     */
    val rewriteRounds: Int,
    /**
     * How many related entities the ELTM context injection (search seeded by
     * the rewritten query) puts into `<memories>`' `<related-entities>`;
     * REQUIRED — the injected size is related to the main model's context,
     * so the operator must set it explicitly. `0` skips the entity search
     * (no embed call, empty section).
     */
    val relatedEntitiesLimit: Int,
    /**
     * How many related diary notes the ELTM context injection puts into
     * `<memories>`' `<related-notes>`; REQUIRED like
     * [relatedEntitiesLimit]. `0` skips the note search (no embed call,
     * empty section); with both limits `0`, the query rewrite one-shot is
     * skipped too (it exists only to feed these searches).
     */
    val relatedNotesLimit: Int,
    /**
     * Vector cosine similarity floor for the `create_entity` near-match
     * candidates (0..1): a candidate above it is offered to the writer LLM
     * for disambiguation/merge decisions.
     */
    val entityMatchThreshold: Double = 0.5,
    /**
     * Vector cosine similarity floor for `search_notes` (0..1): the RAG
     * floor under which a diary entry is not surfaced.
     */
    val noteSearchThreshold: Double = 0.1,
    /** Round cap for the ELTM writer tool loop; `0` = unlimited. */
    val maxWriterRounds: Int = 150,
    /**
     * How many background workers drain the extraction queue
     * (`memory/eltm/ExtractionQueueWorker.kt`) — the chat-deletion path
     * enqueues history snapshots and these workers run the memory pipeline
     * off the request path. REQUIRED: it bounds the concurrent background
     * extractions (their LLM calls dominate; each worker runs one job at a
     * time).
     */
    val queueWorkers: Int,
    /**
     * The extraction queue's visibility timeout in minutes: a claimed job
     * moves its `visible_after` this far into the future, so it is invisible
     * to every worker for the window. Doubles as the crash-recovery delay (a
     * worker dying mid-job leaves the job to re-emerge at the lease
     * boundary) — it must comfortably exceed the worst-case extraction
     * duration, or a still-running job gets claimed twice (benign — the ELTM
     * writer deduplicates — but wasteful). REQUIRED. See
     * `memory/eltm/ExtractionQueue.kt`.
     */
    val jobTimeoutMinutes: Int,
    /**
     * How long after a KNOWN failure a queue job re-emerges for its retry
     * (unlimited retries; every failure logs). Typically much shorter than
     * [jobTimeoutMinutes] — the lease covers crashes, this covers the
     * ordinary failure path. REQUIRED. See `memory/eltm/ExtractionQueue.kt`.
     */
    val retryDelayMinutes: Int,
) {
    fun validate() {
        listOf(
            "memory.eltm.extractionModel" to extractionModel,
            "memory.eltm.embeddingModel" to embeddingModel,
            "memory.eltm.writerModel" to writerModel,
            "memory.eltm.rewriteModel" to rewriteModel,
        ).forEach { (name, id) ->
            require(id.isNotBlank()) { "$name must not be blank" }
        }
        require(rewriteRounds >= 1) { "memory.eltm.rewriteRounds must be >= 1, got $rewriteRounds" }
        require(relatedEntitiesLimit >= 0) {
            "memory.eltm.relatedEntitiesLimit must be >= 0, got $relatedEntitiesLimit"
        }
        require(relatedNotesLimit >= 0) {
            "memory.eltm.relatedNotesLimit must be >= 0, got $relatedNotesLimit"
        }
        require(entityMatchThreshold in 0.0..1.0) {
            "memory.eltm.entityMatchThreshold must be in [0, 1], got $entityMatchThreshold"
        }
        require(noteSearchThreshold in 0.0..1.0) {
            "memory.eltm.noteSearchThreshold must be in [0, 1], got $noteSearchThreshold"
        }
        require(maxWriterRounds >= 0) { "memory.eltm.maxWriterRounds must be >= 0, got $maxWriterRounds" }
        require(queueWorkers >= 1) { "memory.eltm.queueWorkers must be >= 1, got $queueWorkers" }
        require(jobTimeoutMinutes >= 1) {
            "memory.eltm.jobTimeoutMinutes must be >= 1, got $jobTimeoutMinutes"
        }
        require(retryDelayMinutes >= 1) {
            "memory.eltm.retryDelayMinutes must be >= 1, got $retryDelayMinutes"
        }
    }
}
