package info.skyblond.daapu.hand

import info.skyblond.daapu.agent.chat.ChatMessage

/**
 * Observes every COLLECT run ([HandService.runCollect] and
 * [HandService.runCollectPartial]) when it ends — the observability seam for
 * the otherwise hidden one-shot pipelines (title generation, query rewrite,
 * compaction summarization, memory extraction, the ELTM writer) plus the
 * investigate sub-agent and its partial-history summarizer. The main chat
 * loop runs streaming ([HandService.run]) and never passes through here.
 *
 * The canonical consumer is the one-shot trace (`agent/pipeline/
 * OneShotTrace.kt`): a config-gated renderer that logs the system prompt,
 * the input messages and the full per-round transcript (reasoning, text,
 * tool calls + args, tool results, usage).
 *
 * Semantics:
 * - called exactly ONCE per collect run, after it ends:
 *   - success: [error] is null, [messages] is the full collected history;
 *   - a hand `error` event: [error] is the [HandRunException], [messages]
 *     is the partial history collected before it (the same recovery
 *     [HandRunResult] carries);
 *   - a transport-level failure (dropped stream, dead connection):
 *     [error] is the raw exception and [messages] whatever was collected
 *     before the drop; the exception is rethrown to the caller either way —
 *     observing never swallows a failure;
 * - never called on cancellation (a cancelled run is a teardown, not a
 *   diagnosis target);
 * - [label] is the caller's diagnostic stage name ("ELTM write",
 *   "Investigator", ...), null when the caller passed none. Purely
 *   decorative: it never reaches the wire or the stored history;
 * - [request] is the caller's request as built (messages, system prompt,
 *   model spec, budgets): the runId [HandService.run] generates for the
 *   wire is internal to the run plumbing and not visible here. WARNING:
 *   `request.model.apiKey` is a secret — log selected fields only, never
 *   the request object wholesale;
 *
 * Implementations must not mutate the inputs and must be safe to run inline
 * with the run's own coroutine: [HandService] downgrades an observer
 * failure to a log warning, never letting observability break a run — for
 * `Exception`s only (see [HandService.notifyCollectObserver]): an `Error`
 * propagates and fails the run, so implementations should not allocate
 * unboundedly.
 */
interface CollectRunObserver {
    suspend fun onCollect(
        label: String?,
        request: HandRunRequest,
        messages: List<ChatMessage>,
        error: Exception?,
    )
}
