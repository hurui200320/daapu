package info.skyblond.daapu.testutil

import info.skyblond.daapu.agent.ModelCatalog
import info.skyblond.daapu.agent.model.EmbeddingModel
import info.skyblond.daapu.agent.pipeline.eltm.EltmWriterService
import info.skyblond.daapu.config.testAppConfig
import info.skyblond.daapu.hand.FakeHand
import info.skyblond.daapu.hand.HandClient
import info.skyblond.daapu.hand.HandRunPolicy
import info.skyblond.daapu.memory.eltm.*

/**
 * An [EltmWriterService] wired to a scripted [hand] and a real
 * [PostgresEltmService] (the test database) by default,
 * with the catalog's default model and generous run knobs.
 */
fun testEltmWriterService(
    hand: FakeHand,
    eltmService: EltmService = testPostgresEltmService(FakeHand()),
    maxWriterRounds: Int = 150,
): EltmWriterService {
    val model = testLlm("bifrost/cerebras/gemma-4-31b")
    return EltmWriterService(
        writerModel = model,
        hand = testHandService(hand),
        eltmService = eltmService,
        maxWriterRounds = maxWriterRounds,
        policy = HandRunPolicy(0, 0),
    )
}

/**
 * A [PostgresEltmService] against the REAL test database ([TestDb]), wired
 * to a scripted [hand] (embeddings come from the hand seam, never the DB):
 * the `testAppConfig()` embedding model, the config thresholds, a no-retry
 * policy. Callers pass e.g. `FakeHand(DeterministicEmbeddings().apply
 * { ... }.script)` for exact similarity control. Extending classes wipe
 * the tables via [DbTestBase] before each test.
 */
fun testPostgresEltmService(
    hand: HandClient = FakeHand(),
): PostgresEltmService {
    TestDb.init()
    val config = testAppConfig()
    val model = testEmbeddingModel()
    return PostgresEltmService(
        embeddingModel = model,
        hand = testHandService(hand),
        entityMatchThreshold = config.memory.eltm.entityMatchThreshold,
        noteSearchThreshold = config.memory.eltm.noteSearchThreshold,
        policy = HandRunPolicy(0, 0),
    )
}

/**
 * The `testAppConfig()` embedding model: tests pin vectors to its
 * [info.skyblond.daapu.agent.model.EmbeddingModel.dimensions] so the
 * hand-side dimension check passes.
 */
fun testEmbeddingModel(): EmbeddingModel {
    val config = testAppConfig()
    return ModelCatalog.fromConfig(config.providers)
        .findEmbeddingModel(config.memory.eltm.embeddingModel)
        ?: error("testAppConfig's embedding model not in its own catalog")
}

/** The test embedding model's dimensionality, resolved once per test JVM. */
private val embeddingDimensions: Int by lazy { testEmbeddingModel().dimensions }

/**
 * A unit vector along axis [axis] in the test embedding model's space:
 * registered vectors on different axes are orthogonal (similarity 0), the
 * same axis is similarity 1.0 — the exact-similarity control the
 * vector-search tests need ([DeterministicEmbeddings.register]).
 */
fun testAxisVector(axis: Int): List<Float> =
    List(embeddingDimensions) { if (it == axis) 1f else 0f }
