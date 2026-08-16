package info.skyblond.daapu.hand

import info.skyblond.daapu.agent.chat.ChatMessage
import info.skyblond.daapu.agent.chat.ChatMessagePart
import info.skyblond.daapu.agent.chat.ChatMessageRole
import info.skyblond.daapu.agent.model.LLM
import info.skyblond.daapu.agent.tool.ToolProvider
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
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

    /**
     * Non-streaming variant of [run]: consumes the full run flow and returns
     * every [ChatMessage] it produced, in order — the per-round assistant
     * message followed by its tool results (the same reconstruction the chat
     * loop uses). The caller decides what to keep; the one-shot services
     * only look at the last message.
     *
     * The run ends on exactly one of:
     * - a `done` event: the collected messages are returned (by construction
     *   the last message is the final assistant `stop` message — tool-call
     *   rounds continue the loop, so a successful run never ends on a tool
     *   result);
     * - a `run_error` event: throws [HandRunException] with the hand's error
     *   type (including `round_limit`, `empty_response`, and `upstream` when
     *   the retries are exhausted);
     * - a dropped connection before a terminal event: throws
     *   [HandUpstreamException].
     */
    suspend fun runCollect(
        request: HandRunRequest,
        toolProvider: ToolProvider,
        model: LLM,
    ): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()
        var terminal: HandEvent.Done? = null
        run(request, toolProvider, model).collect { event ->
            when (event) {
                // per-round authoritative message; the deltas are dropped
                is HandEvent.AssistantMessage -> messages += event.message
                // paired with the assistant's tool_call parts by id: the args
                // already live in the call, so no extra lookup is needed
                is HandEvent.ToolResult -> messages += ChatMessage(
                    ChatMessageRole.ToolResult,
                    listOf(
                        ChatMessagePart.ToolResult(
                            id = event.id,
                            tool = event.name,
                            parts = event.parts,
                            isError = event.isError,
                        )
                    ),
                )

                is HandEvent.Done -> terminal = event
                is HandEvent.Retry -> logger.info { "one-shot retry: ${event.message}" }
                is HandEvent.RunError -> throw HandRunException(event.type, event.message)
                // stream noise or display echoes: nothing to collect
                is HandEvent.TextDelta,
                is HandEvent.ReasoningDelta,
                is HandEvent.ToolCall -> Unit
            }
        }
        // defensive: [HandClient.run] already fails a stream that closes
        // without a terminal event, so this should not be reachable
        check(terminal != null) { "one-shot run ended without a terminal event" }
        return messages
    }

    /** Close the underlying hand HTTP client (see [HandClient.close]). */
    override fun close() {
        hand.close()
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
