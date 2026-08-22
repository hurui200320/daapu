package info.skyblond.daapu

import info.skyblond.daapu.agent.ModelCatalog
import info.skyblond.daapu.agent.model.EmbeddingModel
import info.skyblond.daapu.config.AppConfig
import info.skyblond.daapu.config.MAX_VECTOR_DIMENSIONS
import info.skyblond.daapu.config.loadConfig
import info.skyblond.daapu.db.EltmEntities
import info.skyblond.daapu.db.EltmEntityAttributes
import info.skyblond.daapu.db.EltmNotes
import info.skyblond.daapu.db.MemoryMetaNumber
import info.skyblond.daapu.db.initDatabase
import info.skyblond.daapu.db.withTransaction
import info.skyblond.daapu.di.daapuModule
import info.skyblond.daapu.hand.HandService
import info.skyblond.daapu.memory.eltm.PostgresEltmService
import info.skyblond.daapu.memory.eltm.entityEmbeddingText
import info.skyblond.daapu.memory.eltm.noteEmbeddingText
import info.skyblond.daapu.memory.eltm.padVector
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.koin.dsl.koinApplication

/**
 * One-off maintenance entry point: re-embed EVERY stored ELTM vector
 * (`eltm_entities.embedding`, `eltm_notes.embedding`) with the embedding
 * model the config currently points to (`memory.eltm.embeddingModel`).
 * Run it after switching embedding models — the old vectors are useless to
 * the new model, and cosine similarities across models are not comparable.
 *
 * Run via `./gradlew reembed` (uses `./config.jsonc` like the server).
 *
 * The text shapes are the SAME functions the write path uses —
 * [entityEmbeddingText] (name + category + `key: value` attribute lines)
 * and [noteEmbeddingText] (the trimmed note) — so a future format change
 * stays consistent between new writes and this refresh.
 *
 * Rows are processed in id order, one page at a time (bounded memory),
 * embedded in batches through the hand (`/v1/embed`), and each batch is
 * written back in its own transaction. Fails fast on any embed error (the
 * hand already retries `hand.maxRetries` times per request): already
 * written batches stay written, a failed run is safely re-runnable.
 *
 * On full success the global ELTM write counter (`memory_meta_number.
 * eltm_version`, [PostgresEltmService.ELTM_VERSION_KEY]) is bumped ONCE, so
 * every chat's next run flags `eltm-updated` — the retrieval results are
 * different under the new model.
 *
 * Relationships and attributes carry no stored vectors, so nothing else
 * needs refreshing. The vector width never depends on the
 * model (zero-padded to [MAX_VECTOR_DIMENSIONS]), so no schema change is
 * ever needed.
 */
fun main() {
    val config = loadConfig()
    initDatabase(config.database.url, config.database.user, config.database.password)
    // the same container the server uses: only HandService and ModelCatalog
    // are resolved, so the eagerly-connecting MCP tool servers are never
    // constructed; closing the app closes the hand client (onClose)
    val koin = koinApplication { modules(daapuModule(config)) }
    try {
        runBlocking {
            reembedAll(
                config = config,
                hand = koin.koin.get(),
                catalog = koin.koin.get(),
            )
        }
    } finally {
        koin.close()
    }
}

private suspend fun reembedAll(
    config: AppConfig,
    hand: HandService,
    catalog: ModelCatalog,
) {
    val model = catalog.findEmbeddingModel(config.memory.eltm.embeddingModel)
        ?: throw IllegalArgumentException(
            "memory.eltm.embeddingModel '${config.memory.eltm.embeddingModel}' is not in the model catalog"
        )
    val maxRetries = config.hand.maxRetries
    val timeoutMs = config.hand.streamIdleTimeoutMs

    val entityCount = reembedEntities(hand, model, maxRetries, timeoutMs)
    val noteCount = reembedNotes(hand, model, maxRetries, timeoutMs)

    if (entityCount + noteCount == 0L) {
        logger.info { "nothing to re-embed, the ELTM is empty; version counter left untouched" }
        return
    }
    withTransaction {
        MemoryMetaNumber.update({ MemoryMetaNumber.key eq PostgresEltmService.ELTM_VERSION_KEY }) {
            it[MemoryMetaNumber.value] = MemoryMetaNumber.value + 1L
        }
    }
    logger.info { "done: re-embedded $entityCount entities and $noteCount notes " +
            "(eltm_version bumped so the next chat run flags eltm-updated)" }
}

