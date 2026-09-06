package info.skyblond.daapu.agent.tool

import kotlinx.serialization.json.JsonNull
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
 *   a present-but-unparseable primitive answers `null` and the caller turns
 *   that into the tool's own "required and must not be blank" error result.
 *   A non-primitive value (object/array) throws [IllegalArgumentException]
 *   with the key in the message (like strict mode) — the caller must never
 *   mistake a type error for an absent argument.
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
 * content (e.g. the number `5` reads as `"5"`); a present object/array
 * throws [IllegalArgumentException] ("[key] must be a string") — a type
 * error, never an absent argument. Strict mode throws the same for any
 * non-string primitive or non-primitive value.
 */
internal fun JsonObject.textArg(key: String, strict: Boolean = false): String? {
    val element = this[key] ?: return null
    if (strict) {
        val primitive = element as? JsonPrimitive
            ?: throw IllegalArgumentException("$key must be a string")
        if (!primitive.isString) throw IllegalArgumentException("$key must be a string")
        return primitive.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
    }
    val primitive = try {
        element.jsonPrimitive
    } catch (e: IllegalArgumentException) {
        // a present object/array is a type error naming this key, not
        // jsonPrimitive's key-less "not a JsonPrimitive" (see strictDate
        // in EltmToolProvider, which wraps the same way)
        throw IllegalArgumentException("$key must be a string", e)
    }
    return primitive.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
}

/**
 * The long value of [key] (lenient): absent, an explicit JSON null, or an
 * unparseable JSON primitive → null; a JSON number or numeric string
 * parses, anything else answers null. A present object/array throws
 * [IllegalArgumentException] ("[key] must be a number") — a type error,
 * never an absent argument. (The JsonNull check is explicit, not just
 * `contentOrNull == null`: the null-is-absent invariant must survive a
 * future strict mode the way [intArg]'s does.)
 */
internal fun JsonObject.longArg(key: String): Long? {
    val element = this[key] ?: return null
    if (element is JsonNull) return null
    val primitive = try {
        element.jsonPrimitive
    } catch (e: IllegalArgumentException) {
        // a present object/array is a type error naming this key — never
        // leak jsonPrimitive's key-less "not a JsonPrimitive" (callers
        // only surface e.message), and never answer null, which the
        // caller would mistake for an absent argument (see the
        // lenient/strict contract above)
        throw IllegalArgumentException("$key must be a number", e)
    }
    return primitive.contentOrNull?.trim()?.toLongOrNull()
}

/**
 * The int value of [key]: lenient mode as [longArg] (an unparseable JSON
 * primitive → null; a present object/array throws like strict mode);
 * strict mode requires a present JSON whole number or a numeric string —
 * a non-primitive, a float, a boolean or a non-numeric string throws
 * [IllegalArgumentException] ("[key] must be a number" / "must be an
 * integer, got '...'"). Deliberately STRICTER than the filesystem server's
 * zod `z.number()`, which accepts any JSON number and truncates floats
 * while reading: here the value must be whole — an invalid argument, not
 * a silently truncated one. Numeric strings ARE accepted (unlike a pure
 * type check): the arguments are LLM-authored and the model sometimes
 * emits `"5"` for an integer schema — coercing a well-formed numeric
 * string avoids a pointless error round-trip, while a float or garbage
 * still fails loudly. The `>= 1` range check stays with the callers.
 */
internal fun JsonObject.intArg(key: String, strict: Boolean = false): Int? {
    val element = this[key] ?: return null
    // an explicit JSON null is absent, not a value: optional arguments
    // (limit/offset/head/tail) fall back to their defaults instead of
    // costing an error round-trip (see strictDate in EltmToolProvider,
    // which treats null the same way)
    if (element is JsonNull) return null
    if (!strict) {
        val primitive = try {
            element.jsonPrimitive
        } catch (e: IllegalArgumentException) {
            // same type-error stance as longArg: a present object/array
            // names this key instead of answering null (absent)
            throw IllegalArgumentException("$key must be a number", e)
        }
        return primitive.contentOrNull?.trim()?.toIntOrNull()
    }
    val primitive = element as? JsonPrimitive
        ?: throw IllegalArgumentException("$key must be a number")
    if (primitive.isString) {
        // LLM-authored numeric strings ("5") coerce; anything else (floats,
        // garbage, booleans-as-strings) is an invalid argument, not a
        // silent default
        return primitive.contentOrNull?.trim()?.toIntOrNull()
            ?: throw IllegalArgumentException("$key must be an integer, got '$primitive'")
    }
    return primitive.contentOrNull?.toIntOrNull()
        ?: throw IllegalArgumentException("$key must be an integer, got '$primitive'")
}

/**
 * The boolean value of [key] (lenient): absent or an unparseable JSON
 * primitive → null; the strings "true"/"false" (and JSON booleans) parse
 * via [String.toBooleanStrictOrNull]. A present object/array throws
 * [IllegalArgumentException] ("[key] must be a boolean") — a type error,
 * never an absent argument.
 */
internal fun JsonObject.boolArg(key: String): Boolean? {
    val element = this[key] ?: return null
    val primitive = try {
        element.jsonPrimitive
    } catch (e: IllegalArgumentException) {
        // same type-error stance as longArg: a present object/array names
        // this key instead of answering null (absent)
        throw IllegalArgumentException("$key must be a boolean", e)
    }
    return primitive.contentOrNull?.toBooleanStrictOrNull()
}

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
