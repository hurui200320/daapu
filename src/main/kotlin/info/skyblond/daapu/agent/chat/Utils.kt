package info.skyblond.daapu.agent.chat

/**
 * The flattened text of a part list: every [ChatMessagePart.Text] part's
 * text, joined in order by newlines. Non-text parts (reasoning, tool
 * calls, attachments) are ignored — this is the display/report text, not
 * the message's full content. No trim: the join mirrors the parts as-is
 * (e.g. [info.skyblond.daapu.agent.tool.LengthSafeToolProvider]'s
 * truncation boundary depends on the untrimmed join).
 */
fun List<ChatMessagePart>.joinedText(): String =
    filterIsInstance<ChatMessagePart.Text>()
        .joinToString("\n") { it.text }

/**
 * [joinedText] trimmed: the canonical "the text of this content" shape
 * shared by the one-shot answers, the investigator's reports and the
 * tool-result flattening.
 */
fun List<ChatMessagePart>.textContent(): String = joinedText().trim()

/**
 * The number of user rounds in the chat (a round is one user message
 * through to the next user message).
 */
fun List<ChatMessage>.roundCount(): Int =
    count { it.role == ChatMessageRole.User }

/**
 * Take trailing [n] user rounds, cut at user-message boundaries so every
 * tool_call/tool_result pair stays whole. The round count is clamped to the
 * chat's own round count: with fewer rounds, everything from the FIRST user
 * message on is returned. Empty when [n] is <= 0 or the chat has no user
 * message at all.
 */
fun List<ChatMessage>.takeLastNRound(n: Int): List<ChatMessage> {
    if (n <= 0) return emptyList()
    val userIndexes = mapIndexedNotNull { index, message ->
        if (message.role == ChatMessageRole.User) index else null
    }
    if (userIndexes.isEmpty()) return emptyList()
    return subList(userIndexes[userIndexes.size - minOf(n, userIndexes.size)], size)
}
