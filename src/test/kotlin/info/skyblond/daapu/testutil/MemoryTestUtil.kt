package info.skyblond.daapu.testutil

import info.skyblond.daapu.agent.chat.ChatMessage
import info.skyblond.daapu.agent.chat.ChatMessagePart
import info.skyblond.daapu.agent.oneshot.sstm.MergeMemoryToolProvider
import info.skyblond.daapu.hand.HandEvent
import info.skyblond.daapu.hand.assistantMessage
import info.skyblond.daapu.hand.toolRoundEvents
import info.skyblond.daapu.memory.sstm.MemoriesWithVersion
import info.skyblond.daapu.memory.sstm.ShortTermMemory
import info.skyblond.daapu.memory.sstm.SstmService
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

/** A fake [SstmService] recording writes. */
class RecordingSstmService(
    private val memories: List<ShortTermMemory> = emptyList(),
) : SstmService {
    val created = mutableListOf<String>()
    val updated = mutableListOf<Pair<Long, String>>()
    val deleted = mutableListOf<Long>()

    override suspend fun listMemories(): MemoriesWithVersion =
        MemoriesWithVersion(memories, "test-version")

    override suspend fun createMemory(content: String): ShortTermMemory {
        created += content
        return ShortTermMemory(created.size.toLong(), Instant.EPOCH, content)
    }

    override suspend fun updateMemory(id: Long, content: String): ShortTermMemory {
        updated += id to content
        return ShortTermMemory(id, Instant.EPOCH, content)
    }

    override suspend fun deleteMemory(id: Long): Boolean {
        deleted += id
        return true
    }
}

/** An assistant message whose only part is one `add_memory` tool call. */
fun addMemoryRound(id: String, content: String): ChatMessage = assistantMessage(
    parts = listOf(
        ChatMessagePart.ToolCall(
            id = id,
            tool = "add_memory",
            args = buildJsonObject { put("content", content) },
        )
    ),
    finishReason = "tool_calls",
)

/**
 * A scripted merge run: one `add_memory` tool round (executed through the
 * merge provider, standing in for the hand's tool callback) followed by the
 * final confirmation.
 */
suspend fun mergeRunFlow(sstm: SstmService, content: String = "likes coffee"): List<HandEvent> {
    val provider = MergeMemoryToolProvider(sstm)
    val round = addMemoryRound("call_merge", content)
    return listOf(HandEvent.AssistantMessage(round)) +
            toolRoundEvents(round, provider) +
            listOf(
                HandEvent.AssistantMessage(assistantMessage("done")),
                HandEvent.Done("stop"),
            )
}
