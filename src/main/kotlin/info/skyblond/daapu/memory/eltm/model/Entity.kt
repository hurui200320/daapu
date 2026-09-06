package info.skyblond.daapu.memory.eltm.model

import info.skyblond.daapu.memory.eltm.EltmService

/**
 * One ELTM entity: a named thing with a category. The category disambiguates
 * homonyms ("Apple" as fruit vs company). All descriptive content lives in
 * the diary notes, never here.
 */
data class EltmEntity(
    val id: Long,
    val canonicalName: String,
    val category: String,
)

/**
 * An entity read view carrying its latest diary note inline plus its
 * content-backed prominence counters (the "how much do we know" signal:
 * [noteCount] diary entries, [relationshipCount] relationships in BOTH
 * directions, valid or invalidated — each triple counts once, `valid` is a
 * state, not a second row; drill into history via
 * [EltmService.getEntityNotes]) and its [attributes] (current-state facts,
 * keys alphabetically ordered).
 */
data class EntityView(
    val entity: EltmEntity,
    val noteCount: Int,
    val relationshipCount: Int,
    val latestNote: EltmNote?,
    val attributes: EntityAttributes,
)

/**
 * A vector-search hit: the [EntityView] plus its cosine [score] — the
 * whole model-visible picture in one batch, so the writer LLM can weigh
 * candidates beyond similarity without a per-hit drill-down.
 */
data class EntityWithScore(
    val view: EntityView,
    val score: Double,
)

/**
 * One entity waiting to be created or fetched by its `(name, category)`
 * key: the draft for the bulk create ([EltmService.createEntities] /
 * [EltmService.createRelationships]'s endpoints), the stored row is
 * [EltmEntity]. Normalized exactly like [EltmService.createEntity]'s
 * arguments.
 */
data class EntityDraft(
    val name: String,
    val category: String,
)
