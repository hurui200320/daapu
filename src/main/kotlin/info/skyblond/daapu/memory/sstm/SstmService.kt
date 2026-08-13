package info.skyblond.daapu.memory.sstm

import java.security.MessageDigest

/**
 * CRUD on the shared short-term memories (`sstms` table), plus a versioned
 * snapshot consumed by the turn loop's context injection: the loop compares
 * the snapshot's version against the one stored on the chat at the last
 * successful run to flag whether memories changed since then.
 */
interface SstmService {
    suspend fun listMemories(): MemoriesWithVersion

    suspend fun createMemory(content: String): ShortTermMemory

    /**
     * Update a memory, bumping its last_update so the injection order
     * reflects recency. Returns null when the memory doesn't exist.
     */
    suspend fun updateMemory(id: Long, content: String): ShortTermMemory?

    suspend fun deleteMemory(id: Long): Boolean
}

/**
 * Shared base for [SstmService] implementations: hosts the version
 * fingerprint so every implementation derives the same version semantics.
 * Kotlin interfaces cannot declare `protected` members, so this is an
 * abstract class rather than a default interface method.
 */
abstract class AbstractSstmService : SstmService {

    /**
     * SHA-256 fingerprint of [memories], in list order. Not cryptographic:
     * it only needs to change whenever the table's contents or order change.
     * The caller picks the order; the Postgres implementation sorts by
     * `(last_update, id)` so the hash is order-sensitive but deterministic.
     */
    protected fun digestVersion(memories: List<ShortTermMemory>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        memories.forEach { memory ->
            digest.update("${memory.id};${memory.lastUpdate};${memory.content}\n".toByteArray())
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}
