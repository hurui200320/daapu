package info.skyblond.daapu.db

import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.*

/**
 * The `memory_meta_number` key of the global ELTM write counter (see
 * `V1__init.sql`; the table itself is the generic [MemoryMetaNumber] KV
 * store). Every ELTM write bumps it atomically inside its own transaction;
 * the persist loop compares it against `chats.eltm_version` for the
 * `eltm-updated` injection flag, and the re-embed script bumps it once on
 * success so the next chat run re-flags.
 */
internal const val ELTM_VERSION_KEY = "eltm_version"

/**
 * Atomically bump the numeric counter [key] (`value = value + 1` UPDATE on
 * the column itself — no read-modify-write race). AMBIENT transaction: only
 * call inside [withTransaction], so the bump commits with the caller's
 * write and the digest fingerprint moves exactly when the visible state
 * changes. Fails fast when the row is absent: the migration seeds it, so a
 * missing row means a broken database and a silently lost bump (the
 * `eltm-updated` flag would stop moving) must not happen.
 */
fun bumpMetaCounter(key: String) {
    val updated = MemoryMetaNumber.update({ MemoryMetaNumber.key eq key }) {
        it[MemoryMetaNumber.value] = MemoryMetaNumber.value + 1L
    }
    check(updated == 1) {
        "meta counter \"$key\" has no row to bump — the migration seeds it, " +
                "the database state is broken"
    }
}

/**
 * Read the numeric counter [key]; 0 when the row is absent (a fresh
 * database before the first write). AMBIENT transaction.
 */
fun readMetaCounter(key: String): Long =
    MemoryMetaNumber.selectAll()
        .where { MemoryMetaNumber.key eq key }
        .singleOrNull()?.get(MemoryMetaNumber.value) ?: 0L

/** [bumpMetaCounter] in its own transaction. */
suspend fun bumpMetaCounterTx(key: String): Unit = withTransaction { bumpMetaCounter(key) }

/** [readMetaCounter] in its own transaction. */
suspend fun readMetaCounterTx(key: String): Long = withTransaction { readMetaCounter(key) }
