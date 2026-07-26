package info.skyblond.xmpp

import info.skyblond.xmpp.chat.ClientIncomingChatMessageListener
import info.skyblond.xmpp.omemo.ClientOmemoMessageListener
import info.skyblond.xmpp.omemo.ClientOmemoMucMessageListener
import info.skyblond.xmpp.omemo.OmemoBlindTrustCallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withContext
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.jivesoftware.smack.ReconnectionListener
import org.jivesoftware.smack.ReconnectionManager
import org.jivesoftware.smack.ReconnectionManager.ReconnectionPolicy
import org.jivesoftware.smack.chat2.ChatManager
import org.jivesoftware.smack.tcp.XMPPTCPConnection
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration
import org.jivesoftware.smackx.omemo.OmemoManager
import org.jivesoftware.smackx.omemo.OmemoMessage.Sent
import org.jivesoftware.smackx.omemo.OmemoStore
import org.jivesoftware.smackx.omemo.internal.OmemoDevice
import org.jivesoftware.smackx.omemo.signal.SignalCachingOmemoStore
import org.jivesoftware.smackx.omemo.signal.SignalOmemoService
import org.jivesoftware.smackx.omemo.trust.OmemoFingerprint
import org.jivesoftware.smackx.omemo.util.OmemoConstants
import org.jivesoftware.smackx.pep.PepManager
import org.jivesoftware.smackx.pubsub.AccessModel
import org.jxmpp.jid.BareJid
import org.jxmpp.jid.EntityBareJid
import org.slf4j.LoggerFactory
import org.whispersystems.libsignal.IdentityKey
import org.whispersystems.libsignal.IdentityKeyPair
import org.whispersystems.libsignal.SessionCipher
import org.whispersystems.libsignal.SignalProtocolAddress
import org.whispersystems.libsignal.ecc.ECPublicKey
import org.whispersystems.libsignal.state.PreKeyBundle
import org.whispersystems.libsignal.state.PreKeyRecord
import org.whispersystems.libsignal.state.SessionRecord
import org.whispersystems.libsignal.state.SignedPreKeyRecord
import java.security.Security

/**
 * XMPP Chat client.
 *
 * The current impl use blind trust model for OMEMO sessions. It's a bot, it can't verify fingerprints.
 *
 * [omemoStore] the store backend. May not pick up if current omemo service already has a store backend.
 * */
