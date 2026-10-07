package app.vowed.wallet

import android.net.Uri
import app.vowed.core.Base58
import app.vowed.data.Prefs
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.solana.mobilewalletadapter.clientlib.ConnectionIdentity
import com.solana.mobilewalletadapter.clientlib.MobileWalletAdapter
import com.solana.mobilewalletadapter.clientlib.TransactionResult
import com.solana.mobilewalletadapter.common.signin.SignInWithSolana

/** The wallet said no, was not installed, or the connection failed. The message is safe to show. */
class WalletException(message: String) : Exception(message)

class SignInOutcome(val address: String, val signedMessage: ByteArray, val signature: ByteArray)

/**
 * Everything that touches the user's wallet goes through Mobile Wallet Adapter (MWA). The app never holds a private key:
 * signing a message or a transaction always happens in the wallet app, after the user approves it there.
 * The identity below is what the wallet shows to the user ("Vowed wants to connect").
 */
class WalletManager(private val prefs: Prefs) {
    private val adapter = MobileWalletAdapter(
        connectionIdentity = ConnectionIdentity(
            identityUri = Uri.parse("https://vowed.app"),
            iconUri = Uri.parse("favicon.ico"),
            identityName = "Vowed",
        ),
    ).also { it.authToken = prefs.mwaAuthToken } // default blockchain is Solana devnet

    private fun <T> TransactionResult<T>.unwrap(): T = when (this) {
        is TransactionResult.Success -> payload.also { prefs.mwaAuthToken = adapter.authToken }
        is TransactionResult.NoWalletFound -> throw WalletException("No Solana wallet app was found on this phone. Install a Mobile Wallet Adapter wallet first.")
        is TransactionResult.Failure -> throw WalletException(message.ifBlank { "The wallet request failed." })
    }

    /**
     * Connects to the wallet and has it sign the Sign-In-With-Solana message in one wallet session. The backend's nonce is per
     * wallet, so [fetchNonce] is called with the address the wallet just authorized.
     */
    suspend fun connectAndSignIn(sender: ActivityResultSender, fetchNonce: suspend (String) -> app.vowed.data.NonceResponse): SignInOutcome =
        adapter.transact(sender) { auth ->
            val addressBytes = auth.accounts.first().publicKey
            val address = Base58.encode(addressBytes)
            val n = fetchNonce(address)
            val payload = SignInWithSolana.Payload(n.domain, addressBytes, n.statement, Uri.parse(n.uri), "1", null, n.nonce, n.issuedAt, n.expirationTime, null, null, null)
            val message = payload.prepareMessage(addressBytes).encodeToByteArray()
            val signature = signMessagesDetached(arrayOf(message), arrayOf(addressBytes)).messages.first().signatures.first()
            SignInOutcome(address, message, signature)
        }.unwrap()

    /** Detached ed25519 signature of [message] by the connected account. */
    suspend fun signMessage(sender: ActivityResultSender, wallet: String, message: ByteArray): ByteArray {
        val addr = Base58.decode(wallet)
        return adapter.transact(sender) { signMessagesDetached(arrayOf(message), arrayOf(addr)) }.unwrap()
            .messages.first().signatures.first()
    }

    /** Signs and submits [tx] (an unsigned legacy transaction). Returns the 64-byte transaction signature. */
    suspend fun signAndSend(sender: ActivityResultSender, tx: ByteArray): ByteArray =
        adapter.transact(sender) { signAndSendTransactions(arrayOf(tx)) }.unwrap().signatures.first()

    suspend fun disconnect(sender: ActivityResultSender) {
        runCatching { adapter.disconnect(sender) }
        prefs.mwaAuthToken = null
    }
}
