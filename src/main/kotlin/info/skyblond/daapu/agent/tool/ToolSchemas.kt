package info.skyblond.daapu.agent.tool

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The JSON-Schema builders shared by the tool providers' [ToolSpec]
 * advertisements (`EltmToolProvider`, `FsToolProvider`, `GsgToolProvider`).
 * Every provider's schema is a plain JSON-Schema object (the hand forwards
 * it verbatim to the gateway), and the shapes below are the only ones the
 * providers emit — written once here, so the advertised schemas stay
 * consistent and a shape change happens in one place.
 */

/** A string property schema. */
internal fun stringSchema(description: String): JsonObject = buildJsonObject {
    put("type", "string")
    put("description", description)
}

/** An integer property schema (numeric ids, limits, offsets). */
internal fun integerSchema(description: String): JsonObject = buildJsonObject {
    put("type", "integer")
    put("description", description)
}

/** A boolean property schema. */
internal fun boolSchema(description: String): JsonObject = buildJsonObject {
    put("type", "boolean")
    put("description", description)
}

/** A string property schema restricted to [values] (a JSON `enum`). */
internal fun enumStringSchema(description: String, vararg values: String): JsonObject =
    buildJsonObject {
        put("type", "string")
        put("description", description)
        put("enum", buildJsonArray { values.forEach { add(it) } })
    }

/** An array-of-strings property schema. */
internal fun stringArraySchema(description: String): JsonObject = buildJsonObject {
    put("type", "array")
    put("items", buildJsonObject { put("type", "string") })
    put("description", description)
}

/**
 * The object schema every tool's `schema` carries: `type: object` plus the
 * [properties], with the `required` array emitted only when non-empty (an
 * argument-less tool declares no constraint).
 */
internal fun objectSchema(
    required: List<String>,
    vararg properties: Pair<String, JsonObject>,
): JsonObject = buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject {
        properties.forEach { (name, schema) -> put(name, schema) }
    })
    if (required.isNotEmpty()) {
        put("required", buildJsonArray { required.forEach { add(it) } })
    }
}
