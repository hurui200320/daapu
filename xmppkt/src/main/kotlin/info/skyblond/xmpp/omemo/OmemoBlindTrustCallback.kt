package info.skyblond.xmpp.omemo

import org.jivesoftware.smackx.omemo.internal.OmemoDevice
import org.jivesoftware.smackx.omemo.trust.OmemoFingerprint
import org.jivesoftware.smackx.omemo.trust.OmemoTrustCallback
import org.jivesoftware.smackx.omemo.trust.TrustState

/**
 * Blindly trust new devices, we're bot.
 * */
class OmemoBlindTrustCallback : OmemoTrustCallback {
    override fun getTrust(device: OmemoDevice, fingerprint: OmemoFingerprint): TrustState {
        return TrustState.trusted
    }

    override fun setTrust(
        device: OmemoDevice, fingerprint: OmemoFingerprint, state: TrustState
    ) {
        // nop, we're blindly trust
    }
}