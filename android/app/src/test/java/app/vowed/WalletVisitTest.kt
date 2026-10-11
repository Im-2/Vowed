package app.vowed

import app.vowed.wallet.WalletAuth
import app.vowed.wallet.WalletErrors
import app.vowed.wallet.WalletException
import app.vowed.wallet.WalletTrace
import app.vowed.wallet.WalletTransport
import app.vowed.wallet.WalletVisit
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One wallet visit for one transaction: how many times the wallet opens, how many screens a returning user sees, and what happens on every
 * failure. The fake wallet counts launches and can behave like a wallet that shows a Connect screen, refuses a saved authorization, or never answers.
 */
class WalletVisitTest {
    private val sig = ByteArray(64) { 7 }

    private class FakeWallet(
        /** a wallet that still knows the saved token answers silently; otherwise it shows its Connect screen (counted) */
        val knowsToken: String? = "TOKEN",
        val authDelayMs: Long = 0,
        val signDelayMs: Long = 0,
        val connectFails: WalletException? = null,
        val authFailsOnce: WalletException? = null,
        val signFails: WalletException? = null,
    ) : WalletTransport {
        var launches = 0
        var connectScreens = 0 // times the wallet showed a Connect screen
        var approvalScreens = 0
        var authorizeCalls = mutableListOf<String?>()
        var closed = mutableListOf<String>()
        var signPending = false
        private var failedOnce = false

        override suspend fun launch() { launches++ }
        override suspend fun connect(timeoutMs: Long): String { connectFails?.let { throw it }; return "protocol V1" }
        override suspend fun authorize(token: String?): WalletAuth {
            authorizeCalls += token
            if (authFailsOnce != null && token != null && !failedOnce) { failedOnce = true; throw authFailsOnce }
            if (authDelayMs > 0) delay(authDelayMs)
            if (token == null || token != knowsToken) connectScreens++
            return WalletAuth("TOKEN", ByteArray(32))
        }
        override suspend fun signAndSend(tx: ByteArray): ByteArray {
            approvalScreens++
            signPending = true
            try {
                if (signDelayMs > 0) delay(signDelayMs)
                signFails?.let { throw it }
                return ByteArray(64) { 7 }
            } finally { signPending = false }
        }
        override suspend fun close(reason: String) { closed += reason }
    }

    private fun visit(w: FakeWallet, trace: WalletTrace = WalletTrace(), stallMs: Long = 8_000, onStalled: () -> Unit = {}) =
        WalletVisit(w, trace, stallAfterMs = stallMs, onStalled = onStalled)

    private fun run(v: WalletVisit, token: String?, saved: MutableList<String?> = mutableListOf()): ByteArray = runBlocking { v.signAndSend(ByteArray(300), token) { saved += it } }

    @Test fun aLoggedInUserStakesWithOneLaunchNoConnectScreenAndOneApproval() {
        val w = FakeWallet()
        val saved = mutableListOf<String?>()
        assertEquals(64, run(visit(w), "TOKEN", saved).size)
        assertEquals("one wallet launch", 1, w.launches)
        assertEquals("zero Connect screens: the saved authorization is used", 0, w.connectScreens)
        assertEquals("one approval screen", 1, w.approvalScreens)
        assertEquals(listOf<String?>("TOKEN"), w.authorizeCalls)
        assertEquals(1, w.closed.size)
        assertEquals(listOf<String?>("TOKEN"), saved)
    }

    @Test fun withoutASavedAuthorizationTheWalletConnectsAndSignsInTheSameVisit() {
        val w = FakeWallet()
        run(visit(w), null)
        assertEquals(1, w.launches)
        assertEquals("one Connect screen and one approval, in one visit", 1, w.connectScreens)
        assertEquals(1, w.approvalScreens)
    }

    @Test fun aWalletThatRefusesTheSavedTokenIsAskedAgainInTheSameSessionNotRelaunched() {
        val refused = WalletException(WalletErrors.AUTHORIZATION_REFUSED, WalletErrors.Kind.AuthorizationRefused, code = -1)
        val w = FakeWallet(authFailsOnce = refused)
        val saved = mutableListOf<String?>()
        run(visit(w), "OLD", saved)
        assertEquals("still ONE launch", 1, w.launches)
        assertEquals(listOf<String?>("OLD", null), w.authorizeCalls)
        assertEquals("one Connect screen (the token was refused) and one approval", 1, w.connectScreens)
        assertEquals(1, w.approvalScreens)
        assertNull("the refused token was discarded", saved.first())
    }

