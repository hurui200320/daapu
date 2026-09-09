package info.skyblond.daapu.memory.eltm

import info.skyblond.daapu.memory.eltm.model.*
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.*

/**
 * The transfer service behind `GET /api/eltm/export` and
 * `POST /api/eltm/import` (`server/endpoint/EltmRoute.kt`): a whole-store
 * snapshot out, a merge in. Every write rides [EltmService]'s
 * create-or-fetch methods (embeddings through the hand, unique-violation
 * handling tolerant of the extraction pipeline's concurrent writers), so
 * the import needs no lock of its own — the same stance as the digest
 * route (nothing here touches the chats table).
 *
 * Import semantics (the merge; the wire shape is [EltmExportPayload]):
 * - Entities match on `(normalizeName(name), category)` — the DB's unique
 *   key — never on the file uuid; a missing entity is created, an existing
 *   one is kept as-is. Near-match disambiguation ([EltmService.createEntity]'s
 *   `nearMatches`) is deliberately out of scope — a transfer is a mechanical
 *   merge; duplicate resolution stays the writer pipeline's job. The file
 *   holds each key at most once — the entity's
 *   `(name, category)` key, each of its attribute keys, and each
 *   relationship triple: duplicates (after normalization) are rejected by
 *   the pre-write validation, mirroring the DB's own uniqueness
 *   constraints (an export is a store backup — a duplicate is corruption,
 *   not something to fold).
 * - Attributes: an existing key keeps its value unless `overwriteAttr` is
 *   set (the file's value wins; an identical value is the service's
 *   no-op); a new key is always set. DB-only keys are never deleted.
 * - Notes dedup on (event date, trimmed text): a note the subject already
 *   carries (in the DB or earlier in the same list) is skipped,
 *   everything else is appended — the diary is add-only, so an old file
 *   may append older-dated entries to a live DB.
 * - Relationships match on the triple (resolved entity ids + verb); a
 *   missing one is created; duplicate triples (same endpoint uuids, verbs
 *   that normalize identically) are rejected like duplicate entity keys.
 *   The exported `valid` state applies only when the file's newest note
 *   date is strictly newer than the target row's latest note date (a row
 *   without notes loses) — the DB is the newer
 *   truth for what it holds. A row created by THIS import always takes the
 *   file's state: nothing pre-exists to protect. Notes never carry their
 *   structural flag (not stored), so validity can never be replayed from
 *   the file's notes — the file's state must be written explicitly. HOW a
 *   flip lands keeps the diary model's coupling (a structural change
 *   carries its reason, see [EltmService.attachNotesToRelationship]): a
 *   SNAPSHOT row's flip rides its stage-3 note attach in ONE transaction —
 *   a row the file wins always has its newest file note missing from the
 *   snapshot (else the DB would be at least as new and the file would not
 *   win), so a note is always there to carry the flip. A created row
 *   without file notes takes its state right in the relationship stage,
 *   via the note-less [EltmService.setRelationshipValid] — necessarily
 *   there: the rule gives note-less matched rows to the DB, so a re-run
 *   could never re-derive that flip. Re-running the file heals every
 *   partial state: each flip re-derives to the same value or no-ops, and
 *   the notes dedup-resume.
 * - Fail-fast partial, like the persona import: the whole file is
 *   validated BEFORE the first write (a broken file creates nothing), then
 *   the merge runs as three stages — entities with their attributes,
 *   relationships (a created note-less row takes its state here, see the
 *   valid rule above), then every subject's notes, a snapshot row's
 *   validity flip riding its note attach — and the first failure aborts
 *   the request with everything already written sticking; the partial
 *   boundary is the batch, not the row: the
 *   entity stage resolves ALL file entities up front in ONE bulk
 *   create-or-fetch ([EltmService.createEntities] — one batched embed
 *   call series, one transaction, one counter bump; never a per-entity
 *   embed call and create-or-fetch chain), the relationship stage likewise
 *   ([EltmService.createRelationships]), and each entity's attribute
 *   write set and each subject's note batch are ONE transaction
 *   (embeddings ride the hand's batched `/v1/embed` — see
 *   [EltmService.attachNotesToEntity]); re-running the same file skips the
 *   existing content (dedup) and resumes.
 *
 * Concurrency stance: the merge decisions read ONE pre-import snapshot
 * ([EltmService.exportAll]) and the writes re-check against the live DB
 * through the service's create-or-fetch methods. A live writer can slip a
 * matching note/row in between the snapshot and the write — the result is
 * a duplicated note (the same dedup granularity the writer itself
 * accepts), never a broken state. Attributes decide on the snapshot too:
 * a key the snapshot does not hold is always set, so a key a live writer
 * creates between the snapshot and the write is overwritten by the file's
 * value even under `overwriteAttr=false` — setting is overwrite-by-design
 * in the service, and the identical-value race lands on the service's
 * no-op (counted as kept).
 *
 * The created/matched counters are row-accurate against the snapshot:
 * create-or-fetch returns the key's row whatever won the write race, so a
 * row the snapshot already held (e.g. a concurrent refine renaming it onto
 * the file's key) counts as matched, and only a row the snapshot never
 * held counts as created (see [EltmImportSummary]).
 */
