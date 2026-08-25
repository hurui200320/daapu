package info.skyblond.daapu.agent.persona

import info.skyblond.daapu.server.FakePersonaStore
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/**
 * Pins [PersonaService]: the code-only default persona (never a store row,
 * resolved from code), request resolution with fail-fast on unknown ids, and
 * the create/update/delete validation (blank name/prompt, whitelist syntax,
 * whitelist entries the chat loop does not serve, the reserved id 0).
 */
class PersonaServiceTest {

    // the servable-namespace snapshot: the chat loop's combined set (the
    // MCP namespaces plus `gsg`; the granular `eltm` tools are NOT loop
    // namespaces and must be rejected)
    private val served = setOf("gsg", "web")

    private fun service(store: PersonaStore = FakePersonaStore()) =
        PersonaService(store, served)

    @Test
    fun `list returns the code default first, then the rows`() = runBlocking {
        val store = FakePersonaStore()
        store.seed(Persona(1L, "Writer", "You are a writer.", listOf("gsg")))
        val personas = service(store).list()
        assertEquals(2, personas.size)
        assertEquals(DEFAULT_PERSONA_ID, personas[0].id, "the code default leads the list")
        assertEquals(DEFAULT_PERSONA_SYSTEM_PROMPT, personas[0].systemPrompt)
        assertEquals(emptyList(), personas[0].allowedNamespaces, "default whitelist = all")
        assertEquals(1L, personas[1].id)
    }

    @Test
    fun `the default persona resolves from code with an empty store`() = runBlocking {
        // the default never touches the store: no seeding, no sync
        val persona = assertNotNull(service(FakePersonaStore()).resolveForRequest(DEFAULT_PERSONA_ID))
        assertEquals(DEFAULT_PERSONA_ID, persona.id)
        assertEquals(DEFAULT_PERSONA_SYSTEM_PROMPT, persona.systemPrompt)
    }

    @Test
    fun `a stored persona resolves from the store`() = runBlocking {
        val store = FakePersonaStore()
        store.seed(Persona(1L, "Writer", "You are a writer.", listOf("gsg")))
        val persona = assertNotNull(service(store).resolveForRequest(1L))
        assertEquals(1L, persona.id)
        assertEquals(listOf("gsg"), persona.allowedNamespaces)
    }

    @Test
    fun `an unknown persona resolves to null`() = runBlocking {
        // the caller (ChatRunService.prepareRun) maps the null onto a 400
        // before any run starts
        assertNull(service().resolveForRequest(999L))
    }

    @Test
    fun `create rejects a blank name or system prompt`() = runBlocking {
        val service = service()
        assertFailsWith<IllegalArgumentException> { service.create("  ", "prompt", emptyList()) }
        assertFailsWith<IllegalArgumentException> { service.create("name", "   ", emptyList()) }
    }

    @Test
    fun `create rejects malformed whitelist entries`() = runBlocking {
        val service = service()
        assertFailsWith<IllegalArgumentException> {
            service.create("name", "prompt", listOf(" "))
        }
        assertFailsWith<IllegalArgumentException> {
            service.create("name", "prompt", listOf("a__b"))
        }
    }

    @Test
    fun `create rejects duplicate whitelist entries`() = runBlocking {
        // a duplicate is a save-time mistake: the stored list should be the
        // user's intent, not a bag of repeats (the run path dedupes into a
        // Set anyway, but the record must stay clean). Whitespace-padded
        // duplicates collapse after the trim, so `["gsg", " gsg "]` is a
        // duplicate too.
        val e = assertFailsWith<IllegalArgumentException> {
            service().create("name", "prompt", listOf("gsg", "gsg"))
        }
        assertTrue(e.message!!.contains("must be unique"))
        assertFailsWith<IllegalArgumentException> {
            service().create("name", "prompt", listOf("gsg", " gsg "))
        }
    }

    @Test
    fun `create rejects a namespace the chat loop does not serve`() = runBlocking {
        // `eltm` is deliberately not a loop namespace (the loop reaches the
        // ELTM only through gsg__investigate): a persona may not whitelist it
        val e = assertFailsWith<IllegalArgumentException> {
            service().create("name", "prompt", listOf("eltm"))
        }
        assertTrue(
            e.message!!.contains("not served by the chat loop"),
            "the error names the servable set: ${e.message}",
        )
    }

    @Test
    fun `create trims name, prompt and whitelist entries and stores them`() = runBlocking {
        val store = FakePersonaStore()
        val persona = service(store).create(
            "  Writer  ",
            "  You are a writer.  ",
            listOf(" gsg ", "  web  "),
        )
        assertEquals("Writer", persona.name)
        assertEquals("You are a writer.", persona.systemPrompt)
        // the whitelist is trimmed like the other fields: padded entries are
        // typos, not intent — validation AND storage see the trimmed list
        assertEquals(listOf("gsg", "web"), persona.allowedNamespaces)
        assertEquals(persona, store.findById(persona.id))
    }

    @Test
    fun `update rejects the reserved default persona`() = runBlocking {
        val e = assertFailsWith<IllegalArgumentException> {
            service().update(DEFAULT_PERSONA_ID, "x", "y", emptyList())
        }
        assertTrue(e.message!!.contains("read-only"))
    }

    @Test
    fun `delete rejects the reserved default persona`() = runBlocking {
        val e = assertFailsWith<IllegalArgumentException> {
            service().delete(DEFAULT_PERSONA_ID)
        }
        assertTrue(e.message!!.contains("cannot be deleted"))
    }

    @Test
    fun `update and delete pass through to the store`() = runBlocking {
        val store = FakePersonaStore()
        store.seed(Persona(1L, "Writer", "You are a writer.", listOf("gsg")))
        val service = service(store)

        val updated = assertNotNull(service.update(1L, "Poet", "You are a poet.", emptyList()))
        assertEquals("Poet", updated.name)
        assertNull(service.update(999L, "x", "y", emptyList()), "unknown id → null")

        assertTrue(service.delete(1L))
        assertFalse(service.delete(1L), "already deleted → false")
    }
}
