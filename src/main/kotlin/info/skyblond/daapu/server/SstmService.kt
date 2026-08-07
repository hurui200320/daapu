package info.skyblond.daapu.server

import info.skyblond.daapu.db.SSTMs
import info.skyblond.daapu.db.withTransaction
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant

/**
 * Backend logic behind the `/api/memories` endpoints: CRUD on the shared
 * short-term memories (`sstms` table), kept separate from [ChatRunService].
 * The agent's context injection reads the same table directly
 * (`agent/ChatAgentFactory.kt`); this service is only the web-facing CRUD.
 */
class SstmService {

    suspend fun listMemories(): List<MemoryDto> = withTransaction {
        SSTMs.selectAll()
            .orderBy(SSTMs.lastUpdate to SortOrder.ASC)
            .map { row ->
                MemoryDto(
                    row[SSTMs.id],
                    row[SSTMs.lastUpdate].toString(),
                    row[SSTMs.content]
                )
            }
    }

    suspend fun createMemory(content: String): MemoryDto = withTransaction {
        val now = Instant.now()
        val id = (SSTMs.insert {
            it[SSTMs.content] = content
            it[SSTMs.lastUpdate] = now
        } get SSTMs.id)
        MemoryDto(id, now.toString(), content)
    }

    /**
     * Update a memory, bumping its last_update so the injection order
     * reflects recency. Returns null when the memory doesn't exist.
     */
    suspend fun updateMemory(id: Long, content: String): MemoryDto? = withTransaction {
        val now = Instant.now()
        val updated = SSTMs.update({ SSTMs.id eq id }) {
            it[SSTMs.content] = content
            it[SSTMs.lastUpdate] = now
        }
        if (updated == 0) null else MemoryDto(id, now.toString(), content)
    }

    suspend fun deleteMemory(id: Long): Boolean = withTransaction {
        SSTMs.deleteWhere { SSTMs.id eq id } > 0
    }
}