class EltmTransferService(private val eltm: EltmService) {

    /**
     * Export the whole ELTM as the transfer payload: every entity under a
     * fresh file-scope uuid (minted per call — the uuid is a join key
     * only, see [EltmExportPayload]), its attributes and diary notes
     * nested in order, every relationship with its endpoint references,
     * structural state and notes.
     */
    suspend fun exportEltm(): EltmExportPayload {
        val snapshot = eltm.exportAll()
        val uuids: Map<Long, String> = snapshot.entities.associate {
            it.id to UUID.randomUUID().toString()
        }
        val (notesByEntity, notesByRelationship) = groupNotesBySubject(snapshot.notes)
        return EltmExportPayload(
            entities = snapshot.entities.associate { entity ->
                uuids.getValue(entity.id) to EltmExportEntity(
                    name = entity.canonicalName,
                    category = entity.category,
                    attributes = snapshot.attributes[entity.id].orEmpty(),
                    notes = notesByEntity[entity.id].orEmpty().map { it.toExport() },
                )
            },
            relationships = snapshot.relationships.map { rel ->
                // the REPEATABLE READ snapshot (see EltmService.exportAll)
                // guarantees both endpoints are snapshot entities
                EltmExportRelationship(
                    srcUuid = uuids.getValue(rel.srcId),
                    verb = rel.verb,
                    dstUuid = uuids.getValue(rel.dstId),
                    valid = rel.valid,
                    notes = notesByRelationship[rel.id].orEmpty().map { it.toExport() },
                )
            },
        )
    }

