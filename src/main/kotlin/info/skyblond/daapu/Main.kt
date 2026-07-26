package info.skyblond.daapu

import info.skyblond.xmpp.XMPPChatClient
import kotlinx.coroutines.runBlocking
import org.jivesoftware.smack.proxy.ProxyInfo
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration
import org.jivesoftware.smackx.omemo.signal.SignalCachingOmemoStore
import org.jivesoftware.smackx.omemo.signal.SignalFileBasedOmemoStore
import org.jxmpp.jid.impl.JidCreate
import org.slf4j.LoggerFactory
import java.io.File

private val logger = LoggerFactory.getLogger("Application")

fun main() {
    val config = XMPPTCPConnectionConfiguration.builder()
        .setXmppDomain(requireEnv("XMPP_DOMAIN"))
        .setHost(requireEnv("XMPP_SERVER_HOST"))
        .setUsernameAndPassword(
            requireEnv("XMPP_ACCOUNT_USERNAME"),
            requireEnv("XMPP_ACCOUNT_PASSWORD")
        )
        .setProxyInfo(ProxyInfo.forSocks5Proxy("127.0.0.1", 2080, "", ""))
        .build()
    val omemoStore = SignalCachingOmemoStore(
        SignalFileBasedOmemoStore(File("./omemo-store").also { it.mkdirs() })
    )

    val xmppClient = XMPPChatClient(config, omemoStore)
    xmppClient.start()

    logger.info("Bot device id: " + xmppClient.ourDevice.deviceId)
    logger.info("Bot key fingerprint: " + xmppClient.ourFingerprint.blocksOf8Chars())

    val jid = JidCreate.from("skyblond@xmpp.skyblond.info").asEntityBareJidOrThrow()

    runBlocking {
        xmppClient.sendTextMessage(jid, "I'm online", true)

        for (message in xmppClient.incomingMessageChannel) {
            if (message.stanza.from == null) {
                logger.warn("Skip message with unknown from")
                continue
            }
            if (message.stanza.from == xmppClient.ourBareJid) {
                logger.warn("Skip our own message")
                continue
            }

            logger.info("[${message.type}]${message.stanza.from}(${message.stanza.stanzaId}): ${message.content}")
            xmppClient.sendTextMessage(
                message.stanza.from.asEntityBareJidOrThrow(),
                "Got it: ${message.content}",
                message.encrypted
            )
        }
    }

    xmppClient.close()
}
