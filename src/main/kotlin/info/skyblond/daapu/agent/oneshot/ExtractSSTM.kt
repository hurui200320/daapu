package info.skyblond.daapu.agent.oneshot

import dev.langchain4j.model.openai.OpenAiChatModel
import info.skyblond.daapu.agent.lc4j.llm.LLM
import info.skyblond.daapu.agent.refreshSystemPrompt
import info.skyblond.daapu.chat.ChatMessage
import info.skyblond.daapu.chat.ChatMessagePart
import info.skyblond.daapu.chat.ChatMessageRole
import info.skyblond.daapu.memory.sstm.SstmService


class SstmExtractor(
    private val model: LLM,
    private val chatModel: OpenAiChatModel,
    private val sstmService: SstmService
) {
    fun processDiscardedMessages(
        droppedMessages: List<ChatMessage>,
    ) {
        val extract = extract(droppedMessages)
        mergeToSstm(extract)
    }

    private fun extract(droppedMessages: List<ChatMessage>): String {
        val chat = (droppedMessages + ChatMessage(
            role = ChatMessageRole.User,
            parts = listOf(
                ChatMessagePart.Text(
                    "Extract memories item according to the system prompt."
                )
            ),
        )).refreshSystemPrompt(
            renderExtractorSystemPrompt()
        )

        // TODO: check capabilities, send request
        TODO()

        // TODO: when ELTM is ready, detect SSTM length and trigger ELTM
    }

    fun mergeToSstm(extraction: String) {
        // TODO: Sstm tools?
        TODO()
    }


    companion object {
        private const val NOTHING_TO_REMEMBER_TEXT = "Nothing worth remember."

        private fun renderExtractorSystemPrompt(): String = """
You're extracting memories from a discarded conversation.
Extract **all** important information from the conversation history into a list of self-contained facts suitable for long-term memory.

Focus on:
- The user's preferences, likes, dislikes, and personal details
- Plans, goals, pending tasks, and unresolved questions
- Decisions and constraints
- Facts about people, projects, and entities: keep names, numbers, ids, and values verbatim
- Anything a future conversation would need to know

Rules:
- Each fact must be self-contained: replace pronouns with the entity name or "the user"
- Write facts in the same language as the conversation
- Do not invent details that are not present in the history
- Merge overlapping information into one fact
- When nothing is worth remembering, output sentence "$NOTHING_TO_REMEMBER_TEXT"
""".trimIndent().trim()


        private fun renderMergerSystemPrompt(): String = """
You're merging summarized memories into the memory system (SSTM).
You have access to tools that manipulating the SSTM.
The SSTM is a numbered list of memories.
The current state and the candidate facts extracted from a recent conversation are given in the user message.

Update the SSTM with the memory tools:
- list_memories: view the current SSTM (also listed in the user message)
- add_memory(content): add a new memory
- update_memory(id, content): replace an existing memory's content in place
- delete_memory(id): remove an existing memory

For each candidate decide exactly one action:
- ADD: the candidate is new information -> add_memory
- UPDATE: the candidate refines an existing memory (the same fact with more detail or a correction) -> update_memory with the existing id
- DELETE: the candidate contradicts an existing memory and the new fact is authoritative -> delete_memory, then add_memory for the new fact
- NONE: the candidate is already covered by an existing memory -> do nothing

Rules:
- Never modify memories unrelated to the candidates
- Keep memories concise and self-contained
- When all candidates are handled, reply with a short confirmation and make no further tool calls
""".trimIndent().trim()
    }
}