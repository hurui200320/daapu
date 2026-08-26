package info.skyblond.daapu.agent.tool

import info.skyblond.daapu.agent.chat.ChatMessagePart

/**
 * A `ToolProvider` decorator that caps the total text of every successful
 * tool result at [maxLength] chars — a safety net against a tool server
 * returning a multi-megabyte dump that would blow the model's context
 * (MCP servers rarely enforce their own output budgets). The cap is in
 * CHARS, not tokens, BY DESIGN: estimating tokens reliably is impossible
 * across providers and models (tokenizers differ, and the hand has no
 * server-side tokenizer), while a char count is deterministic and cheap.
 *
 * Only the TEXT of a result is capped (attachments are preserved
 * untouched, in their original order ahead of the merged text); a result
 * whose joined text already fits the cap is returned exactly as the
 * delegate produced it — no copy, no reordering. A result that exceeds the
 * cap has its text parts merged into one (in their original order, joined
 * by newlines) and truncated; the truncation marker is budgeted INSIDE the
 * cap, so the merged text fits [maxLength] whenever the cap is larger than
 * the marker itself — a cap smaller than the marker (a few tens of chars,
 * which would make any real tool result unusable anyway) falls back to the
 * marker alone, which then exceeds it. The cut always lands on a
 * well-formed UTF-16 boundary: when it would split a surrogate pair, the
 * dangling high half is dropped (the kept prefix then ends one unit short
 * of the cap). Error results (`isError`) are never
 * truncated, BY DESIGN: a tool error is expected to be a short, concise
 * description of what went wrong (never a content dump — servers return
 * those as successful results), and cutting it would hide the cause and
 * prevent the model from recovering in the next round.
 *
 * Everything else is delegated unchanged: [namespaces],
 * [specifications], [executionTimeoutSeconds] and the result's
 * `id`/`tool`/`isError` flags.
 */
class LengthSafeToolProvider(
    private val delegate: ToolProvider,
    private val maxLength: Int,
) : ToolProvider {

    init {
        require(maxLength > 0) {
            "Length safe tool provider: the maxLength must be positive"
        }
    }

    override fun namespaces(): Set<String> = delegate.namespaces()

    override suspend fun specifications(): List<ToolSpec> = delegate.specifications()

    override fun executionTimeoutSeconds(toolName: String): Long =
        delegate.executionTimeoutSeconds(toolName)

    override suspend fun execute(request: ToolCallRequest): ChatMessagePart.ToolResult {
        val result = delegate.execute(request)

        // an error result is the model's signal of what went wrong: return
        // it untouched, whatever its size
        if (result.isError) return result

        // the joined text's length decides BEFORE any copy.
        // The check counts the '\n' separators the join
        // would add (one per text part beyond the first),
        // so the boundary matches the truncation path exactly.
        val textParts = result.parts.filterIsInstance<ChatMessagePart.Text>()
        val joinedLength = textParts.sumOf { it.text.length } + (textParts.size - 1).coerceAtLeast(0)
        if (joinedLength <= maxLength) return result

        // similar to opencode: merge all text parts into one, truncate and
        // put it as the last part (attachments keep their original order,
        // ahead of the merged text). The parts' `.text` is joined, never
        // their toString() — a data class toString would leak the
        // "Text(text=...)" wrapper into the model's context.
        val text = textParts.joinToString("\n") { it.text }

        // the marker is budgeted INSIDE the cap: subtracting its own length
        // from the kept prefix makes the merged text fit maxLength exactly —
        // except when the cap is smaller than the marker itself, in which
        // case the coerce keeps a pathological tiny cap from throwing and
        // the marker alone is returned (see the class KDoc)
        val marker = "\n\n[tail truncated: the tool result was ${text.length} chars, " +
                "capped at $maxLength chars]"
        val keptLength = (maxLength - marker.length).coerceAtLeast(0)
        var kept = text.take(keptLength)
        // the cap counts UTF-16 code units, so a naive take could cut
        // between a surrogate pair's halves and leave a lone high
        // surrogate at the end of the kept prefix — malformed text the
        // model would see (and JSON-escape). When the cut landed inside a
        // pair, drop the dangling high half (its low half was cut off
        // anyway); the kept prefix is then shorter than the cap by one.
        if (kept.isNotEmpty() && kept.last().isHighSurrogate() &&
            kept.length < text.length && text[kept.length].isLowSurrogate()
        ) {
            kept = kept.dropLast(1)
        }
        val newText = kept + marker
        val newParts = result.parts.filter {
            it !is ChatMessagePart.Text
        } + ChatMessagePart.Text(newText)
        return result.copy(parts = newParts)
    }
}
