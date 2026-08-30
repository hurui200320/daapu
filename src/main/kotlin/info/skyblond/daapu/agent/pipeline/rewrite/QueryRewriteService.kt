package info.skyblond.daapu.agent.pipeline.rewrite

import info.skyblond.daapu.agent.chat.ChatMessage
import info.skyblond.daapu.agent.chat.takeLastNRound
import info.skyblond.daapu.agent.model.LLM
import info.skyblond.daapu.agent.pipeline.runOneShotText
import info.skyblond.daapu.agent.context.ContextInjection
import info.skyblond.daapu.agent.context.InjectionSpec
import info.skyblond.daapu.hand.HandRunPolicy
import info.skyblond.daapu.hand.HandService
import java.time.ZonedDateTime

class QueryRewriteService(
    private val model: LLM,
    private val hand: HandService,
    private val policy: HandRunPolicy,
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
        // fail fast on a non-positive round count (config `memory.eltm.
        // rewriteRounds` validates this at boot; this guards direct callers):
        // the shared clip helper treats it as "nothing to take", which here
        // would silently skip the rewrite instead of failing loudly
        require(rounds >= 1) { "rewriteRounds must be >= 1, got $rounds" }
        val historyClipped = history.takeLastNRound(rounds)
        // a chat with no user message has nothing to rewrite: there is no
        // latest input to make standalone
        if (historyClipped.isEmpty()) {
            return null
        }
        // a `memory.eltm.rewriteModel` config error
        model.checkPromptContentCapabilities(historyClipped)
        val chat = contextInjection.injectContext(
            contextInjection.removeInjection(historyClipped),
            // the rewrite's own injection is the time-only simple shape (all
            // ELTM fields null): a stateless one-shot, there is no ELTM
            // update flag or memories to report
            spec = InjectionSpec(
                time = ZonedDateTime.now(),
                eltmUpdated = null,
                relatedEntities = null,
                relatedNotes = null,
            ),
        )

        val rewrite = hand.runOneShotText(
            model = model,
            messages = chat,
            systemPrompt = renderRewriteSystemPrompt(),
            policy = policy,
            label = "Query rewrite",
        )

        return if (rewrite == NOTHING_TO_QUERY) null
        else rewrite
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
