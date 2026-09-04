package info.skyblond.daapu.agent.persona

import info.skyblond.daapu.testutil.DbTestBase
import info.skyblond.daapu.testutil.TestDb
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/**
 * Pins [PersonaService]: the code-only default persona (never a store row,
 * resolved from code), request resolution with fail-fast on unknown ids, the
 * create/update/delete validation (blank name/prompt, whitelist syntax,
 * whitelist entries the chat loop does not serve, the reserved id 0), and the
 * export/import transfer semantics (see `exportPersonas`/`importPersonas`).
 */
class PersonaServiceTest : DbTestBase() {

    // the servable-namespace snapshot: the chat loop's combined set (the
    // MCP namespaces plus `gsg`; the granular `eltm` tools are NOT loop
    // namespaces and must be rejected)
    private val served = setOf("gsg", "web")

    private fun service(store: PersonaStore = PostgresPersonaStore()) =
        PersonaService(store, served)

    @Test
    fun `list returns the code default first, then the rows`() = runBlocking {
        val store = PostgresPersonaStore()
        val writer = TestDb.seedPersonaRow("Writer", "You are a writer.", listOf("gsg"))
        val personas = service(store).list()
        assertEquals(2, personas.size)
        assertEquals(DEFAULT_PERSONA_ID, personas[0].id, "the code default leads the list")
        assertEquals(DEFAULT_PERSONA_SYSTEM_PROMPT, personas[0].systemPrompt)
        assertEquals(emptyList(), personas[0].allowedNamespaces, "default whitelist = all")
        assertEquals(writer.id, personas[1].id)
    }

    @Test
    fun `the default persona resolves from code with an empty store`() = runBlocking {
        // the default never touches the store: no seeding, no sync
        val persona = assertNotNull(service(PostgresPersonaStore()).resolveForRequest(DEFAULT_PERSONA_ID))
        assertEquals(DEFAULT_PERSONA_ID, persona.id)
        assertEquals(DEFAULT_PERSONA_SYSTEM_PROMPT, persona.systemPrompt)
    }

    @Test
    fun `a stored persona resolves from the store`() = runBlocking {
        val store = PostgresPersonaStore()
        val writer = TestDb.seedPersonaRow("Writer", "You are a writer.", listOf("gsg"))
        val persona = assertNotNull(service(store).resolveForRequest(writer.id))
        assertEquals(writer.id, persona.id)
        assertEquals(listOf("gsg"), persona.allowedNamespaces)
    }

    @Test
    fun `an unknown persona resolves to null`() = runBlocking {
        // the caller (ChatService.prepareRun) maps the null onto a 400
        // before any run starts
        assertNull(service().resolveForRequest(999L))
    }

    @Test
    fun `create rejects a blank name or system prompt`() = runBlocking {
        val service = service()
        val blankName = assertFailsWith<IllegalArgumentException> {
            service.create("  ", "prompt", emptyList())
        }
        val blankPrompt = assertFailsWith<IllegalArgumentException> {
            service.create("name", "   ", emptyList())
        }
        assertTrue(blankName.message!!.contains("name must not be blank"), blankName.message)
        assertTrue(blankPrompt.message!!.contains("prompt must not be blank"), blankPrompt.message)
    }

    @Test
    fun `create rejects malformed whitelist entries`() = runBlocking {
        val service = service()
        val blank = assertFailsWith<IllegalArgumentException> {
            service.create("name", "prompt", listOf(" "))
        }
        val doubleUnderscore = assertFailsWith<IllegalArgumentException> {
            service.create("name", "prompt", listOf("a__b"))
        }
        assertTrue(blank.message!!.contains("must not be blank"), blank.message)
        assertTrue(
            doubleUnderscore.message!!.contains("must not contain '__'"),
            doubleUnderscore.message,
        )
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
        val padded = assertFailsWith<IllegalArgumentException> {
            service().create("name", "prompt", listOf("gsg", " gsg "))
        }
        assertTrue(padded.message!!.contains("must be unique"))
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
        val store = PostgresPersonaStore()
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
        val store = PostgresPersonaStore()
        val writer = TestDb.seedPersonaRow("Writer", "You are a writer.", listOf("gsg"))
        val service = service(store)

        val updated = assertNotNull(service.update(writer.id, "Poet", "You are a poet.", emptyList()))
        assertEquals("Poet", updated.name)
        assertNull(service.update(999L, "x", "y", emptyList()), "unknown id → null")

        assertTrue(service.delete(writer.id))
        assertFalse(service.delete(writer.id), "already deleted → false")
    }

    // ---- export ----

    @Test
    fun `export lists every row in creation order and excludes the default persona`() = runBlocking {
        val store = PostgresPersonaStore()
        TestDb.seedPersonaRow("Writer", "You are a writer.", listOf("gsg"))
        TestDb.seedPersonaRow("Poet", "You are a poet.", emptyList())

        val entries = service(store).exportPersonas()

        assertEquals(
            listOf(
                PersonaExportEntry("Writer", "You are a writer.", listOf("gsg")),
                PersonaExportEntry("Poet", "You are a poet.", emptyList()),
            ),
            entries,
        )
        assertTrue(entries.none { it.name == "Default" }, "the code-only default is not exported")
    }

