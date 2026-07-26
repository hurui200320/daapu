package info.skyblond.xmpp

import org.jivesoftware.smack.packet.Stanza

// TODO
class ChatMessage(
    val type: Type,
    val encrypted: Boolean,
    val stanza: Stanza,
    val content: String
) {
    enum class Type {
        DM, MUC
    }
}