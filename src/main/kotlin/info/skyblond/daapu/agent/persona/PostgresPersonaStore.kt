package info.skyblond.daapu.agent.persona

import info.skyblond.daapu.db.Personas
import info.skyblond.daapu.db.withTransaction
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.*

/**
 * Postgres-backed [PersonaStore]. The `allowed_namespaces` column holds the
 * whitelist as a JSON array of strings (`[]` = all namespaces served by the
 * chat loop). The row id is a BIGSERIAL DB identity (see `V2__personas.sql`)
 * and travels through the wire types as the number itself; the reserved code
 * default (`DEFAULT_PERSONA_ID`, 0) never reaches this store.
 */
class PostgresPersonaStore : PersonaStore {

    override suspend fun list(): List<Persona> = withTransaction {
        Personas.selectAll()
            .orderBy(Personas.id to SortOrder.ASC)
            .map { row -> row.toPersona() }
    }

    override suspend fun findById(id: Long): Persona? = withTransaction {
        Personas.selectAll()
            .where { Personas.id eq id }
            .singleOrNull()
            ?.toPersona()
    }

    override suspend fun create(
        name: String,
        systemPrompt: String,
        allowedNamespaces: List<String>,
    ): Persona = withTransaction {
        val id = Personas.insert {
            it[Personas.name] = name
            it[Personas.systemPrompt] = systemPrompt
            it[Personas.allowedNamespaces] = json.encodeToString(allowedNamespaces)
        } get Personas.id
        Persona(id, name, systemPrompt, allowedNamespaces)
    }

    override suspend fun update(
        id: Long,
        name: String,
        systemPrompt: String,
        allowedNamespaces: List<String>,
    ): Persona? = withTransaction {
        val updated = Personas.update({ Personas.id eq id }) {
            it[Personas.name] = name
            it[Personas.systemPrompt] = systemPrompt
            it[Personas.allowedNamespaces] = json.encodeToString(allowedNamespaces)
        }
        if (updated == 0) null else Persona(id, name, systemPrompt, allowedNamespaces)
    }

    override suspend fun delete(id: Long): Boolean = withTransaction {
        Personas.deleteWhere { Personas.id eq id } > 0
    }

    private fun ResultRow.toPersona() = Persona(
        id = this[Personas.id],
        name = this[Personas.name],
        systemPrompt = this[Personas.systemPrompt],
        allowedNamespaces = json.decodeFromString<List<String>>(this[Personas.allowedNamespaces]),
    )

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
    }
}
