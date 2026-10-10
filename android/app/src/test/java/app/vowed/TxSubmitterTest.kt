package app.vowed

import app.vowed.core.Base58
import app.vowed.core.TxReview
import app.vowed.data.ApiException
import app.vowed.data.TxSubmitter
import app.vowed.wallet.WalletErrors
import app.vowed.wallet.WalletException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Staking, creating, claiming: ONE wallet request each, and a clear failure (never a silent second request). */
class TxSubmitterTest {
    private val sig = ByteArray(64) { (it + 1).toByte() }
    private fun tx(kind: String) = PendingTx(kind, TxReview("t", emptyList()), ByteArray(300), "POOL")

    private class Counters { var wallet = 0; var back = 0; val synced = mutableListOf<String>(); val statuses = mutableListOf<String>() }

    private fun submitter(c: Counters, wallet: suspend () -> ByteArray = { c.wallet++; ByteArray(64) { (it + 1).toByte() } }, sync: suspend (String) -> Unit = { c.synced += it }) =
        TxSubmitter(signAndSend = { _, _, _ -> wallet() }, backOnScreen = { c.back++ }, sync = sync, pause = {})

    @Test fun createJoinAndClaimEachAskTheWalletExactlyOnce() = runBlocking {
        for (kind in listOf("create", "join", "claim")) {
            val c = Counters()
            val signature = submitter(c).submit(tx(kind)) { c.statuses += it }
            assertEquals("$kind: one wallet request", 1, c.wallet)
            assertEquals(Base58.encode(sig), signature)
            assertEquals(listOf(signature), c.synced)
            assertEquals(1, c.back)
            assertEquals(listOf("Waiting for your wallet…", "Confirming on Solana…"), c.statuses)
        }
    }

    @Test fun aStreakFreezePaymentIsNotMirroredToTheBackend() = runBlocking {
        val c = Counters()
        submitter(c).submit(tx("freeze")) { }
        assertEquals(1, c.wallet)
        assertTrue(c.synced.isEmpty())
    }

    @Test fun aRejectedRequestIsOneWalletCallThenAnError() {
        val c = Counters()
        val s = submitter(c, wallet = { c.wallet++; throw WalletException(WalletErrors.DECLINED, WalletErrors.Kind.Declined) })
        val e = runCatching { runBlocking { s.submit(tx("join")) { } } }.exceptionOrNull()
        assertTrue(e is WalletException)
        assertEquals(1, c.wallet)
        assertEquals("the app still waits for Vowed to be on screen", 1, c.back)
        assertTrue(c.synced.isEmpty())
    }

    @Test fun theBackendNotSeeingTheTransactionYetIsRetriedWithoutTheWallet() = runBlocking {
        val c = Counters()
        var calls = 0
        submitter(c, sync = { calls++; if (calls < 3) throw ApiException(404, "not_found", "not yet") }).submit(tx("join")) { }
        assertEquals(3, calls)
        assertEquals(1, c.wallet)
    }

    @Test fun anotherBackendErrorIsReportedAfterTheWalletSucceededWithoutAskingItAgain() {
        val c = Counters()
        val s = submitter(c, sync = { throw ApiException(500, "http_500", "server error") })
        val e = runCatching { runBlocking { s.submit(tx("join")) { } } }.exceptionOrNull()
        assertTrue(e is ApiException)
        assertEquals(1, c.wallet)
    }
}
