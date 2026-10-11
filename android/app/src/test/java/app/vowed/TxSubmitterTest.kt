package app.vowed

import app.vowed.core.Base58
import app.vowed.core.TxReview
import app.vowed.data.ApiException
import app.vowed.data.CheckResponse
import app.vowed.data.Landing
import app.vowed.data.LandingCheck
import app.vowed.data.TxSubmitter
import app.vowed.wallet.KeyValueFake
import app.vowed.wallet.SignMethod
import app.vowed.wallet.VisitResult
import app.vowed.wallet.WalletCaps
import app.vowed.wallet.WalletErrors
import app.vowed.wallet.WalletException
import app.vowed.wallet.WalletMemory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Staking, creating, claiming: ONE wallet request each, a signed copy handed to the server exactly once, and the chain read before anything is asked twice. */
class TxSubmitterTest {
    private val sig = ByteArray(64) { (it + 1).toByte() }
    private fun tx(kind: String) = PendingTx(kind, TxReview("t", emptyList()), ByteArray(300), "POOL")

    private class Counters { var wallet = 0; var back = 0; var submitted = 0; val synced = mutableListOf<String>(); val statuses = mutableListOf<String>() }

    private fun submitter(
        c: Counters,
        wallet: suspend () -> VisitResult = { c.wallet++; VisitResult.Sent(ByteArray(64) { (it + 1).toByte() }, SignMethod.SendBySelf, "p") },
        submitSigned: suspend (ByteArray) -> String = { c.submitted++; Base58.encode(ByteArray(64) { (it + 1).toByte() }) },
        sync: suspend (String) -> Unit = { c.synced += it },
    ) = TxSubmitter(sign = { _, _, _ -> wallet() }, submitSigned = submitSigned, backOnScreen = { c.back++ }, sync = sync, pause = {})

    @Test fun createJoinAndClaimEachAskTheWalletExactlyOnce() = runBlocking {
        for (kind in listOf("create", "join", "claim")) {
            val c = Counters()
            val signature = submitter(c).submit(tx(kind)) { c.statuses += it }
            assertEquals("$kind: one wallet request", 1, c.wallet)
            assertEquals(Base58.encode(sig), signature)
            assertEquals(listOf(signature), c.synced)
            assertEquals(1, c.back)
            assertEquals(0, c.submitted)
            assertEquals(listOf("Waiting for your wallet…", "Confirming on Solana…"), c.statuses)
        }
    }

    @Test fun aWalletThatOnlySignsHandsTheSignedTransactionToTheServerExactlyOnce() = runBlocking {
        val c = Counters()
        var received: ByteArray? = null
        val s = submitter(
            c,
            wallet = { c.wallet++; VisitResult.Signed(ByteArray(200) { 9 }, SignMethod.SignOnly, "p") },
            submitSigned = { bytes -> c.submitted++; received = bytes; "SERVERSIG" },
        )
        val signature = s.submit(tx("join")) { c.statuses += it }
        assertEquals("SERVERSIG", signature)
        assertEquals(1, c.wallet)
        assertEquals("the signed copy was handed over once", 1, c.submitted)
        assertEquals(200, received!!.size)
        assertEquals(listOf("SERVERSIG"), c.synced)
        assertEquals(listOf("Waiting for your wallet…", "Sending your signed transaction…", "Confirming on Solana…"), c.statuses)
    }

    @Test fun aServerThatFailsToTakeTheSignedCopyIsNotAskedTwiceByTheSubmitter() {
        val c = Counters()
        val s = submitter(
            c,
            wallet = { c.wallet++; VisitResult.Signed(ByteArray(200) { 9 }, SignMethod.SignOnly, "p") },
            submitSigned = { c.submitted++; throw ApiException(0, "network", "Vowed couldn't reach its server.") },
        )
        val e = runCatching { runBlocking { s.submit(tx("join")) { } } }.exceptionOrNull()
        assertTrue(e is ApiException)
        assertEquals("one try only: the chain is read before anything is sent again", 1, c.submitted)
        assertEquals(1, c.wallet)
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

    // ---------------------------------------------------------------- the chain is read when the outcome is unknown

    private fun check(landedAt: Int, counter: IntArray) = suspend {
        counter[0]++
        if (counter[0] >= landedAt) CheckResponse(true, "your stake is in the challenge", "SIG") else CheckResponse(false, "your stake is not in the challenge")
    }

    @Test fun aClosedAfterSendThatLandedIsFoundOnChainAndReportedAsSuccess() = runBlocking {
        val n = intArrayOf(0)
        val l = LandingCheck(check(3, n), pause = {}).poll()
        assertTrue(l is Landing.Landed)
        assertEquals("SIG", (l as Landing.Landed).signature)
        assertEquals("it stops as soon as the step is found", 3, n[0])
    }

    @Test fun aClosedAfterSendThatDidNotLandIsAFailureOnlyAfterTwentySecondsOfReadingTheChain() = runBlocking {
        val n = intArrayOf(0)
        val waited = mutableListOf<Long>()
        val log = mutableListOf<String>()
        val l = LandingCheck(check(Int.MAX_VALUE, n), pause = { waited += it }, log = { log += it }).poll()
        assertTrue(l is Landing.NotLanded)
        assertEquals("every 2 s for 20 s: 11 reads", 11, n[0])
        assertEquals(20_000L, waited.sum())
        assertTrue(waited.all { it == 2_000L })
        assertTrue(log.first().contains("not on chain"))
    }

    @Test fun aChainThatCannotBeReadIsUnknownNotAFailure() = runBlocking {
        val l = LandingCheck({ throw ApiException(0, "network", "Vowed couldn't reach its server.") }, pause = {}).poll()
        assertTrue(l is Landing.Unknown)
    }

    @Test fun oneAnswerAfterErrorsIsStillFound() = runBlocking {
        var n = 0
        val l = LandingCheck({ n++; if (n < 3) throw ApiException(500, "http_500", "x") else CheckResponse(true, "ok") }, pause = {}).poll()
        assertTrue(l is Landing.Landed)
    }

    // ---------------------------------------------------------------- what is remembered about each wallet app

    @Test fun theMethodAndTheTokenMemoryAreKeptPerWalletApp() {
        val mem = WalletMemory(KeyValueFake())
        assertNull(mem.method("app.phantom"))
        assertTrue(mem.tokenHonoured("app.phantom"))
        mem.rememberMethod("app.phantom", SignMethod.SignOnly)
        mem.refuseToken("app.phantom")
        assertEquals(SignMethod.SignOnly, mem.method("app.phantom"))
        assertFalse(mem.tokenHonoured("app.phantom"))
        // another wallet is not affected
        assertNull(mem.method("com.solana.mwallet"))
        assertTrue(mem.tokenHonoured("com.solana.mwallet"))
        assertEquals(SignMethod.SendBySelf, SignMethod.SignOnly.other())
        assertEquals(SignMethod.SendBySelf, app.vowed.wallet.MethodChooser.choose(null, null, null))
        assertEquals(SignMethod.SignOnly, app.vowed.wallet.MethodChooser.choose(null, null, WalletCaps(false, null, "", "")))
        assertEquals(SignMethod.SendBySelf, app.vowed.wallet.MethodChooser.choose(SignMethod.SendBySelf, SignMethod.SignOnly, WalletCaps(false, null, "", "")))
    }
}
