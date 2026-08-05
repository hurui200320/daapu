package info.skyblond.daapu.agent

import ai.koog.prompt.message.MessagePart
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContextInjectionTest {
    private val contextInjection = ContextInjection()

    @Test
    fun `test isInjection invalid`() {
        assertFalse {
            contextInjection.isInjection(MessagePart.Text("Normal string here..."))
        }
        assertFalse {
            contextInjection.isInjection(MessagePart.Text("<xml></xml> with string"))
        }
        assertFalse {
            contextInjection.isInjection(MessagePart.Text("<a></a>"))
        }
    }

    @Test
    fun `test isInjection non-text part`() {
        // only Text parts can carry an injection; anything else is rejected
        // without ever touching the XML validator
        assertFalse {
            contextInjection.isInjection(MessagePart.Reasoning(content = listOf("<injection/>")))
        }
    }

    @Test
    fun `test injection round trip`() {
        val injectionPart = contextInjection.generateInjection(
            ZonedDateTime.now(), false, false,
            listOf("Hello", "world")
        )
        assertTrue {
            contextInjection.isInjection(injectionPart)
        }
        assertFalse {
            contextInjection.isInjection(MessagePart.Text(injectionPart.text + "<a></a>"))
        }
    }

    @Test
    fun `test isInjection strictness`() {
        // the schema uses xs:sequence and declares no attributes: a future
        // relaxation would silently accept injections we did not generate,
        // so pin the strictness
        val reordered = """<injection><memories/><real-time-info><localtime>2026-08-05T12:00:00Z</localtime><sstm-updated>false</sstm-updated><eltm-updated>false</eltm-updated></real-time-info></injection>"""
        assertFalse {
            contextInjection.isInjection(MessagePart.Text(reordered))
        }
        val unexpectedElement = """<injection><real-time-info><localtime>2026-08-05T12:00:00Z</localtime><sstm-updated>false</sstm-updated><eltm-updated>false</eltm-updated></real-time-info><memories/><extra/></injection>"""
        assertFalse {
            contextInjection.isInjection(MessagePart.Text(unexpectedElement))
        }
        val unexpectedAttribute = """<injection foo="bar"><real-time-info><localtime>2026-08-05T12:00:00Z</localtime><sstm-updated>false</sstm-updated><eltm-updated>false</eltm-updated></real-time-info><memories/></injection>"""
        assertFalse {
            contextInjection.isInjection(MessagePart.Text(unexpectedAttribute))
        }
    }

    @Test
    fun `test localtime format`() {
        // pin timeFormatter: seconds precision with a numeric offset, no
        // nanoseconds
        val injectionPart = contextInjection.generateInjection(
            ZonedDateTime.of(2026, 8, 5, 12, 34, 56, 789_000_000, java.time.ZoneOffset.ofHours(2)),
            false, false,
            emptyList()
        )
        assertTrue {
            injectionPart.text.contains("<localtime>2026-08-05T12:34:56+02:00</localtime>")
        }
        // and the pinned format still validates against the schema
        assertTrue {
            contextInjection.isInjection(injectionPart)
        }
    }

    @Test
    fun `test XXE payload rejected`() {
        // schema-valid except for the DOCTYPE/entity, so the only reason
        // isInjection can return false is the XXE protection (without it,
        // &xxe; would resolve into localtime, an xs:string, and validate)
        val payload = """<!DOCTYPE injection [
            <!ENTITY xxe SYSTEM "file:///etc/passwd">
        ]><injection><real-time-info><localtime>&xxe;</localtime><sstm-updated>false</sstm-updated><eltm-updated>false</eltm-updated></real-time-info><memories/></injection>"""
        assertFalse {
            contextInjection.isInjection(MessagePart.Text(payload))
        }
    }

    @Test
    fun `test injection round trip with control characters`() {
        val injectionPart = contextInjection.generateInjection(
            ZonedDateTime.now(), false, false,
            listOf("bad\u0001memory <a>&", "emoji \uD83D\uDE00")
        )
        assertTrue {
            contextInjection.isInjection(injectionPart)
        }
        assertFalse {
            injectionPart.text.contains('\u0001')
        }
        // markup is escaped exactly once on the wire, not double-escaped
        assertTrue {
            injectionPart.text.contains("badmemory &lt;a&gt;&amp;")
        }
        assertFalse {
            injectionPart.text.contains("&amp;lt;")
        }
        val parsed = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(java.io.ByteArrayInputStream(injectionPart.text.toByteArray()))
        val memories = parsed.getElementsByTagName("memory")
        // control char stripped, markup round-trips back to the original text
        assertEquals("badmemory <a>&", memories.item(0).textContent)
        assertEquals("emoji \uD83D\uDE00", memories.item(1).textContent)
    }
}