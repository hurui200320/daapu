package info.skyblond.daapu.agent.tool

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The shared JSON-Schema builders behind every [ToolSpec] advertisement
 * (`EltmToolProvider`, `FsToolProvider`, `GsgToolProvider`): the exact
 * shapes are pinned here — the hand forwards each schema verbatim to the
 * gateway, so a silent shape change would silently change what every
 * provider advertises.
 */
class ToolSchemasTest {

    @Test
    fun `scalar property schemas carry type and description`() {
        assertEquals(
            buildJsonObject {
                put("type", "string")
                put("description", "d")
            },
            stringSchema("d"),
        )
        assertEquals(
            buildJsonObject {
                put("type", "integer")
                put("description", "d")
            },
            integerSchema("d"),
        )
        assertEquals(
            buildJsonObject {
                put("type", "boolean")
                put("description", "d")
            },
            boolSchema("d"),
        )
    }

    @Test
    fun `enum and string-array schemas pin their restriction`() {
        assertEquals(
            buildJsonObject {
                put("type", "string")
                put("description", "d")
                put("enum", buildJsonArray { add("a"); add("b") })
            },
            enumStringSchema("d", "a", "b"),
        )
        assertEquals(
            buildJsonObject {
                put("type", "array")
                put("items", buildJsonObject { put("type", "string") })
                put("description", "d")
            },
            stringArraySchema("d"),
        )
    }

    @Test
    fun `object schema emits required only when non-empty`() {
        // the argument-less shape (e.g. fs__list_allowed_directories):
        // no `required` key at all
        assertEquals(
            buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject { })
            },
            objectSchema(required = emptyList()),
        )
        assertEquals(
            buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("query", buildJsonObject {
                        put("type", "string")
                        put("description", "q")
                    })
                    put("limit", buildJsonObject {
                        put("type", "integer")
                        put("description", "l")
                    })
                })
                put("required", buildJsonArray { add("query") })
            },
            objectSchema(
                required = listOf("query"),
                "query" to stringSchema("q"),
                "limit" to integerSchema("l"),
            ),
        )
    }

    @Test
    fun `object schema keeps the property render order stable`() {
        // JsonObject equality ignores key order, but the schema travels as
        // JSON text: pin the render order so it cannot drift silently
        val schema: JsonObject = objectSchema(
            required = listOf("b"),
            "a" to stringSchema("a"),
            "b" to stringSchema("b"),
        )
        assertEquals(listOf("type", "properties", "required"), schema.keys.toList())
        assertEquals(
            listOf("a", "b"),
            (schema["properties"] as JsonObject).keys.toList(),
        )
    }
}