    @Test
    fun `export keeps same-name rows`() = runBlocking {
        // no name uniqueness: the array format must carry both rows losslessly
        val store = PostgresPersonaStore()
        TestDb.seedPersonaRow("Writer", "You are a writer.", listOf("gsg"))
        TestDb.seedPersonaRow("Writer", "You are a poet.", emptyList())

        val entries = service(store).exportPersonas()

        assertEquals(2, entries.size)
        assertEquals(listOf("You are a writer.", "You are a poet."), entries.map { it.systemPrompt })
    }

    // ---- import ----

    @Test
    fun `import creates unmatched entries and answers the created and skipped split`() = runBlocking {
        val store = PostgresPersonaStore()
        TestDb.seedPersonaRow("Poet", "You are a poet.", emptyList())

        val summary = service(store).importPersonas(
            listOf(
                PersonaExportEntry("Writer", "You are a writer.", listOf("gsg")),
                // exact match on the seeded row → skipped
                PersonaExportEntry("Poet", "You are a poet.", emptyList()),
            )
        )

        assertEquals(listOf("Writer"), summary.created)
        assertEquals(listOf("Poet"), summary.skipped)
        val writer = store.list().single { it.name == "Writer" }
        assertEquals("You are a writer.", writer.systemPrompt)
        assertEquals(listOf("gsg"), writer.allowedNamespaces)
    }

    @Test
    fun `import skips on the namespace set regardless of order`() = runBlocking {
        val store = PostgresPersonaStore()
        TestDb.seedPersonaRow("Writer", "You are a writer.", listOf("gsg", "web"))

        val summary = service(store).importPersonas(
            listOf(PersonaExportEntry("Writer", "You are a writer.", listOf("web", "gsg")))
        )

        assertEquals(emptyList(), summary.created)
        assertEquals(listOf("Writer"), summary.skipped)
        assertEquals(1, store.list().size, "no duplicate row is minted")
    }

    @Test
    fun `import mints a new row when the same name carries different content`() = runBlocking {
        val store = PostgresPersonaStore()
        TestDb.seedPersonaRow("Writer", "You are a writer.", listOf("gsg"))

        val summary = service(store).importPersonas(
            listOf(
                PersonaExportEntry("Writer", "You are a poet.", listOf("gsg")),
                PersonaExportEntry("Writer", "You are a writer.", listOf("web")),
            )
        )

        assertEquals(listOf("Writer", "Writer"), summary.created)
        val writers = store.list().filter { it.name == "Writer" }
        assertEquals(3, writers.size)
        assertEquals(
            setOf("You are a poet.", "You are a writer."),
            writers.map { it.systemPrompt }.toSet(),
        )
    }

    @Test
    fun `import matches padded input against the trimmed stored row`() = runBlocking {
        // create() trims before storing, so the skip-decision must compare
        // trimmed values — padded input matches, it does not mint a copy
        val store = PostgresPersonaStore()
        TestDb.seedPersonaRow("Writer", "You are a writer.", listOf("gsg"))

        val summary = service(store).importPersonas(
            listOf(
                PersonaExportEntry(
                    "  Writer  ",
                    "  You are a writer.  ",
                    listOf(" gsg "),
                )
            )
        )

        assertEquals(emptyList(), summary.created)
        assertEquals(listOf("Writer"), summary.skipped)
        assertEquals(1, store.list().size)
    }

    @Test
    fun `import is fail-fast and partial, and a re-run resumes`() = runBlocking {
        val store = PostgresPersonaStore()

        val aborted = assertFailsWith<IllegalArgumentException> {
            service(store).importPersonas(
                listOf(
                    PersonaExportEntry("Writer", "You are a writer.", listOf("gsg")),
                    // `a__b` is not a valid namespace (the `__` join is reserved)
                    PersonaExportEntry("Broken", "You are broken.", listOf("a__b")),
                )
            )
        }
        assertTrue(aborted.message!!.contains("must not contain '__'"), aborted.message)
        assertEquals(listOf("Writer"), store.list().map { it.name }, "earlier creates stick")

        // re-running the same file skips what stuck and fails on what didn't;
        // fixing the entry (or the server's served set) completes the import
        val summary = service(store).importPersonas(
            listOf(PersonaExportEntry("Writer", "You are a writer.", listOf("gsg")))
        )
        assertEquals(emptyList(), summary.created)
        assertEquals(listOf("Writer"), summary.skipped)
    }

    @Test
    fun `import with the same persona twice creates once then skips against its own output`() = runBlocking {
        val store = PostgresPersonaStore()

        val summary = service(store).importPersonas(
            listOf(
                PersonaExportEntry("Writer", "You are a writer.", listOf("gsg")),
                PersonaExportEntry("Writer", "You are a writer.", listOf("gsg")),
            )
        )

        assertEquals(listOf("Writer"), summary.created)
        assertEquals(listOf("Writer"), summary.skipped)
        assertEquals(1, store.list().size)
    }

    @Test
    fun `importing an export back into an unchanged store skips everything`() = runBlocking {
        val store = PostgresPersonaStore()
        TestDb.seedPersonaRow("Writer", "You are a writer.", listOf("gsg"))
        TestDb.seedPersonaRow("Poet", "You are a poet.", emptyList())

        val summary = service(store).importPersonas(service(store).exportPersonas())

        assertEquals(emptyList(), summary.created)
        assertEquals(listOf("Writer", "Poet"), summary.skipped)
    }

    @Test
    fun `import of an empty list answers an empty summary`() = runBlocking {
        val summary = service().importPersonas(emptyList())
        assertEquals(PersonaImportSummary(emptyList(), emptyList()), summary)
    }
}
