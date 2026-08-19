package info.skyblond.daapu.agent.oneshot.eltm

import info.skyblond.daapu.agent.chat.ChatMessage
import info.skyblond.daapu.agent.chat.ChatMessagePart
import info.skyblond.daapu.agent.chat.ChatMessageRole
import info.skyblond.daapu.agent.model.LLM
import info.skyblond.daapu.agent.model.LLMCapability
import info.skyblond.daapu.agent.oneshot.sstm.formatDate
import info.skyblond.daapu.hand.HandRunRequest
import info.skyblond.daapu.hand.HandService
import info.skyblond.daapu.hand.toHandModelSpec
import info.skyblond.daapu.memory.eltm.EltmService
import info.skyblond.daapu.memory.sstm.ShortTermMemory
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import java.time.LocalDate
import java.time.ZoneId

/**
 * The ELTM writer agent: the one `/v1/run` tool loop that moves evicted SSTM
 * entries into the ELTM (the model, round cap, retry budget and idle timeout
 * are the `memory.eltm` / `hand.*` config values). The model executes the 10
 * ELTM tools ([EltmToolProvider]) back through the hand's callback
 * route; any terminal failure throws [IllegalStateException] (wrapping the
 * cause) and fails the run — the SSTM purge only deletes a victim batch
 * after its writer run succeeds, so a failed writing never loses data.
 */
class EltmWriterService(
    private val writerModel: LLM,
    private val hand: HandService,
    private val eltmService: EltmService,
    /** Round cap for the writer tool loop; `0` = unlimited. */
    private val maxWriterRounds: Int,
    private val maxRetries: Int,
    private val streamIdleTimeoutMs: Long,
) {
    suspend fun writeToEltm(
        victims: List<ShortTermMemory>,
        date: LocalDate = LocalDate.now(),
    ) {
        require(victims.isNotEmpty()) { "cannot write an empty victim batch" }
        require(writerModel.supports(LLMCapability.Output.ToolCalls)) {
            "Writer model ${writerModel.id} does not support tool calls"
        }
        val toolProvider = EltmToolProvider(eltmService)
        val chat = listOf(
            ChatMessage(
                ChatMessageRole.User,
                listOf(ChatMessagePart.Text(buildWriterInput(victims, date))),
            ),
        )
        try {
            hand.runCollect(
                HandRunRequest(
                    model = writerModel.toHandModelSpec(),
                    messages = chat,
                    systemPrompt = renderWriterSystemPrompt(),
                    maxTokens = writerModel.maxOutputTokens,
                    maxRounds = maxWriterRounds,
                    maxRetries = maxRetries,
                    streamIdleTimeoutMs = streamIdleTimeoutMs,
                ),
                toolProvider = toolProvider,
                model = writerModel,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("ELTM write failed", e)
        }
    }

    companion object {
        private val logger = KotlinLogging.logger {}

        /**
         * The writer's input: the current date plus the victims' contents
         * verbatim (the only source the writer may record — it must never
         * invent details).
         */
        internal fun buildWriterInput(victims: List<ShortTermMemory>, date: LocalDate): String {
            val block = victims.joinToString("\n\n") {
                "## Memory ${it.id}\n" +
                        "> Last modified: ${formatDate(LocalDate.ofInstant(it.lastUpdate, ZoneId.systemDefault()))}\n" +
                        it.content
            }
            return "Current date: ${formatDate(date)}\n\n" +
                    "Memory entries evicted from the short-term memory:\n```\n$block\n```"
        }

        private fun renderWriterSystemPrompt(): String = """
You're maintaining an external long-term memory (ELTM) knowledge store.
You are given memory entries evicted from the short-term memory. Preserve their information by writing it into the ELTM.

The ELTM has three kinds of records:
- Entities: a named thing with a category (e.g. name "Apple" with category "fruit" vs "company"). A group of people can be one entity.
- Relationships: a directed edge (source entity, verb, destination entity). Use consistent, general, timeless verbs: "colleague_of", not "became_colleague_of". Create or fetch them with create_relationship; their validity (active/ended) only changes through a diary note (add_relationship_note's valid flag).
- Notes: dated diary entries attached to exactly ONE entity or ONE relationship. ALL descriptive content lives in notes. Entities and relationships have no description fields.

Rules:
- Record only information explicitly present in the input. Never invent details.
- "The user" maps to the canonical entity with name "user" (category "person").
- Before creating an entity, call search_entities to find existing ones. create_entity returns near matches: if one of them is the same thing, use that id; if you discover true duplicates, call merge_entities with the better-canonical entity as winner_id. To rename or re-categorize an entity, create the new entity and merge the old one into it.
- A note belongs to exactly one subject. An event about a relationship (met, broke up, started working together) attaches to that relationship. An event about one entity attaches to that entity. It may mention other entities by name in the text, but must NOT be duplicated under each of them.
- Notes are add-only. To correct or supersede older information, add a NEW note with the current fact and its date. If a relationship no longer holds, add a note to it explaining the ending and pass valid=false; if a previously-ended relationship holds again (e.g. rejoined the company), add a note about the new event and pass valid=true. Only change validity on genuine endings.
- Before adding a note about a subject, check its recent notes with get_entity_notes / get_relationship_notes (or search_notes to find already-recorded content) and skip content that is already recorded: a retried run must not duplicate diary entries.
- event_date is the absolute date the event happened (YYYY-MM-DD); use today's date when unknown.
- Keep notes self-contained and concise (3-5 sentences; names, numbers, and ids verbatim). If add_entity_note or add_relationship_note fails with a "too large" embedding error, split the content into several smaller notes.
- When everything is recorded, reply with a short confirmation and make no further tool calls.
""".trimIndent().trim()
    }
}