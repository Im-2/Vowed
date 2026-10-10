package app.vowed.wallet

import android.net.Uri
import app.vowed.core.Base58
import app.vowed.data.Prefs
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.solana.mobilewalletadapter.clientlib.ConnectionIdentity
import com.solana.mobilewalletadapter.clientlib.MobileWalletAdapter
import com.solana.mobilewalletadapter.clientlib.TransactionResult
import com.solana.mobilewalletadapter.clientlib.protocol.JsonRpc20Client
import com.solana.mobilewalletadapter.common.signin.SignInWithSolana

/** The wallet said no, was not installed, or the connection failed. The message is safe to show; [raw] is the technical text for "Details". */
class WalletException(message: String, val kind: WalletErrors.Kind = WalletErrors.Kind.Other, val code: Int? = null, val raw: String? = null) : Exception(message)

class SignInOutcome(val address: String, val signedMessage: ByteArray, val signature: ByteArray)

/** How the sign-in uses the wallet. */
enum class SignInMode {
    /** one wallet session: connect, then ask for the signature in the same session (works with the Mock wallet) */
    SingleSession,

    /** two fresh wallet sessions: connect, return to Vowed, then a second session for the signature (for wallets that end the session after connecting) */
    SplitSessions,
}

/**
 * Everything that touches the user's wallet goes through Mobile Wallet Adapter (MWA). The app never holds a private key:
 * signing a message or a transaction always happens in the wallet app, after the user approves it there.
 * The identity below is what the wallet shows to the user ("Vowed wants to connect") and what it may verify: the identity URI is a host we
 * control (the backend), which publishes /.well-known/assetlinks.json and the icon (see backend/src/http/identity.ts).
 *
 * [bringToFront] is a best-effort "bring Vowed back to the screen" used when a wallet does not return by itself; it returns a note for the trace.
 */
