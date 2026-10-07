package info.skyblond.daapu.agent.pipeline.compaction

import info.skyblond.daapu.agent.chat.ChatMessage

data class ChatCompactionResult(
    /**
     * Messages that summary has replaced.
     * */
    val droppedMessages: List<ChatMessage>,
    /**
     * The chat to continue.
     * */
    val newChat: List<ChatMessage>,
    /**
     * The number of rounds [newChat] preserves verbatim after the
     * summary message: the ACTUAL kept count, which can be SMALLER than
     * the requested keep when the chat was too short to honor it (the
     * `splitMessage` clamp). 0 means the whole chat collapsed into the
     * summary message alone.
     * */
    val keptRounds: Int,
)
