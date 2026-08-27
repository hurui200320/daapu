package info.skyblond.daapu.testutil

import info.skyblond.daapu.agent.oneshot.eltm.EltmWriterService
import info.skyblond.daapu.hand.EmbeddingException
import info.skyblond.daapu.hand.FakeHand
import info.skyblond.daapu.memory.eltm.*
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * An in-memory [EltmService] for writer tests: mirrors the Postgres
 * semantics where they matter for tool/pipeline behavior (normalization,
 * create-or-fetch for entities and relationships, add-only notes, subject
 * existence, the
 * note+validity compound event), with no real
 * embeddings — vector search degrades to substring matching and the
 * near matches are empty unless scripted. A scripted [embedFailure] makes
 * the embed-dependent writes throw like a real embedding call.
 */
class FakeEltmService : EltmService {
    val entities = mutableMapOf<Long, EltmEntity>()
    val relationships = mutableMapOf<Long, EltmRelationship>()
    val notes = mutableMapOf<Long, EltmNote>()
    val attributes = mutableMapOf<Long, MutableMap<String, String>>()
    val merged = mutableListOf<Pair<Long, Long>>()
    val invalidated = mutableListOf<Long>()

    /** Mirrors `memory_meta_number.eltm_version`: bumped on every visible write. */
    var writeVersion = 0L

    var embedFailure: EmbeddingException? = null

    private var nextEntityId = 1L
    private var nextRelId = 1L
    private var nextNoteId = 1L

    private fun failEmbed() {
        embedFailure?.let { throw it }
    }

    override suspend fun createEntity(name: String, category: String): CreateEntityResult {
        failEmbed()
        val canonical = normalizeName(name)
        val cat = category.trim().lowercase()
        val existing = entities.values.firstOrNull {
            it.canonicalName == canonical && it.category == cat
        }
        val entity = if (existing != null) {
            existing
        } else {
            writeVersion++
            EltmEntity(
                id = nextEntityId++,
                canonicalName = canonical,
                category = cat,
            )
        }
        entities[entity.id] = entity
        return CreateEntityResult(entity, emptyList())
    }

    override suspend fun refineEntity(
        entityId: Long,
        newName: String?,
        newCategory: String?,
    ): EltmEntity {
        // mirror Postgres: validation and the no-op/collision checks come
        // first, the embed (and its failure) last, so a failure leaves the
        // store untouched
        val canonical = newName?.let {
            normalizeName(it).also { name ->
                require(name.isNotBlank()) { "entity name must not be blank" }
            }
        }
        val trimmedCategory = newCategory?.trim()?.lowercase()
        if (trimmedCategory != null) {
            require(trimmedCategory.isNotBlank()) { "entity category must not be blank" }
        }
        val current = entities[entityId]
            ?: throw IllegalArgumentException("entity $entityId does not exist")
        val newCat = trimmedCategory ?: current.category
        val newCanonical = canonical ?: current.canonicalName
        if (current.canonicalName == newCanonical && current.category == newCat) {
            return current
        }
        // fail fast on a collision before the embed, like Postgres: the
        // target (name, category) is another entity's key — the caller must
        // merge the two instead
        entities.values.firstOrNull {
            it.id != entityId && it.canonicalName == newCanonical && it.category == newCat
        }?.let { existing ->
            throw IllegalArgumentException(
                "an entity \"$newCanonical\" (category $newCat) already exists " +
                    "as entity ${existing.id}: merge the two instead"
            )
        }
        failEmbed()
        val refined = current.copy(canonicalName = newCanonical, category = newCat)
        entities[entityId] = refined
        writeVersion++
        return refined
    }

    override suspend fun createRelationship(srcId: Long, dstId: Long, verb: String): EltmRelationship {
        val v = normalizeVerb(verb)
        // fail fast on missing endpoints, like Postgres
        require(srcId in entities) { "entity $srcId does not exist" }
        require(dstId in entities) { "entity $dstId does not exist" }
        // exactly ONE row per triple: create-or-fetch — an existing row
        // (active or invalidated) is returned as-is, validity never changes
        // here (only attachNoteToRelationship's valid flag moves it)
        val existing = relationships.values.firstOrNull {
            it.srcId == srcId && it.dstId == dstId && it.verb == v
        }
        val rel = if (existing != null) {
            existing
        } else {
            writeVersion++
            EltmRelationship(
                id = nextRelId++,
                srcId = srcId,
                dstId = dstId,
                verb = v,
                valid = true,
            )
        }
        relationships[rel.id] = rel
        return rel
    }

