package info.skyblond.daapu.testutil

import info.skyblond.daapu.agent.chat.ChatMessage
import info.skyblond.daapu.agent.chat.ChatMessagePart
import info.skyblond.daapu.agent.oneshot.eltm.EltmToolProvider
import info.skyblond.daapu.hand.HandEvent
import info.skyblond.daapu.hand.assistantMessage
import info.skyblond.daapu.hand.toolRoundEvents
import info.skyblond.daapu.memory.eltm.EltmService
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** An assistant message whose only part is one `create_entity` tool call. */
fun createEntityRound(id: String, name: String, category: String = "general"): ChatMessage =
    assistantMessage(
        parts = listOf(
            ChatMessagePart.ToolCall(
                id = id,
                tool = "create_entity",
                args = buildJsonObject {
                    put("name", name)
                    put("category", category)
                },
            )
        ),
        finishReason = "tool_calls",
    )

/** An assistant message whose only part is one `add_entity_note` tool call. */
fun addEntityNoteRound(id: String, entityId: Long, date: String, note: String): ChatMessage =
    assistantMessage(
        parts = listOf(
            ChatMessagePart.ToolCall(
                id = id,
                tool = "add_entity_note",
                args = buildJsonObject {
                    put("entity_id", entityId)
                    put("event_date", date)
                    put("note", note)
                },
            )
        ),
        finishReason = "tool_calls",
    )

/**
 * A scripted ELTM writer run: one `create_entity` tool round (executed
 * through the real [EltmToolProvider], standing in for the hand's tool
 * callback), then one `add_entity_note` round on the created entity (found
 * back through [EltmService.searchEntities]), followed by the final
 * confirmation.
 */
suspend fun writerRunFlow(
    eltm: EltmService,
    name: String = "user",
    note: String = "likes coffee",
    date: String = "2026-08-17",
): List<HandEvent> {
    val provider = EltmToolProvider(eltm)
    val createRound = createEntityRound("call_create", name)
    // execute the create up front (the same provider round the returned flow
    // replays, standing in for the hand's callback), so the entity exists
    // when the note round is built — create-or-fetch is idempotent, so the
    // replay is harmless
    val createEvents = toolRoundEvents(createRound, provider)
    val noteRound = addEntityNoteRound(
        "call_note",
        eltm.searchEntities(name, 1).first().entity.id,
        date,
        note,
    )
    return listOf(
        HandEvent.AssistantMessage(createRound),
    ) + createEvents + listOf(
        HandEvent.AssistantMessage(noteRound),
    ) + toolRoundEvents(noteRound, provider) + listOf(
        HandEvent.AssistantMessage(assistantMessage("done")),
        HandEvent.Done("stop"),
    )
}
