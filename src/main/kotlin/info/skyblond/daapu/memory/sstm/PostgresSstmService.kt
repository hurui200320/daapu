package info.skyblond.daapu.memory.sstm

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
 * Postgres-backed [SstmService]: CRUD on the `sstms` table and a
 * SHA-256 fingerprint of the table contents as the version.
 */
class PostgresSstmService : AbstractSstmService() {

    override suspend fun listMemories(): MemoriesWithVersion = withTransaction {
        val memories = SSTMs.selectAll()
            // id as tiebreaker: last_update alone is not unique, and the
            // version hash is order-sensitive
            .orderBy(SSTMs.lastUpdate to SortOrder.ASC, SSTMs.id to SortOrder.ASC)
            .map { row ->
                ShortTermMemory(
                    row[SSTMs.id],
                    row[SSTMs.lastUpdate],
                    row[SSTMs.content]
                )
            }
        MemoriesWithVersion(memories, digestVersion(memories))
    }

    override suspend fun createMemory(content: String): ShortTermMemory = withTransaction {
        val now = Instant.now()
        val id = (SSTMs.insert {
            it[SSTMs.content] = content
            it[SSTMs.lastUpdate] = now
        } get SSTMs.id)
        ShortTermMemory(id, now, content)
    }

    /**
     * Update a memory, bumping its last_update so the injection order
     * reflects recency. Returns null when the memory doesn't exist.
     */
    override suspend fun updateMemory(id: Long, content: String): ShortTermMemory? =
        withTransaction {
            val row = SSTMs.selectAll().where { SSTMs.id eq id }.singleOrNull()
            if (row == null) return@withTransaction null
            // skip update if content is identical
            if (row[SSTMs.content] == content)
                return@withTransaction ShortTermMemory(
                    row[SSTMs.id],
                    row[SSTMs.lastUpdate],
                    row[SSTMs.content]
                )
            // perform update
            val now = Instant.now()
            val updated = SSTMs.update({ SSTMs.id eq id }) {
                it[SSTMs.content] = content
                it[SSTMs.lastUpdate] = now
            }
            if (updated == 0) null else ShortTermMemory(id, now, content)
        }

    override suspend fun deleteMemory(id: Long): Boolean = withTransaction {
        SSTMs.deleteWhere { SSTMs.id eq id } > 0
    }
}
