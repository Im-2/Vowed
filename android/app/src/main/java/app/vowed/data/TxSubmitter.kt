package app.vowed.data

import app.vowed.PendingTx
import app.vowed.core.Base58
import kotlinx.coroutines.delay

/**
 * Sends one reviewed transaction: ONE wallet request (one signature, one visit), then tells the backend so it mirrors the result.
 * The wallet, the "wait until Vowed is on screen" step and the backend call are passed in, so a test can count how many times the wallet is asked.
 */
class TxSubmitter(
    /** the wallet request: transaction bytes, kind, the server's simulation note; returns the 64-byte transaction signature */
    private val signAndSend: suspend (ByteArray, String, String?) -> ByteArray,
    /** returns when Vowed is on screen again (some wallets leave their own screen up) */
    private val backOnScreen: suspend () -> Unit,
    private val sync: suspend (String) -> Unit,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    /** Returns the base58 transaction signature once the backend has the result (or the transaction needs no mirroring). */
    suspend fun submit(tx: PendingTx, status: (String) -> Unit): String {
        status("Waiting for your wallet…")
        val sig = try {
            signAndSend(tx.txBytes, tx.kind, tx.simulationNote)
        } finally {
            backOnScreen()
        }
        status("Confirming on Solana…")
        val signature = Base58.encode(sig)
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