    /**
     * Import (merge) a transfer payload per the class KDoc's semantics,
     * answering the per-kind split. Every invalid entry fails the request
     * with [IllegalArgumentException] (the route maps it to 400) — before
     * any write for shape problems, after earlier writes stuck otherwise.
     */
    suspend fun importEltm(
        payload: EltmExportPayload,
        overwriteAttr: Boolean,
    ): EltmImportSummary {
        validate(payload)

        // one pre-import snapshot drives every merge decision (entity
        // matching, attribute collision, note dedup, the valid rule); the
        // writes re-check against the live DB through the service methods
        // (concurrency stance: class KDoc)
        val before = eltm.exportAll()
        // the file holds each entity key / relationship triple at most once
        // (validate), so the lookups never need in-import updates
        val entitiesByKey = before.entities
            .associateBy { it.canonicalName to it.category }
        // the created/matched counting baseline: a create-or-fetch result
        // whose id the snapshot already held was matched, whatever the race
        // did to its key in between (see the class KDoc's stance)
        val snapshotEntityIds = before.entities.mapTo(HashSet()) { it.id }
        val attributesByEntity = before.attributes
        val (entityNotes, relationshipNotes) = groupNotesBySubject(before.notes)
        val relationshipsByTriple = before.relationships
            .associateBy { Triple(it.srcId, it.dstId, it.verb) }
        val snapshotRelationshipIds = before.relationships.mapTo(HashSet()) { it.id }

        var entitiesCreated = 0
        var entitiesMatched = 0
        var relationshipsCreated = 0
        var relationshipsMatched = 0
        var notesInserted = 0
        var notesSkipped = 0
        var attributesWritten = 0
        var attributesKept = 0

        // ---- stage 1: entities and their attributes -------------------
        logger.info { "ELTM import stage 1 (entities and attrs) start" }
        // file uuid to db id
        val entityIds = HashMap<String, Long>(payload.entities.size)
        val pendingCreates = ArrayList<Pair<String, EntityDraft>>()
        for ((uuid, entry) in payload.entities) {
            val key = normalizeName(entry.name) to entry.category.trim().lowercase()
            val existing = entitiesByKey[key]
            if (existing == null) {
                pendingCreates += uuid to EntityDraft(entry.name, entry.category)
            } else {
                entityIds[uuid] = existing.id
                entitiesMatched++
            }
        }
        val createdEntities = eltm.createEntities(pendingCreates.map { it.second })
        for ((pair, entity) in pendingCreates.zip(createdEntities)) {
            entityIds[pair.first] = entity.id
            // by-row counting (see snapshotEntityIds): a concurrent refine
            // can rename a snapshot row onto this key between the snapshot
            // and the write — that row was matched
            if (entity.id in snapshotEntityIds) entitiesMatched++ else entitiesCreated++
        }

        // attributes: the kept/written decision reads the snapshot's
        // state — the file holds each attribute key once (validate), so
        // there are no in-file writes to track. The snapshot-vs-live races
        // here (an identical concurrent value counts as kept; a
        // concurrently created key is overwritten even under
        // overwriteAttr=false) are the class KDoc's concurrency stance.
        // The entity's whole write set rides ONE setEntityAttributes call:
        // one embed, one transaction, one counter bump — never a re-embed
        // per key.
        for ((uuid, entry) in payload.entities) {
            val entityId = entityIds.getValue(uuid)
            val snapshotAttrs = attributesByEntity[entityId].orEmpty()
            val pendingAttrs = LinkedHashMap<String, String>()
            for ((rawKey, rawValue) in entry.attributes) {
                val k = normalizeAttributeKey(rawKey)
                val v = rawValue.trim()
                if (snapshotAttrs.containsKey(k) && (!overwriteAttr || snapshotAttrs[k] == v)) {
                    // kept: the flag is off, or the file echoes the value
                    attributesKept++
                } else {
                    pendingAttrs[k] = v
                }
            }
            if (pendingAttrs.isNotEmpty()) {
                val written = eltm.setEntityAttributes(entityId, pendingAttrs)
                attributesWritten += written
                attributesKept += pendingAttrs.size - written
            }
        }

        // ---- stage 2: relationships and their structural state ---------
        logger.info { "ELTM import stage 2 (relationships) start" }
        // array[index] = relationship to exist (true = exist, false = created)
        val resolvedRelationships =
            arrayOfNulls<Pair<EltmRelationship, Boolean>>(payload.relationships.size)
        val pendingRelationships = ArrayList<Pair<Int, RelationshipDraft>>()
        payload.relationships.forEachIndexed { index, rel ->
            val srcId = entityIds.getValue(rel.srcUuid)
            val dstId = entityIds.getValue(rel.dstUuid)
            val verb = normalizeVerb(rel.verb)
            val existing = relationshipsByTriple[Triple(srcId, dstId, verb)]
            if (existing == null) {
                pendingRelationships += index to RelationshipDraft(srcId, verb, dstId)
            } else {
                resolvedRelationships[index] = existing to true
                relationshipsMatched++
            }
        }
        val createdRelationships = eltm.createRelationships(pendingRelationships.map { it.second })
        for ((pair, relationship) in pendingRelationships.zip(createdRelationships)) {
            resolvedRelationships[pair.first] = relationship to false
            // by-row counting, as in the entity stage: a concurrent merge can
            // re-point a snapshot row onto this triple between the snapshot
            // and the write — that row was matched
            if (relationship.id in snapshotRelationshipIds) {
                relationshipsMatched++
            } else {
                relationshipsCreated++
            }
        }

        // the file's structural state for the SNAPSHOT rows the file wins,
        // applied in stage 3 riding each row's note attach (index = file
        // relationship index)
        val validTargets = arrayOfNulls<Boolean>(payload.relationships.size)
        for ((index, rel) in payload.relationships.withIndex()) {
            val (relationship, snapshotHeld) = resolvedRelationships[index]
                ?: error("relationship $index was not resolved by the relationship stage")
            // the snapshot's row for this triple, or null when the snapshot
            // did not hold it (this import's creation, or a concurrent
            // writer's mid-import one)
            val existing = if (snapshotHeld) relationship else null

            // the valid rule (class KDoc), decided from the PRE-import
            // snapshot state — the stage-3 notes are not in it
            val fileLatest = rel.notes.maxOfOrNull { LocalDate.parse(it.date) }
            val dbLatest = relationshipNotes[relationship.id]
                .orEmpty()
                .maxOfOrNull { it.eventDate }
            // `existing == null`: nothing pre-exists to protect, so the
            // file's state always applies
            val fileWins: Boolean = when {
                existing == null -> true
                fileLatest != null -> dbLatest == null || fileLatest.isAfter(dbLatest)
                else -> false
            }

            if (fileWins && relationship.valid != rel.valid) {
                if (existing == null && rel.notes.isEmpty()) {
                    // if not exist in db and no notes, we can set valid right now
                    eltm.setRelationshipValid(relationship.id, rel.valid)
                } else {
                    // otherwise we record the valid and set it in stage 3
                    validTargets[index] = rel.valid
                }
            }
        }

        // ---- stage 3: every subject's notes + the snapshot rows' flips -
        logger.info { "ELTM import stage 3 (notes) start" }
        for ((uuid, entry) in payload.entities) {
            val entityId = entityIds.getValue(uuid)
            val (pending, skipped) = pendingNotes(entry.notes, entityNotes[entityId].orEmpty())
            notesSkipped += skipped
            if (pending.isNotEmpty()) {
                notesInserted += eltm.attachNotesToEntity(entityId, pending).size
            }
        }

        for ((index, rel) in payload.relationships.withIndex()) {
            val relationship = resolvedRelationships[index]?.first
                ?: error("relationship $index was not resolved by the relationship stage")
            val targetValid = validTargets[index]
            val (pending, skipped) = pendingNotes(
                rel.notes,
                relationshipNotes[relationship.id].orEmpty()
            )
            notesSkipped += skipped
            if (pending.isNotEmpty()) {
                // create pending notes and set valid flag
                notesInserted += eltm.attachNotesToRelationship(
                    relationship.id, pending, valid = targetValid,
                ).notes.size
            } else if (targetValid != null) {
                // no notes pending to create, but we still need to set valid flag
                eltm.setRelationshipValid(relationship.id, targetValid)
            }
        }

        val summary = EltmImportSummary(
            entitiesCreated = entitiesCreated,
            entitiesMatched = entitiesMatched,
            relationshipsCreated = relationshipsCreated,
            relationshipsMatched = relationshipsMatched,
            notesInserted = notesInserted,
            notesSkipped = notesSkipped,
            attributesWritten = attributesWritten,
            attributesKept = attributesKept,
        )
        logger.info {
            "ELTM import finished: entities created=${summary.entitiesCreated} " +
                    "matched=${summary.entitiesMatched}, relationships " +
                    "created=${summary.relationshipsCreated} matched=${summary.relationshipsMatched}, " +
                    "notes inserted=${summary.notesInserted} skipped=${summary.notesSkipped}, " +
                    "attributes written=${summary.attributesWritten} kept=${summary.attributesKept}"
        }
        return summary
    }

