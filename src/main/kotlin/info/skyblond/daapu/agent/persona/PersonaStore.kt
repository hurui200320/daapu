package info.skyblond.daapu.agent.persona

/**
 * The `personas`-table seam: every raw database access to persona rows
 * (list, create, update, delete, id lookup) lives here, so callers never
 * touch Exposed directly. The DEFAULT persona is not a row — it lives in
 * code and is served by `PersonaService`, never this store.
 */
interface PersonaStore {
    /** All persona rows, oldest first (BIGSERIAL ids sort by creation time). */
    suspend fun list(): List<Persona>

    /** The persona row, or null when it doesn't exist. */
    suspend fun findById(id: Long): Persona?

    /**
     * Insert a new persona row; the numeric id is assigned by the DB
     * identity and returned. Returns the row.
     */
    suspend fun create(
        name: String,
        systemPrompt: String,
        allowedNamespaces: List<String>,
    ): Persona

    /** Update the row; returns the updated row, or null when it doesn't exist. */
    suspend fun update(
        id: Long,
        name: String,
        systemPrompt: String,
        allowedNamespaces: List<String>,
    ): Persona?

    /** Delete the row; returns whether a row was deleted. */
    suspend fun delete(id: Long): Boolean
}
