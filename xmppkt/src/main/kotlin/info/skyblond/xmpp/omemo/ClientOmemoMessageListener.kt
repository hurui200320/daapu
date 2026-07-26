package info.skyblond.xmpp.omemo

import info.skyblond.xmpp.ChatMessage
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.trySendBlocking
import org.jivesoftware.smack.packet.Message
import org.jivesoftware.smack.packet.Stanza
import org.jivesoftware.smackx.carbons.packet.CarbonExtension
import org.jivesoftware.smackx.omemo.OmemoMessage
import org.jivesoftware.smackx.omemo.listener.OmemoMessageListener
import org.slf4j.LoggerFactory

class ClientOmemoMessageListener(
    private val messageChannel: SendChannel<ChatMessage>,
) : OmemoMessageListener {
    companion object {
        private val logger = LoggerFactory.getLogger(ClientOmemoMessageListener::class.java)
    }

    override fun onOmemoMessageReceived(
        stanza: Stanza, decryptedMessage: OmemoMessage.Received
    ) {
        if (decryptedMessage.body == null) {
            logger.debug("Skip key transportation message")
            return
        }
        val result = messageChannel.trySendBlocking(
            ChatMessage(
                type = ChatMessage.Type.DM, encrypted = true, stanza = stanza,
                content = decryptedMessage.body
            )
        )
        if (result.isFailure) {
            logger.error("Failed to send message to channel", result.exceptionOrNull())
        }
    }

    override fun onOmemoCarbonCopyReceived(
        direction: CarbonExtension.Direction,
        carbonCopy: Message, wrappingMessage: Message,
        decryptedCarbonCopy: OmemoMessage.Received
    ) {
        logger.error(
            "Received carbon copy message, the current impl does NOT support HA set up. " +
                    "This may cause desync with the bot history, please fix asap. " +
                    "Ignoring carbon copy message..."
        )
    }
}