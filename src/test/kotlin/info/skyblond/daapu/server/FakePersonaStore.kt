package info.skyblond.daapu.server

import info.skyblond.daapu.agent.persona.Persona
import info.skyblond.daapu.agent.persona.PersonaStore

/**
 * An in-memory [PersonaStore] for service/route tests: seeded via [seed]
 * and inspected via [rows] without a database. The DEFAULT persona is not
 * stored here either — it lives in code ([PersonaService] serves it
 * directly), so the fake mirrors the production store's contract.
 */
class FakePersonaStore : PersonaStore {
    private val rows = mutableMapOf<Long, Persona>()
    // ids mirror the production BIGSERIAL identity: a counter that starts at
    // 1 (0 is the reserved code default, never a row) and skips ids already
    // taken by [seed] so create never overwrites a seeded row
    private var nextId = 1L

    fun seed(persona: Persona) {
        rows[persona.id] = persona
    }

    fun rows(): List<Persona> = rows.values.toList()

    override suspend fun list(): List<Persona> = rows.values.toList()

    override suspend fun findById(id: Long): Persona? = rows[id]

    override suspend fun create(
        name: String,
        systemPrompt: String,
        allowedNamespaces: List<String>,
    ): Persona {
        var id: Long
        do {
            id = nextId++
        } while (rows.containsKey(id))
        return Persona(id, name, systemPrompt, allowedNamespaces).also { rows[id] = it }
    }

    override suspend fun update(
        id: Long,
        name: String,
        systemPrompt: String,
        allowedNamespaces: List<String>,
    ): Persona? = rows[id]?.let {
        Persona(id, name, systemPrompt, allowedNamespaces).also { rows[id] = it }
    }

    override suspend fun delete(id: Long): Boolean = rows.remove(id) != null
}
