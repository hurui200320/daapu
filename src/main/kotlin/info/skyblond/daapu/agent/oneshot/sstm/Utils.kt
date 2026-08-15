package info.skyblond.daapu.agent.oneshot.sstm

import info.skyblond.daapu.memory.sstm.ShortTermMemory

internal fun buildMergeInput(existing: List<ShortTermMemory>, candidates: String): String {
    val existingBlock = if (existing.isEmpty()) "(none)"
    else existing.joinToString("\n\n") { "## Memory ${it.id}\n${it.content}" }
    return "Current SSTM:\n```\n$existingBlock\n```\n\n" +
            "Candidate facts extracted from a recent conversation:\n```\n$candidates\n```"
}
