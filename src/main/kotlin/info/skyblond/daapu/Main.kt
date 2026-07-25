package info.skyblond.daapu

import info.skyblond.xmpp.XMPPChatClient
import kotlinx.coroutines.runBlocking
import org.jivesoftware.smack.proxy.ProxyInfo
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration
import org.jivesoftware.smackx.omemo.signal.SignalCachingOmemoStore
import org.jivesoftware.smackx.omemo.signal.SignalFileBasedOmemoStore
import org.jxmpp.jid.impl.JidCreate
import java.io.File


fun main() {
    val config = XMPPTCPConnectionConfiguration.builder()
        .setXmppDomain(readEnv("XMPP_DOMAIN"))
        .setHost(readEnv("XMPP_SERVER_HOST"))
        .setUsernameAndPassword(readEnv("XMPP_ACCOUNT_USERNAME"), readEnv("XMPP_ACCOUNT_PASSWORD"))
        .setProxyInfo(ProxyInfo.forSocks5Proxy("127.0.0.1", 2080, "", ""))
        .build()
    val omemoStore = SignalCachingOmemoStore(
        SignalFileBasedOmemoStore(File("./omemo-store").also { it.mkdirs() })
    )

    val xmppClient = XMPPChatClient(config, omemoStore, 200)
    xmppClient.start()

    println(xmppClient.ourDevice.deviceId)
    println(xmppClient.ourFingerprint.blocksOf8Chars())

    val jid = JidCreate.from("skyblond@xmpp.skyblond.info").asEntityBareJidOrThrow()
    xmppClient.sendTextMessage(jid, "I'm online", true)

    runBlocking {
        for (message in xmppClient.incomingMessageChannel) {
            println("[${message.type}]${message.stanza.from}(${message.stanza.stanzaId}): ${message.content}")
        }
    }
}