    // ------------------------------------------------------------------
    // shared helpers
    // ------------------------------------------------------------------

    /**
     * Group notes by their subject: (entity-keyed, relationship-keyed),
     * each preserving encounter order. A note's subject is exactly one of
     * the two columns (the migration CHECK) — anything else fails loudly
     * here, so a broken schema can never silently drop a note from an
     * export's backup or an import's dedup baseline.
     */
    private fun groupNotesBySubject(
        notes: List<EltmNote>,
    ): Pair<Map<Long, List<EltmNote>>, Map<Long, List<EltmNote>>> {
        val byEntity = HashMap<Long, MutableList<EltmNote>>()
        val byRelationship = HashMap<Long, MutableList<EltmNote>>()
        for (note in notes) {
            when {
                note.entityId != null && note.relationshipId == null ->
                    byEntity.getOrPut(note.entityId) { mutableListOf() }.add(note)

                note.relationshipId != null && note.entityId == null ->
                    byRelationship.getOrPut(note.relationshipId) { mutableListOf() }.add(note)

                else -> error("note ${note.id} must carry exactly one subject")
            }
        }
        return byEntity to byRelationship
    }

    /**
     * Dedup [notes] against the subject's [existingNotes] (the snapshot's
     * rows for this subject): an exact (event date, trimmed text) match —
     * in the DB or earlier in the same list, tracked in the seen-set — is
     * skipped. The caller appends the rest through ONE bulk attach call
     * (batched embeds, one transaction for the subject's whole batch).
     * @return the drafts to attach (possibly empty) and the skipped count.
     */
    private fun pendingNotes(
        notes: List<EltmExportNote>,
        existingNotes: List<EltmNote>,
    ): Pair<List<NoteDraft>, Int> {
        // stored notes are trimmed by the service, so the comparison is exact
        val seen = existingNotes.mapTo(HashSet()) { it.eventDate to it.note }
        val pending = ArrayList<NoteDraft>()
        var skipped = 0
        for (note in notes) {
            val date = LocalDate.parse(note.date)
            val text = note.note.trim()
            if ((date to text) in seen) {
                skipped++
                continue
            }
            pending += NoteDraft(date, text)
            seen += date to text
        }
        return pending to skipped
    }

