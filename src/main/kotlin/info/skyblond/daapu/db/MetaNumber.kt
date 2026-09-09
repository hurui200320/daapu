package info.skyblond.daapu.db

import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.*

/**
 * The `gsg_meta_number` key of the global ELTM write counter (see
 * `V1__init.sql`; the table itself is the generic [GsgMetaNumber] KV
 * store). Every ELTM write bumps it atomically inside its own transaction;
 * the persist loop compares it against `chats.eltm_version` for the
 * `eltm-updated` injection flag, and the re-embed script bumps it once on
 * success so the next chat run re-flags.
 */
internal const val ELTM_VERSION_KEY = "eltm_version"

/**
 * The `gsg_meta_number` key of the ELTM maintenance-mode flag: 0 means
 * disabled, any non-zero value means enabled. What the mode blocks — and
 * what stays open — is owned by `requireEltmNotInMaintenance`'s KDoc
 * (`server/endpoint/MaintenanceRoute.kt`).
 */
internal const val ELTM_MAINTENANCE_KEY = "eltm_maintenance"

/**
 * Atomically bump the numeric entry [key] (`value = value + 1` UPDATE on
 * the column itself — no read-modify-write race). AMBIENT transaction: only
 * call inside [withTransaction], so the bump commits with the caller's
 * write and the version moves exactly when the visible state
 * changes. Fails fast when the row is absent: the migration seeds it, so a
 * missing row means a broken database and a silently lost bump (the
 * `eltm-updated` flag would stop moving) must not happen.
 */
fun bumpMetaNumber(key: String) {
    val updated = GsgMetaNumber.update({ GsgMetaNumber.key eq key }) {
        it[GsgMetaNumber.value] = GsgMetaNumber.value + 1L
    }
    check(updated == 1) {
        "meta number \"$key\" has no row to bump — the migration seeds it, " +
                "the database state is broken"
    }
}

/**
 * Set a numeric entry [key] to [value]. AMBIENT transaction.
 */
fun setMetaNumber(key: String, value: Long) {
    val updated = GsgMetaNumber.update({ GsgMetaNumber.key eq key }) {
        it[GsgMetaNumber.value] = value
    }
    check(updated == 1) {
        "meta number \"$key\" has no row to set — the migration seeds it, " +
                "the database state is broken"
    }
}

/**
 * Read the numeric entry [key]; 0 when the row is absent (a fresh
 * database before the first write). AMBIENT transaction.
 */
fun readMetaNumber(key: String): Long =
    GsgMetaNumber.selectAll()
        .where { GsgMetaNumber.key eq key }
        .singleOrNull()?.get(GsgMetaNumber.value) ?: 0L

/** [bumpMetaNumber] in its own transaction. */
suspend fun bumpMetaNumberTx(key: String): Unit = withTransaction { bumpMetaNumber(key) }

/** [readMetaNumber] in its own transaction. */
suspend fun readMetaNumberTx(key: String): Long = withTransaction { readMetaNumber(key) }

/**
 * [bumpMetaNumber] on the ELTM write counter ([ELTM_VERSION_KEY]) — the
 * ELTM write paths' one-call bump. AMBIENT transaction.
 */
internal fun bumpEltmWriteVersion() = bumpMetaNumber(ELTM_VERSION_KEY)

/**
 * [readMetaNumber] on the ELTM write counter ([ELTM_VERSION_KEY]) — the
 * `EltmService.version()` backing read. AMBIENT transaction.
 */
internal fun currentEltmWriteVersion(): Long = readMetaNumber(ELTM_VERSION_KEY)

/**
 * Set eltm maintenance mode. AMBIENT transaction.
 */
internal fun setEltmMaintenanceMode(enabled: Boolean) =
    setMetaNumber(ELTM_MAINTENANCE_KEY, if (enabled) 1L else 0L)

/**
 * Read eltm maintenance mode. Non zero value means enabled, otherwise (0) disabled.
 * AMBIENT transaction.
 */
internal fun isEltmMaintenanceModeEnabled(): Boolean = readMetaNumber(ELTM_MAINTENANCE_KEY) != 0L

/** [setEltmMaintenanceMode] in its own transaction. */
internal suspend fun setEltmMaintenanceModeTx(enabled: Boolean): Unit =
    withTransaction { setEltmMaintenanceMode(enabled) }

/** [isEltmMaintenanceModeEnabled] in its own transaction. */
internal suspend fun isEltmMaintenanceModeEnabledTx(): Boolean =
    withTransaction { isEltmMaintenanceModeEnabled() }
