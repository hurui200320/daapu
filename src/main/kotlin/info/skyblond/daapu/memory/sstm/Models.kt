package info.skyblond.daapu.memory.sstm

import java.time.Instant

data class ShortTermMemory(
    val id: Long,
    val lastUpdate: Instant,
    val content: String,
)

/**
 * A snapshot of the memory table plus a version fingerprint of its contents.
 * The version is compared against the version stored on the chat at the last
 * successful run to decide whether the SSTM changed since then.
 */
data class MemoriesWithVersion(
    val memories: List<ShortTermMemory>,
    val version: String,
)
