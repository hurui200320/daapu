package info.skyblond.daapu.agent

import info.skyblond.daapu.history.HistoryPart
import java.io.StringReader
import java.io.StringWriter
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


class ContextInjection {
    companion object {
        private const val XSD_RESOURCE_PATH = "/agent/injectionSchema.xsd"

        // Compiled once per JVM: a Schema is thread-safe for newValidator()
        // (only the Validator instances are single-threaded), so the
        // per-request ContextInjection instances don't each pay an XSD parse.
        private val schema: Schema = run {
            ContextInjection::class.java.getResourceAsStream(XSD_RESOURCE_PATH)?.use { ins ->
                val schemaSource = StreamSource(ins)
                val schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)
                // isInjection() validates untrusted-looking text (e.g. the first
                // part of a user message), so forbid external DTD/entity and
                // schema access to avoid XXE-style resolution.
                schemaFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                schemaFactory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "")
                schemaFactory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
                schemaFactory.newSchema(schemaSource)
            } ?: error("Failed to load XML schema from $XSD_RESOURCE_PATH")
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

    // Note we're not reusing the factories and builders,
    // they should be reused, but they are not guaranteed to be thread safe,
    // making reusing risky if not properly handled
    fun generateInjection(
        time: ZonedDateTime,
        sstmUpdated: Boolean,
        eltmUpdated: Boolean,
        memoryList: List<String>
    ): HistoryPart.Text {
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
                textContent = timeFormatter.format(time)
            }
        )
        realtimeInfo.appendChild(
            document.createElement("sstm-updated").apply {
                textContent = sstmUpdated.toString()
            }
        )
        realtimeInfo.appendChild(
            document.createElement("eltm-updated").apply {
                textContent = eltmUpdated.toString()
            }
        )

        // val memories
        val memories = document.createElement("memories")
        injection.appendChild(memories)
        memoryList.forEach { memoryText ->
            memories.appendChild(
                document.createElement("memory").apply {
                    textContent = sanitizeForXml10(memoryText)
                }
            )
        }

        // convert to string
        val transformerFactory = TransformerFactory.newInstance()
        val transformer = transformerFactory.newTransformer()
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
        val stringWriter = StringWriter()
        transformer.transform(DOMSource(document), StreamResult(stringWriter))
        return HistoryPart.Text(stringWriter.toString())
    }

    fun isInjection(part: HistoryPart.Text): Boolean {
        try {
            val validator = schema.newValidator()
            val xmlSource = StreamSource(StringReader(part.text))
            validator.validate(xmlSource)
            return true
        } catch (_: Exception) {
            return false
        }
    }
}