    @Test fun aSecondRefusalIsAnErrorNotALoop() {
        val w = object : WalletTransport {
            var launches = 0
            var auth = 0
            override suspend fun launch() { launches++ }
            override suspend fun connect(timeoutMs: Long) = "v"
            override suspend fun authorize(token: String?): WalletAuth { auth++; throw WalletException("no", WalletErrors.Kind.AuthorizationRefused, code = -1) }
            override suspend fun signAndSend(tx: ByteArray): ByteArray = error("never")
            override suspend fun close(reason: String) {}
        }
        val e = runCatching { runBlocking { WalletVisit(w, WalletTrace()).signAndSend(ByteArray(10), "T") { } } }.exceptionOrNull()
        assertTrue(e is WalletException)
        assertEquals(1, w.launches)
        assertEquals("one try with the token, one without, then it stops", 2, w.auth)
    }

    @Test fun everyFailurePathOpensTheWalletAtMostOnceAndClosesTheSession() {
        val declined = WalletException(WalletErrors.DECLINED, WalletErrors.Kind.Declined, code = -3)
        val cases = listOf(
            FakeWallet(connectFails = WalletException(WalletErrors.NO_RESPONSE, WalletErrors.Kind.NoResponse)),
            FakeWallet(signFails = declined),
            FakeWallet(signFails = WalletException(WalletErrors.SESSION_CLOSED, WalletErrors.Kind.SessionClosed)),
            FakeWallet(signFails = WalletException(WalletErrors.EXPIRED, WalletErrors.Kind.Other, code = -2)),
        )
        for (w in cases) {
            val e = runCatching { run(visit(w), "TOKEN") }.exceptionOrNull()
            assertTrue(e is WalletException)
            assertEquals("no automatic second launch", 1, w.launches)
            assertEquals("the session is closed by Vowed at the end, once", 1, w.closed.size)
        }
    }

    @Test fun nothingClosesTheSessionWhileTheWalletIsStillShowingItsScreen() = runBlocking {
        val w = FakeWallet(signDelayMs = 300)
        val job = launch { visit(w).signAndSend(ByteArray(10), "TOKEN") { } }
        delay(100)
        assertTrue(w.signPending)
        assertTrue("no close while waiting", w.closed.isEmpty())
        job.join()
        assertEquals(1, w.closed.size)
    }

    @Test fun theWaitingHintAppearsOnlyWhenTheWalletIsSlowToAnswer() = runBlocking {
        var stalled = 0
        run(visit(FakeWallet(), stallMs = 200, onStalled = { stalled++ }), "TOKEN")
        assertEquals("a quick wallet never shows the hint", 0, stalled)
        val slow = FakeWallet(signDelayMs = 500)
        visit(slow, stallMs = 100, onStalled = { stalled++ }).signAndSend(ByteArray(10), "TOKEN") { }
        assertEquals("a slow one shows it once", 1, stalled)
        assertEquals("and it did not open the wallet again", 1, slow.launches)
    }

    @Test fun tryAgainEndsTheRequestAndClosesTheSessionWithoutOpeningTheWalletAgain() = runBlocking {
        val w = FakeWallet(signDelayMs = 10_000)
        val trace = WalletTrace()
        val job = launch { runCatching { visit(w, trace).signAndSend(ByteArray(10), "TOKEN") { } } }
        delay(100)
        job.cancel() // the person tapped Try again
        job.join()
        assertEquals(1, w.launches)
        assertEquals("the cancelled request still closes its session", 1, w.closed.size)
        assertTrue(trace.render().contains("session closed"))
    }

    @Test fun theTraceHasTheStepsInOrderWithTimes() {
        val trace = WalletTrace()
        run(visit(FakeWallet(authDelayMs = 30), trace), "TOKEN")
        val t = trace.render()
        val order = listOf("association started", "wallet launched", "connection established", "authorize sent", "authorize result", "sign request sent", "wallet response", "session closed")
        var at = -1
        for (step in order) {
            val i = t.indexOf(step)
            assertTrue("missing or out of order: $step\n$t", i > at)
            at = i
        }
        assertTrue(t.contains("by Vowed; reason: finished: the wallet answered"))
        assertFalse(t.contains("eyJ"))
    }

    @Test fun aSlowAuthorizeIsReportedAsALikelyScreen() {
        val trace = WalletTrace()
        var now = 0L // the visit's own clock is injected, so 8 s pass without waiting
        val w = object : WalletTransport {
            override suspend fun launch() {}
            override suspend fun connect(timeoutMs: Long) = "v"
            override suspend fun authorize(token: String?): WalletAuth { now += 8_000; return WalletAuth("T", ByteArray(32)) }
            override suspend fun signAndSend(tx: ByteArray): ByteArray { now += 1_000; return sig }
            override suspend fun close(reason: String) {}
        }
        runBlocking { WalletVisit(w, trace, clock = { now }).signAndSend(ByteArray(10), null) { } }
        assertTrue(trace.render().contains("it probably showed a screen"))
    }
}
