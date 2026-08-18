package info.skyblond.daapu.agent.oneshot.sstm

import info.skyblond.daapu.memory.sstm.ShortTermMemory
import java.time.LocalDate
import java.time.ZoneId

internal fun buildMergeInput(
    existing: List<ShortTermMemory>,
    candidates: String,
    date: LocalDate,
): String {
    val existingBlock = if (existing.isEmpty()) "(none)"
    else existing.joinToString("\n\n") {
        "## Memory ${it.id}\n" +
                "> Last modified: ${formatDate(LocalDate.ofInstant(it.lastUpdate, ZoneId.systemDefault()))}\n" +
                it.content
    }
    return "Current date: ${formatDate(date)}\n\n" +
            "Current SSTM:\n```\n$existingBlock\n```\n\n" +
            "Candidate facts extracted from a recent conversation:\n```\n$candidates\n```"
}

// the bare ISO date (yyyy-MM-dd): the model only needs the day resolution,
// the zone has already been applied at the call site
internal fun formatDate(date: LocalDate): String = date.toString()
