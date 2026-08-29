package info.skyblond.daapu.agent.oneshot.investigate

import info.skyblond.daapu.agent.chat.ChatMessage
import info.skyblond.daapu.agent.chat.ChatMessagePart
import info.skyblond.daapu.agent.chat.ChatMessageRole
import info.skyblond.daapu.agent.chat.textContent
import info.skyblond.daapu.agent.model.LLM
import info.skyblond.daapu.agent.model.LLMCapability
import info.skyblond.daapu.agent.oneshot.lastMessageText
import info.skyblond.daapu.agent.tool.EmptyToolProvider
import info.skyblond.daapu.agent.tool.ToolProvider
import info.skyblond.daapu.hand.HandRunPolicy
import info.skyblond.daapu.hand.HandService
import info.skyblond.daapu.hand.HandUpstreamException
import info.skyblond.daapu.hand.handRunRequest
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * The investigator agent: the one `/v1/run` tool loop the main agent
 * delegates deep memory and web searches to (via the `gsg__investigate`
 * tool, `agent/persist/GsgToolProvider.kt`). The model executes the
 * read-only ELTM tools plus the MCP tools (the provider is a
 * [info.skyblond.daapu.agent.tool.WhitelistedToolProvider] over the
 * sub-agent's own [info.skyblond.daapu.agent.tool.CombinedToolProvider])
 * back through the hand's callback route; the model, round cap, retry
 * budget and idle timeout are the `agent.*` / `hand.*` config values.
 *
 * The run is *elastic*: a stopped run never crashes the caller. On a clean
 * stop the final assistant message is the report; on a `round_limit` stop
 * the whole partial history is summarized by a no-tools one-shot on the
 * same model (a failed summarization falls back to the raw assistant
 * texts); on a `context_exhausted` stop only the tool-call trace (name +
 * args) is reported — the partial history may not even fit into the
 * summarizer. Any other classified hand error comes back as an error
 * result instead of failing the chat run.
 */
class InvestigatorService(
    private val model: LLM,
    private val hand: HandService,
    private val toolProvider: ToolProvider,
    /** Round cap for the investigation tool loop; `0` = unlimited. */
    private val maxRounds: Int,
    private val policy: HandRunPolicy,
) {
    suspend fun runInvestigate(query: String): InvestigateOutcome {
        require(query.isNotBlank()) { "cannot investigate a blank query" }
        require(model.supports(LLMCapability.Output.ToolCalls)) {
            "Investigate model ${model.id} does not support tool calls"
        }
        val chat = listOf(
            ChatMessage(
                ChatMessageRole.User,
                listOf(ChatMessagePart.Text(buildInvestigateInput(query))),
            ),
        )
        model.checkPromptContentCapabilities(chat)
        val result = try {
            hand.runCollectPartial(
                handRunRequest(
                    model = model,
                    messages = chat,
                    systemPrompt = renderInvestigatorSystemPrompt(),
                    policy = policy,
                    maxRounds = maxRounds,
                ),
                toolProvider = toolProvider,
                model = model,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: HandUpstreamException) {
            // a dropped transport carries no recoverable partial state
            return InvestigateOutcome.text("Investigation failed: upstream: ${e.message}", isError = true)
        }
        return when (val error = result.exception) {
            null -> extractReport(result.result)
            else -> when (error.type) {
                ROUND_LIMIT -> InvestigateOutcome.text(buildRoundLimitReport(result.result))
                CONTEXT_EXHAUSTED -> InvestigateOutcome.text(buildContextExhaustedReport(result.result))
                else -> InvestigateOutcome.text(
                    "Investigation failed: ${error.type}: ${error.message}",
                    isError = true,
                )
            }
        }
    }

    private fun extractReport(messages: List<ChatMessage>): InvestigateOutcome {
        val assistant = messages.lastOrNull { it.role == ChatMessageRole.Assistant }
        if (assistant != null && assistant.finishReason == "stop") {
            val parts = assistant.parts.filterIsInstance<ChatMessagePart.ContentPart>()
            val text = parts.textContent()
            if (text.isNotBlank()) return InvestigateOutcome(parts)
        }
        // a "clean" run without a final assistant text is as broken as a
        // context-exhausted one: hand the caller the trace
        return InvestigateOutcome.text(buildNoReportReport(messages), isError = true)
    }

    private suspend fun buildRoundLimitReport(messages: List<ChatMessage>): String =
        "The investigator hit the maximum number of rounds ($maxRounds) and was forced to stop.\n\n" +
                "Partial findings so far:\n${summarizePartial(messages)}\n\n" +
                "Refine the query to narrow the scope and try again."

    /**
     * A real LLM summarization of the whole partial history (the same
     * investigate model, no tools): the caller must see what was found so
     * far to refine the query. A failed summarization falls back to the
     * raw assistant texts — the graceful degradation never fails the run.
     */
    private suspend fun summarizePartial(messages: List<ChatMessage>): String {
        return try {
            model.checkPromptContentCapabilities(messages)
            val summaryInput = messages + ChatMessage(
                ChatMessageRole.User,
                listOf(ChatMessagePart.Text(SUMMARY_INSTRUCTION)),
            )
            hand.runCollect(
                handRunRequest(
                    model = model,
                    messages = summaryInput,
                    systemPrompt = renderSummarySystemPrompt(),
                    policy = policy,
                    maxRounds = 0,
                ),
                toolProvider = EmptyToolProvider,
                model = model,
            ).lastMessageText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn { "Partial investigation summarization failed, falling back to the raw assistant texts: ${e.message}" }
            messages.asSequence()
                .filter { it.role == ChatMessageRole.Assistant }
                .flatMap { it.parts.asSequence() }
                .toList()
                .textContent()
                .takeIf { it.isNotBlank() }
                ?: "No partial findings were recorded before the stop."
        }
    }

    private fun buildContextExhaustedReport(messages: List<ChatMessage>): String =
        recoveryReport("The investigator exhausted its context window before finishing.", messages)

    private fun buildNoReportReport(messages: List<ChatMessage>): String =
        recoveryReport("The investigation finished without producing a report.", messages)

    private fun recoveryReport(header: String, messages: List<ChatMessage>): String {
        val trace = messages.asSequence()
            .filter { it.role == ChatMessageRole.Assistant }
            .flatMap { it.parts.asSequence() }
            .filterIsInstance<ChatMessagePart.ToolCall>()
            .joinToString("\n") { "${it.tool}(${it.args})" }
            .takeIf { it.isNotBlank() }
        return header + "\n\n" +
                (trace?.let { "Tool call trace:\n$it\n\n" } ?: "") +
                "Refine the query to narrow the scope and try again."
    }

    companion object {
        private val logger = KotlinLogging.logger {}

        private const val ROUND_LIMIT = "round_limit"
        private const val CONTEXT_EXHAUSTED = "context_exhausted"
        private const val SUMMARY_INSTRUCTION =
            "Summarize this chat history according to the system prompt."

        /**
         * The investigator's input: the current date/time (the read-only
         * ELTM tools accept date ranges, so relative dates in the query
         * need an anchor) plus the query verbatim.
         */
        internal fun buildInvestigateInput(query: String): String =
            "Current date and time: ${ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)}\n\n" +
                    query

        private fun renderInvestigatorSystemPrompt(): String = """
You're an investigator sub-agent, launched by the main agent with a single query.

Your job is to gather information about the query and return ONE detailed, self-contained report at your LAST output.
Only your last output is returned to the main agent.

You have two information sources, use their tools:
- The ELTM (external long-term memory): GraphRAG-ish system that has entities, relationships and diary style notes with semantice search.
- Real-time web tools (MCP servers, e.g. the exa namespace): web search and page fetch for current information.

Rules:
- Multi-step reasoning is expected: search, read the results, follow up with more targeted searches, cross-check, then write the report.
- Do not rely on trained knowledge for recent facts; verify them with tools.
- The final report is the ONLY thing the caller sees: your intermediate tool results and reasoning are discarded. Repeat every fact you use into the report verbatim (names, numbers, dates, ids).
- The report must be self-contained and structured (headings), long enough to fully cover the query.
- When you have enough information, stop calling tools and write the report.
- Never invent facts; if a source is empty or the answer is unknown, say so explicitly.
- If query is ambiguous, explicitly state the ambiguity so the main agent can refine the query.
- Resolve relative dates ("today", "last week") against the current date and time in the user message.
""".trimIndent().trim()

        private fun renderSummarySystemPrompt(): String = """
You're summarizing the partial history of an investigation sub-agent that was forced to stop before finishing.

Summarize what the investigation covered, what was searched and found, and any partial conclusions. The summary will be returned to the main agent, which will refine the query and try again.
""".trimIndent().trim()
    }
}

/** The result of one investigate run: the report content plus whether it failed. */
data class InvestigateOutcome(
    /**
     * The report as content parts: today always text (the clean report
     * keeps the final assistant message's text parts verbatim, the recovery
     * reports are single text parts), but attachments can travel later.
     * The shape mirrors a `ChatMessagePart.ToolResult`'s `parts`, so the
     * `gsg__investigate` tool (`agent/persist/GsgToolProvider.kt`) packages
     * it without a lossy string round-trip.
     */
    val report: List<ChatMessagePart.ContentPart>,
    val isError: Boolean = false,
) {
    /** The flattened text of the report's text parts. */
    val text: String
        get() = report.textContent()

    companion object {
        /** A report consisting of a single text part. */
        fun text(text: String, isError: Boolean = false): InvestigateOutcome =
            InvestigateOutcome(listOf(ChatMessagePart.Text(text)), isError)
    }
}
