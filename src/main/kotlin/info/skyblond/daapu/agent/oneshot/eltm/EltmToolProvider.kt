package info.skyblond.daapu.agent.oneshot.eltm

import info.skyblond.daapu.agent.chat.ChatMessagePart
import info.skyblond.daapu.agent.tool.*
import info.skyblond.daapu.config.TOOL_RESERVED_NAMESPACES
import info.skyblond.daapu.config.validateToolNamespaceSyntax
import info.skyblond.daapu.hand.EmbeddingException
import info.skyblond.daapu.memory.eltm.EltmService
import info.skyblond.daapu.memory.eltm.normalizeAttributeKey
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
 * the ELTM directly; the SSTM purge pipeline drives the writer agent); the
 * read-only provider serves the recall sub-session (Phase 4).
 *
 * The optional [namespace] switches the provider between the two shapes:
 * blank (the default — one-shot services like the writer/recall agents use
 * it) advertises the bare tool names; a non-blank namespace (e.g. `eltm`,
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
        if (namespace.isBlank()) toolName else "${namespace}__$toolName"

    private fun bareName(advertisedName: String): String? {
        val prefix = "${namespace}__"
        return if (namespace.isBlank()) advertisedName
        else advertisedName.takeIf { it.startsWith(prefix) }?.substring(prefix.length)
    }

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
                    val query = args.requiredText("query") ?: return errorResult(
                        request, "query is required and must not be blank"
                    )
                    val limit = args.optionalInt("limit") ?: 5
                    if (limit < 1) return errorResult(request, "limit must be >= 1")
                    val hits = eltmService.searchEntities(query, limit)
                    if (hits.isEmpty()) {
                        textResult(request, "No matching entities.")
                    } else {
                        val lines = mutableListOf<String>()
                        for (hit in hits) {
                            lines += buildString {
                                append("# Entity ${hit.entity.id} - \"${hit.entity.canonicalName}\" (${hit.entity.category})")
                                append(" - similarity ${"%.3f".format(hit.score)}, notes ${hit.noteCount}, relations ${hit.relationshipCount}")
                                if (hit.attributes.isNotEmpty()) {
                                    append("\nAttributes:\n")
                                    append(hit.attributes.toSortedMap().entries.joinToString("\n") {
                                        "  ${it.key}: ${it.value}"
                                    })
                                }
                                hit.latestNote?.let {
                                    append("\nLatest note (${it.eventDate}): ${it.note}")
                                }
                            }
                        }
                        textResult(request, lines.joinToString("\n\n"))
                    }
                }

                "get_relationships" -> {
                    val id = args.requiredLong("entity_id") ?: return errorResult(
                        request, "entity_id is required and must be a number"
                    )
                    if (!eltmService.entityExists(id)) {
                        return errorResult(request, "entity $id does not exist")
                    }
                    val includeInvalid = args.optionalBool("include_invalid") ?: false
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
                    val entityId = args.requiredLong("entity_id") ?: return errorResult(
                        request, "entity_id is required and must be a number"
                    )
                    if (!eltmService.entityExists(entityId)) {
                        return errorResult(request, "entity $entityId does not exist")
                    }
                    val (from, to) = args.strictDateRange()
                    val limit = args.optionalInt("limit") ?: 5
                    val offset = args.optionalInt("offset") ?: 0
                    if (limit < 1) return errorResult(request, "limit must be >= 1")
                    if (offset < 0) return errorResult(request, "offset must be >= 0")
                    val notes = eltmService.getEntityNotes(entityId, from, to, limit, offset)
                    if (notes.isEmpty()) textResult(request, "No notes.")
                    else notes.joinToString("\n\n") { "## ${it.eventDate} (note ${it.id})\n${it.note}" }
                        .let { textResult(request, it) }
                }

                "get_relationship_notes" -> {
                    val relId = args.requiredLong("relationship_id") ?: return errorResult(
                        request, "relationship_id is required and must be a number"
                    )
                    if (!eltmService.relationshipExists(relId)) {
                        return errorResult(request, "relationship $relId does not exist")
                    }
                    val (from, to) = args.strictDateRange()
                    val limit = args.optionalInt("limit") ?: 5
                    val offset = args.optionalInt("offset") ?: 0
                    if (limit < 1) return errorResult(request, "limit must be >= 1")
                    if (offset < 0) return errorResult(request, "offset must be >= 0")
                    val notes = eltmService.getRelationshipNotes(relId, from, to, limit, offset)
                    if (notes.isEmpty()) textResult(request, "No notes.")
                    else notes.joinToString("\n\n") { "## ${it.eventDate} (note ${it.id})\n${it.note}" }
                        .let { textResult(request, it) }
                }

                "search_notes" -> {
                    val query = args.requiredText("query") ?: return errorResult(
                        request, "query is required and must not be blank"
                    )
                    val entityId = args.optionalLong("entity_id")
                    val relId = args.optionalLong("relationship_id")
                    if (entityId != null && relId != null) {
                        return errorResult(
                            request, "a note search accepts at most one subject"
                        )
                    }
                    if (entityId != null && !eltmService.entityExists(entityId)) {
                        return errorResult(request, "entity $entityId does not exist")
                    }
                    if (relId != null && !eltmService.relationshipExists(relId)) {
                        return errorResult(request, "relationship $relId does not exist")
                    }
                    val (from, to) = args.strictDateRange()
                    val limit = args.optionalInt("limit") ?: 5
                    if (limit < 1) return errorResult(request, "limit must be >= 1")
                    val notes = eltmService.searchNotes(query, entityId, relId, from, to, limit)
                    if (notes.isEmpty()) textResult(request, "No matching notes.")
                    else notes.joinToString("\n\n") { "## ${it.eventDate} (note ${it.id})\n${it.note}" }
                        .let { textResult(request, it) }
                }

                "create_entity" -> {
                    val name = args.requiredText("name") ?: return errorResult(
                        request, "name is required and must not be blank"
                    )
                    val category = args.optionalText("category") ?: "general"
                    val result = eltmService.createEntity(name, category)
                    val view = eltmService.getEntity(result.entity.id)
                    val entity = view?.entity ?: result.entity
                    buildString {
                        append("# Entity ${entity.id} - \"${entity.canonicalName}\" (${entity.category})")
                        append(" - notes ${view?.noteCount ?: 0}, relations ${view?.relationshipCount ?: 0}")
                        if (view != null && view.attributes.isNotEmpty()) {
                            append("\nAttributes:\n")
                            append(view.attributes.toSortedMap().entries.joinToString("\n") {
                                "  ${it.key}: ${it.value}"
                            })
                        }
                        if (result.nearMatches.isEmpty()) {
                            append("\nNo near matches.")
                        } else {
                            append("\nNear matches (check for duplicates):")
                            result.nearMatches.forEach { match ->
                                append("\n- Entity ${match.entity.id} - \"${match.entity.canonicalName}\" (${match.entity.category})")
                                append(" - similarity ${"%.3f".format(match.score)}, notes ${match.noteCount}, relations ${match.relationshipCount}")
                                if (match.attributes.isNotEmpty()) {
                                    append("\n  Attributes:\n")
                                    append(match.attributes.toSortedMap().entries.joinToString("\n") {
                                        "    ${it.key}: ${it.value}"
                                    })
                                }
                            }
                        }
                    }.let { textResult(request, it) }
                }

                "create_relationship" -> {
                    val src = args.requiredLong("src_id") ?: return errorResult(
                        request, "src_id is required and must be a number"
                    )
                    val dst = args.requiredLong("dst_id") ?: return errorResult(
                        request, "dst_id is required and must be a number"
                    )
                    val verb = args.requiredText("verb") ?: return errorResult(
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
                    val winner = args.requiredLong("winner_id") ?: return errorResult(
                        request, "winner_id is required and must be a number"
                    )
                    val loser = args.requiredLong("loser_id") ?: return errorResult(
                        request, "loser_id is required and must be a number"
                    )
                    eltmService.mergeEntities(winner, loser)
                    textResult(request, "Entity $loser merged into entity $winner.")
                }

                "add_entity_note" -> {
                    val entityId = args.requiredLong("entity_id") ?: return errorResult(
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
                    val note = args.requiredText("note") ?: return errorResult(
                        request, "note is required and must not be blank"
                    )
                    val created = eltmService.attachNoteToEntity(entityId, eventDate, note)
                    textResult(
                        request,
                        "Note ${created.id} added (${created.eventDate}, subject entity $entityId)."
                    )
                }

                "add_relationship_note" -> {
                    val relId = args.requiredLong("relationship_id") ?: return errorResult(
                        request, "relationship_id is required and must be a number"
                    )
                    val eventDate = args.strictDate("event_date") ?: return errorResult(
                        request, "event_date is required and must be YYYY-MM-DD"
                    )
                    val note = args.requiredText("note") ?: return errorResult(
                        request, "note is required and must not be blank"
                    )
                    val valid = args.optionalBool("valid")
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
                    val entityId = args.requiredLong("entity_id") ?: return errorResult(
                        request, "entity_id is required and must be a number"
                    )
                    val key = args.requiredText("key") ?: return errorResult(
                        request, "key is required and must not be blank"
                    )
                    val value = args.requiredText("value") ?: return errorResult(
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
                    val entityId = args.requiredLong("entity_id") ?: return errorResult(
                        request, "entity_id is required and must be a number"
                    )
                    val key = args.requiredText("key") ?: return errorResult(
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
            // delete the offending attribute and retry the merge)
            if (e.type == "invalid_request") {
                val fix = when (bareName(request.name)) {
                    "set_entity_attribute" ->
                        "shorten the value and retry."
                    "merge_entities" ->
                        "the merged entity's content is too large, shorten or delete an attribute and retry."
                    "create_entity" ->
                        "shorten the name and retry."
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

    private fun JsonObject.requiredText(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }

    private fun JsonObject.optionalText(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }

    private fun JsonObject.requiredLong(key: String): Long? =
        this[key]?.jsonPrimitive?.contentOrNull?.toLongOrNull()

    private fun JsonObject.optionalLong(key: String): Long? =
        this[key]?.jsonPrimitive?.contentOrNull?.toLongOrNull()

    private fun JsonObject.optionalInt(key: String): Int? =
        this[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull()

    private fun JsonObject.optionalBool(key: String): Boolean? =
        this[key]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()

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

    private fun textResult(request: ToolCallRequest, text: String): ChatMessagePart.ToolResult =
        ChatMessagePart.ToolResult(
            id = request.id,
            tool = request.name,
            parts = listOf(ChatMessagePart.Text(text)),
        )

    private fun errorResult(request: ToolCallRequest, error: String): ChatMessagePart.ToolResult =
        ChatMessagePart.ToolResult(
            id = request.id,
            tool = request.name,
            parts = listOf(ChatMessagePart.Text("Error: $error")),
            isError = true,
        )

    companion object {
        private val logger = KotlinLogging.logger {}

        private val WRITE_TOOLS = setOf(
            "create_entity",
            "create_relationship",
            "merge_entities",
            "add_entity_note",
            "add_relationship_note",
            "set_entity_attribute",
            "delete_entity_attribute",
        )

        private fun stringSchema(description: String) = buildJsonObject {
            put("type", "string")
            put("description", description)
        }

        private fun integerSchema(description: String) = buildJsonObject {
            put("type", "integer")
            put("description", description)
        }

        private fun boolSchema(description: String) = buildJsonObject {
            put("type", "boolean")
            put("description", description)
        }

        private fun objectSchema(
            required: List<String>,
            vararg properties: Pair<String, JsonObject>,
        ) = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                properties.forEach { (name, schema) -> put(name, schema) }
            })
            if (required.isNotEmpty()) {
                put("required", buildJsonArray { required.forEach { add(it) } })
            }
        }
    }
}