    /**
     * Validate the WHOLE payload before the first write: names/verbs/keys
     * normalize to non-blank, attribute values are non-blank single lines,
     * note dates parse and note texts are non-blank, every relationship
     * endpoint references an exported entity, and the file's keys are
     * unique after normalization — each `(name, category)`, each attribute
     * key within its entity, and each `(src, verb, dst)` triple at most
     * once, mirroring the DB's own uniqueness constraints (an export is a
     * store backup; a file that violates them is corrupt, not foldable —
     * see the class KDoc).
     * Messages carry the offending path (`entities[<uuid>].notes[3].date`),
     * so a broken file is fixable from the 400 alone.
     */
    private fun validate(payload: EltmExportPayload) {
        val entityKeys = HashMap<Pair<String, String>, String>()
        for ((uuid, entity) in payload.entities) {
            val path = "entities[$uuid]"
            require(normalizeName(entity.name).isNotBlank()) { "$path.name must not be blank" }
            require(entity.category.isNotBlank()) { "$path.category must not be blank" }
            // compared AFTER normalization — the same form the merge and
            // the DB use ("Kindle"/"device" and "  kindle  "/"Device" are
            // one key)
            val key = normalizeName(entity.name) to entity.category.trim().lowercase()
            val firstUuid = entityKeys.put(key, uuid)
            if (firstUuid != null) {
                throw IllegalArgumentException(
                    "$path duplicates entities[$firstUuid]: both resolve to " +
                            "the entity key (\"${key.first}\", \"${key.second}\")"
                )
            }
            val attrKeys = HashMap<String, String>()
            for ((attrKey, value) in entity.attributes) {
                val k = normalizeAttributeKey(attrKey)
                require(k.isNotBlank()) {
                    "$path.attributes: key must not be blank"
                }
                require(value.isNotBlank()) { "$path.attributes[$attrKey]: value must not be blank" }
                require(value.none { it == '\n' || it == '\r' }) {
                    "$path.attributes[$attrKey]: value must be a single line"
                }
                // one row per (entity, key): two keys that normalize alike
                // fold onto ONE row — rejected like duplicate entity keys
                val firstRawKey = attrKeys.put(k, attrKey)
                if (firstRawKey != null) {
                    throw IllegalArgumentException(
                        "$path.attributes[$attrKey] duplicates $path.attributes[$firstRawKey]: " +
                                "both normalize to \"$k\""
                    )
                }
            }
            entity.notes.forEachIndexed { i, note -> validateNote("$path.notes[$i]", note) }
        }
        val triples = HashMap<Triple<String, String, String>, Int>()
        payload.relationships.forEachIndexed { i, rel ->
            val path = "relationships[$i]"
            require(normalizeVerb(rel.verb).isNotBlank()) { "$path.verb must not be blank" }
            require(rel.srcUuid in payload.entities) {
                "$path.srcUuid does not reference an exported entity"
            }
            require(rel.dstUuid in payload.entities) {
                "$path.dstUuid does not reference an exported entity"
            }
            // same endpoint uuids + verbs that normalize identically fold
            // onto ONE DB row: rejected, like duplicate entity keys
            val triple = Triple(rel.srcUuid, rel.dstUuid, normalizeVerb(rel.verb))
            val firstIndex = triples.put(triple, i)
            if (firstIndex != null) {
                throw IllegalArgumentException(
                    "$path duplicates relationships[$firstIndex]: both resolve to " +
                            "the triple (${triple.first} -[${triple.third}]-> ${triple.second})"
                )
            }
            rel.notes.forEachIndexed { j, note -> validateNote("$path.notes[$j]", note) }
        }
    }

    private fun validateNote(path: String, note: EltmExportNote) {
        try {
            LocalDate.parse(note.date)
        } catch (_: DateTimeParseException) {
            throw IllegalArgumentException("$path.date must be YYYY-MM-DD, got '${note.date}'")
        }
        require(note.note.isNotBlank()) { "$path.note must not be blank" }
    }

    private fun EltmNote.toExport() = EltmExportNote(date = eventDate.toString(), note = note)

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