class WalletManager(
    private val prefs: Prefs,
    identityUri: String = "https://vowed-backend.onrender.com",
    private val gate: WalletSessionGate = WalletSessionGate(),
    private val bringToFront: () -> String = { "not available" },
    private val trace: () -> WalletTrace = { WalletDiagnostics.current },
) {
    /**
     * The time the library waits for the wallet to answer one request, in milliseconds. It is 120 s instead of the 90 s default on purpose: a
     * wallet's Connect button can stay disabled for a few seconds, a first wallet start can take up to a minute, and a new user reads the sheet
     * before approving. (The library also has fixed waits of 20 s to launch the wallet and 10 s to connect to it; those are not configurable.)
     */
    private val adapter = MobileWalletAdapter(
        connectionIdentity = ConnectionIdentity(
            identityUri = Uri.parse(identityUri),
            iconUri = Uri.parse("icon.png"),
            identityName = "Vowed",
        ),
        timeout = WALLET_REQUEST_TIMEOUT_MS,
    ).also { it.authToken = prefs.mwaAuthToken } // default blockchain is Solana devnet

    /** The MWA error code the wallet answered with (for example -7, cluster not supported), looking through wrapped exceptions. */
    private fun remoteCode(e: Throwable?): Int? {
        var t = e
        var depth = 0
        while (t != null && depth++ < 6) {
            if (t is JsonRpc20Client.JsonRpc20RemoteException) return t.code
            t = t.cause
        }
        return null
    }

    private fun <T> TransactionResult<T>.unwrap(step: String): T = when (this) {
        is TransactionResult.Success -> payload.also { prefs.mwaAuthToken = adapter.authToken; trace().add("$step: ok") }
        is TransactionResult.NoWalletFound -> {
            trace().add("$step: no wallet app found")
            throw WalletException("No Solana wallet app was found on this phone. Install a Mobile Wallet Adapter wallet first.")
        }
        // The library reports declined requests, rejected transactions and a missing wallet with similar wording; WalletErrors picks the plain sentence.
        is TransactionResult.Failure -> {
            val code = remoteCode(e)
            val text = WalletErrors.flatten(e)
            val kind = WalletErrors.classify("$message | $text", code)
            trace().add("$step: failed", "kind=$kind${if (code != null) ", wallet error code $code" else ""}; $text")
            throw WalletException(
                when (kind) {
                    WalletErrors.Kind.PossibleMismatch -> WalletErrors.POSSIBLE_MISMATCH
                    WalletErrors.Kind.SessionClosed -> WalletErrors.SESSION_CLOSED
                    else -> WalletErrors.explain(message)
                },
                kind, code, "$message | $text",
            )
        }
    }

    /** One wallet session at a time, with a short gap and one retry of a failed connection step (see [WalletSessionGate]). */
    private suspend fun <T> sequential(attempt: suspend () -> TransactionResult<T>): TransactionResult<T> =
        gate.run({ r: TransactionResult<T> -> r is TransactionResult.Failure && WalletSessionGate.isAssociationFailure(r.message) }, attempt)

    private fun buildMessage(n: app.vowed.data.NonceResponse, addressBytes: ByteArray): ByteArray {
        val payload = SignInWithSolana.Payload(n.domain, addressBytes, n.statement, Uri.parse(n.uri), "1", null, n.nonce, n.issuedAt, n.expirationTime, null, null, null)
        return payload.prepareMessage(addressBytes).encodeToByteArray()
    }

    /**
     * Connects to the wallet and has it sign the Sign-In-With-Solana message. In [SignInMode.SingleSession] both happen in one wallet session; in
     * [SignInMode.SplitSessions] the connection is one session and the signature a second, fresh one, with [waitForUser] in between (it returns when
     * Vowed is on screen again, so the second wallet screen is opened from the foreground). The backend's nonce was fetched before the wallet opened
     * (see SignInNonce); [fetchNonce] only runs for an older server.
     */
    suspend fun connectAndSignIn(
        sender: ActivityResultSender,
        nonce: app.vowed.data.SignInNonce,
        fetchNonce: suspend (String) -> app.vowed.data.NonceResponse,
        mode: SignInMode,
        waitForUser: suspend () -> Unit,
    ): SignInOutcome {
        // "Connect wallet" is a new connection: forget any earlier authorization (it may belong to another wallet) so the wallet asks, not reauthorizes
        adapter.authToken = null
        val t = trace()
        t.add("association started", "mode=$mode, chain=${adapter.blockchain.fullName}")
        return when (mode) {
            SignInMode.SingleSession -> sequential {
                t.add("session 1 opened", "authorize, then sign_messages in the same session")
                adapter.transact(sender) { auth ->
                    val addressBytes = auth.accounts.first().publicKey
                    val address = Base58.encode(addressBytes)
                    t.add("authorize result", "accounts=${auth.accounts.size}, address=$address")
                    val n = nonce.forWallet(address, fetchNonce)
                    val message = buildMessage(n, addressBytes)
                    t.add("request sent", "sign_messages (1 message) in the same session")
                    val signature = signMessagesDetached(arrayOf(message), arrayOf(addressBytes)).messages.first().signatures.first()
                    t.add("sign_messages result", "1 signature received")
                    SignInOutcome(address, message, signature)
                }
            }.unwrap("session 1")

            SignInMode.SplitSessions -> {
                val addressBytes = sequential {
                    t.add("session 1 opened", "authorize only")
                    adapter.transact(sender) { auth ->
                        t.add("authorize result", "accounts=${auth.accounts.size}, address=${Base58.encode(auth.accounts.first().publicKey)}")
                        auth.accounts.first().publicKey
                    }
                }.unwrap("session 1")
                val address = Base58.encode(addressBytes)
                t.add("session 1 ended", "waiting for Vowed to be on screen before opening the wallet again")
                waitForUser()
                val n = nonce.forWallet(address, fetchNonce)
                val message = buildMessage(n, addressBytes)
                t.add("session 2 opened", "sign_messages only, in a fresh session")
                val signature = signMessage(sender, address, message)
                SignInOutcome(address, message, signature)
            }
        }
    }

    /** Detached ed25519 signature of [message] by the connected account (its own wallet session). */
    suspend fun signMessage(sender: ActivityResultSender, wallet: String, message: ByteArray): ByteArray {
        val addr = Base58.decode(wallet)
        val result = sequential { adapter.transact(sender) { signMessagesDetached(arrayOf(message), arrayOf(addr)) } }
        // a wallet that does not know the saved authorization says so; then it is asked again from scratch, once
        if (result is TransactionResult.Failure && result.message.contains("auth token", ignoreCase = true)) {
            trace().add("session 2", "the wallet did not accept the saved authorization; asking again")
            adapter.authToken = null
            return sequential { adapter.transact(sender) { signMessagesDetached(arrayOf(message), arrayOf(addr)) } }.unwrap("session 2 (second try)").messages.first().signatures.first()
        }
        return result.unwrap("session 2").messages.first().signatures.first()
    }

    /** Signs and submits [tx] (an unsigned legacy transaction). Returns the 64-byte transaction signature. */
    suspend fun signAndSend(sender: ActivityResultSender, tx: ByteArray): ByteArray =
        sequential { adapter.transact(sender) { signAndSendTransactions(arrayOf(tx)) } }.unwrap("sign and send").signatures.first()

    /** Drops the saved wallet authorization (the next connect asks the wallet again). */
    fun forgetAuthorization() {
        adapter.authToken = null
        prefs.mwaAuthToken = null
    }

    /** Best-effort: brings Vowed back in front when a wallet leaves its own screen up. Returns what happened, for the trace. */
    fun tryBringToFront(): String = bringToFront()

    suspend fun disconnect(sender: ActivityResultSender) {
        runCatching { adapter.disconnect(sender) }
        prefs.mwaAuthToken = null
    }

    companion object {
        const val WALLET_REQUEST_TIMEOUT_MS = 120_000
    }
}