class XMPPChatClient(
    connectionConfig: XMPPTCPConnectionConfiguration,
    omemoStore: OmemoStore<IdentityKeyPair, IdentityKey, PreKeyRecord, SignedPreKeyRecord,
            SessionRecord, SignalProtocolAddress, ECPublicKey, PreKeyBundle, SessionCipher>,
) : AutoCloseable {

    // OMEMO service is a shared singleton
    private val omemoService: SignalOmemoService =
        SignalOmemoService.getInstance() as SignalOmemoService

    // these objects should be reused
    private val xmppConnection = XMPPTCPConnection(connectionConfig)
    private val reconnectionManager = ReconnectionManager.getInstanceFor(xmppConnection)
    private val pepManager = PepManager.getInstanceFor(xmppConnection)
    private val chatManager = ChatManager.getInstanceFor(xmppConnection)
    // TODO: MUC?
    // TODO: some queue for incoming messages? How do dedup plain text chat and OMEMO message?
    //       Both plain text message and OMEMO message has the same stanza id

    private val _incomingMessageChannel = Channel<ChatMessage>(Channel.UNLIMITED)
    val incomingMessageChannel: ReceiveChannel<ChatMessage> = _incomingMessageChannel

    // OMEMO manager must be initialized after the setting up OMEMO service
    private val omemoManager: OmemoManager

    init {
        // must set up store backend before creating OMEMO manager,
        // otherwise it will create a default store
        try {
            omemoService.setOmemoStoreBackend(
                SignalCachingOmemoStore(omemoStore)
            )
        } catch (ex: IllegalStateException) {
            logger.warn(
                "Failed to set OMEMO store backend. " +
                        "Since the OMEMO service is shared, the store backend might be already set",
                ex
            )
        }
        // enable reconnection
        reconnectionManager.setReconnectionPolicy(ReconnectionPolicy.FIXED_DELAY)
        reconnectionManager.setFixedDelay(10) // 10s
        reconnectionManager.addReconnectionListener(object : ReconnectionListener {
            override fun reconnectingIn(seconds: Int) {
                logger.info("Reconnecting in $seconds second(s)...")
            }

            override fun reconnectionFailed(e: Exception?) {
                logger.warn("Reconnecting in failed!", e)
                if (xmppConnection.isConnected) {
                    logger.error("Reconnecting failed but connection report connected, corrupted connection!")
                }
            }
        })
        reconnectionManager.enableAutomaticReconnection()
        // set up OMEMO manager
        omemoManager = OmemoManager.getInstanceFor(xmppConnection)
        omemoManager.setTrustCallback(OmemoBlindTrustCallback())
        omemoManager.addOmemoMessageListener(ClientOmemoMessageListener(_incomingMessageChannel))
        omemoManager.addOmemoMucMessageListener(
            ClientOmemoMucMessageListener(
                _incomingMessageChannel
            )
        )
        // set up plain (unencrypted chat manager)
        chatManager.addIncomingListener(ClientIncomingChatMessageListener(_incomingMessageChannel))
    }

    fun start() {
        // initialize OMEMO manager for the connection
        xmppConnection.connect().login()
        omemoManager.initialize()
        // ensure PEP access model is open, otherwise other client cannot get our device id
        val pm = pepManager.pepPubSubManager
        val nodes = arrayOf(
            OmemoConstants.PEP_NODE_DEVICE_LIST,
            OmemoConstants.PEP_NODE_BUNDLE_FROM_DEVICE_ID(omemoManager.deviceId)
        )
        for (node in nodes) {
            try {
                val leaf = pm.getLeafNode(node)
                val cfg = leaf.nodeConfiguration
                val form = cfg.fillableForm
                form.accessModel = AccessModel.open
            } catch (ex: Throwable) {
                logger.warn("Failed to set access model to open for node $node", ex)
            }
        }
    }

    override fun close() {
        reconnectionManager.disableAutomaticReconnection()
        xmppConnection.disconnect()
        _incomingMessageChannel.close(Exception("XMPP Chat Client closing"))
    }

    val ourDevice: OmemoDevice
        get() = omemoManager.ownDevice

    val ourFingerprint: OmemoFingerprint
        get() = omemoManager.ownFingerprint

    val ourBareJid: BareJid
        get() = omemoManager.ownJid

    // TODO: what about non-text content? File upload extension?
    suspend fun sendTextMessage(
        jid: EntityBareJid,
        text: String,
        forceEncrypted: Boolean = false
    ): Unit = withContext(
        Dispatchers.IO
    ) {
        val targetSupportOmemo = omemoManager.contactSupportsOmemo(jid)
        if (targetSupportOmemo || forceEncrypted) {
            if (!targetSupportOmemo) error("Contact doesn't support OMEMO but encryption is enforced")
            omemoManager.requestDeviceListUpdateFor(jid)
            val mb = xmppConnection.stanzaFactory.buildMessageStanza()
            val encrypted: Sent = omemoManager.encrypt(jid, text)
            xmppConnection.sendStanza(encrypted.buildMessage(mb, jid))
        } else {
            chatManager.chatWith(jid).send(text)
        }
    }

    // TODO: reply to a message?
    // TODO: receive and send emoji reaction?
    // TODO: send message to MUC?

    companion object {
        private val logger = LoggerFactory.getLogger(XMPPChatClient::class.java)

        init {
            // Required fix for modern JDK
            // Since JDK 21, SunJCE's AES/GCM/NoPadding requires GCMParameterSpec instead of IvParameterSpec.
            // Smack's OmemoAesCipher still uses IvParameterSpec.
            // Smack's test suite add BC as a JCE provider, so we do the same.
            Security.insertProviderAt(BouncyCastleProvider(), 1)
            // confirm license and perform set up
            SignalOmemoService.acknowledgeLicense()
            SignalOmemoService.setup()
            // Disable stream management resumption due to a reconnect bug with TLS.
            // Sometimes when connect or reconnect, it will throw NoResponseException with
            // timeout when establishing TLS. However, in connectInternal() (XMPPTCPConnection.java:860),
            // `connected = true` is set at line 865, BEFORE TLS is established.
            // Then TLS failed and we get this NoResponseException and AbstractXMPPConnection.connect()'s
            // catch block (AbstractXMPPConnection.java:556) calls instantShutdown() and rethrows.
            // The shutdown(true) at line 539 checks `disconnectedButResumeable`, but this is still true
            // because initState() (called by connect()) does NOT reset `disconnectedButResumeable`.
            // So shutdown(true) early-returns without setting connected = false.
            // From this point, the connection appears to be connected, but actually not.
            // So the connection object itself is corrupted and cannot recover.
            // To work around this, we disable UseStreamManagementResumptionDefault,
            // so that each time we re-connect, we must connect, login and re-pulling the states we need.
            // As a side effect, it also skips the branch that set `disconnectedButResumeable` to true,
            // preventing a connection failed but report connected.
            XMPPTCPConnection.setUseStreamManagementResumptionDefault(false)
        }
    }
}