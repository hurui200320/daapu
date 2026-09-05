package info.skyblond.daapu.memory.eltm

import info.skyblond.daapu.agent.model.EmbeddingModel
import info.skyblond.daapu.config.MAX_VECTOR_DIMENSIONS
import info.skyblond.daapu.db.*
import info.skyblond.daapu.hand.HandRunPolicy
import info.skyblond.daapu.hand.HandService
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.*
import java.sql.Connection
import java.time.LocalDate

/**
 * Postgres-backed [EltmService] over the `eltm_entities` /
 * `eltm_entity_attributes` / `eltm_relationships` / `eltm_notes` tables
 * (`V1__init.sql`).
 *
 * The entity embedding is `embed(canonical_name || ' ' || category)`, plus
 * the attributes as `key: value` lines alphabetically by key
 * ([entityEmbeddingText]) — attribute writes re-embed the entity, so facts
 * are semantically searchable.
 *
 * Embeddings go through the hand ([HandService.embed]) and are zero-padded
 * to the fixed column width ([MAX_VECTOR_DIMENSIONS]) on write; similarity
 * queries use the pgvector cosine distance helper
 * ([VectorColumnType.cosineDistance], rendered as the `<=>` operator) with
 * the query vector padded identically — cosine similarity is invariant under
 * zero-padding.
 *
 * Decision logic worth unit-testing (normalization, merge/collision
 * planning) lives outside the SQL; the SQL paths are covered by the
 * DB-backed `PostgresEltmServiceTest` (throwaway testcontainers database).
 */
