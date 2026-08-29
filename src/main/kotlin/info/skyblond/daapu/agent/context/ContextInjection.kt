package info.skyblond.daapu.agent.context

import info.skyblond.daapu.agent.chat.ChatMessage
import info.skyblond.daapu.agent.chat.ChatMessagePart
import info.skyblond.daapu.agent.chat.ChatMessageRole
import info.skyblond.daapu.memory.eltm.EntityWithScore
import org.w3c.dom.Document
import java.io.StringReader
import java.io.StringWriter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter.ISO_LOCAL_DATE
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import javax.xml.transform.stream.StreamSource
import javax.xml.validation.Schema
import javax.xml.validation.SchemaFactory
import javax.xml.validation.Validator


/**
 * A related diary note ready for the injection XML: the note itself plus its
 * subject identified by names, so the model sees which diary it belongs to
 * without ids-only references. For an entity subject ([subjectType] `"entity"`)
 * [subjectAttributes] carries `name` + `category`; for a relationship subject
 * (`"relationship"`) it carries `src-name`, `verb`, `dst-name`.
 */
data class RelatedNoteView(
    val id: Long,
    val eventDate: LocalDate,
    val subjectType: String,
    val subjectAttributes: Map<String, String>,
    val note: String,
)

/**
 * The context injection payload for the latest user message (see
 * [ContextInjection.generateInjection]).
 *
 * The three ELTM fields are null together (a simple injection: only the time
 * basics — `localtime`, no `eltm-updated`, no `<memories>`) or non-null
 * together (the full ELTM injection; the related lists are EMPTY when
 * nothing related was found, never null). A mixed spec is rejected at
 * construction.
 */
data class InjectionSpec(
    val time: ZonedDateTime,
    val eltmUpdated: Boolean?,
    val relatedEntities: List<EntityWithScore>?,
    val relatedNotes: List<RelatedNoteView>?,
) {
    init {
        val nulls = listOf(eltmUpdated, relatedEntities, relatedNotes).count { it == null }
        require(nulls == 0 || nulls == 3) {
            "InjectionSpec: eltmUpdated, relatedEntities and relatedNotes must be all null " +
                    "(simple injection) or all non-null (ELTM injection)"
        }
    }
}

/**
 * Builds the harness-injected context parts and applies/removes them on a
 * chat:
 *
 * - [injectContext] prepends a small `<meta><sent-at>...</sent-at></meta>`
 *   time anchor to every historical user message carrying a `createdAt`
 *   (rendered in the server's CURRENT zone, so a server zone change
 *   re-renders every anchor consistently instead of freezing the old
 *   offset), and — when an [InjectionSpec] is given (the chat loop only) —
 *   prepends the `<injection>` to the latest user message, stamping its
 *   `createdAt` if missing. The injection is the full shape (real-time info
 *   + memories) when the spec's ELTM fields are non-null, or the time-only
 *   simple shape (just `localtime`) when they are all null. With a null
 *   spec (the one-shot services) it only anchors, adding no injection and
 *   never stamping.
 *
 * - [removeInjection] strips the harness parts again (idempotent, safe on
 *   clean input), so a consumer can treat any incoming chat as potentially
 *   injected, sanitize it, and re-inject without double injection.
 *
 * The equality-based anchor recognition is zone-sensitive (the render uses
 * the server's CURRENT zone), so it is only sound because harness parts
 * never outlive the request: anchors are regenerated per request and
 * stripped before every store, so a stored chat can never carry a stale
 * anchor and a zone change can never strand one in storage.
 *
 * Both are careful about user text that merely resembles the harness XML:
 * a part is only recognized as a time anchor when it matches the exact
 * deterministic rendering of the message's own `createdAt`
 * ([hasMetaPart]); a user message that happens to contain a valid
 * `<meta>` with different content is kept as user content. The full
 * injection is only recognized structurally (the XSDs): a user message whose
 * FIRST part is a valid `<injection>` is indistinguishable, and is treated
 * as harness (the same accepted behavior as before this class existed).
 * Only the two shapes the generator actually emits are recognized — the full
 * shape (an [InjectionSpec] with non-null ELTM fields, see [generateInjection])
 * and the time-only simple shape (an all-null [InjectionSpec]); a hybrid
 * (e.g. `eltm-updated` without `<memories>`) validates against neither schema
 * and survives as user content.
 */
