package app.vowed.data

import android.util.Base64
import app.vowed.AppContainer
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender

/** Progress shown while connecting: each step is plain language. */
enum class ConnectStep { Wallet, SigningIn, RegisteringDevice, Done }

class Account(val wallet: String, val deviceId: String, val trustCap: String)

/**
 * Wallet connection, backend sign-in and the phone's proof key, in that order:
 *  1. the wallet connects and signs a sign-in message (proves the user controls the address; no funds are touched);
 *  2. the backend verifies it and returns a short-lived token (kept in memory only);
 *  3. a proof key is created in the Keystore and registered, so that a stake can be bound to this phone.
 */
class AccountRepository(private val c: AppContainer) {
    val current: Account?
        get() {
            val w = c.prefs.wallet ?: return null
            val d = c.prefs.registeredDevice(w) ?: return null
            return Account(w, d, c.prefs.deviceTrustCap(w) ?: "low")
        }

    val signedIn: Boolean get() = c.api.token != null

    suspend fun connect(sender: ActivityResultSender, onStep: (ConnectStep) -> Unit = {}): Account {
        onStep(ConnectStep.Wallet)
        val out = c.wallet.connectAndSignIn(sender) { address -> onStep(ConnectStep.SigningIn); c.api.nonce(address) }
        val res = c.api.verify(
            VerifyRequest(
                wallet = out.address,
                message = Base64.encodeToString(out.signedMessage, Base64.NO_WRAP),
                signature = Base64.encodeToString(out.signature, Base64.NO_WRAP),
            ),
        )
        c.api.token = res.token
        val previous = c.prefs.wallet
        if (previous != null && previous != res.wallet) c.prefs.clearAccount() // a different wallet: forget the old account's local state
        c.prefs.wallet = res.wallet
        if (c.prefs.registeredDevice(res.wallet) == null || !c.deviceKey(res.wallet).exists) {
            onStep(ConnectStep.RegisteringDevice)
            registerDevice(sender, res.wallet)
        }
        onStep(ConnectStep.Done)
        return current!!
    }

    /** Called when the app starts with a saved wallet but no backend token (the token is not stored). */
    suspend fun resume(sender: ActivityResultSender): Account = connect(sender)

    /**
     * Creates the proof key with the server's challenge and registers it. A device that cannot attest (an emulator, for instance)
     * is accepted only when the backend allows it, and only with the lowest trust cap; the user is told.
     */
    private suspend fun registerDevice(sender: ActivityResultSender, wallet: String) {
        val key = c.deviceKey(wallet)
        suspend fun attempt(withAttestation: Boolean): DeviceRegisterResponse {
            val ch = c.api.deviceChallenge()
            val challengeBytes = Base64.decode(ch.challenge, Base64.DEFAULT)
            val attested = key.generate(if (withAttestation) challengeBytes else null)
            val deviceId = key.id()
            val message = "Vowed device registration\nwallet: $wallet\ndevice: $deviceId\nchallenge: ${ch.challenge}".encodeToByteArray()
            val sig = c.wallet.signMessage(sender, wallet, message)
            return c.api.registerDevice(
                DeviceRegisterRequest(
                    devicePublicKey = Base64.encodeToString(key.publicKeySpki(), Base64.NO_WRAP),
                    attestationChain = if (attested) key.certificateChain().map { Base64.encodeToString(it, Base64.NO_WRAP) } else null,
                    challenge = ch.challenge,
                    walletSignature = Base64.encodeToString(sig, Base64.NO_WRAP),
                ),
            )
        }
        val reg = try {
            attempt(withAttestation = true)
        } catch (e: ApiException) {
            // The backend refused the hardware attestation. In development it will still take a key without one (lowest trust).
            if (e.code != "attestation_failed") throw e
            attempt(withAttestation = false)
        }
        c.prefs.setRegisteredDevice(wallet, reg.deviceId)
        c.prefs.setDeviceTrustCap(wallet, reg.trustCap)
    }

    suspend fun disconnect(sender: ActivityResultSender) {
        c.wallet.disconnect(sender)
        c.api.token = null
        c.prefs.wallet?.let { c.deviceKey(it).delete() }
        c.prefs.clearAccount()
        c.prefs.onboarded = false
    }
}