// TODO: split this to EltmStore, the service should own the embedded text construction, etc.
class PostgresEltmService(
    private val embeddingModel: EmbeddingModel,
    private val hand: HandService,
    private val entityMatchThreshold: Double,
    private val noteSearchThreshold: Double,
    private val policy: HandRunPolicy,
) : EltmService {

    // ------------------------------------------------------------------
    // writes
    // ------------------------------------------------------------------

    override suspend fun createEntity(name: String, category: String): CreateEntityResult {
        val canonical = normalizeName(name)
        val cat = category.trim().lowercase()
        require(canonical.isNotBlank()) { "entity name must not be blank" }
        require(cat.isNotBlank()) { "entity category must not be blank" }

        // ONE transaction for the whole create-or-fetch — the key lookup,
        // the hand embed call, the insert, the near-match search and the
        // returned view's reads. The connection is held across the embed
        // (the same stance as setEntityAttributes); nothing is locked at
        // embed time (a plain SELECT takes no locks under READ COMMITTED),
        // so the embed-inside-transaction adds no deadlock surface.
        return withTransaction {
            val row = findEntityByKey(canonical, cat) ?: run {
                val embedding = embedText(entityEmbeddingText(canonical, cat, emptyMap()))
                // create-or-fetch in ONE transaction: INSERT ... ON CONFLICT DO
                // NOTHING RETURNING (`insertReturning(ignoreErrors = true)` on
                // Postgres) never aborts the transaction — a null result means a
                // concurrent run inserted the same (name, category) first, and
                // since a same-key insert blocks until the winner resolves, the
                // conflicting row is committed and visible to the re-select in
                // the SAME transaction (no nested transaction, no SQLState
                // handling). Only a real insert bumps the write counter.
                //
                // The CONFLICT clause is untargeted (Exposed's API offers no
                // conflict target here), so it swallows a violation of ANY unique
                // constraint on the table. That is safe only while the table's
                // unique constraints are exactly the intended (name, category)
                // key plus the BIGSERIAL PK (collision effectively impossible): a
                // future index must revisit this or the violation would be
                // misread as a same-key race (the re-select then fails with the
                // "not visible" error below — misleading, but fail-fast).
                val inserted = EltmEntities.insertReturning(
                    returning = listOf(
                        EltmEntities.id,
                        EltmEntities.canonicalName,
                        EltmEntities.category,
                        EltmEntities.embedding,
                    ),
                    ignoreErrors = true,
                ) {
                    it[EltmEntities.canonicalName] = canonical
                    it[EltmEntities.category] = cat
                    it[EltmEntities.embedding] = embedding
                }.singleOrNull()
                if (inserted != null) {
                    bumpWriteVersion()
                    inserted
                } else {
                    findEntityByKey(canonical, cat)
                        ?: error("unique conflict but the entity ($canonical, $cat) is not visible")
                }
            }

            val entity = row.toEntity()
            val nearMatches = row[EltmEntities.embedding]?.let { stored ->
                similarEntities(
                    queryVector = stored,
                    excludeId = entity.id,
                    threshold = entityMatchThreshold,
                    limit = NEAR_MATCH_LIMIT
                )
            } ?: emptyList()
            // the view rides the SAME transaction — the caller never pays
            // a read-after-write round trip
            CreateEntityResult(view = entityViewOf(entity), nearMatches = nearMatches)
        }
    }

    override suspend fun createEntities(entries: List<EntityDraft>): List<EltmEntity> {
        if (entries.isEmpty()) return emptyList()
        // normalize/validate every entry up front (fail fast before any
        // work), naming the offending index
        val normalized = entries.mapIndexed { index, (name, category) ->
            val canonical = normalizeName(name)
            val cat = category.trim().lowercase()
            require(canonical.isNotBlank()) { "entity name must not be blank (entry $index)" }
            require(cat.isNotBlank()) { "entity category must not be blank (entry $index)" }
            canonical to cat
        }
        // the bulk create-or-fetch (the batch unit's semantics:
        // EltmService.createEntities): ONE transaction holds the batched
        // key lookups, the batched embeds, the per-key ON CONFLICT inserts
        // and the re-selects — the connection is held across the embeds,
        // the same stance as setEntityAttributes
        return withTransaction {
            // duplicate keys fold onto ONE row: create-or-fetch per key
            val keys = normalized.distinct()
            val existing = findEntitiesByKeys(keys).associateBy {
                it[EltmEntities.canonicalName] to it[EltmEntities.category]
            }
            val missing = keys.filter { it !in existing }
            val resolved = HashMap(existing)
            if (missing.isNotEmpty()) {
                // the missing keys' texts ride the hand's batched /v1/embed —
                // ONE batched call series for the whole batch, never one
                // embed call per entity (see embedAll)
                val embeddings = embedAll(
                    missing.map { (name, cat) -> entityEmbeddingText(name, cat, emptyMap()) }
                )
                var insertedAny = false
                for ((key, embedding) in missing.zip(embeddings)) {
                    // the single createEntity insert path, per key: ON
                    // CONFLICT DO NOTHING RETURNING adopts a concurrent
                    // same-key insert (a null result) — the re-select below
                    // resolves it (see createEntity for the full rationale)
                    val row = EltmEntities.insertReturning(
                        returning = listOf(
                            EltmEntities.id,
                            EltmEntities.canonicalName,
                            EltmEntities.category,
                        ),
                        ignoreErrors = true,
                    ) {
                        it[EltmEntities.canonicalName] = key.first
                        it[EltmEntities.category] = key.second
                        it[EltmEntities.embedding] = embedding
                    }.singleOrNull()
                    if (row != null) {
                        insertedAny = true
                        resolved[key] = row
                    }
                }
                // only a real insert bumps the write counter — a fully
                // conflict-adopted batch is a pure read (per-entity
                // semantics preserved)
                if (insertedAny) bumpWriteVersion()
                // resolve the conflict-adopted keys (the rare path —
                // usually every insert above succeeded)
                val adopted = missing.filter { it !in resolved }
                if (adopted.isNotEmpty()) {
                    findEntitiesByKeys(adopted).forEach { row ->
                        resolved[row[EltmEntities.canonicalName] to row[EltmEntities.category]] = row
                    }
                }
            }
            normalized.map { key ->
                resolved[key]?.toEntity()
                    ?: error("unique conflict but the entity (${key.first}, ${key.second}) is not visible")
            }
        }
    }

    override suspend fun refineEntity(
        entityId: Long, newName: String?, newCategory: String?,
    ): EntityView {
        val canonical = newName?.let {
            normalizeName(it).also { name ->
                require(name.isNotBlank()) { "entity name must not be blank" }
            }
        }
        val trimmedCategory = newCategory?.trim()?.lowercase()
        if (trimmedCategory != null) {
            require(trimmedCategory.isNotBlank()) { "entity category must not be blank" }
        }
        // like setEntityAttributes: ONE transaction for the whole
        // read-modify-write, the hand embed call included — the connection is
        // held across the embed. The entity row is locked FOR UPDATE before
        // the collision check, so the rename can never race a concurrent
        // write on the same entity (or a merge). A no-op refine is a pure
        // read: no embedding call, no counter bump. The returned view rides
        // the SAME transaction — the caller never pays a read-after-write
        // round trip.
        return withTransaction {
            val row = findEntityRowByIdForUpdate(entityId)
                ?: throw IllegalArgumentException("entity $entityId does not exist")
            val currentName = row[EltmEntities.canonicalName]
            val currentCat = row[EltmEntities.category]
            val newCat = trimmedCategory ?: currentCat
            val newCanonical = canonical ?: currentName
            if (currentName == newCanonical && currentCat == newCat) {
                // identical (name, category): nothing changes — a refine that
                // only echoes the current state is a pure read
                return@withTransaction entityViewOf(row.toEntity())
            }
            // fail fast on a collision before the embed: the target
            // (name, category) is already another entity's key — the caller
            // must merge the two instead (an unhandled unique violation on
            // the UPDATE below would escape as a raw SQL error)
            checkNoNameCollision(entityId, newCanonical, newCat)
            val embedding = embedText(
                entityEmbeddingText(
                    newCanonical,
                    newCat,
                    attributesOf(entityId),
                )
            )
            try {
                EltmEntities.update({ EltmEntities.id eq entityId }) {
                    it[EltmEntities.canonicalName] = newCanonical
                    it[EltmEntities.category] = newCat
                    it[EltmEntities.embedding] = embedding
                }
            } catch (e: Exception) {
                if (!e.isUniqueViolation()) throw e
                // a concurrent run created the target (name, category)
                // between the check above and the UPDATE: the update rolled
                // back AND the transaction is now aborted (PostgreSQL refuses
                // every further statement in it, so no re-check is possible
                // here) — raise the merge-instead error DIRECTLY (the racing
                // row's id cannot be included: it is not queryable from an
                // aborted transaction). NEVER let a raw SQLException escape:
                // one that escapes this withTransaction block makes Exposed
                // re-run the whole block (the hand embed call included) up to
                // defaultMaxAttempts (3) times; an IllegalArgumentException
                // is terminal and maps to the tool layer's model-visible
                // error.
                throw IllegalArgumentException(
                    "an entity \"$newCanonical\" (category $newCat) already exists " +
                            "as another entity: merge the two instead",
                    e,
                )
            }
            bumpWriteVersion()
            entityViewOf(EltmEntity(entityId, newCanonical, newCat))
        }
    }

    override suspend fun createRelationship(
        srcId: Long, dstId: Long, verb: String
    ): RelationshipView {
        val v = normalizeVerb(verb)
        require(v.isNotBlank()) { "relationship verb must not be blank" }
        // ONE transaction for the whole create-or-fetch — the endpoint
        // checks, the find-or-insert and the returned view's reads (no
        // embed exists on this path). The endpoint check rides the SAME
        // transaction, which also decides the missing id — no second
        // re-query, and the message matches the state that failed the
        // check. The returned view rides the same transaction too, so the
        // caller never pays a read-after-write round trip.
        return withTransaction {
            val missingEntityId = when {
                findEntityById(srcId) == null -> srcId
                findEntityById(dstId) == null -> dstId
                else -> null
            }
            require(missingEntityId == null) { "entity $missingEntityId does not exist" }
            // exactly ONE row per triple: the row IS the relationship — an
            // existing row (active OR invalidated) is a pure read returned
            // as-is; validity only moves with a diary event
            // (attachNotesToRelationship's valid flag), never here.
            // The insert rides ON CONFLICT DO NOTHING RETURNING (see
            // createEntity for the full rationale AND the untargeted-clause
            // caveat: the triple is the table's only unique index besides
            // the PK, so the swallowed violation can only be a same-key
            // race), so a concurrent same-triple insert never aborts
            // the transaction, and the re-select below stays in it.
            val rel = findRelationshipByTriple(srcId, v, dstId)?.toRelationship() ?: run {
                val insertedId = EltmRelationships.insertReturning(
                    returning = listOf(EltmRelationships.id),
                    ignoreErrors = true,
                ) {
                    it[EltmRelationships.srcId] = srcId
                    it[EltmRelationships.dstId] = dstId
                    it[EltmRelationships.verb] = v
                }.singleOrNull()?.get(EltmRelationships.id)
                if (insertedId != null) {
                    bumpWriteVersion()
                    // a fresh row always starts active (the column default)
                    // — constructed directly, no trailing re-read (a re-read
                    // in a SEPARATE transaction could even miss the row: a
                    // concurrent merge folding it away between the two)
                    EltmRelationship(insertedId, srcId, dstId, v, valid = true)
                } else {
                    findRelationshipByTriple(srcId, v, dstId)?.toRelationship()
                        ?: error("unique conflict but the relationship ($srcId -[$v]-> $dstId) is not visible")
                }
            }
            relationshipViewOf(rel)
        }
    }

    override suspend fun createRelationships(triples: List<RelationshipDraft>): List<EltmRelationship> {
        if (triples.isEmpty()) return emptyList()
        // normalize every verb up front (fail fast before any work, naming
        // the entry's index)
        val normalized = triples.mapIndexed { index, (srcId, verb, dstId) ->
            val v = normalizeVerb(verb)
            require(v.isNotBlank()) { "relationship verb must not be blank (entry $index)" }
            Triple(srcId, v, dstId)
        }
        // the bulk create-or-fetch (the batch unit's semantics:
        // EltmService.createRelationships): ONE transaction holds the
        // endpoint check, the batched triple lookups, the per-triple ON
        // CONFLICT inserts and the re-selects — no embed exists on this
        // path
        return withTransaction {
            // ONE endpoint existence check for the whole batch (a single
            // inList query); the first missing id in input order fails the
            // call before any insert (the FK would catch them later with a
            // raw SQL error)
            val endpointIds = normalized.flatMapTo(HashSet()) { listOf(it.first, it.third) }
            val present = EltmEntities.select(EltmEntities.id)
                .where { EltmEntities.id inList endpointIds }
                .mapTo(HashSet()) { it[EltmEntities.id] }
            val missingEntityId = normalized.firstNotNullOfOrNull { (srcId, _, dstId) ->
                when {
                    srcId !in present -> srcId
                    dstId !in present -> dstId
                    else -> null
                }
            }
            require(missingEntityId == null) { "entity $missingEntityId does not exist" }

            // duplicate triples fold onto ONE row: create-or-fetch per triple
            val distinct = normalized.distinct()
            val existing = findRelationshipsByTriples(distinct).associateBy {
                Triple(it[EltmRelationships.srcId], it[EltmRelationships.verb], it[EltmRelationships.dstId])
            }.mapValues { it.value.toRelationship() }
            val missing = distinct.filter { it !in existing }
            val resolved = HashMap(existing)
            if (missing.isNotEmpty()) {
                var insertedAny = false
                for ((srcId, v, dstId) in missing) {
                    // the single createRelationship insert path, per triple:
                    // ON CONFLICT DO NOTHING RETURNING adopts a concurrent
                    // same-triple insert (a null result) — the re-select
                    // below resolves it (see createEntity for the full
                    // rationale)
                    val insertedId = EltmRelationships.insertReturning(
                        returning = listOf(EltmRelationships.id),
                        ignoreErrors = true,
                    ) {
                        it[EltmRelationships.srcId] = srcId
                        it[EltmRelationships.dstId] = dstId
                        it[EltmRelationships.verb] = v
                    }.singleOrNull()?.get(EltmRelationships.id)
                    if (insertedId != null) {
                        insertedAny = true
                        resolved[Triple(srcId, v, dstId)] =
                            EltmRelationship(insertedId, srcId, dstId, v, valid = true)
                    }
                }
                // only a real insert bumps the write counter — a fully
                // conflict-adopted batch is a pure read
                if (insertedAny) bumpWriteVersion()
                // resolve the conflict-adopted triples (the rare path)
                val adopted = missing.filter { it !in resolved }
                if (adopted.isNotEmpty()) {
                    findRelationshipsByTriples(adopted).forEach { row ->
                        resolved[
                            Triple(
                                row[EltmRelationships.srcId],
                                row[EltmRelationships.verb],
                                row[EltmRelationships.dstId],
                            )
                        ] = row.toRelationship()
                    }
                }
            }
            normalized.map { triple ->
                resolved[triple]
                    ?: error(
                        "unique conflict but the relationship " +
                            "(${triple.first} -[${triple.second}]-> ${triple.third}) is not visible"
                    )
            }
        }
    }

    override suspend fun attachNotesToEntity(
        entityId: Long,
        notes: List<NoteDraft>,
    ): List<EltmNote> {
        val drafts = notes.map { it.validated() }
        if (drafts.isEmpty()) return emptyList()
        // fail fast on a missing subject with a clear message before the
        // embed call (the FK would catch it later with a SQL error)
        require(withTransaction { findEntityById(entityId) != null }) {
            "entity $entityId does not exist"
        }
        // the batch's embeddings ride the hand's batched /v1/embed — never
        // one call per note (see EltmService)
        val embeddings = embedAll(drafts.map { noteEmbeddingText(it.note) })
        return withTransaction {
            // the whole batch rides ONE JDBC batched INSERT (the generated
            // ids read back aligned with the input rows — no unique
            // constraint exists on the add-only notes table, so no conflict
            // can skew the association), never one INSERT statement per
            // note
            val rows = EltmNotes.batchInsert(
                drafts.zip(embeddings),
                shouldReturnGeneratedValues = true,
            ) { (draft, embedding) ->
                this[EltmNotes.entityId] = entityId
                this[EltmNotes.relationshipId] = null
                this[EltmNotes.eventDate] = draft.eventDate
                this[EltmNotes.note] = draft.note
                this[EltmNotes.embedding] = embedding
            }
            bumpWriteVersion()
            rows.mapIndexed { index, row ->
                val draft = drafts[index]
                EltmNote(
                    id = row[EltmNotes.id],
                    entityId = entityId,
                    relationshipId = null,
                    eventDate = draft.eventDate,
                    note = draft.note,
                )
            }
        }
    }

    override suspend fun attachNoteToEntity(
        entityId: Long, eventDate: LocalDate, note: String,
    ): EltmNote = attachNotesToEntity(entityId, listOf(NoteDraft(eventDate, note))).single()

    override suspend fun attachNotesToRelationship(
        relationshipId: Long,
        notes: List<NoteDraft>,
        valid: Boolean?,
    ): RelationshipNotesResult {
        val drafts = notes.map { it.validated() }
        // a bare structural change carries no reason (see EltmService): a
        // [valid] flip must ride at least one note — setRelationshipValid is
        // the only note-less validity write
        require(drafts.isNotEmpty() || valid == null) {
            "a bare structural change carries no reason: attach a note or use setRelationshipValid"
        }
        // fail fast on a missing subject with a clear message before the
        // embed call (the FK would catch it later with a SQL error); the
        // check also reads the current validity — the post-attach state the
        // result reports when no [valid] argument moves it
        val currentValid = withTransaction {
            val rel = findRelationshipById(relationshipId)
                ?: throw IllegalArgumentException("relationship $relationshipId does not exist")
            rel.valid
        }
        if (drafts.isEmpty()) {
            // a degenerate no-op (no notes, no validity change): nothing
            // embeds, nothing writes, nothing bumps — a pure read
            return RelationshipNotesResult(emptyList(), currentValid)
        }
        val embeddings = embedAll(drafts.map { noteEmbeddingText(it.note) })
        return withTransaction {
            if (valid != null) {
                // idempotent: setting the current state affects no row
                // (an already-closed edge stays closed, an already-valid
                // one stays valid) — the compound event still bumps once
                EltmRelationships.update({ EltmRelationships.id eq relationshipId }) {
                    it[EltmRelationships.valid] = valid
                }
            }
            // the whole batch rides ONE JDBC batched INSERT (the generated
            // ids read back aligned — no unique constraint exists on the
            // add-only notes table), never one INSERT statement per note
            val rows = EltmNotes.batchInsert(
                drafts.zip(embeddings),
                shouldReturnGeneratedValues = true,
            ) { (draft, embedding) ->
                this[EltmNotes.entityId] = null
                this[EltmNotes.relationshipId] = relationshipId
                this[EltmNotes.eventDate] = draft.eventDate
                this[EltmNotes.note] = draft.note
                this[EltmNotes.embedding] = embedding
            }
            bumpWriteVersion()
            RelationshipNotesResult(
                notes = rows.mapIndexed { index, row ->
                    val draft = drafts[index]
                    EltmNote(
                        id = row[EltmNotes.id],
                        entityId = null,
                        relationshipId = relationshipId,
                        eventDate = draft.eventDate,
                        note = draft.note,
                    )
                },
                valid = valid ?: currentValid,
            )
        }
    }

    override suspend fun attachNoteToRelationship(
        relationshipId: Long, eventDate: LocalDate, note: String, valid: Boolean?,
    ): RelationshipNotesResult = attachNotesToRelationship(
        relationshipId,
        listOf(NoteDraft(eventDate, note)),
        valid,
    )

    override suspend fun setEntityAttributes(entityId: Long, values: Map<String, String>): Int {
        // normalize/validate every entry up front (fail fast before any
        // write). One row per (entity, key): two raw keys that canonicalize
        // alike fold onto one entry, the later value wins (the map's own
        // semantics)
        val normalized = LinkedHashMap<String, String>()
        for ((key, value) in values) {
            val k = normalizeAttributeKey(key)
            val v = value.trim()
            require(k.isNotBlank()) { "attribute key must not be blank" }
            require(v.isNotBlank()) { "attribute value must not be blank" }
            // the value is appended to the entity embedding text as a single
            // `key: value` line: a newline would corrupt the line structure
            require(v.none { it == '\n' || it == '\r' }) {
                "attribute value must be a single line"
            }
            normalized[k] = v
        }
        if (normalized.isEmpty()) return 0
        // ONE transaction for the whole read-modify-write, the hand embed
        // call included: the connection is held across the embed (the price
        // of consistency). The entity row is locked FOR UPDATE before the
        // attribute read, so a concurrent attribute write on the same entity
        // (or a merge) blocks here and then sees the fresh state — the
        // embedding text and the attribute rows can never diverge. ONE embed
        // of the FINAL text for the whole batch (never per key), ONE bump.
        return withTransaction {
            // fail fast on a missing subject with a clear message before
            // the embed call (the FK would catch it later with a SQL error);
            // the FOR UPDATE lock is the serialization point
            val row = findEntityRowByIdForUpdate(entityId)
                ?: throw IllegalArgumentException("entity $entityId does not exist")
            val current = attributesOf(entityId)
            // a no-op key is a pure read for that key: only the changed
            // keys write, and an all-identical batch never embeds nor bumps
            val changed = normalized.filter { (k, v) -> current[k] != v }
            if (changed.isEmpty()) {
                return@withTransaction 0
            }
            // EmbeddingException (e.g. invalid_request) propagates to the
            // caller (rolled back)
            val embedding = embedText(
                entityEmbeddingText(
                    row[EltmEntities.canonicalName],
                    row[EltmEntities.category],
                    current + changed,
                )
            )
            // one row per (entity, key): overwrite the value in place — the
            // whole batch rides ONE JDBC batched UPSERT (ON CONFLICT
            // (entity_id, key) DO UPDATE SET value = excluded.value), never
            // one UPSERT statement per key
            EltmEntityAttributes.batchUpsert(
                changed.entries,
                keys = arrayOf(EltmEntityAttributes.entityId, EltmEntityAttributes.key),
                shouldReturnGeneratedValues = false,
            ) { (k, v) ->
                this[EltmEntityAttributes.entityId] = entityId
                this[EltmEntityAttributes.key] = k
                this[EltmEntityAttributes.value] = v
            }
            EltmEntities.update({ EltmEntities.id eq entityId }) {
                it[EltmEntities.embedding] = embedding
            }
            bumpWriteVersion()
            changed.size
        }
    }

    override suspend fun setEntityAttribute(entityId: Long, key: String, value: String): Boolean =
        setEntityAttributes(entityId, mapOf(key to value)) > 0

    override suspend fun deleteEntityAttribute(entityId: Long, key: String) {
        val k = normalizeAttributeKey(key)
        require(k.isNotBlank()) { "attribute key must not be blank" }
        // like setEntityAttributes: ONE transaction with the hand embed call
        // inside and the entity row locked FOR UPDATE at the start, so the
        // embedding always matches the surviving attributes
        withTransaction {
            val row = findEntityRowByIdForUpdate(entityId)
                ?: throw IllegalArgumentException("entity $entityId does not exist")
            val current = attributesOf(entityId)
            require(current.containsKey(k)) {
                "attribute \"$k\" does not exist on entity $entityId"
            }
            val embedding = embedText(
                entityEmbeddingText(
                    row[EltmEntities.canonicalName],
                    row[EltmEntities.category],
                    current - k,
                )
            )
            EltmEntityAttributes.deleteWhere {
                (EltmEntityAttributes.entityId eq entityId) and
                    (EltmEntityAttributes.key eq k)
            }
            EltmEntities.update({ EltmEntities.id eq entityId }) {
                it[EltmEntities.embedding] = embedding
            }
            bumpWriteVersion()
        }
    }

    override suspend fun setRelationshipValid(relationshipId: Long, valid: Boolean): Boolean =
        withTransaction {
            // existence check + change detection, then the atomic UPDATE —
            // no FOR UPDATE lock needed: validity is not part of the
            // embedding text, so unlike setEntityAttributes a concurrent
            // write between the read and the update can never make a
            // stored embedding diverge (last write wins on a boolean). The
            // UPDATE's row count IS checked: a concurrent merge fold that
            // deleted the row between the read and the update fails fast
            // instead of reporting a write that wrote nothing (and bumping
            // the counter for it).
            val current = findRelationshipById(relationshipId)
                ?: throw IllegalArgumentException("relationship $relationshipId does not exist")
            if (current.valid == valid) {
                false
            } else {
                val updated = EltmRelationships.update({ EltmRelationships.id eq relationshipId }) {
                    it[EltmRelationships.valid] = valid
                }
                if (updated == 0) {
                    throw IllegalArgumentException("relationship $relationshipId no longer exists")
                }
                bumpWriteVersion()
                true
            }
        }

    override suspend fun mergeEntities(winnerId: Long, loserId: Long) = withTransaction {
        require(winnerId != loserId) { "cannot merge an entity into itself" }
        // the WHOLE merge — the reads, the fold's embed and the writes —
        // runs in ONE transaction, the hand embed call included: the
        // connection is held across the embed (the price of consistency).
        // Both entity rows are locked FOR UPDATE first, so the fold can
        // never race a concurrent attribute write: an attribute write on
        // either entity blocks here and then sees the merged state — the
        // loser's rows present at write time are exactly the ones folded
        // (no orphaned row silently cascade-deleted, no PK collision).
        // Winner wins a colliding key.
        // The two row locks are taken in ascending id order (not winner
        // first), so two opposite-direction concurrent merges (A→B and B→A)
        // acquire the locks in the same order and can never deadlock — the
        // second blocks on the first and then proceeds on the merged state.
        val firstId = minOf(winnerId, loserId)
        val secondId = maxOf(winnerId, loserId)
        val firstRow = findEntityRowByIdForUpdate(firstId)
            ?: throw IllegalArgumentException("entity $firstId does not exist")
        val secondRow = findEntityRowByIdForUpdate(secondId)
            ?: throw IllegalArgumentException("entity $secondId does not exist")
        // the row of [winnerId]/[loserId] among the two locked rows
        val winnerRow = if (firstId == winnerId) firstRow else secondRow
        val loserRow = if (firstId == winnerId) secondRow else firstRow
        val winnerName = winnerRow[EltmEntities.canonicalName]
        val winnerCat = winnerRow[EltmEntities.category]
        val winnerAttrs = attributesOf(winnerId)
        val loserAttrs = attributesOf(loserId)
        // the fold plan is the shared decision logic (the loser's unique
        // keys fold in, the colliding ones keep the winner's value), so the
        // writes below and the re-embed decision can never disagree
        val foldPlan = planAttributeFold(winnerAttrs, loserAttrs)
        // only re-embed when the fold actually changed the winner's
        // attribute text — an attribute-less merge reuses the stored vector
        val winnerEmbedding = if (foldPlan.changesText) {
            embedText(entityEmbeddingText(winnerName, winnerCat, foldPlan.winnerAttributes))
        } else null

        val loserRels = EltmRelationships.selectAll().where {
            (EltmRelationships.srcId eq loserId) or (EltmRelationships.dstId eq loserId)
        }.toList()
        // The relationship-fold decision tree below (self-loop → invalidate,
        // triple collision → fold duplicate away, else re-point) has NO
        // shared pure planner — unlike the attribute fold above — and the
        // loop interleaves DB lookups with its own mutations (earlier
        // iterations create/delete rows the survivor lookup must see), so
        // planner extraction needs care to simulate that in-loop state.
        // The tree's behavior is pinned by PostgresEltmServiceTest's
        // mergeEntities tests.
        for (rel in loserRels) {
            val newSrc =
                if (rel[EltmRelationships.srcId] == loserId) winnerId
                else rel[EltmRelationships.srcId]
            val newDst =
                if (rel[EltmRelationships.dstId] == loserId) winnerId
                else rel[EltmRelationships.dstId]

            val survivor = findRelationshipByTriple(newSrc, rel[EltmRelationships.verb], newDst)
            when {
                // the re-pointed edge would become a self-loop (winner—winner,
                // from winner—loser, loser—winner or loser—loser): invalidate
                // instead of re-pointing — a self-loop is never meaningful.
                // A twin self-loop row may already exist (another loser edge
                // collapsed onto the same triple first): fold into it instead
                // of violating the unique index
                newSrc == newDst -> if (survivor != null) {
                    EltmNotes.update({ EltmNotes.relationshipId eq rel[EltmRelationships.id] }) {
                        it[EltmNotes.relationshipId] = survivor[EltmRelationships.id]
                    }
                    EltmRelationships.deleteWhere {
                        EltmRelationships.id eq rel[EltmRelationships.id]
                    }
                } else {
                    EltmRelationships.update({ EltmRelationships.id eq rel[EltmRelationships.id] }) {
                        it[EltmRelationships.srcId] = newSrc
                        it[EltmRelationships.dstId] = newDst
                        it[EltmRelationships.valid] = false
                    }
                }

                survivor != null && survivor[EltmRelationships.id] != rel[EltmRelationships.id] -> {
                    // collides with an existing row of the same triple
                    // (valid or not): re-point the duplicate's diary notes
                    // to the survivor, fold the validity (the survivor
                    // holds the edge if either row held it), and only
                    // THEN delete the duplicate row (the ON DELETE
                    // CASCADE must never destroy diary notes)
                    EltmNotes.update({ EltmNotes.relationshipId eq rel[EltmRelationships.id] }) {
                        it[EltmNotes.relationshipId] = survivor[EltmRelationships.id]
                    }
                    if (rel[EltmRelationships.valid] && !survivor[EltmRelationships.valid]) {
                        EltmRelationships.update({ EltmRelationships.id eq survivor[EltmRelationships.id] }) {
                            it[EltmRelationships.valid] = true
                        }
                    }
                    EltmRelationships.deleteWhere {
                        EltmRelationships.id eq rel[EltmRelationships.id]
                    }
                }

                // no collision (or the survivor IS this row): re-point
                else -> EltmRelationships.update({ EltmRelationships.id eq rel[EltmRelationships.id] }) {
                    it[EltmRelationships.srcId] = newSrc
                    it[EltmRelationships.dstId] = newDst
                }
            }
        }

        // re-point the loser's entity notes, fold its attributes per the
        // shared plan (the colliding rows are dropped: re-pointing them
        // would overwrite the winner's value on the composite PK; the
        // foldable rows re-point to the winner), then delete the loser row
        // (the cascade never fires: no note references the loser anymore)
        EltmNotes.update({ EltmNotes.entityId eq loserId }) {
            it[EltmNotes.entityId] = winnerId
        }
        val droppedKeys = foldPlan.droppedKeys.toList()
        if (droppedKeys.isNotEmpty()) {
            EltmEntityAttributes.deleteWhere {
                (EltmEntityAttributes.entityId eq loserId) and
                    (EltmEntityAttributes.key inList droppedKeys)
            }
        }
        val foldableKeys = foldPlan.foldableKeys.toList()
        if (foldableKeys.isNotEmpty()) {
            EltmEntityAttributes.update({
                (EltmEntityAttributes.entityId eq loserId) and
                    (EltmEntityAttributes.key inList foldableKeys)
            }) {
                it[EltmEntityAttributes.entityId] = winnerId
            }
        }
        if (winnerEmbedding != null) {
            EltmEntities.update({ EltmEntities.id eq winnerId }) {
                it[EltmEntities.embedding] = winnerEmbedding
            }
        }
        EltmEntities.deleteWhere { EltmEntities.id eq loserId }
        // one bump for the whole transactional merge (the loser delete is
        // the reliable change signal; the re-points ride the same commit)
        bumpWriteVersion()
    }

    // ------------------------------------------------------------------
    // reads
    // ------------------------------------------------------------------

    /**
     * Shared paging guards for every paginated read. The HTTP boundary
     * mirrors these as 400s (`server/endpoint/Params.kt`); the service-side
     * check stays because the tools and the pipeline call the service
     * directly.
     */
    private fun requirePaging(limit: Int, offset: Int) {
        require(limit >= 1) { "limit must be >= 1, got $limit" }
        require(offset >= 0) { "offset must be >= 0, got $offset" }
    }

    override suspend fun searchEntities(query: String, limit: Int): List<EntityWithScore> {
        require(query.isNotBlank()) { "query must not be blank" }
        require(limit >= 1) { "limit must be >= 1, got $limit" }
        val q = embedText(query)
        return withTransaction {
            similarEntities(q, excludeId = null, entityMatchThreshold, limit)
        }
    }

    override suspend fun listEntities(limit: Int, offset: Int): List<EntityView> = withTransaction {
        requirePaging(limit, offset)
        val entities = EltmEntities.selectAll()
            .orderBy(EltmEntities.id to SortOrder.ASC)
            .limit(limit)
            .offset(offset.toLong())
            .map { it.toEntity() }
        // a whole page's counts, latest notes and attributes in bounded
        // batch queries (see noteCountsAndLatest) instead of the
        // single-subject helpers' 5 per row (the single-subject reads stay
        // per-row: one row, five queries)
        val noteSummary = noteCountsAndLatest(EltmNotes.entityId, entities.map { it.id })
        val relationshipCounts = relationshipCountsFor(entities.map { it.id })
        val attributes = attributesFor(entities.map { it.id })
        entities.map { entity ->
            EntityView(
                entity = entity,
                noteCount = noteSummary[entity.id]?.first ?: 0,
                relationshipCount = relationshipCounts[entity.id] ?: 0,
                latestNote = noteSummary[entity.id]?.second,
                attributes = attributes[entity.id] ?: emptyMap(),
            )
        }
    }

    override suspend fun listRelationships(limit: Int, offset: Int): List<RelationshipView> =
        withTransaction {
            requirePaging(limit, offset)
            val rels = EltmRelationships.selectAll()
                .orderBy(EltmRelationships.id to SortOrder.ASC)
                .limit(limit)
                .offset(offset.toLong())
                .map { it.toRelationship() }
            if (rels.isEmpty()) return@withTransaction emptyList()
            // the whole page's views ride the shared batch builder
            toRelationshipViews(rels)
        }

    override suspend fun exportAll(): EltmSnapshot = withTransaction(
        // REPEATABLE READ, not the default READ COMMITTED: the four SELECTs
        // below must be ONE snapshot — under READ COMMITTED each statement
        // takes its own, so the extraction pipeline committing between them
        // would leak a relationship whose endpoint entity the first select
        // never saw (breaking the export's uuid join) or notes for a
        // subject outside the snapshot (silently dropped from the backup).
        // See EltmService.exportAll. The read-only workload cannot hit the
        // write-conflict aborts REPEATABLE READ can raise.
        isolation = Connection.TRANSACTION_REPEATABLE_READ,
    ) {
        // the whole store (the snapshot rationale lives on
        // EltmService.exportAll); only the content columns — the embedding
        // vectors (2000 dims per row) never travel
        val entities = EltmEntities
            .select(EltmEntities.id, EltmEntities.canonicalName, EltmEntities.category)
            .orderBy(EltmEntities.id to SortOrder.ASC)
            .map { it.toEntity() }
        EltmSnapshot(
            entities = entities,
            attributes = attributesFor(entities.map { it.id }),
            relationships = EltmRelationships.selectAll()
                .orderBy(EltmRelationships.id to SortOrder.ASC)
                .map { it.toRelationship() },
            notes = EltmNotes.select(
                EltmNotes.id,
                EltmNotes.entityId,
                EltmNotes.relationshipId,
                EltmNotes.eventDate,
                EltmNotes.note,
            ).orderBy(EltmNotes.eventDate to SortOrder.ASC, EltmNotes.id to SortOrder.ASC)
                .map { it.toNote() },
        )
    }

    override suspend fun getEntity(id: Long): EntityView? = withTransaction {
        val entity = findEntityById(id) ?: return@withTransaction null
        entityViewOf(entity)
    }

    override suspend fun getRelationship(id: Long): RelationshipView? = withTransaction {
        val rel = findRelationshipById(id) ?: return@withTransaction null
        // the single-subject cheap helpers, NOT the page builder's batch
        // queries — one row must never pay for a page (and never
        // materialize the subject's whole diary, see noteCountsAndLatest)
        relationshipViewOf(rel)
    }

    override suspend fun entityExists(entityId: Long): Boolean =
        withTransaction { findEntityRowById(entityId) != null }

    override suspend fun relationshipExists(relationshipId: Long): Boolean =
        withTransaction { findRelationshipById(relationshipId) != null }

    override suspend fun getRelationships(
        entityId: Long,
        includeInvalid: Boolean,
    ): List<RelationshipView> = withTransaction {
        val cond: Op<Boolean> =
            (EltmRelationships.srcId eq entityId) or (EltmRelationships.dstId eq entityId)

        val filtered = if (includeInvalid) cond else cond and (EltmRelationships.valid eq true)
        val rels = EltmRelationships.selectAll().where { filtered }
            .orderBy(EltmRelationships.id to SortOrder.DESC)
            .map { it.toRelationship() }
        // the whole drill-down rides the shared batch builder, not a
        // per-row view build
        toRelationshipViews(rels)
    }

    private fun toRelationshipViews(rels: List<EltmRelationship>): List<RelationshipView> {
        if (rels.isEmpty()) return emptyList()
        // a whole list's endpoint names, note counts and latest notes in
        // 2 queries instead of the single-subject helpers' 4 per row
        val names = EltmEntities.selectAll()
            .where { EltmEntities.id inList rels.flatMap { listOf(it.srcId, it.dstId) } }
            .associate { it[EltmEntities.id] to it[EltmEntities.canonicalName] }
        val noteSummary = noteCountsAndLatest(EltmNotes.relationshipId, rels.map { it.id })
        return rels.map { rel ->
            RelationshipView(
                relationship = rel,
                srcName = names[rel.srcId] ?: "<deleted entity ${rel.srcId}>",
                dstName = names[rel.dstId] ?: "<deleted entity ${rel.dstId}>",
                noteCount = noteSummary[rel.id]?.first ?: 0,
                latestNote = noteSummary[rel.id]?.second,
            )
        }
    }

    /**
     * Paginated notes of ONE subject, newest event first. The diary ordering
     * rule is `event_date DESC, id DESC` and exists in exactly three SQL
     * spots ([noteQuery], [latestNote], [noteCountsAndLatest]) — update
     * them together or the single-subject and batch views disagree.
     */
    private suspend fun noteQuery(
        column: Column<Long?>,
        subjectId: Long,
        from: LocalDate?,
        to: LocalDate?,
        limit: Int,
        offset: Int,
    ): List<EltmNote> {
        require(from == null || to == null || !from.isAfter(to)) {
            "from must not be after to"
        }
        requirePaging(limit, offset)
        return withTransaction {
            selectNoteContent().where {
                (column eq subjectId)
                    .andIfNotNull(from?.let { EltmNotes.eventDate greaterEq it })
                    .andIfNotNull(to?.let { EltmNotes.eventDate lessEq it })
            }
                .orderBy(EltmNotes.eventDate to SortOrder.DESC, EltmNotes.id to SortOrder.DESC)
                .limit(limit)
                .offset(offset.toLong())
                .map { it.toNote() }
        }
    }

    override suspend fun getEntityNotes(
        entityId: Long,
        from: LocalDate?,
        to: LocalDate?,
        limit: Int,
        offset: Int,
    ): List<EltmNote> = noteQuery(EltmNotes.entityId, entityId, from, to, limit, offset)

    override suspend fun getRelationshipNotes(
        relationshipId: Long,
        from: LocalDate?,
        to: LocalDate?,
        limit: Int,
        offset: Int,
    ): List<EltmNote> = noteQuery(EltmNotes.relationshipId, relationshipId, from, to, limit, offset)

    override suspend fun searchNotes(
        query: String,
        entityId: Long?,
        relationshipId: Long?,
        from: LocalDate?,
        to: LocalDate?,
        limit: Int,
    ): List<EltmNote> {
        require(query.isNotBlank()) { "query must not be blank" }
        require(entityId == null || relationshipId == null) {
            "a note search accepts at most one subject"
        }
        require(limit >= 1) { "limit must be >= 1, got $limit" }
        require(from == null || to == null || !from.isAfter(to)) {
            "from must not be after to"
        }
        val q = embedText(query)
        return withTransaction { searchNotesByVector(q, entityId, relationshipId, from, to, limit) }
    }

    override suspend fun searchEntitiesAndNotes(
        query: String,
        entityLimit: Int,
        noteLimit: Int,
    ): EltmSearchHits {
        require(query.isNotBlank()) { "query must not be blank" }
        require(entityLimit >= 0) { "entityLimit must be >= 0, got $entityLimit" }
        require(noteLimit >= 0) { "noteLimit must be >= 0, got $noteLimit" }
        // both limits zero: nothing to retrieve — no embed call, no query
        if (entityLimit == 0 && noteLimit == 0) return EltmSearchHits(emptyList(), emptyList())
        // ONE embedded query feeds both halves (the whole point — see
        // EltmService.searchEntitiesAndNotes); the two searches ride ONE
        // transaction
        val q = embedText(query)
        return withTransaction {
            EltmSearchHits(
                entities = if (entityLimit > 0) {
                    similarEntities(q, excludeId = null, entityMatchThreshold, entityLimit)
                } else emptyList(),
                notes = if (noteLimit > 0) {
                    searchNotesByVector(q, null, null, null, null, noteLimit)
                } else emptyList(),
            )
        }
    }

    /**
     * The note search's SQL over an ALREADY-EMBEDDED query vector — the
     * shared body of [searchNotes] (its own embed) and
     * [searchEntitiesAndNotes] (the one embed feeding both halves).
     * Ambient transaction.
     */
    private fun searchNotesByVector(
        q: List<Float>,
        entityId: Long?,
        relationshipId: Long?,
        from: LocalDate?,
        to: LocalDate?,
        limit: Int,
    ): List<EltmNote> {
        val dist = VectorColumnType.cosineDistance(EltmNotes.embedding, q)
        // pgvector's HNSW index post-filters: a selective WHERE (subject
        // or date range) can end the index scan early, so this can
        // return FEWER than [limit] rows even when further matches
        // exist (pgvector <=0.7 behavior; iterative scans would fix it).
        // The exact match (similarity 1.0) always survives in practice.
        return selectNoteContent().where {
            (dist lessEq 1.0 - noteSearchThreshold)
                .and(EltmNotes.embedding.isNotNull())
                .andIfNotNull(entityId?.let { EltmNotes.entityId eq it })
                .andIfNotNull(relationshipId?.let { EltmNotes.relationshipId eq it })
                .andIfNotNull(from?.let { EltmNotes.eventDate greaterEq it })
                .andIfNotNull(to?.let { EltmNotes.eventDate lessEq it })
        }
            .orderBy(dist to SortOrder.ASC)
            .limit(limit)
            .map { it.toNote() }
    }

    // ------------------------------------------------------------------
    // version
    // ------------------------------------------------------------------

    override suspend fun version(): String =
        withTransaction { currentWriteVersion() }.toString()

    // ------------------------------------------------------------------
    // shared helpers (ambient transaction: only called inside withTransaction)
    // ------------------------------------------------------------------

    /**
     * Atomically bump the global ELTM write counter
     * (`memory_meta_number.eltm_version`, see `db/MetaCounter.kt`) by one.
     * Called inside the same transaction as every visible-state write, so
     * the bump commits with the write — the version moves
     * exactly when the ELTM changes.
     */
    private fun bumpWriteVersion() = bumpMetaCounter(ELTM_VERSION_KEY)

    /** Read the global ELTM write counter (the write version). Ambient transaction. */
    private fun currentWriteVersion(): Long = readMetaCounter(ELTM_VERSION_KEY)

    /**
     * Fail with the merge-instead collision error when the target
     * (name, category) belongs to a DIFFERENT entity than [entityId] (the
     * entity's own row is not a collision — [refineEntity] may echo its
     * current identity). Ambient transaction.
     */
    private fun checkNoNameCollision(entityId: Long, name: String, category: String) {
        findEntityByKey(name, category)?.let { existing ->
            if (existing[EltmEntities.id] != entityId) {
                throw IllegalArgumentException(
                    "an entity \"$name\" (category $category) already exists " +
                            "as entity ${existing[EltmEntities.id]}: merge the two instead"
                )
            }
        }
    }

    private fun findEntityByKey(canonicalName: String, category: String): ResultRow? =
        EltmEntities.selectAll().where {
            (EltmEntities.canonicalName eq canonicalName) and (EltmEntities.category eq category)
        }.singleOrNull()

    private fun findEntityRowById(id: Long): ResultRow? =
        EltmEntities.selectAll().where { EltmEntities.id eq id }.singleOrNull()

    /**
     * The entity row with `FOR UPDATE` — the read-modify-write lock held
     * for the whole write transaction, so a concurrent write on the same
     * entity (set/delete attribute, merge) blocks here until this commit
     * and then re-reads the fresh state (never a stale read-modify-write).
     */
    private fun findEntityRowByIdForUpdate(id: Long): ResultRow? =
        EltmEntities.selectAll().where { EltmEntities.id eq id }
            .forUpdate(ForUpdateOption.ForUpdate)
            .singleOrNull()

    private fun findEntityById(id: Long): EltmEntity? =
        findEntityRowById(id)?.toEntity()

    private fun findRelationshipById(id: Long): EltmRelationship? =
        EltmRelationships.selectAll().where { EltmRelationships.id eq id }.singleOrNull()
            ?.toRelationship()

    /** The ONE row for a triple (full unique index), whatever its validity. */
    private fun findRelationshipByTriple(srcId: Long, verb: String, dstId: Long): ResultRow? =
        EltmRelationships.selectAll().where {
            (EltmRelationships.srcId eq srcId) and
                    (EltmRelationships.dstId eq dstId) and
                    (EltmRelationships.verb eq verb)
        }.singleOrNull()

    /**
     * The rows for a batch of (canonical name, category) keys in ONE query
     * (an OR of per-key conjunctions — every disjunct is a point lookup on
     * the `(canonical_name, category)` unique index), the batched
     * counterpart of [findEntityByKey] for the bulk create-or-fetch.
     * Ambient transaction.
     */
    private fun findEntitiesByKeys(keys: List<Pair<String, String>>): List<ResultRow> {
        if (keys.isEmpty()) return emptyList()
        val cond = keys.map { (name, cat) ->
            (EltmEntities.canonicalName eq name) and (EltmEntities.category eq cat)
        }.reduce { a, b -> a or b }
        return EltmEntities.selectAll().where { cond }.toList()
    }

    /**
     * The rows for a batch of (src, verb, dst) triples in ONE query (an OR
     * of per-triple conjunctions — each disjunct is a point lookup on the
     * triple's unique index), the batched counterpart of
     * [findRelationshipByTriple] for the bulk create-or-fetch. Ambient
     * transaction.
     */
    private fun findRelationshipsByTriples(triples: List<Triple<Long, String, Long>>): List<ResultRow> {
        if (triples.isEmpty()) return emptyList()
        val cond = triples.map { (srcId, verb, dstId) ->
            (EltmRelationships.srcId eq srcId) and
                    (EltmRelationships.dstId eq dstId) and
                    (EltmRelationships.verb eq verb)
        }.reduce { a, b -> a or b }
        return EltmRelationships.selectAll().where { cond }.toList()
    }

    /**
     * The single-subject entity view (counts, latest note, attributes) from
     * the cheap single-subject helpers — the shared builder behind
     * [getEntity] and the write paths' returned views ([createEntity],
     * [refineEntity]), so a write's result never needs a follow-up read
     * transaction. Ambient transaction.
     */
    private fun entityViewOf(entity: EltmEntity): EntityView = EntityView(
        entity = entity,
        noteCount = countNotes(EltmNotes.entityId, entity.id),
        relationshipCount = countRelationshipsForEntity(entity.id),
        latestNote = latestNote(EltmNotes.entityId, entity.id),
        attributes = attributesOf(entity.id),
    )

    /**
     * The single-subject relationship view (endpoint names, note count,
     * latest note) from the cheap single-subject helpers — a `COUNT`, a
     * `LIMIT 1` latest-note read and two endpoint name lookups, never the
     * page builder's batch queries (one row must not pay for a page). The
     * shared builder behind [getRelationship] and [createRelationship]'s
     * returned view. Ambient transaction.
     */
    private fun relationshipViewOf(rel: EltmRelationship): RelationshipView = RelationshipView(
        relationship = rel,
        srcName = entityNameOf(rel.srcId),
        dstName = entityNameOf(rel.dstId),
        noteCount = countNotes(EltmNotes.relationshipId, rel.id),
        latestNote = latestNote(EltmNotes.relationshipId, rel.id),
    )

    /**
     * The entity's canonical name, or the defensive placeholder for a gone
     * row (the merge path re-points relationships before deleting their
     * loser endpoints, so a live row's endpoints exist — the placeholder
     * only masks a broken state). Ambient transaction.
     */
    private fun entityNameOf(id: Long): String =
        findEntityRowById(id)?.get(EltmEntities.canonicalName) ?: "<deleted entity $id>"

    /**
     * The subject's newest note (event date, then id — the same ordering as
     * [noteQuery] and [noteCountsAndLatest]; the diary ordering rule lives
     * in exactly those three SQL spots).
     * The subject is ONE of the two note columns (the
     * migration CHECK), so callers pass the matching column. Ambient
     * transaction.
     */
    private fun latestNote(column: Column<Long?>, subjectId: Long): EltmNote? =
        selectNoteContent().where { column eq subjectId }
            .orderBy(EltmNotes.eventDate to SortOrder.DESC, EltmNotes.id to SortOrder.DESC)
            .limit(1).singleOrNull()?.toNote()

    /** The subject's diary-note count. Ambient transaction. */
    private fun countNotes(column: Column<Long?>, subjectId: Long): Int =
        EltmNotes.selectAll().where { column eq subjectId }.count().toInt()

    /**
     * The relationships of ONE entity (src OR dst, self-loops counted once —
     * [relationshipCountsFor]'s rule), delegated to the batch helper so the
     * counting rule exists in exactly one place. Ambient transaction.
     */
    private fun countRelationshipsForEntity(entityId: Long): Int =
        relationshipCountsFor(listOf(entityId))[entityId] ?: 0

    /**
     * A content-only query over notes: every column EXCEPT the embedding.
     * The vectors dominate the row size and no read path consumes them
     * (a search compares server-side through the `<=>` expression, stored
     * vectors are only written — see [exportAll] for the same rationale);
     * the parsed-but-discarded `vector(2000)` per row is pure waste.
     * Ambient transaction.
     */
    private fun selectNoteContent() = EltmNotes.select(
        EltmNotes.id,
        EltmNotes.entityId,
        EltmNotes.relationshipId,
        EltmNotes.eventDate,
        EltmNotes.note,
    )

    /**
     * Per-subject note counts and latest notes (by event date, then id — the
     * same ordering as [noteQuery]/[latestNote]; the diary ordering rule
     * lives in exactly those three SQL spots) for a whole page of subjects,
     * in TWO bounded queries — a `GROUP BY` count and a `DISTINCT ON`
     * latest-note select — each returning at most one row per subject,
     * never materializing the subjects' whole diaries in memory (a heavy
     * diary must not make its page reads heavy). Ambient transaction.
     */
    private fun noteCountsAndLatest(
        column: Column<Long?>,
        subjectIds: List<Long>,
    ): Map<Long, Pair<Int, EltmNote?>> {
        if (subjectIds.isEmpty()) return emptyMap()
        // the counts aggregate server-side: one output row per subject
        val countExpr = EltmNotes.id.count()
        val counts: Map<Long, Int> = EltmNotes.select(column, countExpr)
            .where { column inList subjectIds }
            .groupBy(column)
            .mapNotNull { row -> row[column]?.let { it to row[countExpr].toInt() } }
            .toMap()
        // the latest note per subject: DISTINCT ON keeps the first row per
        // subject under the ordering — exactly the newest. `withDistinctOn`
        // prepends the subject column to ORDER BY itself, satisfying
        // Postgres's leftmost-ORDER-BY requirement on DISTINCT ON.
        val latest: Map<Long, EltmNote> = selectNoteContent()
            .where { column inList subjectIds }
            .withDistinctOn(column to SortOrder.ASC)
            .orderBy(EltmNotes.eventDate to SortOrder.DESC, EltmNotes.id to SortOrder.DESC)
            .map { it.toNote() }
            // a note's subject is exactly one of the two columns (migration
            // CHECK), so the fallback chain only masks a broken schema
            .associateBy { it.entityId ?: it.relationshipId ?: error("note has no subject") }
        return subjectIds.associateWith { id -> (counts[id] ?: 0) to latest[id] }
    }

    /**
     * Per-entity relationship counts (the entity as src OR dst) for a whole
     * page of entities, in ONE query. Ambient transaction.
     */
    private fun relationshipCountsFor(entityIds: List<Long>): Map<Long, Int> {
        if (entityIds.isEmpty()) return emptyMap()
        return EltmRelationships.select(EltmRelationships.srcId, EltmRelationships.dstId)
            .where {
                (EltmRelationships.srcId inList entityIds) or
                    (EltmRelationships.dstId inList entityIds)
            }
            // a self-loop (src == dst, e.g. a merge-invalidated winner—
            // winner edge) is ONE row: count it once, like
            // countRelationshipsForEntity and the drill-down list
            .map { row ->
                val src = row[EltmRelationships.srcId]
                val dst = row[EltmRelationships.dstId]
                if (src == dst) listOf(src) else listOf(src, dst)
            }
            .flatten()
            .groupingBy { it }
            .eachCount()
    }

    /**
     * The current-state attributes of ONE entity, keys alphabetically
     * ordered. Ambient transaction.
     */
    private fun attributesOf(entityId: Long): Map<String, String> =
        EltmEntityAttributes.selectAll().where { EltmEntityAttributes.entityId eq entityId }
            .map { it[EltmEntityAttributes.key] to it[EltmEntityAttributes.value] }
            .toMap()
            .toSortedMap()

    /**
     * Per-entity current-state attributes (keys alphabetically ordered) for a
     * whole page of entities, in ONE query. Ambient transaction.
     */
    private fun attributesFor(entityIds: List<Long>): Map<Long, Map<String, String>> {
        if (entityIds.isEmpty()) return emptyMap()
        return EltmEntityAttributes.selectAll()
            .where { EltmEntityAttributes.entityId inList entityIds }
            .map { it[EltmEntityAttributes.entityId] to (it[EltmEntityAttributes.key] to it[EltmEntityAttributes.value]) }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, pairs) -> pairs.toMap().toSortedMap() }
    }

    /**
     * Cosine-similarity search over stored entity embeddings, most similar
     * first, at or above [threshold], capped at [limit]; [excludeId] (used
     * for near matches) skips the row itself. Ambient transaction.
     *
     * pgvector's HNSW index post-filters: the WHERE above (threshold,
     * `excludeId`) can end the index scan early, so this can return FEWER
     * than [limit] rows even when further matches exist (pgvector <=0.7
     * behavior; iterative scans would fix it). The threshold only ever
     * DROPS candidates (distance is exact per visited row), so a returned
     * hit is always genuinely above [threshold].
     *
     * The count, latest-note and attribute columns of the original
     * correlated-subquery SQL come from batch queries over the candidate
     * ids (Exposed v1 has no scalar subquery in the select list); the
     * candidate set is at most [limit] rows, so the extra round trips are
     * negligible.
     */
    private fun similarEntities(
        queryVector: List<Float>,
        excludeId: Long?,
        threshold: Double,
        limit: Int,
    ): List<EntityWithScore> {
        val dist = VectorColumnType.cosineDistance(EltmEntities.embedding, queryVector)
        val candidates = EltmEntities.select(
            EltmEntities.id,
            EltmEntities.canonicalName,
            EltmEntities.category,
            dist,
        ).where {
            (dist lessEq 1.0 - threshold)
                .and(EltmEntities.embedding.isNotNull())
                .andIfNotNull(excludeId?.let { EltmEntities.id neq it })
        }
            .orderBy(dist to SortOrder.ASC)
            .limit(limit)
            .map { it[EltmEntities.id] to it }
        if (candidates.isEmpty()) return emptyList()
        val ids = candidates.map { (id, _) -> id }
        // note counts AND latest notes in ONE query over the candidate ids
        // (the same batch helper the page reads use), so each hit carries
        // its full model-visible picture without a per-hit drill-down
        val noteSummary = noteCountsAndLatest(EltmNotes.entityId, ids)
        val relationshipCounts = relationshipCountsFor(ids)
        val attributes = attributesFor(ids)
        return candidates.map { (id, row) ->
            EntityWithScore(
                entity = EltmEntity(
                    id = id,
                    canonicalName = row[EltmEntities.canonicalName],
                    category = row[EltmEntities.category],
                ),
                noteCount = noteSummary[id]?.first ?: 0,
                latestNote = noteSummary[id]?.second,
                relationshipCount = relationshipCounts[id] ?: 0,
                score = 1.0 - row[dist],
                attributes = attributes[id] ?: emptyMap(),
            )
        }
    }

    // ------------------------------------------------------------------
    // row mapping
    // ------------------------------------------------------------------

    private fun ResultRow.toEntity(): EltmEntity = EltmEntity(
        id = this[EltmEntities.id],
        canonicalName = this[EltmEntities.canonicalName],
        category = this[EltmEntities.category],
    )

    private fun ResultRow.toRelationship(): EltmRelationship = EltmRelationship(
        id = this[EltmRelationships.id],
        srcId = this[EltmRelationships.srcId],
        dstId = this[EltmRelationships.dstId],
        verb = this[EltmRelationships.verb],
        valid = this[EltmRelationships.valid],
    )

    private fun ResultRow.toNote(): EltmNote = EltmNote(
        id = this[EltmNotes.id],
        entityId = this[EltmNotes.entityId],
        relationshipId = this[EltmNotes.relationshipId],
        eventDate = this[EltmNotes.eventDate],
        note = this[EltmNotes.note],
    )

    // ------------------------------------------------------------------
    // embedding
    // ------------------------------------------------------------------

    /** The padded vector for ONE text (entity texts, search queries). */
    private suspend fun embedText(text: String): List<Float> =
        embedAll(listOf(text)).single()

    /**
     * The padded vectors for a whole batch of texts, at most
     * [EMBED_BATCH_SIZE] inputs per hand `/v1/embed` call — the batch is
     * the point (never one HTTP round trip per note), the cap keeps one
     * batch inside the embedding gateway's per-request input limits. An
     * empty batch calls nothing.
     */
    private suspend fun embedAll(texts: List<String>): List<List<Float>> =
        texts.chunked(EMBED_BATCH_SIZE).flatMap { chunk ->
            hand.embed(embeddingModel, chunk, policy).vectors
                .map { padVector(it, MAX_VECTOR_DIMENSIONS) }
        }

    // ------------------------------------------------------------------
    // note insert helpers
    // ------------------------------------------------------------------

    /** Trim the note text and fail fast on a blank one (the stored form). */
    private fun NoteDraft.validated(): NoteDraft {
        val trimmed = note.trim()
        require(trimmed.isNotBlank()) { "note must not be blank" }
        return NoteDraft(eventDate, trimmed)
    }

    companion object {
        private const val NEAR_MATCH_LIMIT = 5

        /**
         * Per-embed-call input cap: embedding gateways cap the `input`
         * array (and its total tokens), so an over-cap batch splits into
         * several calls instead of one request the gateway refuses.
         * Internal so the DB-backed tests can build an over-cap batch.
         */
        internal const val EMBED_BATCH_SIZE = 64
    }
}
