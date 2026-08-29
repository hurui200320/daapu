package info.skyblond.daapu.agent.oneshot.eltm

import info.skyblond.daapu.agent.chat.ChatMessage
import info.skyblond.daapu.agent.chat.ChatMessagePart
import info.skyblond.daapu.agent.chat.ChatMessageRole
import info.skyblond.daapu.agent.model.LLM
import info.skyblond.daapu.agent.model.LLMCapability
import info.skyblond.daapu.agent.oneshot.runOneShotCollect
import info.skyblond.daapu.hand.HandRunPolicy
import info.skyblond.daapu.hand.HandService
import info.skyblond.daapu.memory.eltm.EltmService
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.LocalDate

/**
 * The ELTM writer agent: the one `/v1/run` tool loop that writes the facts
 * extracted from discarded conversations into the ELTM (the model, round
 * cap, retry budget and idle timeout are the `memory.eltm` / `hand.*`
 * config values). The model executes the 13 ELTM tools
 * ([EltmToolProvider]) back through the hand's callback route; any terminal
 * failure throws [IllegalStateException] (wrapping the cause) and fails the
 * run — a failed write never loses data (a retry re-extracts from the still
 * existing history, and the writer skips content that is already recorded).
 */
class EltmWriterService(
    private val writerModel: LLM,
    private val hand: HandService,
    private val eltmService: EltmService,
    /** Round cap for the writer tool loop; `0` = unlimited. */
    private val maxWriterRounds: Int,
    private val policy: HandRunPolicy,
) {
    suspend fun writeToEltm(
        facts: String,
        date: LocalDate = LocalDate.now(),
    ) {
        require(facts.isNotBlank()) { "cannot write a blank fact batch" }
        require(writerModel.supports(LLMCapability.Output.ToolCalls)) {
            "Writer model ${writerModel.id} does not support tool calls"
        }
        val toolProvider = EltmToolProvider(eltmService)
        val chat = listOf(
            ChatMessage(
                ChatMessageRole.User,
                listOf(ChatMessagePart.Text(buildWriterInput(facts, date))),
            ),
        )
        hand.runOneShotCollect(
            model = writerModel,
            messages = chat,
            systemPrompt = renderWriterSystemPrompt(),
            policy = policy,
            label = "ELTM write",
            maxRounds = maxWriterRounds,
            toolProvider = toolProvider,
        )
    }

    companion object {
        private val logger = KotlinLogging.logger {}

        /**
         * The writer's input: the current date plus the extracted facts
         * verbatim (the only source the writer may record — it must never
         * invent details).
         */
        internal fun buildWriterInput(facts: String, date: LocalDate): String {
            return "Current date: ${formatDate(date)}\n\n" +
                    "Candidate facts extracted from a discarded conversation:\n```\n$facts\n```"
        }

        // the bare ISO date (yyyy-MM-dd): the model only needs the day resolution,
        // the zone has already been applied at the call site
        internal fun formatDate(date: LocalDate): String = date.toString()

        private fun renderWriterSystemPrompt(): String = """
You're maintaining an external long-term memory (ELTM) knowledge store.
You are given candidate facts extracted from a discarded conversation. Preserve their information by writing it into the ELTM.

The ELTM has four kinds of records:
- Entities: a named thing with a category (e.g. name "Apple" with category "fruit" vs "company"). A group of people can be one entity.
- Relationships: a directed edge (source entity, verb, destination entity). Use consistent, general, timeless verbs: "colleague_of", not "became_colleague_of". Create or fetch them with create_relationship; their validity (active/ended) only changes through a diary note (add_relationship_note's valid flag).
- Attributes: structured key-value facts about ONE entity: its current-state identity (e.g. a person's realname and nickname, a device's model). One value per (entity, key): setting the same key again overwrites. They are embedded with the entity, so facts are semantically searchable.
- Notes: dated diary entries attached to exactly ONE entity or ONE relationship. Dated narrative events and descriptions live here. You should ONLY attach a note to a relationship if the event is related to both the entities AND the verb.

Rules:
- Record only information explicitly present in the input. Never invent details.
- "The user" maps to the canonical entity with name "user" (category "person").
- When new entity should be created but without a defined name, include words like "unknown", "unspecified" in the name with some description. E.g. "unknown chinese company", or "unspecified female friend", etc.
- Timeless structured facts about an entity (model, realname, nickname, serial numbers, etc.) are ATTRIBUTES (set_entity_attribute), not notes. Use notes only for dated events and narrative. Attribute values must be a single line. Before setting an attribute, check the entity's current attributes (search_entities renders them in the hits) and skip facts already recorded.
- Before creating an entity, call search_entities to find existing ones. create_entity returns near matches: if one of them is the same thing, use that id; if you discover true duplicates, call merge_entities with the better-canonical entity as winner_id. To refine an EXISTING entity's identity (e.g. a placeholder "friend" now identified as "Alice", or a re-categorization), call refine_entity with its id and the new name and/or category: the entity keeps its id, so its notes, relationships and attributes stay attached. Only merge when two entities are true duplicates.
- A note belongs to exactly one subject. An event about a relationship (met, broke up, started working together) attaches to that relationship. An event about one entity attaches to that entity. It may mention other entities by name in the text, but must NOT be duplicated under each of them.
- Notes are add-only. To correct or supersede older information, add a NEW note with the current fact and its date. If a relationship no longer holds, add a note to it explaining the ending and pass valid=false; if a previously-ended relationship holds again (e.g. rejoined the company), add a note about the new event and pass valid=true. Only change validity on genuine endings.
- Before adding a note about a subject, check its recent notes with get_entity_notes / get_relationship_notes (or search_notes to find already-recorded content) and skip content that is already recorded: a retried run must not duplicate diary entries.
- event_date is the absolute date the event happened (YYYY-MM-DD); use today's date when unknown.
- Keep notes self-contained and concise (3-5 sentences; names, numbers, and ids verbatim). If add_entity_note or add_relationship_note fails with a "too large" embedding error, split the content into several smaller notes.
- When everything is recorded, reply with a short confirmation and make no further tool calls.
""".trimIndent().trim()
    }
}