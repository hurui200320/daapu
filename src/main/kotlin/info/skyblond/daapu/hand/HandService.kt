package info.skyblond.daapu.hand

import info.skyblond.daapu.agent.model.LLM
import info.skyblond.daapu.agent.tool.ToolProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.UUID

/**
 * The brain's hand-facing service: the [HandClient] transport plus the
 * tool-callback wiring of [HandCallbackService]. The agent layer talks to
 * this single seam; the client itself stays a pure HTTP transport.
 *
 * `run` owns everything the tool callback needs:
 * - the `runId` is INTERNAL to the run/callback plumbing: it is generated
 *   here (unless the request already carries one) and the in-flight run is
 *   registered under it before the request goes out. The entry is evicted
 *   when the flow ends — success, failure, or brain-disconnect
 *   cancellation (the flow body's `finally` runs on cancellation).
 * - the tool callback URL is attached on every `/v1/run` request (the hand
 *   only POSTs it when a tool call actually needs executing, so it is
 *   harmless without tools), keeping the callback wiring out of the agent
 *   layer.
 *
 * The runId carries no meaning to the chat loop: a fresh id is generated
 * per `/v1/run` call (reactive-compaction retries each get their own), and
 * the registry is only consulted by the hand's callback POSTs while a run
 * stream is open — the hand waits for each callback before continuing, so
 * no callback can legitimately arrive between runs.
 */
class HandService(
    private val hand: HandClient,
    private val handCallback: HandCallbackService,
    /** This brain's tool callback endpoint the hand POSTs to. */
    private val toolCallbackUrl: String,
) : AutoCloseable {
    /**
     * The chat round loop as a stream of [HandEvent]s (see
     * [HandClient.run]). The in-flight run is registered under the request's
     * runId (generated here when absent) before the request is sent and
     * evicted when the stream ends, so the hand's tool callbacks can always
     * resolve their provider and model.
     */
    suspend fun run(
        request: HandRunRequest,
        toolProvider: ToolProvider,
        model: LLM,
    ): Flow<HandEvent> = flow {
        val runId = request.runId ?: UUID.randomUUID().toString()
        val prepared = request.copy(
            runId = runId,
            toolCallbackUrl = request.toolCallbackUrl ?: toolCallbackUrl,
        )
        handCallback.register(runId, toolProvider, model)
        try {
            hand.run(prepared).collect { emit(it) }
        } finally {
            handCallback.unregister(runId)
        }
    }

    /** Non-streaming one-shot (extractor, compactor, merger rounds). */
    suspend fun complete(request: HandCompleteRequest): HandCompleteResponse = hand.complete(request)

    /** Close the underlying hand HTTP client (see [HandClient.close]). */
    override fun close() {
        hand.close()
    }
}