    override suspend fun attachNoteToEntity(
        entityId: Long,
        eventDate: LocalDate,
        note: String,
    ): EltmNote = attachNote(entityId, null, eventDate, note)

    override suspend fun attachNoteToRelationship(
        relationshipId: Long,
        eventDate: LocalDate,
        note: String,
        valid: Boolean?,
    ): EltmNote {
        val created = attachNote(null, relationshipId, eventDate, note)
        if (valid != null) {
            // mirrors the Postgres compound event: apply the structural
            // validity to THE row (idempotent — setting the current state
            // is a no-op) with the note; ONE version bump for the whole
            // event, carried by the note insert
            val rel = relationships[relationshipId] ?: return created
            if (valid != rel.valid) {
                relationships[relationshipId] = rel.copy(valid = valid)
                if (!valid) invalidated += relationshipId
            }
        }
        return created
    }

    private fun attachNote(
        entityId: Long?,
        relationshipId: Long?,
        eventDate: LocalDate,
        note: String,
    ): EltmNote {
        failEmbed()
        require(entityId == null || entities.containsKey(entityId)) {
            "entity $entityId does not exist"
        }
        require(relationshipId == null || relationships.containsKey(relationshipId)) {
            "relationship $relationshipId does not exist"
        }
        writeVersion++
        val noteRow = EltmNote(
            id = nextNoteId++,
            entityId = entityId,
            relationshipId = relationshipId,
            eventDate = eventDate,
            note = note.trim(),
            createdAt = OffsetDateTime.now(),
        )
        notes[noteRow.id] = noteRow
        return noteRow
    }

    override suspend fun setEntityAttribute(entityId: Long, key: String, value: String): Boolean {
        val k = normalizeAttributeKey(key)
        val v = value.trim()
        require(k.isNotBlank()) { "attribute key must not be blank" }
        require(v.isNotBlank()) { "attribute value must not be blank" }
        require(v.none { it == '\n' || it == '\r' }) { "attribute value must be a single line" }
        require(entities.containsKey(entityId)) { "entity $entityId does not exist" }
        val current = attributes[entityId]
        if (current != null && current[k] == v) return false
        // mirror Postgres: validation and the no-op check come first, the
        // embed (and its failure) last, so a failure leaves the store
        // untouched
        failEmbed()
        attributes.getOrPut(entityId) { mutableMapOf() }[k] = v
        writeVersion++
        return true
    }

    override suspend fun deleteEntityAttribute(entityId: Long, key: String) {
        val k = normalizeAttributeKey(key)
        require(k.isNotBlank()) { "attribute key must not be blank" }
        require(entities.containsKey(entityId)) { "entity $entityId does not exist" }
        val current = attributes[entityId]
        require(current?.containsKey(k) == true) {
            "attribute \"$k\" does not exist on entity $entityId"
        }
        failEmbed()
        current.remove(k)
        writeVersion++
    }

