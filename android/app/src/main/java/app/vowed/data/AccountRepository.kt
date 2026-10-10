package app.vowed.data

import android.util.Base64
import app.vowed.AppContainer
import app.vowed.wallet.AppForeground
import app.vowed.wallet.WalletDiagnostics
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender

/** Progress shown while connecting: each step is plain language. */
enum class ConnectStep { Server, Wallet, ReturnToVowed, SigningIn, RegisteringDevice, Done }

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

    /** After an app restart: sign back in from the saved (encrypted) session, without the wallet, while it is still valid. */
    fun restoreSession(): Boolean = c.sessions.restore(c.prefs.wallet)

    /** Quietly swaps a token that is about to run out for a new one. */
    suspend fun refreshSession(): SessionKeeper.Outcome = c.sessions.refreshIfNeeded()

    /** The server said the token is no good. */
    fun sessionExpired() = c.sessions.expire()

    /**
     * "Reset connection": forgets the backend session and the wallet's authorization so the next connect starts clean. The proof key on this
     * phone and the device registration are kept (a stake is bound to that key); the account on the server is not touched.
     */
    fun resetConnection() {
        flow.clear()
        c.sessions.forget()
        c.prefs.mwaAuthToken = null
        c.wallet.forgetAuthorization()
    }

    /**
     * Waits until Vowed is on screen again. Some wallets (Phantom, in the field tests) leave their own screen up when they are done, so the app keeps
     * running behind it; opening the wallet again or calling the server from there can fail (Android refuses background screen starts and may block a
     * background app's network). The app first tries to bring itself forward, then asks the person to return, and waits up to 90 s.
     */
    private suspend fun waitForVowed(onStep: (ConnectStep) -> Unit) {
        AppForeground.ensureOnScreen(WalletDiagnostics.current, { c.wallet.tryBringToFront() }, { onStep(ConnectStep.ReturnToVowed) })
        if (AppForeground.isResumed) onStep(ConnectStep.SigningIn)
    }

    private val flow = SignInFlow(
        fetchNonce = { c.api.openNonce() },
        wallet = { nonce -> senderForFlow!!.let { c.wallet.signIn(it, nonce) } },
        verify = { out ->
            c.api.verify(
                VerifyRequest(
                    wallet = out.address,
                    message = Base64.encodeToString(out.signedMessage, Base64.NO_WRAP),
                    signature = Base64.encodeToString(out.signature, Base64.NO_WRAP),
                ),
            )
        },
        adopt = { res ->
            c.sessions.adopt(res)
            val previous = c.prefs.wallet
            if (previous != null && previous != res.wallet) c.prefs.clearAccount() // a different wallet: forget the old account's local state
            c.prefs.wallet = res.wallet
        },
        registered = { w -> c.prefs.registeredDevice(w) != null && c.deviceKey(w).exists },
        register = { w -> registerDevice(senderForFlow!!, w) },
    )

    @Volatile private var senderForFlow: ActivityResultSender? = null

    /**
     * Connect and sign in. The wallet is opened ONCE (one request, one visit); see [SignInFlow] for what a retry does. A saved session needs no wallet
     * at all ([restoreSession]).
     */
    suspend fun connect(sender: ActivityResultSender, onStep: (ConnectStep) -> Unit = {}): Account {
        val trace = if (flow.hasSavedProgress) WalletDiagnostics.current.also { it.add("retry started", "foreground=${AppForeground.isResumed}") } else WalletDiagnostics.begin().also { it.add("connect started", "foreground=${AppForeground.isResumed}") }
        senderForFlow = sender
        try {
            onStep(ConnectStep.Server)
            // the wallet may have left the app behind its own screen: network calls must wait until Vowed is in front
            waitForVowed(onStep)
            flow.run(trace, onStep)
        } finally {
            senderForFlow = null
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
        val trace = WalletDiagnostics.current
        suspend fun attempt(withAttestation: Boolean, withWalletSignature: Boolean): DeviceRegisterResponse {
            val ch = c.api.deviceChallenge()
            val challengeBytes = Base64.decode(ch.challenge, Base64.DEFAULT)
            val attested = key.generate(if (withAttestation) challengeBytes else null)
            trace.add("phone key", "created in the Keystore, hardware attestation: ${if (attested) "yes" else "no (low trust)"}")
            val deviceId = key.id()
            // The wallet signed in moments ago, which already proves the person controls it: the server accepts the registration without a second wallet screen.
            val sig = if (withWalletSignature) {
                val message = "Vowed device registration\nwallet: $wallet\ndevice: $deviceId\nchallenge: ${ch.challenge}".encodeToByteArray()
                Base64.encodeToString(c.wallet.signMessage(sender, wallet, message), Base64.NO_WRAP)
            } else null
            return c.api.registerDevice(
                DeviceRegisterRequest(
                    devicePublicKey = Base64.encodeToString(key.publicKeySpki(), Base64.NO_WRAP),
                    attestationChain = if (attested) key.certificateChain().map { Base64.encodeToString(it, Base64.NO_WRAP) } else null,
                    challenge = ch.challenge,
                    walletSignature = sig,
                ),
            )
        }
        var withAttestation = true
        var withWallet = false
        val reg = run {
            var last: ApiException? = null
            repeat(3) {
                try {
                    return@run attempt(withAttestation, withWallet)
                } catch (e: ApiException) {
                    last = e
                    trace.add("register phone refused", "HTTP ${e.status}, ${e.code}: ${e.message}")
                    when (e.code) {
                        // The backend refused the hardware attestation. In development it will still take a key without one (lowest trust).
                        "attestation_failed" -> if (withAttestation) withAttestation = false else throw e
                        // The sign-in is older than the server allows for a registration without the wallet: this one needs the wallet's own signature.
                        "wallet_signature_required" -> if (!withWallet) withWallet = true else throw e
                        else -> throw e
                    }
                }
            }
            throw last!!
        }
        c.prefs.setRegisteredDevice(wallet, reg.deviceId)
        c.prefs.setDeviceTrustCap(wallet, reg.trustCap)
    }

    /**
     * The server forgot this phone (after an update that wiped its data). Registers the phone again WITHOUT opening the wallet while the sign-in is fresh: with the proof key it already has, so that
     * existing stakes stay valid (without hardware attestation, because a fresh server challenge cannot be added to an old key; the trust cap is
     * then the lowest), or with a new attested key when there is none.
     */
    suspend fun reRegisterDevice(): Boolean {
        val wallet = c.prefs.wallet ?: error("no wallet")
        val key = c.deviceKey(wallet)
        if (!key.exists) { c.prefs.setRegisteredDevice(wallet, null); return false } // a new key needs the full sign-in again
        val ch = c.api.deviceChallenge()
        val reg = try {
            c.api.registerDevice(DeviceRegisterRequest(devicePublicKey = Base64.encodeToString(key.publicKeySpki(), Base64.NO_WRAP), attestationChain = null, challenge = ch.challenge))
        } catch (e: ApiException) {
            if (e.code != "wallet_signature_required") throw e
            // The sign-in is too old for a silent registration. The wallet is never opened by itself: the next sign-in (a tap) registers the phone.
            c.prefs.setRegisteredDevice(wallet, null)
            c.sessions.expire()
            return false
        }
        c.prefs.setRegisteredDevice(wallet, reg.deviceId)
        c.prefs.setDeviceTrustCap(wallet, reg.trustCap)
        return true
    }

    suspend fun disconnect(sender: ActivityResultSender) {
        c.wallet.disconnect(sender)
        c.sessions.forget()
        c.prefs.wallet?.let { c.deviceKey(it).delete() }
        c.prefs.clearAccount()
        c.prefs.onboarded = false
    }
}
