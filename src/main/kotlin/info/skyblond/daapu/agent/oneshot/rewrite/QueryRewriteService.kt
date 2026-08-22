package info.skyblond.daapu.agent.oneshot.rewrite

import info.skyblond.daapu.agent.chat.ChatMessage
import info.skyblond.daapu.agent.chat.ChatMessageRole
import info.skyblond.daapu.agent.model.LLM
import info.skyblond.daapu.agent.oneshot.lastMessageText
import info.skyblond.daapu.agent.persist.ContextInjection
import info.skyblond.daapu.agent.persist.InjectionSpec
import info.skyblond.daapu.agent.tool.EmptyToolProvider
import info.skyblond.daapu.hand.HandRunRequest
import info.skyblond.daapu.hand.HandService
import info.skyblond.daapu.hand.toHandModelSpec
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import java.time.ZonedDateTime

class QueryRewriteService(
    private val model: LLM,
    private val hand: HandService,
    private val maxRetries: Int,
    private val streamIdleTimeoutMs: Long,
    private val contextInjection: ContextInjection = ContextInjection(),
) {
    /**
     * Rewrite the query based on the chat history and user parts.
     * The [history] should include the latest user message.
     *
     * @return null if nothing to query (the sentinel, or a clipped history
     * with no user message at all), otherwise the rewritten query.
     * */
    suspend fun rewriteQuery(
        history: List<ChatMessage>,
        rounds: Int
    ): String? {
        val historyClipped = takeLastNRound(chat = history, lastNRound = rounds)
        // a chat with no user message has nothing to rewrite: there is no
        // latest input to make standalone
        if (historyClipped.isEmpty()) {
            return null
        }
        // fail fast on a capability mismatch before the LLM call: the same
        // prompt would fail identically forever (the run loop's per-round
        // check semantics, applied to the configured rewrite model — a
        // `memory.eltm.rewriteModel` config error)
        model.checkPromptContentCapabilities(historyClipped)
        val chat = contextInjection.injectContext(
            contextInjection.removeInjection(historyClipped),
            spec = InjectionSpec(
                time = ZonedDateTime.now(),
                eltmUpdated = false,
            ),
        )

        val rewrite = try {
            hand.runCollect(
                HandRunRequest(
                    model = model.toHandModelSpec(),
                    messages = chat,
                    systemPrompt = renderRewriteSystemPrompt(),
                    maxTokens = model.maxOutputTokens,
                    // 0 = no round cap, safe here: no tools are declared
                    // (and [EmptyToolProvider] answers stray calls with an
                    // error result), so the loop ends on the first stop
                    maxRounds = 0,
                    maxRetries = maxRetries,
                    streamIdleTimeoutMs = streamIdleTimeoutMs,
                ),
                toolProvider = EmptyToolProvider,
                model = model,
            ).lastMessageText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("Query rewrite failed", e)
        }

        return if (rewrite == NOTHING_TO_QUERY) null
        else rewrite
    }

    internal fun takeLastNRound(
        chat: List<ChatMessage>,
        lastNRound: Int
    ): List<ChatMessage> {
        require(lastNRound >= 1)
        val userIndexes = chat.mapIndexedNotNull { index, message ->
            if (message.role == ChatMessageRole.User) index else null
        }
        if (userIndexes.isEmpty()) {
            return emptyList()
        }
        val take = minOf(lastNRound, userIndexes.size)
        val cutIdx = if (take == 0) chat.size else userIndexes[userIndexes.size - take]
        return chat.subList(cutIdx, chat.size)
    }

    companion object {
        private const val NOTHING_TO_QUERY = "Nothing worth query."

        private fun renderRewriteSystemPrompt(): String = """
You're rewriting the user input for an embedding based RAG search system.
Rewrite user's LATEST input as complete, standalone, and self-contained sentences.

Rules:
- Each query must be self-contained: dereference time and entities based on the provided context 
- Write query in the same language as the user's latest message
- Do not invent details that are not present in the context
- When nothing is worth querying, output sentence "$NOTHING_TO_QUERY" exactly. For example, user just saying "Hi".
""".trimIndent().trim()

    }
}
