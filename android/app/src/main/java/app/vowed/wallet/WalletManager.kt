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

/** The wallet said no, was not installed, or the connection failed. The message is safe to show. */
class WalletException(message: String, val kind: WalletErrors.Kind = WalletErrors.Kind.Other, val code: Int? = null) : Exception(message)

class SignInOutcome(val address: String, val signedMessage: ByteArray, val signature: ByteArray)

/**
 * Everything that touches the user's wallet goes through Mobile Wallet Adapter (MWA). The app never holds a private key:
 * signing a message or a transaction always happens in the wallet app, after the user approves it there.
 * The identity below is what the wallet shows to the user ("Vowed wants to connect").
 */
class WalletManager(private val prefs: Prefs, private val gate: WalletSessionGate = WalletSessionGate()) {
    private val adapter = MobileWalletAdapter(
        connectionIdentity = ConnectionIdentity(
            identityUri = Uri.parse("https://vowed.app"),
            iconUri = Uri.parse("favicon.ico"),
            identityName = "Vowed",
        ),
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

    private fun <T> TransactionResult<T>.unwrap(): T = when (this) {
        is TransactionResult.Success -> payload.also { prefs.mwaAuthToken = adapter.authToken }
        is TransactionResult.NoWalletFound -> throw WalletException("No Solana wallet app was found on this phone. Install a Mobile Wallet Adapter wallet first.")
        // The library reports declined requests, rejected transactions and a missing wallet with similar wording; WalletErrors picks the plain sentence.
        is TransactionResult.Failure -> {
            val code = remoteCode(e)
            val kind = WalletErrors.classify(message, code)
            throw WalletException(if (kind == WalletErrors.Kind.PossibleMismatch) WalletErrors.POSSIBLE_MISMATCH else WalletErrors.explain(message), kind, code)
        }
    }

    /**
     * Connects to the wallet and has it sign the Sign-In-With-Solana message in one wallet session. The backend's nonce is per
     * wallet, so [fetchNonce] is called with the address the wallet just authorized.
     */
    /** One wallet session at a time, with a short gap and one retry of a failed connection step (see [WalletSessionGate]). */
    private suspend fun <T> sequential(attempt: suspend () -> TransactionResult<T>): TransactionResult<T> =
        gate.run({ r: TransactionResult<T> -> r is TransactionResult.Failure && WalletSessionGate.isAssociationFailure(r.message) }, attempt)

    suspend fun connectAndSignIn(sender: ActivityResultSender, nonce: app.vowed.data.SignInNonce, fetchNonce: suspend (String) -> app.vowed.data.NonceResponse): SignInOutcome =
        sequential { adapter.transact(sender) { auth ->
            val addressBytes = auth.accounts.first().publicKey
            val address = Base58.encode(addressBytes)
            // the nonce was fetched before the wallet opened; only an older server makes this ask the network while the wallet is in front
            val n = nonce.forWallet(address, fetchNonce)
            val payload = SignInWithSolana.Payload(n.domain, addressBytes, n.statement, Uri.parse(n.uri), "1", null, n.nonce, n.issuedAt, n.expirationTime, null, null, null)
            val message = payload.prepareMessage(addressBytes).encodeToByteArray()
            val signature = signMessagesDetached(arrayOf(message), arrayOf(addressBytes)).messages.first().signatures.first()
            SignInOutcome(address, message, signature)
        } }.unwrap()

    /** Detached ed25519 signature of [message] by the connected account. */
    suspend fun signMessage(sender: ActivityResultSender, wallet: String, message: ByteArray): ByteArray {
        val addr = Base58.decode(wallet)
        return sequential { adapter.transact(sender) { signMessagesDetached(arrayOf(message), arrayOf(addr)) } }.unwrap()
            .messages.first().signatures.first()
    }

    /** Signs and submits [tx] (an unsigned legacy transaction). Returns the 64-byte transaction signature. */
    suspend fun signAndSend(sender: ActivityResultSender, tx: ByteArray): ByteArray =
        sequential { adapter.transact(sender) { signAndSendTransactions(arrayOf(tx)) } }.unwrap().signatures.first()

    /** Drops the saved wallet authorization (the next connect asks the wallet again). */
    fun forgetAuthorization() {
        adapter.authToken = null
        prefs.mwaAuthToken = null
    }

    suspend fun disconnect(sender: ActivityResultSender) {
        runCatching { adapter.disconnect(sender) }
        prefs.mwaAuthToken = null
    }
}
