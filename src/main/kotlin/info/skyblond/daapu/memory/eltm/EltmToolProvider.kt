package info.skyblond.daapu.memory.eltm

import info.skyblond.daapu.agent.chat.ChatMessagePart
import info.skyblond.daapu.agent.tool.*
import info.skyblond.daapu.hand.EmbeddingException
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * The ELTM tools backed by an [EltmService]: the read tools (entity /
 * relationship lookup, diary notes, semantic note search) and — unless
 * [readOnly] — the write tools. Each tool mirrors ONE [EltmService] method
 * one-to-one (entities and relationships are never mixed in one tool).
 *
 * The RW provider is the ONLY ELTM write path (the chat model never writes
 * the ELTM directly; the extraction pipeline drives the writer agent); the
 * read-only provider serves the investigate sub-agent's own tool set
 * (the main chat loop reaches the ELTM only through `gsg__investigate`).
 *
 * The optional [namespace] switches the provider between the two shapes:
 * blank (the default — one-shot services like the writer/investigate agents
 * use it) advertises the bare tool names; a non-blank namespace (e.g. `eltm`,
 * one of [TOOL_RESERVED_NAMESPACES]) advertises `"{namespace}__{tool}"` and
 * [execute] only accepts those prefixed names, so the provider can be
 * combined with others (MCP servers) into a
 * [CombinedToolProvider] without name collisions.
 *
 * Error contract: an [EmbeddingException] of type `invalid_request` (the
 * content is too large for the embedding model) answers an `isError` result
 * telling the model to split the content — never silently truncate; other
 * embedding/hand failures answer a generic `isError` (the loop may retry);
 * [ToolTransportException] stays run-fatal (the model cannot react to a dead
 * transport). Numeric ids use `"integer"` schemas; a read on a nonexistent
 * subject or a malformed date answers an `isError` (never a silent empty
 * result).
 */