    override suspend fun mergeEntities(winnerId: Long, loserId: Long) {
        require(winnerId in entities && loserId in entities) {
            "entity $winnerId or $loserId does not exist"
        }
        require(winnerId != loserId) { "cannot merge an entity into itself" }
        // the SAME shared fold plan the Postgres service uses, so the fake
        // can never drift from the real fold/re-embed decisions
        val foldPlan = planAttributeFold(
            attributes[winnerId].orEmpty(),
            attributes[loserId].orEmpty(),
        )
        // mirror Postgres: the fold's embed (and its failure) happens before
        // any mutation, and only when the fold changes the winner's
        // attribute text — an attribute-less merge never embeds
        if (foldPlan.changesText) failEmbed()
        writeVersion++
        merged += winnerId to loserId
        relationships.values.filter { it.srcId == loserId || it.dstId == loserId }.forEach { rel ->
            val newSrc = if (rel.srcId == loserId) winnerId else rel.srcId
            val newDst = if (rel.dstId == loserId) winnerId else rel.dstId
            val survivor = relationships.values.firstOrNull {
                it.srcId == newSrc && it.dstId == newDst && it.verb == rel.verb
            }
            when {
                // self-loop: never meaningful — invalidate; a twin self-loop
                // row may already exist (another loser edge collapsed onto
                // the same triple): fold into it
                newSrc == newDst -> if (survivor != null) {
                    notes.values.filter { it.relationshipId == rel.id }.forEach { note ->
                        notes[note.id] = note.copy(relationshipId = survivor.id)
                    }
                    relationships.remove(rel.id)
                } else {
                    relationships[rel.id] = rel.copy(
                        srcId = newSrc, dstId = newDst, valid = false,
                    )
                }

                survivor != null && survivor.id != rel.id -> {
                    // collides with an existing row of the same triple:
                    // re-point the duplicate's notes to the survivor and
                    // fold the validity (the survivor holds the edge if
                    // either row held it)
                    notes.values.filter { it.relationshipId == rel.id }.forEach { note ->
                        notes[note.id] = note.copy(relationshipId = survivor.id)
                    }
                    if (rel.valid && !survivor.valid) {
                        relationships[survivor.id] = survivor.copy(valid = true)
                    }
                    relationships.remove(rel.id)
                }

                else -> relationships[rel.id] = rel.copy(
                    srcId = newSrc, dstId = newDst,
                )
            }
        }
        notes.values.filter { it.entityId == loserId }.forEach { note ->
            notes[note.id] = note.copy(entityId = winnerId)
        }
        // fold the loser's attributes into the winner per the shared plan
        // (only the foldable keys move — the colliding ones are dropped
        // with the loser, the winner's value wins)
        attributes.remove(loserId)?.forEach { (k, v) ->
            if (k in foldPlan.foldableKeys) {
                attributes.getOrPut(winnerId) { mutableMapOf() }[k] = v
            }
        }
        entities.remove(loserId)
    }

    private fun noteCountFor(entityId: Long?, relationshipId: Long?): Int = notes.values.count {
        (entityId != null && it.entityId == entityId) ||
                (relationshipId != null && it.relationshipId == relationshipId)
    }

    private fun relationshipCountFor(entityId: Long): Int = relationships.values.count {
        it.srcId == entityId || it.dstId == entityId
    }

    private fun attributesOf(entityId: Long): Map<String, String> =
        attributes[entityId].orEmpty().toSortedMap()

    override suspend fun searchEntities(query: String, limit: Int): List<EntityWithScore> =
        entities.values
            .filter { it.canonicalName.contains(normalizeName(query), ignoreCase = true) }
            .sortedByDescending { e ->
                noteCountFor(e.id, null) + relationshipCountFor(e.id)
            }
            .take(limit)
            .map {
                EntityWithScore(
                    entity = it,
                    noteCount = noteCountFor(it.id, null),
                    latestNote = latestNote(it.id, null),
                    relationshipCount = relationshipCountFor(it.id),
                    score = 1.0,
                    attributes = attributesOf(it.id),
                )
            }

    override suspend fun getEntity(id: Long): EntityView? =
        entities[id]?.let {
            EntityView(
                entity = it,
                noteCount = noteCountFor(it.id, null),
                relationshipCount = relationshipCountFor(it.id),
                latestNote = latestNote(it.id, null),
                attributes = attributesOf(it.id),
            )
        }

    override suspend fun entityExists(entityId: Long): Boolean = entities.containsKey(entityId)

    override suspend fun relationshipExists(relationshipId: Long): Boolean =
        relationships.containsKey(relationshipId)

    override suspend fun listEntities(limit: Int, offset: Int): List<EntityView> =
        entities.values.sortedBy { it.id }.drop(offset).take(limit).map {
            EntityView(
                entity = it,
                noteCount = noteCountFor(it.id, null),
                relationshipCount = relationshipCountFor(it.id),
                latestNote = latestNote(it.id, null),
                attributes = attributesOf(it.id),
            )
        }

