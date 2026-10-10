package app.vowed.data

import android.util.Base64
import app.vowed.AppContainer
import app.vowed.wallet.AppForeground
import app.vowed.wallet.SessionRetry
import app.vowed.wallet.SignInMode
import app.vowed.wallet.WalletDiagnostics
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender

/** Progress shown while connecting: each step is plain language. */
enum class ConnectStep { Wallet, ReturnToVowed, SigningIn, RegisteringDevice, Done }

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
        val trace = WalletDiagnostics.current
        if (AppForeground.isResumed) return
        trace.add("waiting for Vowed to be on screen", "bring to front: ${c.wallet.tryBringToFront()}")
        if (AppForeground.awaitResumed(1_500)) { trace.add("Vowed is on screen"); return }
        onStep(ConnectStep.ReturnToVowed)
        val back = AppForeground.awaitResumed(90_000)
        trace.add(if (back) "Vowed is on screen again" else "gave up waiting for Vowed to come back (90 s)")
        onStep(ConnectStep.SigningIn)
    }

    suspend fun connect(sender: ActivityResultSender, onStep: (ConnectStep) -> Unit = {}): Account {
        val trace = WalletDiagnostics.begin()
        trace.add("connect started", "foreground=${AppForeground.isResumed}, saved preference: ${if (c.prefs.walletSplitSession) "two sessions" else "one session"}")
        onStep(ConnectStep.Wallet)
        // Ask for the nonce NOW, while Vowed is on screen: once the wallet opens, Vowed is in the background and Android (Data Saver, battery
        // limits) may block its network, which showed up as "Unable to resolve host". Nothing is sent while the wallet is in front.
        val nonce = SignInNonce.prepare { c.api.openNonce() }
        trace.add("sign-in nonce", if (nonce.wasPrefetched) "fetched before the wallet opened" else "not available; will be asked after the wallet answers (older server)")
        val fetch: suspend (String) -> NonceResponse = { address -> onStep(ConnectStep.SigningIn); c.api.nonce(address) }
        val wait: suspend () -> Unit = { waitForVowed(onStep) }
        val first = if (c.prefs.walletSplitSession) SignInMode.SplitSessions else SignInMode.SingleSession
        val out = try {
            if (first == SignInMode.SplitSessions) {
                c.wallet.connectAndSignIn(sender, nonce, fetch, SignInMode.SplitSessions, wait)
            } else {
                // One automatic retry after "the wallet closed the session", in fresh sessions, and the app remembers it for next time (no loops)
                SessionRetry.run(
                    trace,
                    first = { c.wallet.connectAndSignIn(sender, nonce, fetch, SignInMode.SingleSession, wait) },
                    retry = {
                        c.prefs.walletSplitSession = true
                        waitForVowed(onStep)
                        c.wallet.connectAndSignIn(sender, nonce, fetch, SignInMode.SplitSessions, wait)
                    },
                )
            }
        } finally {
            // the wallet may have left the app behind its own screen: network calls below must wait until Vowed is in front
            waitForVowed(onStep)
        }
        onStep(ConnectStep.SigningIn)
        val res = try {
            c.api.verify(
                VerifyRequest(
                    wallet = out.address,
                    message = Base64.encodeToString(out.signedMessage, Base64.NO_WRAP),
                    signature = Base64.encodeToString(out.signature, Base64.NO_WRAP),
                ),
            )
        } catch (e: ApiException) {
            trace.add("verify failed", "HTTP ${e.status}, ${e.code}")
            if (nonce.wasPrefetched && e.status == 401) throw ApiException(401, "sign_in_expired", "Signing in took too long, so the request expired. Tap Connect wallet and approve again.", e.message)
            throw e
        }
        trace.add("verified by the server")
        c.sessions.adopt(res)
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

    /**
     * The server forgot this phone (after an update that wiped its data). Registers the phone again: with the proof key it already has, so that
     * existing stakes stay valid (without hardware attestation, because a fresh server challenge cannot be added to an old key; the trust cap is
     * then the lowest), or with a new attested key when there is none.
     */
    suspend fun reRegisterDevice(sender: ActivityResultSender) {
        val wallet = c.prefs.wallet ?: error("no wallet")
        val key = c.deviceKey(wallet)
        if (!key.exists) { registerDevice(sender, wallet); return }
        val ch = c.api.deviceChallenge()
        val message = "Vowed device registration\nwallet: $wallet\ndevice: ${key.id()}\nchallenge: ${ch.challenge}".encodeToByteArray()
        val sig = c.wallet.signMessage(sender, wallet, message)
        val reg = c.api.registerDevice(
            DeviceRegisterRequest(
                devicePublicKey = Base64.encodeToString(key.publicKeySpki(), Base64.NO_WRAP),
                attestationChain = null,
                challenge = ch.challenge,
                walletSignature = Base64.encodeToString(sig, Base64.NO_WRAP),
            ),
        )
        c.prefs.setRegisteredDevice(wallet, reg.deviceId)
        c.prefs.setDeviceTrustCap(wallet, reg.trustCap)
    }

    suspend fun disconnect(sender: ActivityResultSender) {
        c.wallet.disconnect(sender)
        c.sessions.forget()
        c.prefs.wallet?.let { c.deviceKey(it).delete() }
        c.prefs.clearAccount()
        c.prefs.onboarded = false
    }
}