class EltmToolProvider(
    private val eltmService: EltmService,
    private val readOnly: Boolean = false,
    /** Non-blank: every advertised name is prefixed with `{namespace}__`. */
    private val namespace: String = "",
) : ToolProvider {
    init {
        validateToolNamespaceSyntax(namespace, "ELTM tool")
    }

    private fun advertisedName(toolName: String): String =
        if (namespace.isBlank()) toolName else nsToolName(namespace, toolName)

    private fun bareName(advertised: String): String? =
        if (namespace.isBlank()) advertised
        else splitNsToolName(advertised)
            ?.takeIf { it.first == namespace }?.second

    private val readSpecs = listOf(
        ToolSpec(
            name = "search_entities",
            description = "Semantic search over entities; returns matching entities with their similarity, attributes and latest note inline. Call this BEFORE creating an entity to find an existing one.",
            schema = objectSchema(
                required = listOf("query"),
                "query" to stringSchema("The entity to search for, e.g. \"alice\""),
                "limit" to integerSchema("Max results (default 5)"),
            ),
        ),
        ToolSpec(
            name = "get_relationships",
            description = "Get the entity's relationships in BOTH directions (with the endpoints' names), each with its latest note inline.",
            schema = objectSchema(
                required = listOf("entity_id"),
                "entity_id" to integerSchema("The entity id"),
                "include_invalid" to boolSchema("Also return invalidated relationships (default false)"),
            ),
        ),
        ToolSpec(
            name = "get_entity_notes",
            description = "The diary notes of ONE entity, newest event first, paginated.",
            schema = objectSchema(
                required = listOf("entity_id"),
                "entity_id" to integerSchema("The subject entity id"),
                "from" to stringSchema("Inclusive lower bound of the event date, YYYY-MM-DD"),
                "to" to stringSchema("Inclusive upper bound of the event date, YYYY-MM-DD"),
                "limit" to integerSchema("Max results (default 5)"),
                "offset" to integerSchema("Pagination offset (default 0)"),
            ),
        ),
        ToolSpec(
            name = "get_relationship_notes",
            description = "The diary notes of ONE relationship, newest event first, paginated.",
            schema = objectSchema(
                required = listOf("relationship_id"),
                "relationship_id" to integerSchema("The subject relationship id"),
                "from" to stringSchema("Inclusive lower bound of the event date, YYYY-MM-DD"),
                "to" to stringSchema("Inclusive upper bound of the event date, YYYY-MM-DD"),
                "limit" to integerSchema("Max results (default 5)"),
                "offset" to integerSchema("Pagination offset (default 0)"),
            ),
        ),
        ToolSpec(
            name = "search_notes",
            description = "Semantic search over diary notes, most relevant first, optionally narrowed to one subject and/or a date range.",
            schema = objectSchema(
                required = listOf("query"),
                "query" to stringSchema("The content to search for in the diary notes"),
                "entity_id" to integerSchema("Narrow to this entity (at most one subject)"),
                "relationship_id" to integerSchema("Narrow to this relationship (at most one subject)"),
                "from" to stringSchema("Inclusive lower bound of the event date, YYYY-MM-DD"),
                "to" to stringSchema("Inclusive upper bound of the event date, YYYY-MM-DD"),
                "limit" to integerSchema("Max results (default 5)"),
            ),
        ),
    )

    private val writeSpecs = listOf(
        ToolSpec(
            name = "create_entity",
            description = "Create or fetch an entity by (name, category): an exact match returns the existing entity. Returns the entity plus near matches: if one is the same thing, use ITS id instead; if true duplicates exist, merge_entities them.",
            schema = objectSchema(
                required = listOf("name"),
                "name" to stringSchema("The entity name (canonicalized: trimmed, whitespace collapsed, lowercase)"),
                "category" to stringSchema("The category disambiguating homonyms, e.g. \"person\", \"company\", \"fruit\" (default \"general\")"),
            ),
        ),
        ToolSpec(
            name = "refine_entity",
            description = "Rename ONE entity in place and/or change its category. e.g. a briefly-mentioned \"friend\" later identified as \"Alice\". The entity's id stays: its notes, relationships and attributes keep pointing at it. Give the new name, the new category, or both — at least one is required, an omitted field keeps the current value. Setting the current (name, category) again is a no-op. If another entity already holds the new (name, category), it errors. Merge the two instead.",
            schema = objectSchema(
                required = listOf("entity_id"),
                "entity_id" to integerSchema("The entity id"),
                "new_name" to stringSchema("The new entity name (canonicalized: trimmed, whitespace collapsed, lowercase); omitted keeps the current one"),
                "new_category" to stringSchema("The new category, e.g. \"person\"; omitted keeps the current one"),
            ),
        ),
        ToolSpec(
            name = "create_relationship",
            description = "Create or fetch a directed relationship (source entity, verb, destination entity). If triple already exists, return existing one. Use consistent, general, timeless verbs: \"works_at\", not \"started_working_at\".",
            schema = objectSchema(
                required = listOf("src_id", "dst_id", "verb"),
                "src_id" to integerSchema("The source entity id"),
                "dst_id" to integerSchema("The destination entity id"),
                "verb" to stringSchema("The verb, e.g. \"colleague_of\" (lowercase, spaces become underscores)"),
            ),
        ),
        ToolSpec(
            name = "merge_entities",
            description = "Merge a duplicate entity into the canonical one: every relationship and note is re-pointed and the loser's attributes fold into the winner (the winner keeps its value on a colliding key); colliding relationships are folded.",
            schema = objectSchema(
                required = listOf("winner_id", "loser_id"),
                "winner_id" to integerSchema("The entity that survives (the better-canonical one)"),
                "loser_id" to integerSchema("The duplicate entity that is absorbed"),
            ),
        ),
        ToolSpec(
            name = "add_entity_note",
            description = "Append a dated diary entry to ONE entity. Add-only: to correct or supersede older information, add a NEW note. Before adding, check the entity's recent notes with get_entity_notes and skip content already recorded.",
            schema = objectSchema(
                required = listOf("entity_id", "event_date", "note"),
                "entity_id" to integerSchema("The subject entity id"),
                "event_date" to stringSchema("The absolute date the event happened, YYYY-MM-DD; today when unknown"),
                "note" to stringSchema("The self-contained diary entry (1-3 sentences; names, numbers, ids verbatim)"),
            ),
        ),
        ToolSpec(
            name = "add_relationship_note",
            description = "Append a dated diary entry to ONE relationship. Add-only: to correct or supersede older information, add a NEW note. Before adding, check the relationship's recent notes with get_relationship_notes and skip content already recorded. valid optionally changes the relationship's structural validity: false when this event ENDS the relationship (the note must explain the ending), true when it (re)establishes it (e.g. rejoined the company).",
            schema = objectSchema(
                required = listOf("relationship_id", "event_date", "note"),
                "relationship_id" to integerSchema("The subject relationship id"),
                "event_date" to stringSchema("The absolute date the event happened, YYYY-MM-DD; today when unknown"),
                "note" to stringSchema("The self-contained diary entry (1-3 sentences; names, numbers, ids verbatim)"),
                "valid" to boolSchema("The structural validity to set. `false` ends the relationship (e.g. left the company), true (re)establishes it (e.g. rejoined the company). Omit to leave the validity unchanged"),
            ),
        ),
        ToolSpec(
            name = "set_entity_attribute",
            description = "Set a structured fact (key-value attribute) on ONE entity, e.g. model=\"Paperwhite 6\", realname, nickname. Attributes are CURRENT-STATE facts — one value per (entity, key): setting the same key again overwrites; setting the identical value is a no-op. Use attributes for timeless structured facts; use add_entity_note for dated events.",
            schema = objectSchema(
                required = listOf("entity_id", "key", "value"),
                "entity_id" to integerSchema("The entity id"),
                "key" to stringSchema("The attribute key, e.g. \"model\", \"realname\", \"nickname\" (canonicalized: lowercase, spaces become underscores)"),
                "value" to stringSchema("The attribute value, a SINGLE line, e.g. \"Paperwhite 6\""),
            ),
        ),
        ToolSpec(
            name = "delete_entity_attribute",
            description = "Remove a structured fact (key-value attribute) from ONE entity, e.g. a nickname that no longer applies.",
            schema = objectSchema(
                required = listOf("entity_id", "key"),
                "entity_id" to integerSchema("The entity id"),
                "key" to stringSchema("The attribute key to remove, e.g. \"nickname\""),
            ),
        ),
    )

    override fun namespaces(): Set<String> =
        if (namespace.isBlank()) emptySet() else setOf(namespace)

    override suspend fun specifications(): List<ToolSpec> =
        (if (readOnly) readSpecs else readSpecs + writeSpecs)
            .map { it.copy(name = advertisedName(it.name)) }

    override suspend fun execute(request: ToolCallRequest): ChatMessagePart.ToolResult {
        val args = request.args
        // in namespaced mode only `{namespace}__{tool}` names are accepted:
        // anything else is not advertised by this provider
        val name = bareName(request.name)
        if (name == null) {
            return errorResult(
                request, "tool '${request.name}' is not advertised by this ELTM provider"
            )
        }
        if (readOnly && name in WRITE_TOOLS) {
            return errorResult(
                request, "tool '${request.name}' is not available in read-only mode"
            )
        }
        logger.info { "Executing tool ${request.name} with arguments: ${request.args}" }
        return try {
            when (name) {
                "search_entities" -> {
                    val query = args.textArg("query") ?: return errorResult(
                        request, "query is required and must not be blank"
                    )
                    val limit = args.limitArg()
                    val hits = eltmService.searchEntities(query, limit)
                    if (hits.isEmpty()) {
                        textResult(request, "No matching entities.")
                    } else {
                        val lines = mutableListOf<String>()
                        for (hit in hits) {
                            lines += buildString {
                                append(entityHeader(hit.entity.id, hit.entity.canonicalName, hit.entity.category))
                                append(" - similarity ${"%.3f".format(hit.score)}, notes ${hit.noteCount}, relations ${hit.relationshipCount}")
                                appendAttributesBlock(hit.attributes)
                                hit.latestNote?.let {
                                    append("\nLatest note (${it.eventDate}): ${it.note}")
                                }
                            }
                        }
                        textResult(request, lines.joinToString("\n\n"))
                    }
                }

                "get_relationships" -> {
                    val id = args.longArg("entity_id") ?: return errorResult(
                        request, "entity_id is required and must be a number"
                    )
                    requireEntity(id)
                    val includeInvalid = args.boolArg("include_invalid") ?: false
                    val views = eltmService.getRelationships(id, includeInvalid)
                    if (views.isEmpty()) textResult(request, "No relationships.")
                    else views.joinToString("\n\n") { view ->
                        buildString {
                            append("# Relationship ${view.relationship.id}: \"${view.srcName}\" - ${view.relationship.verb} - \"${view.dstName}\"")
                            append(" (${if (view.relationship.valid) "active" else "invalidated"}, notes ${view.noteCount})")
                            view.latestNote?.let {
                                append("\nLatest note (${it.eventDate}): ${it.note}")
                            }
                        }
                    }.let { textResult(request, it) }
                }

                "get_entity_notes" -> {
                    val entityId = args.longArg("entity_id") ?: return errorResult(
                        request, "entity_id is required and must be a number"
                    )
                    noteDiaryPage(request, args, entityId = entityId, relationshipId = null)
                }

                "get_relationship_notes" -> {
                    val relId = args.longArg("relationship_id") ?: return errorResult(
                        request, "relationship_id is required and must be a number"
                    )
                    noteDiaryPage(request, args, entityId = null, relationshipId = relId)
                }

                "search_notes" -> {
                    val query = args.textArg("query") ?: return errorResult(
                        request, "query is required and must not be blank"
                    )
                    val entityId = args.longArg("entity_id")
                    val relId = args.longArg("relationship_id")
                    if (entityId != null && relId != null) {
                        return errorResult(
                            request, "a note search accepts at most one subject"
                        )
                    }
                    if (entityId != null) requireEntity(entityId)
                    if (relId != null) requireRelationship(relId)
                    val (from, to) = args.strictDateRange()
                    val limit = args.limitArg()
                    val notes = eltmService.searchNotes(query, entityId, relId, from, to, limit)
                    if (notes.isEmpty()) textResult(request, "No matching notes.")
                    else textResult(request, renderNotes(notes))
                }

                "create_entity" -> {
                    val name = args.textArg("name") ?: return errorResult(
                        request, "name is required and must not be blank"
                    )
                    val category = args.textArg("category") ?: "general"
                    val result = eltmService.createEntity(name, category)
                    val view = eltmService.getEntity(result.entity.id)
                    val entity = view?.entity ?: result.entity
                    buildString {
                        append(entityHeader(entity.id, entity.canonicalName, entity.category))
                        append(" - notes ${view?.noteCount ?: 0}, relations ${view?.relationshipCount ?: 0}")
                        appendAttributesBlock(view?.attributes ?: emptyMap())
                        if (result.nearMatches.isEmpty()) {
                            append("\nNo near matches.")
                        } else {
                            append("\nNear matches (check for duplicates):")
                            result.nearMatches.forEach { match ->
                                append("\n- ${entityHeader(match.entity.id, match.entity.canonicalName, match.entity.category)}")
                                append(" - similarity ${"%.3f".format(match.score)}, notes ${match.noteCount}, relations ${match.relationshipCount}")
                                appendAttributesBlock(match.attributes, indent = "  ")
                            }
                        }
                    }.let { textResult(request, it) }
                }

                "refine_entity" -> {
                    val entityId = args.longArg("entity_id") ?: return errorResult(
                        request, "entity_id is required and must be a number"
                    )
                    val newName = args.textArg("new_name")
                    val newCategory = args.textArg("new_category")
                    if (newName == null && newCategory == null) {
                        return errorResult(
                            request,
                            "at least one of new_name or new_category is required"
                        )
                    }
                    val refined = eltmService.refineEntity(entityId, newName, newCategory)
                    val view = eltmService.getEntity(refined.id)
                    buildString {
                        append(entityHeader(refined.id, refined.canonicalName, refined.category))
                        append(" - notes ${view?.noteCount ?: 0}, relations ${view?.relationshipCount ?: 0}")
                        appendAttributesBlock(view?.attributes ?: emptyMap())
                    }.let { textResult(request, it) }
                }

                "create_relationship" -> {
                    val src = args.longArg("src_id") ?: return errorResult(
                        request, "src_id is required and must be a number"
                    )
                    val dst = args.longArg("dst_id") ?: return errorResult(
                        request, "dst_id is required and must be a number"
                    )
                    val verb = args.textArg("verb") ?: return errorResult(
                        request, "verb is required and must not be blank"
                    )
                    val rel = eltmService.createRelationship(src, dst, verb)
                    val view = eltmService.getRelationship(rel.id)
                    textResult(
                        request,
                        "Relationship ${rel.id}: \"${view?.srcName ?: rel.srcId}\" - ${rel.verb} - " +
                                "\"${view?.dstName ?: rel.dstId}\" " +
                                "(${if (rel.valid) "active" else "invalidated"}, notes ${view?.noteCount ?: 0})"
                    )
                }

                "merge_entities" -> {
                    val winner = args.longArg("winner_id") ?: return errorResult(
                        request, "winner_id is required and must be a number"
                    )
                    val loser = args.longArg("loser_id") ?: return errorResult(
                        request, "loser_id is required and must be a number"
                    )
                    eltmService.mergeEntities(winner, loser)
                    textResult(request, "Entity $loser merged into entity $winner.")
                }

                "add_entity_note" -> {
                    val entityId = args.longArg("entity_id") ?: return errorResult(
                        request, "entity_id is required and must be a number"
                    )
                    if (args.containsKey("valid")) {
                        return errorResult(
                            request,
                            "valid only applies to a relationship subject (add_relationship_note)"
                        )
                    }
                    val eventDate = args.strictDate("event_date") ?: return errorResult(
                        request, "event_date is required and must be YYYY-MM-DD"
                    )
                    val note = args.textArg("note") ?: return errorResult(
                        request, "note is required and must not be blank"
                    )
                    val created = eltmService.attachNoteToEntity(entityId, eventDate, note)
                    textResult(
                        request,
                        "Note ${created.id} added (${created.eventDate}, subject entity $entityId)."
                    )
                }

                "add_relationship_note" -> {
                    val relId = args.longArg("relationship_id") ?: return errorResult(
                        request, "relationship_id is required and must be a number"
                    )
                    val eventDate = args.strictDate("event_date") ?: return errorResult(
                        request, "event_date is required and must be YYYY-MM-DD"
                    )
                    val note = args.textArg("note") ?: return errorResult(
                        request, "note is required and must not be blank"
                    )
                    val valid = args.boolArg("valid")
                    val created =
                        eltmService.attachNoteToRelationship(relId, eventDate, note, valid)
                    val relView = eltmService.getRelationship(relId)
                    val stateLabel = relView?.relationship?.valid?.let {
                        if (it) "active" else "invalidated"
                    }
                    val subject = stateLabel?.let { "relationship $relId, currently $it" }
                        ?: "relationship $relId"
                    textResult(
                        request,
                        "Note ${created.id} added (${created.eventDate}, subject $subject)."
                    )
                }

                "set_entity_attribute" -> {
                    val entityId = args.longArg("entity_id") ?: return errorResult(
                        request, "entity_id is required and must be a number"
                    )
                    val key = args.textArg("key") ?: return errorResult(
                        request, "key is required and must not be blank"
                    )
                    val value = args.textArg("value") ?: return errorResult(
                        request, "value is required and must not be blank"
                    )
                    // canonicalize here so the echoed messages show the model
                    // the stored key form (the service canonicalizes too —
                    // idempotent, the service stays the enforcement point)
                    val k = normalizeAttributeKey(key)
                    val changed = eltmService.setEntityAttribute(entityId, k, value)
                    if (changed) {
                        textResult(request, "Attribute \"$k\" set on entity $entityId.")
                    } else {
                        textResult(
                            request,
                            "Attribute \"$k\" already set to \"$value\" on entity $entityId."
                        )
                    }
                }

                "delete_entity_attribute" -> {
                    val entityId = args.longArg("entity_id") ?: return errorResult(
                        request, "entity_id is required and must be a number"
                    )
                    val key = args.textArg("key") ?: return errorResult(
                        request, "key is required and must not be blank"
                    )
                    val k = normalizeAttributeKey(key)
                    eltmService.deleteEntityAttribute(entityId, k)
                    textResult(
                        request,
                        "Attribute \"$k\" removed from entity $entityId."
                    )
                }

                else -> errorResult(request, "Unknown ELTM tool '${request.name}'")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ToolTransportException) {
            // the model cannot react to a dead transport: fail the run
            throw e
        } catch (e: EmbeddingException) {
            // the embedding model rejected the content: the model can react.
            // The actionable fix depends on WHAT was embedded: a note can be
            // split, an attribute (a single fact) only shortened, a merge
            // re-embeds the winner's name+category+attributes (shorten or
            // delete the offending attribute and retry the merge), a create
            // re-embeds the name+category (shorten it), a refine re-embeds
            // the name+category+attributes (shorten the name or delete an
            // attribute and retry)
            if (e.type == "invalid_request") {
                val fix = when (bareName(request.name)) {
                    "set_entity_attribute" ->
                        "shorten the value and retry."
                    "merge_entities" ->
                        "the merged entity's content is too large, shorten or delete an attribute and retry."
                    "create_entity" ->
                        "shorten the name and retry."
                    "refine_entity" ->
                        "shorten the name or delete an attribute and retry."
                    else ->
                        "split it into several smaller notes and retry."
                }
                errorResult(request, "content too large for the embedding model, $fix")
            } else {
                errorResult(request, "embedding failed (${e.type}): ${e.message}")
            }
        } catch (e: IllegalArgumentException) {
            errorResult(request, e.message ?: "illegal argument")
        } catch (e: Exception) {
            logger.warn(e) { "Unexpected ELTM tool failure on ${request.name}" }
            errorResult(request, "ELTM tool '${request.name}' failed: ${e.message}")
        }
    }

    /** The strict date-filter validation shared by the note read tools. */
    private fun JsonObject.strictDateRange(): Pair<LocalDate?, LocalDate?> {
        val from = strictDate("from")
        val to = strictDate("to")
        if (from != null && to != null && from.isAfter(to)) {
            throw IllegalArgumentException("from must not be after to")
        }
        return from to to
    }

    /**
     * Parse a date argument strictly: absent → null, malformed →
     * [IllegalArgumentException] (surfaced as a model-visible error, never a
     * silently ignored filter).
     */
    private fun JsonObject.strictDate(key: String): LocalDate? {
        val raw = this[key]?.jsonPrimitive?.contentOrNull ?: return null
        return try {
            LocalDate.parse(raw)
        } catch (e: DateTimeParseException) {
            throw IllegalArgumentException("$key must be a valid date in YYYY-MM-DD format")
        }
    }

    // ---------- shared rendering & validation helpers ----------

    /** The `# Entity ...` header line shared by every entity-rendering tool. */
    private fun entityHeader(id: Long, canonicalName: String, category: String): String =
        "# Entity $id - \"$canonicalName\" ($category)"

    /**
     * The alphabetized `Attributes:` block of an entity render ([indent]
     * nests it, e.g. under a near match); empty attributes append nothing.
     * The shared shape keeps the model's view of an entity identical across
     * search_entities, create_entity and refine_entity.
     */
    private fun StringBuilder.appendAttributesBlock(
        attributes: Map<String, String>,
        indent: String = "",
    ) {
        if (attributes.isEmpty()) return
        append("\n${indent}Attributes:\n")
        append(attributes.toSortedMap().entries.joinToString("\n") {
            "$indent  ${it.key}: ${it.value}"
        })
    }

    /** The diary rendering shared by the note read tools: one `## date (note id)` header per note. */
    private fun renderNotes(notes: List<EltmNote>): String =
        notes.joinToString("\n\n") { "## ${it.eventDate} (note ${it.id})\n${it.note}" }

    /**
     * The paginated-read `limit` argument (default 5, must be >= 1).
     * Invalid values throw [IllegalArgumentException] — execute's catch
     * maps it onto the same model-visible error the former inline checks
     * produced.
     */
    private fun JsonObject.limitArg(): Int {
        val limit = intArg("limit") ?: 5
        require(limit >= 1) { "limit must be >= 1" }
        return limit
    }

    /**
     * [limitArg] plus the `offset` argument (default 0, must be >= 0) — the
     * pagination pair of the diary read tools.
     */
    private fun JsonObject.limitOffsetArgs(): Pair<Int, Int> {
        val limit = limitArg()
        val offset = intArg("offset") ?: 0
        require(offset >= 0) { "offset must be >= 0" }
        return limit to offset
    }

    /**
     * Fail with a model-visible error when the subject does not exist: the
     * tools refuse a nonexistent subject instead of answering a silent
     * empty result (or an opaque FK error). Throws
     * [IllegalArgumentException] — execute's catch maps it onto the same
     * error result the former inline checks produced.
     */
    private suspend fun requireEntity(id: Long) {
        if (!eltmService.entityExists(id)) {
            throw IllegalArgumentException("entity $id does not exist")
        }
    }

    /** [requireEntity] for relationships. */
    private suspend fun requireRelationship(id: Long) {
        if (!eltmService.relationshipExists(id)) {
            throw IllegalArgumentException("relationship $id does not exist")
        }
    }

    /**
     * The paginated diary read shared by get_entity_notes and
     * get_relationship_notes: subject existence check, the strict
     * date-range filter, pagination, then the shared note rendering.
     * Exactly one subject id is non-null (each call site parses its own id
     * argument, so the "required and must be a number" errors keep naming
     * the tool's own argument).
     */
    private suspend fun noteDiaryPage(
        request: ToolCallRequest,
        args: JsonObject,
        entityId: Long?,
        relationshipId: Long?,
    ): ChatMessagePart.ToolResult {
        if (entityId != null) requireEntity(entityId)
        if (relationshipId != null) requireRelationship(relationshipId)
        val (from, to) = args.strictDateRange()
        val (limit, offset) = args.limitOffsetArgs()
        val notes = if (entityId != null) {
            eltmService.getEntityNotes(entityId, from, to, limit, offset)
        } else {
            eltmService.getRelationshipNotes(relationshipId!!, from, to, limit, offset)
        }
        return if (notes.isEmpty()) textResult(request, "No notes.")
        else textResult(request, renderNotes(notes))
    }

    companion object {
        private val logger = KotlinLogging.logger {}

        private val WRITE_TOOLS = setOf(
            "create_entity",
            "refine_entity",
            "create_relationship",
            "merge_entities",
            "add_entity_note",
            "add_relationship_note",
            "set_entity_attribute",
            "delete_entity_attribute",
        )
    }
}
