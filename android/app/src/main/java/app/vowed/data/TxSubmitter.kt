package app.vowed.data

import app.vowed.PendingTx
import app.vowed.core.Base58
import kotlinx.coroutines.delay

/**
 * Sends one reviewed transaction: ONE wallet request (one signature, one visit), then tells the backend so it mirrors the result.
 * The wallet, the "wait until Vowed is on screen" step and the backend call are passed in, so a test can count how many times the wallet is asked.
 */
class TxSubmitter(
    /** the wallet visit: transaction bytes, kind, the server's simulation note; returns what the wallet did (sent it, or only signed it) */
    private val sign: suspend (ByteArray, String, String?) -> app.vowed.wallet.VisitResult,
    /** a wallet that only signed: hands the signed transaction to the server, which sends it at most once; returns its signature (base58) */
    private val submitSigned: suspend (ByteArray) -> String,
    /** returns when Vowed is on screen again (some wallets leave their own screen up) */
    private val backOnScreen: suspend () -> Unit,
    private val sync: suspend (String) -> Unit,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    /** Returns the base58 transaction signature once the backend has the result (or the transaction needs no mirroring). */
    suspend fun submit(tx: PendingTx, status: (String) -> Unit): String {
        status("Waiting for your wallet…")
        val result = try {
            sign(tx.txBytes, tx.kind, tx.simulationNote)
        } finally {
            backOnScreen()
        }
        val signature = when (result) {
            is app.vowed.wallet.VisitResult.Sent -> Base58.encode(result.signature)
            // the wallet signed and Vowed's server sends it: exactly one request, and the server answers the same signature if it is asked twice
            is app.vowed.wallet.VisitResult.Signed -> {
                status("Sending your signed transaction…")
                submitSigned(result.signedTransaction)
            }
        }
        status("Confirming on Solana…")
        // the transaction is already submitted; a token payment is not a Vowed program transaction, so there is nothing to mirror
        var synced = tx.kind == "freeze" || tx.kind == "debug"
        repeat(6) { attempt ->
            if (!synced) {
                try { sync(signature); synced = true } catch (e: ApiException) { if (e.code != "not_found") throw e; pause(2_000L + attempt * 500L) }
            }
        }
        return signature
    }
}
