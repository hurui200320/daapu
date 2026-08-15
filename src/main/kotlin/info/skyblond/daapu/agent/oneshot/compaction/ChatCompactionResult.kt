package info.skyblond.daapu.agent.oneshot.compaction

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
)
