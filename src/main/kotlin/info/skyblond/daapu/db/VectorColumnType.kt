package info.skyblond.daapu.db

import info.skyblond.daapu.config.MAX_VECTOR_DIMENSIONS
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ColumnType
import org.jetbrains.exposed.v1.core.Function
import org.jetbrains.exposed.v1.core.QueryParameter
import org.jetbrains.exposed.v1.core.functions.vector.VectorDistance
import org.jetbrains.exposed.v1.core.functions.vector.VectorDistanceMetric
import org.postgresql.util.PGobject

/**
 * A pgvector column: a fixed-width vector of [dimensions] floats, stored in
 * the text form `"[f1,f2,...]"` (PGobject). [dimensions] is the column
 * width declared in `V1__init.sql` — the ELTM tables use
 * [info.skyblond.daapu.config.MAX_VECTOR_DIMENSIONS] (pgvector's HNSW
 * indexing limit for the `vector` type); the service zero-pads every vector
 * to the column width before writing, so cosine similarity is preserved and
 * switching embedding models never needs a schema change.
 */
class VectorColumnType(
    private val dimensions: Int,
) : ColumnType<List<Float>>() {
    init {
        require(dimensions > 0) { "dimensions must be > 0, got $dimensions" }
    }

    override fun sqlType(): String = "vector($dimensions)"

    override fun valueFromDB(value: Any): List<Float> = when (value) {
        is PGobject -> parseVector(value.value)
        is String -> parseVector(value)
        is List<*> -> value.map { (it as Number).toFloat() }
        else -> parseVector(value.toString())
    }

    override fun notNullValueToDB(value: List<Float>): Any {
        require(value.size <= dimensions) {
            "vector has ${value.size} dimensions, column width is $dimensions"
        }
        return PGobject().apply {
            type = "vector"
            this.value = value.joinToString(",", "[", "]")
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        if (!super.equals(other)) return false

        other as VectorColumnType

        return dimensions == other.dimensions
    }

    override fun hashCode(): Int = 31 * super.hashCode() + dimensions

    private fun parseVector(text: String?): List<Float> {
        if (text.isNullOrBlank()) return emptyList()
        return text.removePrefix("[").removeSuffix("]")
            .split(",")
            .map { it.trim().toFloat() }
    }

    companion object {
        /**
         * The pgvector cosine distance `column <=> vector` as an Exposed
         * expression. The query vector is bound as a [VectorColumnType]
         * parameter at the full [MAX_VECTOR_DIMENSIONS] column width — callers
         * zero-pad it with [padVector] before the call, so it always fits
         * (cosine similarity is invariant under zero-padding).
         */
        fun cosineDistance(
            column: Column<List<Float>?>,
            vector: List<Float>,
        ): Function<Double> = VectorDistance(
            column,
            QueryParameter(vector, VectorColumnType(MAX_VECTOR_DIMENSIONS)),
            VectorDistanceMetric.COSINE,
        )
    }
}

/**
 * Zero-pad a vector to [width] — the width of the [VectorColumnType] column it
 * is written to or queried against. The extra zero dimensions contribute
 * nothing to dot products or norms, so cosine similarity is preserved exactly
 * and the stored width never depends on the embedding model in use.
 */
internal fun padVector(vector: List<Float>, width: Int): List<Float> {
    require(vector.size <= width) { "vector has ${vector.size} dimensions, width is $width" }
    if (vector.size == width) return vector
    return vector + List(width - vector.size) { 0f }
}