    override suspend fun listRelationships(limit: Int, offset: Int): List<RelationshipView> =
        relationships.values.sortedBy { it.id }.drop(offset).take(limit).map { rel ->
            RelationshipView(
                relationship = rel,
                srcName = entities[rel.srcId]?.canonicalName ?: "<deleted>",
                dstName = entities[rel.dstId]?.canonicalName ?: "<deleted>",
                noteCount = noteCountFor(null, rel.id),
                latestNote = latestNote(null, rel.id),
            )
        }

    override suspend fun getRelationship(id: Long): RelationshipView? =
        relationships[id]?.let { rel ->
            RelationshipView(
                relationship = rel,
                srcName = entities[rel.srcId]?.canonicalName ?: "<deleted>",
                dstName = entities[rel.dstId]?.canonicalName ?: "<deleted>",
                noteCount = noteCountFor(null, rel.id),
                latestNote = latestNote(null, rel.id),
            )
        }

    override suspend fun getRelationships(
        entityId: Long,
        includeInvalid: Boolean,
    ): List<RelationshipView> = relationships.values
        .filter { (it.srcId == entityId || it.dstId == entityId) && (includeInvalid || it.valid) }
        .sortedByDescending { it.id }
        .map { rel ->
            RelationshipView(
                relationship = rel,
                srcName = entities[rel.srcId]?.canonicalName ?: "<deleted>",
                dstName = entities[rel.dstId]?.canonicalName ?: "<deleted>",
                noteCount = noteCountFor(null, rel.id),
                latestNote = latestNote(null, rel.id),
            )
        }

    private fun notesFor(
        entityId: Long?,
        relationshipId: Long?,
        from: LocalDate?,
        to: LocalDate?,
        limit: Int,
        offset: Int,
    ): List<EltmNote> = notes.values
        .filter {
            (entityId != null && it.entityId == entityId) ||
                    (relationshipId != null && it.relationshipId == relationshipId)
        }
        .filter { from == null || !it.eventDate.isBefore(from) }
        .filter { to == null || !it.eventDate.isAfter(to) }
        .sortedWith(compareByDescending<EltmNote> { it.eventDate }.thenByDescending { it.id })
        .drop(offset)
        .take(limit)

    override suspend fun getEntityNotes(
        entityId: Long,
        from: LocalDate?,
        to: LocalDate?,
        limit: Int,
        offset: Int,
    ): List<EltmNote> = notesFor(entityId, null, from, to, limit, offset)

    override suspend fun getRelationshipNotes(
        relationshipId: Long,
        from: LocalDate?,
        to: LocalDate?,
        limit: Int,
        offset: Int,
    ): List<EltmNote> = notesFor(null, relationshipId, from, to, limit, offset)

    override suspend fun searchNotes(
        query: String,
        entityId: Long?,
        relationshipId: Long?,
        from: LocalDate?,
        to: LocalDate?,
        limit: Int,
    ): List<EltmNote> = notes.values
        .filter { entityId == null || it.entityId == entityId }
        .filter { relationshipId == null || it.relationshipId == relationshipId }
        .filter { it.note.contains(query, ignoreCase = true) }
        .sortedWith(compareByDescending<EltmNote> { it.eventDate }.thenByDescending { it.id })
        .take(limit)

    override suspend fun version(): String = writeVersion.toString()

    private fun latestNote(entityId: Long?, relationshipId: Long?): EltmNote? =
        notes.values
            .filter {
                (entityId != null && it.entityId == entityId) ||
                        (relationshipId != null && it.relationshipId == relationshipId)
            }
            .maxWithOrNull(compareBy<EltmNote> { it.eventDate }.thenBy { it.id })
}

/**
 * An [EltmWriterService] wired to a scripted [hand] and a fake ELTM store,
 * with the catalog's default model and generous run knobs.
 */
fun testEltmWriterService(
    hand: FakeHand,
    eltmService: EltmService = FakeEltmService(),
    maxWriterRounds: Int = 150,
): EltmWriterService {
    val model = testLlm("bifrost/cerebras/gemma-4-31b")
    return EltmWriterService(
        writerModel = model,
        hand = testHandService(hand),
        eltmService = eltmService,
        maxWriterRounds = maxWriterRounds,
        maxRetries = 0,
        streamIdleTimeoutMs = 0,
    )
}