/**
 * Re-embed all entities in id order, one page at a time; the page's
 * attributes ride the entity's embedding text, read with ONE query in the
 * same transaction as the page read (a consistent snapshot). Returns the
 * number of rows processed.
 */
private suspend fun reembedEntities(
    hand: HandService,
    model: EmbeddingModel,
    maxRetries: Int,
    timeoutMs: Long,
): Long {
    var lastId = 0L
    var done = 0L
    while (true) {
        val page: List<Pair<Long, String>> = withTransaction {
            val rows = EltmEntities.selectAll()
                .where { EltmEntities.id greater lastId }
                .orderBy(EltmEntities.id)
                .limit(PAGE_SIZE)
                .toList()
            val ids = rows.map { it[EltmEntities.id] }
            val attributesByEntity = if (ids.isEmpty()) {
                emptyMap()
            } else {
                EltmEntityAttributes.selectAll()
                    .where { EltmEntityAttributes.entityId inList ids }
                    .groupBy { it[EltmEntityAttributes.entityId] }
                    .mapValues { (_, rows) ->
                        rows.associate { it[EltmEntityAttributes.key] to it[EltmEntityAttributes.value] }
                    }
            }
            rows.map { row ->
                row[EltmEntities.id] to entityEmbeddingText(
                    canonicalName = row[EltmEntities.canonicalName],
                    category = row[EltmEntities.category],
                    attributes = attributesByEntity[row[EltmEntities.id]] ?: emptyMap(),
                )
            }
        }
        if (page.isEmpty()) break
        page.writeEachBatch(hand, model, maxRetries, timeoutMs) { id, vector ->
            EltmEntities.update({ EltmEntities.id eq id }) {
                it[EltmEntities.embedding] = vector
            }
        }
        lastId = page.last().first
        done += page.size
        logger.info { "re-embedded $done entities so far" }
    }
    return done
}

/** Re-embed all notes in id order, one page at a time. Returns the rows processed. */
private suspend fun reembedNotes(
    hand: HandService,
    model: EmbeddingModel,
    maxRetries: Int,
    timeoutMs: Long,
): Long {
    var lastId = 0L
    var done = 0L
    while (true) {
        val page: List<Pair<Long, String>> = withTransaction {
            EltmNotes.selectAll()
                .where { EltmNotes.id greater lastId }
                .orderBy(EltmNotes.id)
                .limit(PAGE_SIZE)
                .map { row ->
                    row[EltmNotes.id] to noteEmbeddingText(row[EltmNotes.note])
                }
        }
        if (page.isEmpty()) break
        page.writeEachBatch(hand, model, maxRetries, timeoutMs) { id, vector ->
            EltmNotes.update({ EltmNotes.id eq id }) {
                it[EltmNotes.embedding] = vector
            }
        }
        lastId = page.last().first
        done += page.size
        logger.info { "re-embedded $done notes so far" }
    }
    return done
}

/**
 * Embed the pair's texts in [EMBED_BATCH_SIZE] chunks through the hand and
 * write each chunk's vectors back in its own transaction via [update] (the
 * embed call happens OUTSIDE the transaction — it must not hold a
 * connection). [update] receives the row id and its padded vector.
 */
private suspend fun List<Pair<Long, String>>.writeEachBatch(
    hand: HandService,
    model: EmbeddingModel,
    maxRetries: Int,
    timeoutMs: Long,
    update: suspend (id: Long, vector: List<Float>) -> Unit,
) {
    chunked(EMBED_BATCH_SIZE).forEach { batch ->
        val vectors = hand.embed(model, batch.map { it.second }, maxRetries, timeoutMs)
            .vectors
            .map { padVector(it, MAX_VECTOR_DIMENSIONS) }
        withTransaction {
            batch.zip(vectors).forEach { (idText, vector) ->
                update(idText.first, vector)
            }
        }
    }
}

/** Rows per keyset page read from the tables (bounded memory). */
private const val PAGE_SIZE = 128

/** Texts per `/v1/embed` call. */
private const val EMBED_BATCH_SIZE = 32

private val logger = KotlinLogging.logger {}