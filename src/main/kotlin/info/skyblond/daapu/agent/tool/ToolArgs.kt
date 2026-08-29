package info.skyblond.daapu.agent.tool

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * The tool-argument extraction shared by the tool providers. Two argument
 * contracts exist across the providers, matching the surface each one
 * fronts:
 *
 * - **Lenient** (`strict = false` — the ELTM tools, the `gsg__investigate`
 *   tool): the arguments are LLM-authored and the schemas are advisory, so
 *   a present-but-unparseable value answers `null` and the caller turns
 *   that into the tool's own "required and must not be blank" error result.
 *   A non-primitive value (object/array) is a protocol-level surprise and
 *   propagates the JSON accessors' exception.
 * - **Strict** (`strict = true` — the filesystem tools): mirrors the vanilla
 *   filesystem MCP server's zod schemas, which reject wrong-typed arguments
 *   at the tool-call boundary instead of coercing them: a present value
 *   that is not of the expected type throws [IllegalArgumentException]
 *   with the key in the message (the provider answers it as an `isError`
 *   result).
 *
 * The mode is chosen per call site, so a provider's tolerance is visible
 * where its arguments are read — never an accident of which helper was
 * copied.
 */

/**
 * The text value of [key]: absent → null, present string → trimmed and
 * blank-rejected. Lenient mode coerces other JSON primitives through their
 * content (e.g. the number `5` reads as `"5"`); strict mode throws
 * [IllegalArgumentException] ("[key] must be a string") for any non-string
 * primitive or non-primitive value.
 */
internal fun JsonObject.textArg(key: String, strict: Boolean = false): String? {
    val element = this[key] ?: return null
    if (strict) {
        val primitive = element as? JsonPrimitive
            ?: throw IllegalArgumentException("$key must be a string")
        if (!primitive.isString) throw IllegalArgumentException("$key must be a string")
        return primitive.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
    }
    return element.jsonPrimitive.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
}

/**
 * The long value of [key] (lenient): absent or unparseable → null; a JSON
 * number or numeric string parses, anything else answers null.
 */
internal fun JsonObject.longArg(key: String): Long? =
    this[key]?.jsonPrimitive?.contentOrNull?.toLongOrNull()

/**
 * The int value of [key]: lenient mode as [longArg] (unparseable → null);
 * strict mode requires a present JSON whole number — a string, a
 * non-primitive or a float throws [IllegalArgumentException] ("[key] must
 * be a number" / "must be an integer, got '...'"). Deliberately STRICTER
 * than the filesystem server's zod `z.number()`, which accepts any JSON
 * number and truncates floats while reading: here the value must be whole —
 * an invalid argument, not a silently truncated one. The `>= 1` range check
 * stays with the callers.
 */
internal fun JsonObject.intArg(key: String, strict: Boolean = false): Int? {
    val element = this[key] ?: return null
    if (!strict) return element.jsonPrimitive.contentOrNull?.toIntOrNull()
    val primitive = element as? JsonPrimitive
        ?: throw IllegalArgumentException("$key must be a number")
    if (primitive.isString) throw IllegalArgumentException("$key must be a number")
    return primitive.contentOrNull?.toIntOrNull()
        ?: throw IllegalArgumentException("$key must be an integer, got '$primitive'")
}

/**
 * The boolean value of [key] (lenient): absent or unparseable → null; the
 * strings "true"/"false" (and JSON booleans) parse via
 * [String.toBooleanStrictOrNull].
 */
internal fun JsonObject.boolArg(key: String): Boolean? =
    this[key]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()

/**
 * The string-array value of [key] (strict, like the filesystem server's
 * zod `z.array(z.string())`): absent → null; a present value that is not
 * an array — or an array holding a non-string element — throws
 * [IllegalArgumentException] ("[key] must be an array of strings"). Blank
 * entries are dropped; an array that is empty or all-blank answers null
 * (the caller answers its "required and must not be blank" error).
 */
internal fun JsonObject.stringArrayArg(key: String): List<String>? {
    val array = this[key]?.jsonArray ?: return null
    return array.map { element ->
        val primitive = element as? JsonPrimitive
            ?: throw IllegalArgumentException("$key must be an array of strings")
        if (!primitive.isString) throw IllegalArgumentException("$key must be an array of strings")
        primitive.contentOrNull?.trim().orEmpty()
    }.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }
}
