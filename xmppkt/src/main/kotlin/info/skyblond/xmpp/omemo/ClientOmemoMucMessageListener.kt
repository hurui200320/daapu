package info.skyblond.xmpp.omemo

import info.skyblond.xmpp.ChatMessage
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.trySendBlocking
import org.jivesoftware.smack.packet.Stanza
import org.jivesoftware.smackx.muc.MultiUserChat
import org.jivesoftware.smackx.omemo.OmemoMessage
import org.jivesoftware.smackx.omemo.listener.OmemoMucMessageListener
import org.slf4j.LoggerFactory

class ClientOmemoMucMessageListener(
    private val messageChannel: SendChannel<ChatMessage>,
) : OmemoMucMessageListener {
    companion object {
        private val logger = LoggerFactory.getLogger(ClientOmemoMucMessageListener::class.java)
    }
    // TODO: test how to receive a MUC message, might need to sub or join the room first
    override fun onOmemoMucMessageReceived(
        muc: MultiUserChat, stanza: Stanza, decryptedOmemoMessage: OmemoMessage.Received
    ) {
        if (decryptedOmemoMessage.body == null) {
            logger.debug("Skip key transportation message")
            return
        }
        val result = messageChannel.trySendBlocking(
            ChatMessage(
                type = ChatMessage.Type.MUC, encrypted = true, stanza = stanza,
                content = decryptedOmemoMessage.body
            )
        )
        if (result.isFailure) {
            logger.error("Failed to send message to channel", result.exceptionOrNull())
        }
    }

}