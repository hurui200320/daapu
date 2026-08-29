package info.skyblond.daapu.hand

import info.skyblond.daapu.agent.chat.ChatMessage

/**
 * The failures the hand seam raises, one family per layer:
 *
 * - [HandRunException] — a hand run failed terminally (the hand's `error`
 *   event). The [type] is the hand's error taxonomy (`upstream`,
 *   `context_exhausted`, `output_budget_exhausted`, `content_filter`,
 *   `tool_transport`, `round_limit`, `internal`, ...).
 * - [HandUpstreamException] — the hand could not serve the request at all
 *   (connection failure, HTTP error response, dropped stream without a
 *   terminal event). A hand connection drop is terminal: the stateless hand
 *   cannot resume a dead run.
 * - [EmbeddingException] — a `/v1/embed` call failed (the hand's
 *   `{ok:false,error:{...}}` envelope parsed by the transport, or a
 *   transport-level failure wrapped by [HandService.embed]). The [type] is
 *   the hand's taxonomy restricted to the embed endpoint: `auth` (bad api
 *   key), `invalid_request` (the gateway rejected the input — the too-large
 *   channel the ELTM tool layer maps to "split it into smaller entries"),
 *   `upstream` (transient provider failures, already retried by the hand
 *   against its budget).
 */

/** The hand's error envelope ([HandError.type]/[HandError.message]) as an exception. */
class HandRunException(val type: String, message: String) : Exception(message)

/**
 * The outcome of a one-shot run that keeps its partial history on failure
 * ([HandService.runCollectPartial]): the collected messages plus the
 * terminal [HandRunException] when the run ended on a hand `error` event
 * (null on success). A dropped connection before a terminal event is NOT
 * captured here — it throws [HandUpstreamException] like the plain run.
 */
class HandRunResult(
    val result: List<ChatMessage>,
    val exception: HandRunException? = null,
)

/**
 * The hand could not serve the request at all (connection failure, HTTP
 * error response, dropped stream without a terminal event). A hand
 * connection drop is terminal: the stateless hand cannot resume a dead run.
 */
class HandUpstreamException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * A `/v1/embed` call failed (the hand's `{ok:false,error:{...}}` envelope
 * parsed by the transport, or a transport-level failure wrapped by
 * [HandService.embed]). The [type] is the hand's taxonomy restricted to the
 * embed endpoint: `auth` (bad api key), `invalid_request` (the gateway
 * rejected the input — the too-large channel the ELTM tool layer maps to
 * "split it into smaller entries"), `upstream` (transient provider
 * failures, already retried by the hand against its budget).
 */
class EmbeddingException(val type: String, message: String, cause: Throwable? = null) : Exception(message, cause)
