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

/**
 * What the wallet returned for the sign-in. [viaPayload] is true when the wallet signed the sign-in text as part of connecting (ONE prompt) and
 * false when it ignored the payload and Vowed had to ask for a signature in the same session. [validUntilMs] is when the server's nonce runs out.
 */
class SignInOutcome(val address: String, val signedMessage: ByteArray, val signature: ByteArray, val viaPayload: Boolean = true, val validUntilMs: Long = Long.MAX_VALUE)

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
                    WalletErrors.Kind.AuthorizationRefused -> WalletErrors.AUTHORIZATION_REFUSED
                    WalletErrors.Kind.NetworkMismatch -> WalletErrors.WRONG_NETWORK
                    WalletErrors.Kind.SessionClosed -> WalletErrors.SESSION_CLOSED
                    else -> WalletErrors.explain(message)
                },
                kind, code, "$message | $text",
            )
        }
    }

    /** How many times the wallet was launched since the app started (every Mobile Wallet Adapter session counts once). Read by tests and the diagnostics. */
    val launches = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * One wallet session at a time, with a short gap (see [WalletSessionGate]). [retryConnection] allows ONE quiet retry when the wallet closed before
     * it connected; the sign-in turns it off so that one tap never launches the wallet twice.
     */
    private suspend fun <T> sequential(retryConnection: Boolean = true, attempt: suspend () -> TransactionResult<T>): TransactionResult<T> =
        gate.run({ r: TransactionResult<T> -> retryConnection && r is TransactionResult.Failure && WalletSessionGate.isAssociationFailure(r.message) }) {
            launches.incrementAndGet()
            attempt()
        }

    private fun payloadFor(n: app.vowed.data.NonceResponse, address: ByteArray?): SignInWithSolana.Payload =
        SignInWithSolana.Payload(n.domain, address, n.statement, Uri.parse(n.uri), "1", null, n.nonce, n.issuedAt, n.expirationTime, null, null, null)

    /** The time the server's nonce stops being accepted (the expiry in the payload), with a fallback for a text that cannot be read. */
    private fun validUntil(n: app.vowed.data.NonceResponse): Long =
        runCatching { java.time.Instant.parse(n.expirationTime).toEpochMilli() }.getOrDefault(System.currentTimeMillis() + 8 * 60_000)

    /**
     * Connect AND sign in with ONE wallet request and ONE visit: the official Sign-In-With-Solana payload goes with the authorize request
     * (`transact(sender, payload)`), so a wallet that supports it shows a single sign-in sheet and returns the signed text with the connection.
     * A wallet that ignores the payload (no sign-in result comes back) is asked for the signature in the SAME session, so it is still one visit,
     * never a second one. The server's nonce must already be in hand: nothing needs the network while the wallet is in front.
     */
    suspend fun signIn(sender: ActivityResultSender, nonce: app.vowed.data.NonceResponse): SignInOutcome {
        // "Connect wallet" is a new connection: forget any earlier authorization (it may belong to another wallet) so the wallet asks, not reauthorizes
        adapter.authToken = null
        val t = trace()
        t.add("wallet visit started", "one request: authorize with a sign-in payload (domain ${nonce.domain}), chain=${adapter.blockchain.fullName}")
        return sequential(retryConnection = false) {
            adapter.transact(sender, payloadFor(nonce, null as ByteArray?)) { auth ->
                val addressBytes = auth.accounts.first().publicKey
                val address = Base58.encode(addressBytes)
                val sir = auth.signInResult
                t.add("authorize result", "accounts=${auth.accounts.size}, address=$address, sign-in result from the wallet: ${if (sir != null) "yes (signature ${sir.signature.size} bytes, message ${sir.signedMessage.size} bytes)" else "NO (the wallet ignored the payload)"}")
                if (sir != null) {
                    SignInOutcome(address, sir.signedMessage, sir.signature, viaPayload = true, validUntilMs = validUntil(nonce))
                } else {
                    val message = payloadFor(nonce, addressBytes).prepareMessage(addressBytes).encodeToByteArray()
                    t.add("request sent", "sign_messages in the same session (the wallet ignored the sign-in payload)")
                    val signature = signMessagesDetached(arrayOf(message), arrayOf(addressBytes)).messages.first().signatures.first()
                    t.add("sign_messages result", "1 signature received")
                    SignInOutcome(address, message, signature, viaPayload = false, validUntilMs = validUntil(nonce))
                }
            }
        }.unwrap("wallet visit")
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

    /**
     * Signs and submits [tx] (an unsigned legacy transaction) with `sign_and_send_transactions`, in its own fresh wallet session (the library
     * reauthorizes with the saved authorization first). Returns the 64-byte transaction signature.
     *
     * Like the sign-in: at most ONE retry, in another fresh session, after the wallet closed the session; the wait for the wallet answer is bounded
     * (the library waits up to [WALLET_REQUEST_TIMEOUT_MS] for a request, so the whole attempt is allowed [WALLET_ATTEMPT_TIMEOUT_MS]) and a wallet
     * that never answers ends in [WalletErrors.Kind.NoResponse] instead of waiting for ever. [kind] and [simulation] only describe the transaction in
     * the diagnostics (no keys, no signatures).
     */
    suspend fun signAndSend(sender: ActivityResultSender, tx: ByteArray, kind: String = "transaction", simulation: String? = null, waitForUser: suspend () -> Unit = {}): ByteArray {
        val t = trace()
        t.add("transaction", "kind=$kind, ${tx.size} bytes, wallet method sign_and_send_transactions, server simulation: ${simulation ?: "not reported"}")
        suspend fun attempt(n: Int): ByteArray {
            t.add("session $n opened", "saved authorization: ${if (adapter.authToken != null) "yes (reauthorize)" else "no (authorize)"}")
            val result = kotlinx.coroutines.withTimeoutOrNull(WALLET_ATTEMPT_TIMEOUT_MS) {
                sequential {
                    adapter.transact(sender) { auth ->
                        t.add("authorized", "accounts=${auth.accounts.size}, address=${Base58.encode(auth.accounts.first().publicKey)}")
                        t.add("request sent", "sign_and_send_transactions (1 transaction)")
                        signAndSendTransactions(arrayOf(tx))
                    }
                }
            }
            if (result == null) {
                t.add("no answer", "the wallet did not answer within ${WALLET_ATTEMPT_TIMEOUT_MS / 1000} s")
                throw WalletException(WalletErrors.NO_RESPONSE, WalletErrors.Kind.NoResponse, raw = "no answer from the wallet within ${WALLET_ATTEMPT_TIMEOUT_MS / 1000} s")
            }
            return result.unwrap("session $n").signatures.first()
        }
        return SessionRetry.run(t, first = { attempt(1) }, retry = { waitForUser(); attempt(2) })
    }

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

        /** one whole wallet attempt: launching the wallet (20 s), connecting to it (10 s) and one request (120 s), plus a little room */
        const val WALLET_ATTEMPT_TIMEOUT_MS = 160_000L
    }
}
