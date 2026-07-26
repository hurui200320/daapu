package info.skyblond.xmpp.chat

import info.skyblond.xmpp.ChatMessage
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.trySendBlocking
import org.jivesoftware.smack.chat2.Chat
import org.jivesoftware.smack.chat2.IncomingChatMessageListener
import org.jivesoftware.smack.packet.Message
import org.jivesoftware.smackx.omemo.element.OmemoElement
import org.jivesoftware.smackx.omemo.util.OmemoConstants
import org.jxmpp.jid.EntityBareJid
import org.slf4j.LoggerFactory

class ClientIncomingChatMessageListener(
    private val messageChannel: SendChannel<ChatMessage>,
) : IncomingChatMessageListener {
    companion object {
        private val logger = LoggerFactory.getLogger(ClientIncomingChatMessageListener::class.java)

        private val dmMessageType = listOf(Message.Type.normal, Message.Type.chat)
        private val mucMessageType = listOf(Message.Type.groupchat)
    }

    override fun newIncomingMessage(
        from: EntityBareJid, message: Message, chat: Chat
    ) {
        // skip encrypted message
        if (message.hasExtension(
                OmemoElement.NAME_ENCRYPTED,
                OmemoConstants.OMEMO_NAMESPACE_V_AXOLOTL
            )
        ) {
            logger.debug("Skip encrypted message, stanza id=${message.stanzaId}")
            return
        }
        val messageType = when (message.type) {
            in dmMessageType -> ChatMessage.Type.DM
            in mucMessageType -> ChatMessage.Type.MUC
            else -> { // unknown type
                logger.warn("Unsupported message type ${message.type}")
                return
            }
        }
        val result = messageChannel.trySendBlocking(
            ChatMessage(
                type = messageType, encrypted = false, stanza = message,
                content = message.body
            )
        )
        if (result.isFailure) {
            logger.error("Failed to send message to channel", result.exceptionOrNull())
        }
    }
}