package info.skyblond.daapu.agent.persona

import info.skyblond.daapu.agent.tool.validateToolNamespaceSyntax

/**
 * The persona seam between the `/api/personas` routes, the chat run
 * (`ChatService.prepareRun`) and the [PersonaStore].
 *
 * The DEFAULT persona lives ONLY in code
 * (`agent/persona/DefaultPersona.kt`, id [DEFAULT_PERSONA_ID]): it is never a
 * store row, it resolves from code, and the persona API rejects
 * create/update/delete on it. Prompt updates therefore need no data sync.
 *
 * Validation: a persona's [Persona.allowedNamespaces] is a whitelist over
 * the chat loop's tool set — every entry must be a namespace the loop's
 * [info.skyblond.daapu.agent.tool.CombinedToolProvider] serves at boot (the
 * MCP servers plus `gsg`; the granular `eltm` tools are NOT loop namespaces,
 * so they are rejected here too). An EMPTY whitelist means ALL loop
 * namespaces — the default persona's shape, so no special case is needed
 * for it.
 *
 * Validation errors throw [IllegalArgumentException] (no ktor dependency in
 * this package); the routes map them onto 400. The export/import payload
 * types live in `PersonaTransfer.kt`.
 */
class PersonaService(
    private val store: PersonaStore,
    /** The chat loop's tool namespaces, snapshotted at boot. */
    private val servedNamespaces: Set<String>,
) {

    /** The code default persona first, then the store rows. */
    suspend fun list(): List<Persona> = listOf(defaultPersona()) + store.list()

    /**
     * Resolve a request's persona id: the code default, or a store row.
     * Returns null when the id names no persona (the caller maps it onto a
     * client error before any run starts).
     */
    suspend fun resolveForRequest(personaId: Long): Persona? =
        if (personaId == DEFAULT_PERSONA_ID) defaultPersona() else store.findById(personaId)

    suspend fun create(
        name: String,
        systemPrompt: String,
        allowedNamespaces: List<String>,
    ): Persona {
        // the whitelist entries are trimmed like name and prompt: the raw
        // input travels from the routes, a padded entry is a typo, not intent
        val namespaces = allowedNamespaces.map { it.trim() }
        validateSave(name, systemPrompt, namespaces)
        return store.create(name.trim(), systemPrompt.trim(), namespaces).also {
            check(it.id != DEFAULT_PERSONA_ID) {
                "The default persona lives in code and is read-only"
            }
        }
    }

    suspend fun update(
        id: Long,
        name: String,
        systemPrompt: String,
        allowedNamespaces: List<String>,
    ): Persona? {
        require(id != DEFAULT_PERSONA_ID) {
            "The default persona lives in code and is read-only"
        }
        // trimmed like in create: validation and storage see the same list
        val namespaces = allowedNamespaces.map { it.trim() }
        validateSave(name, systemPrompt, namespaces)
        return store.update(id, name.trim(), systemPrompt.trim(), namespaces)
    }

    suspend fun delete(id: Long): Boolean {
        require(id != DEFAULT_PERSONA_ID) {
            "The default persona lives in code and cannot be deleted"
        }
        return store.delete(id)
    }

    /**
     * The export payload: every store row as a [PersonaExportEntry], id order
     * (creation order) preserved. The DEFAULT persona is NOT exported — it
     * lives in code, never a row, and an import of it would only mint a
     * duplicate shadow row (see `importPersonas`).
     */
    suspend fun exportPersonas(): List<PersonaExportEntry> =
        store.list().map { PersonaExportEntry(it.name, it.systemPrompt, it.allowedNamespaces) }

    /**
     * Import exported entries: per entry, SKIP when an existing persona with
     * the same name already carries the same (trimmed) system prompt and the
     * same namespace SET (order-insensitive — the whitelist is a membership
     * filter, `Persona.serves`); otherwise CREATE through [create] with its
     * full validation. Same-name rows are legal (no uniqueness constraint),
     * so a mismatch simply mints another row.
     *
     * Fail-fast partial: the first entry failing validation aborts the import
     * with [IllegalArgumentException] (the routes map it onto 400) and the
     * personas created before it stick — re-running the same file skips them
     * and resumes. No lock and no transaction: each create is independent,
     * and personas are not read by anything but the per-request persona
     * resolution.
     */
    suspend fun importPersonas(entries: List<PersonaExportEntry>): PersonaImportSummary {
        // one snapshot read for the whole import; every created persona joins
        // it, so a file carrying the same persona twice creates once and then
        // skips against its own output
        val known = store.list().groupBy { it.name }.toMutableMap()
        val created = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        for (entry in entries) {
            // trimmed comparison: create() trims before storing, so padded
            // input must match the trimmed row instead of mismatching it
            val name = entry.name.trim()
            val prompt = entry.systemPrompt.trim()
            val namespaces = entry.allowedNamespaces.map { it.trim() }
            val exists = known[name].orEmpty().any {
                it.systemPrompt == prompt && it.allowedNamespaces.toSet() == namespaces.toSet()
            }
            if (exists) {
                skipped += name
            } else {
                val persona = create(name, prompt, namespaces)
                known[persona.name] = known[persona.name].orEmpty() + persona
                created += persona.name
            }
        }
        return PersonaImportSummary(created, skipped)
    }

    private fun validateSave(
        name: String,
        systemPrompt: String,
        allowedNamespaces: List<String>,
    ) {
        require(name.isNotBlank()) { "Persona name must not be blank" }
        require(systemPrompt.isNotBlank()) { "Persona system prompt must not be blank" }
        allowedNamespaces.forEach {
            require(it.isNotBlank()) { "A persona allowed namespace must not be blank" }
            validateToolNamespaceSyntax(it, "Persona allowed namespaces")
        }
        val duplicates = allowedNamespaces.groupingBy { it }.eachCount()
            .filterValues { it > 1 }.keys
        require(duplicates.isEmpty()) {
            "Persona allowed namespaces must be unique: ${duplicates.joinToString(", ")}"
        }
        val unknown = allowedNamespaces - servedNamespaces
        require(unknown.isEmpty()) {
            "Persona allowed namespaces not served by the chat loop: " +
                    unknown.joinToString(", ") +
                    "; served: " + servedNamespaces.sorted().joinToString(", ")
        }
    }
}
