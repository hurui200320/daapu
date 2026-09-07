package info.skyblond.daapu.config

import kotlinx.serialization.Serializable

/**
 * The observability settings: console diagnostics for the otherwise hidden
 * pipeline runs. Nothing here affects behavior — these knobs only decide
 * what is logged.
 */
@Serializable
data class ObservabilityConfig(
    /**
     * Log the full transcript of every collect run ([info.skyblond.daapu.hand.HandService.runCollect]
     * / [info.skyblond.daapu.hand.HandService.runCollectPartial] — the
     * one-shot pipelines: title generation, query rewrite, compaction
     * summarization, memory extraction, the ELTM writer — plus the
     * investigate sub-agent and its partial-history summarizer) to the
     * console under the logger name "OneShotTrace" (see
     * `agent/pipeline/OneShotTrace.kt`): the system prompt, the input
     * messages, and the per-round transcript (reasoning, text, tool
     * calls with args, tool results, per-round token usage, finish
     * reasons), including the partial history of failed runs. Content is
     * logged verbatim and untruncated — chat content can be sensitive —
     * while attachments render as size placeholders, never their bytes; a
     * long investigation produces a long log. Default off.
     */
    val oneShotTrace: Boolean = false,
)
