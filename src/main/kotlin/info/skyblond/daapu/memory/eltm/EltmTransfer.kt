package info.skyblond.daapu.memory.eltm

import kotlinx.serialization.Serializable

/**
 * One diary note in the transfer payload: the LLM-resolved absolute event
 * date (`YYYY-MM-DD`) plus the note text. No subject: the note nests
 * inside its entity/relationship entry, so no note ids exist at all.
 */
@Serializable
data class EltmExportNote(
    val date: String,
    val note: String,
)

/**
 * One entity entry of the transfer payload. [name] and [category] are the
 * stored canonical forms (already normalized — see [normalizeName]);
 * import matches on `(name, category)`, never on the payload's uuid.
 */
@Serializable
data class EltmExportEntity(
    val name: String,
    val category: String,
    /** Current-state facts, as stored (normalized keys, single-line values). */
    val attributes: Map<String, String>,
    val notes: List<EltmExportNote>,
)

/**
 * One relationship entry of the transfer payload. [srcUuid]/[dstUuid]
 * reference the payload's entity keys; [verb] is the stored canonical form
 * (see [normalizeVerb]); [valid] is the row's structural state AT EXPORT
 * TIME — import applies it only when the file's newest note is newer than
 * the target row's (see `EltmTransferService.importEltm`).
 */
@Serializable
data class EltmExportRelationship(
    val srcUuid: String,
    val verb: String,
    val dstUuid: String,
    val valid: Boolean,
    val notes: List<EltmExportNote>,
)

/**
 * The ELTM transfer payload: the shape of `GET /api/eltm/export`'s
 * attachment (`eltm.json`) AND the request body of `POST /api/eltm/import`
 * (the `overwriteAttr` decision travels as a query parameter, so the
 * exported file posts verbatim as the body — see `EltmRoute.kt`).
 *
 * Entities are keyed by a file-level uuid: minted fresh at every export, a
 * pure join key for the relationships' [EltmExportRelationship.srcUuid] /
 * [EltmExportRelationship.dstUuid] references — import NEVER matches on it
 * (entities match on `(name, category)`, relationships on the resolved
 * entity ids plus verb). No db ids and no embeddings anywhere: the import
 * re-derives everything (fresh row ids, embeddings recomputed through the
 * local hand), so a file transfers across instances with different
 * embedding models.
 */
@Serializable
data class EltmExportPayload(
    val entities: Map<String, EltmExportEntity>,
    val relationships: List<EltmExportRelationship>,
)

/**
 * The `POST /api/eltm/import` response: the merge's per-kind split.
 * [entitiesCreated]/[relationshipsCreated] count file entries whose row the
 * pre-import snapshot did NOT hold (normally this import's insert; in a
 * write race, a concurrent writer's row the create-or-fetch adopted);
 * [entitiesMatched]/[relationshipsMatched] count rows the snapshot already
 * held (see `EltmTransferService`'s concurrency stance).
 * [attributesWritten] counts attribute keys the import SET (a new key, or
 * an overwrite under `overwriteAttr=true` that changed the value);
 * [attributesKept] counts existing keys left alone (skipped under
 * `overwriteAttr=false`, or an identical value — the service's no-op).
 * [notesSkipped] counts exact (event date, trimmed text) duplicates, in the
 * database or earlier in the same file; [notesInserted] the rest.
 */
@Serializable
data class EltmImportSummary(
    val entitiesCreated: Int,
    val entitiesMatched: Int,
    val relationshipsCreated: Int,
    val relationshipsMatched: Int,
    val notesInserted: Int,
    val notesSkipped: Int,
    val attributesWritten: Int,
    val attributesKept: Int,
)