class ContextInjection {
    companion object {
        private const val INJECTION_XSD_RESOURCE_PATH = "/agent/injectionSchema.xsd"
        private const val INJECTION_SIMPLE_XSD_RESOURCE_PATH = "/agent/injectionSimpleSchema.xsd"
        private const val META_XSD_RESOURCE_PATH = "/agent/metaSchema.xsd"

        // Compiled once per JVM: a Schema is thread-safe for newValidator()
        // (only the Validator instances are single-threaded), so the
        // per-request ContextInjection instances don't each pay an XSD parse.
        // The generator emits exactly two injection shapes, each pinned by its
        // own schema (a single minOccurs-loosened schema would also accept
        // hybrids the generator never produces — see injectionSchema.xsd):
        // the full shape (personas with `gsg` access) and the time-only
        // simple shape (personas without it, the query-rewrite one-shot).
        private val fullInjectionSchema: Schema = loadSchema(INJECTION_XSD_RESOURCE_PATH)
        private val simpleInjectionSchema: Schema = loadSchema(INJECTION_SIMPLE_XSD_RESOURCE_PATH)
        private val metaSchema: Schema = loadSchema(META_XSD_RESOURCE_PATH)

        private fun loadSchema(resourcePath: String): Schema {
            return ContextInjection::class.java.getResourceAsStream(resourcePath)?.use { ins ->
                val schemaSource = StreamSource(ins)
                val schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)
                // isInjection()/hasMetaPart() validate untrusted-looking text
                // (e.g. the first part of a user message), so forbid external
                // DTD/entity and schema access to avoid XXE-style resolution.
                schemaFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                schemaFactory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "")
                schemaFactory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
                schemaFactory.newSchema(schemaSource)
            } ?: error("Failed to load XML schema from $resourcePath")
        }

        private fun validateAgainst(schema: Schema, text: String): Boolean {
            try {
                val validator: Validator = schema.newValidator()
                validator.validate(StreamSource(StringReader(text)))
                return true
            } catch (_: Exception) {
                return false
            }
        }
    }

    // Similar to ISO_OFFSET_DATE_TIME but only down to seconds
    private val timeFormatter = DateTimeFormatterBuilder()
        .parseCaseInsensitive()
        .append(ISO_LOCAL_DATE)
        .appendLiteral('T')
        .appendValue(ChronoField.HOUR_OF_DAY, 2)
        .appendLiteral(':')
        .appendValue(ChronoField.MINUTE_OF_HOUR, 2)
        .appendLiteral(':')
        .appendValue(ChronoField.SECOND_OF_MINUTE, 2)
        .parseLenient()
        .appendOffsetId()
        .parseStrict()
        .toFormatter()

    // Strip characters that are invalid in XML 1.0, so they can't break
    // serialization. Markup characters (<, &, ...) are left as-is: the DOM
    // transformer escapes them exactly once during serialization.
    // Valid XML 1.0 chars: #x9 | #xA | #xD | #x20-#xD7FF | #xE000-#xFFFD | #x10000-#x10FFFF
    // (filter by code point so surrogate pairs, e.g. emoji, survive)
    private fun sanitizeForXml10(text: String): String {
        val cps = text.codePoints().filter { cp ->
            cp == 0x9 || cp == 0xA || cp == 0xD ||
                    cp in 0x20..0xD7FF || cp in 0xE000..0xFFFD || cp in 0x10000..0x10FFFF
        }.toArray()
        return String(cps, 0, cps.size)
    }

    fun Document.convertToText(): ChatMessagePart.Text {
        val transformerFactory = TransformerFactory.newInstance()
        val transformer = transformerFactory.newTransformer()
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
        val stringWriter = StringWriter()
        transformer.transform(DOMSource(this), StreamResult(stringWriter))
        return ChatMessagePart.Text(stringWriter.toString())
    }

    // Note we're not reusing the factories and builders,
    // they should be reused, but they are not guaranteed to be thread safe,
    // making reusing risky if not properly handled
    fun generateInjection(spec: InjectionSpec): ChatMessagePart.Text {
        val documentBuilderFactory = DocumentBuilderFactory.newInstance()
        val documentBuilder = documentBuilderFactory.newDocumentBuilder()
        val document = documentBuilder.newDocument()
        // injection
        val injection = document.createElement("injection")
        document.appendChild(injection)

        // realtime info
        val realtimeInfo = document.createElement("real-time-info")
        injection.appendChild(realtimeInfo)
        realtimeInfo.appendChild(
            document.createElement("localtime").apply {
                textContent = timeFormatter.format(spec.time)
            }
        )
        // a null eltmUpdated means the simple injection: no eltm-updated
        // element, no memories — only the time basics
        if (spec.eltmUpdated != null) {
            realtimeInfo.appendChild(
                document.createElement("eltm-updated").apply {
                    textContent = spec.eltmUpdated.toString()
                }
            )
        }

        // the ELTM context injection: the entities and diary notes retrieved
        // for the run's input, under <memories>. Both containers are always
        // present (empty ones included) when the ELTM is injected, so the
        // shape the model sees is stable across requests. A simple injection
        // (all null) omits the section entirely.
        if (spec.relatedEntities != null && spec.relatedNotes != null) {
            val memories = document.createElement("memories")
            injection.appendChild(memories)

            val relatedEntitiesElement = document.createElement("related-entities")
            memories.appendChild(relatedEntitiesElement)
            spec.relatedEntities.forEach { hit ->
                relatedEntitiesElement.appendChild(
                    document.createElement("entity").apply {
                        setAttribute("id", hit.entity.id.toString())
                        setAttribute("name", sanitizeForXml10(hit.entity.canonicalName))
                        setAttribute("category", sanitizeForXml10(hit.entity.category))
                        hit.attributes.forEach { (key, value) ->
                            appendChild(
                                document.createElement("attribute").apply {
                                    setAttribute("key", sanitizeForXml10(key))
                                    textContent = sanitizeForXml10(value)
                                }
                            )
                        }
                    }
                )
            }

            val relatedNotesElement = document.createElement("related-notes")
            memories.appendChild(relatedNotesElement)
            spec.relatedNotes.forEach { view ->
                relatedNotesElement.appendChild(
                    document.createElement("note").apply {
                        setAttribute("id", view.id.toString())
                        setAttribute("date", ISO_LOCAL_DATE.format(view.eventDate))
                        setAttribute("subject-type", view.subjectType)
                        view.subjectAttributes.forEach { (key, value) ->
                            setAttribute(key, sanitizeForXml10(value))
                        }
                        textContent = sanitizeForXml10(view.note)
                    }
                )
            }
        }

        return document.convertToText()
    }

    fun isInjection(part: ChatMessagePart.Text): Boolean =
        // only the two generator-emittable shapes count: the full ELTM shape
        // or the time-only simple shape — a hybrid validates against neither
        validateAgainst(fullInjectionSchema, part.text) ||
            validateAgainst(simpleInjectionSchema, part.text)

    /**
     * The per-message time anchor: `<meta><sent-at>...</sent-at></meta>`,
     * rendered from the message's [createdAt] instant in the server's
     * CURRENT zone (never frozen into the stored data — a server zone change
     * re-renders every anchor consistently, and the model never sees mixed
     * offsets).
     */
    fun generateMeta(createdAt: Instant): ChatMessagePart.Text {
        val documentBuilderFactory = DocumentBuilderFactory.newInstance()
        val documentBuilder = documentBuilderFactory.newDocumentBuilder()
        val document = documentBuilder.newDocument()
        // meta
        val meta = document.createElement("meta")
        document.appendChild(meta)

        meta.appendChild(
            document.createElement("sent-at").apply {
                textContent = timeFormatter.format(ZonedDateTime.ofInstant(createdAt, ZoneId.systemDefault()))
            }
        )

        return document.convertToText()
    }


    /**
     * True when the message leads with a time anchor we actually generated:
     * the part XSD-validates as `<meta>` AND equals the deterministic
     * rendering of the message's own `createdAt`. A user message that
     * merely contains valid `<meta>` XML with other content is never
     * mistaken for one of ours.
     */
    fun hasMetaPart(message: ChatMessage): Boolean {
        // no first part or first part not text, return false
        val first = message.parts.firstOrNull() as? ChatMessagePart.Text ?: return false
        // first part is not valid meta schema, return false
        if (!validateAgainst(metaSchema, first.text)) return false
        // message has no createdAt, return false
        val createdAt = message.createdAt ?: return false
        // check if message meta matches part
        return first.text == generateMeta(createdAt).text
    }

    /**
     * Apply the harness context to a copy of [chat]:
     *
     * - every user message with a [ChatMessage.createdAt] that doesn't
     *   already lead with one of our `<meta>` anchors gets a fresh anchor
     *   prepended (assistant and tool_result messages are never touched —
     *   the stored chat ends with an assistant reply, so their timing is
     *   implied by the surrounding user messages);
     * - with a non-null [spec] (the chat loop), the latest user message
     *   additionally gets any stale leading harness part replaced by a
     *   fresh `<injection>`, and its `createdAt` is stamped with
     *   `spec.time` when missing (so one-shot/challenge messages never need
     *   manual stamping). With a null spec (the one-shot services), only
     *   the anchors are added — no injection, no stamping, no "latest"
     *   special-casing.
     */
    fun injectContext(chat: List<ChatMessage>, spec: InjectionSpec?): List<ChatMessage> {
        val lastUserIndex = chat.indexOfLast { it.role == ChatMessageRole.User }
        return chat.mapIndexed { index, message ->
            if (message.role != ChatMessageRole.User || message.parts.isEmpty()) {
                message
            } else if (index == lastUserIndex && spec != null) {
                // first remove any injection or meta (if any)
                val parts = when {
                    message.parts.firstOrNull() !is ChatMessagePart.Text -> message.parts
                    hasMetaPart(message) || isInjection(message.parts.first() as ChatMessagePart.Text) ->
                        message.parts.drop(1)
                    else -> message.parts
                }
                // ensure user message has a createdAt time
                val stamped = message.copy(createdAt = message.createdAt ?: spec.time.toInstant())
                // inject context
                stamped.copy(
                    parts = listOf(generateInjection(spec)) + parts
                )
            } else if (message.createdAt != null && !hasMetaPart(message)) {
                message.copy(parts = listOf(generateMeta(message.createdAt)) + message.parts)
            } else {
                message
            }
        }
    }

    /**
     * Remove every harness part from a copy of [chat]: our `<meta>` anchors
     * (equality-checked against each message's own [ChatMessage.createdAt],
     * so forged lookalikes are kept) and every XSD-valid `<injection>`
     * first part (the same structural recognition [isInjection] always
     * used). Never empties a message. Idempotent and safe on clean input,
     * so a consumer can sanitize any incoming chat before re-injecting.
     */
    // TODO: maybe add a transient field in Part so we can just mark the injection?
    fun removeInjection(chat: List<ChatMessage>): List<ChatMessage> = chat.map { message ->
        if (message.role != ChatMessageRole.User || message.parts.size <= 1) {
            message
        } else {
            val first = message.parts.first() as? ChatMessagePart.Text
            when {
                first != null && hasMetaPart(message) -> message.copy(parts = message.parts.drop(1))
                first != null && isInjection(first) -> message.copy(parts = message.parts.drop(1))
                else -> message
            }
        }
    }
}
