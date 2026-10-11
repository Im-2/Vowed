package app.vowed

import app.vowed.data.KeyValue
import app.vowed.wallet.SignMethod
import app.vowed.wallet.StallInfo
import app.vowed.wallet.VisitHooks
import app.vowed.wallet.VisitResult
import app.vowed.wallet.WalletAuth
import app.vowed.wallet.WalletCaps
import app.vowed.wallet.WalletErrors
import app.vowed.wallet.WalletException
import app.vowed.wallet.WalletMemory
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
 * One wallet visit for one transaction: how many times the wallet opens, how many screens a returning user sees, which method is used and remembered, and what
 * happens on every failure. The fake wallet counts launches and can behave like Phantom (refuses a saved authorization, never answers sign_and_send),
 * like the Mock wallet (honours it, answers both) or like a wallet that dies.
 */
class WalletVisitTest {
    private val sig = ByteArray(64) { 7 }
    private val signed = ByteArray(200) { 9 }

    private class MapKv : KeyValue {
        val m = HashMap<String, String>()
        override fun get(key: String) = m[key]
        override fun put(key: String, value: String?) { if (value == null) m.remove(key) else m[key] = value }
    }

    private class FakeWallet(
        override val walletPackage: String? = "app.fake.wallet",
        override val walletLabel: String? = "Fakewallet",
        /** a wallet that still knows the saved token answers silently; otherwise it shows its Connect screen (counted) */
        val knowsToken: String? = "TOKEN",
        /** Phantom: refuses every saved token with -1 */
        val refusesTokens: Boolean = false,
        val caps: WalletCaps? = WalletCaps(true, 10, "legacy,0", ""),
        val authDelayMs: Long = 0,
        val signDelayMs: Long = 0,
        /** sign_and_send_transactions never gets an answer (the request just sits there) */
        val sendNeverAnswers: Boolean = false,
        val connectFails: WalletException? = null,
        val signFails: WalletException? = null,
    ) : WalletTransport {
        var launches = 0
        var connectScreens = 0
        var approvalScreens = 0
        val authorizeCalls = mutableListOf<String?>()
        var sendCalls = 0
        var signOnlyCalls = 0
        val closed = mutableListOf<String>()
        var signPending = false

        override suspend fun launch() { launches++ }
        override suspend fun connect(timeoutMs: Long): String { connectFails?.let { throw it }; return "protocol V1" }
        override suspend fun capabilities(): WalletCaps? = caps
        override suspend fun authorize(token: String?): WalletAuth {
            authorizeCalls += token
            if (token != null && refusesTokens) throw WalletException(WalletErrors.AUTHORIZATION_REFUSED, WalletErrors.Kind.AuthorizationRefused, code = -1)
            if (authDelayMs > 0) delay(authDelayMs)
            if (token == null || token != knowsToken) connectScreens++
            return WalletAuth("TOKEN", ByteArray(32))
        }
        override suspend fun signAndSend(tx: ByteArray): ByteArray {
            sendCalls++
            approvalScreens++
            signPending = true
            try {
                if (sendNeverAnswers) delay(60_000)
                if (signDelayMs > 0) delay(signDelayMs)
                signFails?.let { throw it }
                return ByteArray(64) { 7 }
            } finally { signPending = false }
        }
        override suspend fun signOnly(tx: ByteArray): ByteArray {
            signOnlyCalls++
            approvalScreens++
            if (signDelayMs > 0) delay(signDelayMs)
            signFails?.let { throw it }
            return ByteArray(200) { 9 }
        }
        override suspend fun close(reason: String) { closed += reason }
    }

    private fun visit(w: FakeWallet, trace: WalletTrace = WalletTrace(), memory: WalletMemory? = null, hooks: VisitHooks = VisitHooks(), stallMs: Long = 12_000, answerMs: Long = 90_000) =
        WalletVisit(w, trace, memory = memory, hooks = hooks, stallAfterMs = stallMs, answerTimeoutMs = answerMs)

    private fun run(v: WalletVisit, token: String?, override: SignMethod? = null): VisitResult = runBlocking { v.run(ByteArray(300), token, override) }
    private fun fails(v: WalletVisit, token: String? = "TOKEN", override: SignMethod? = null): WalletException = runCatching { run(v, token, override) }.exceptionOrNull() as WalletException

    @Test fun aLoggedInUserStakesWithOneLaunchNoConnectScreenAndOneApproval() {
        val w = FakeWallet()
        val saved = mutableListOf<String?>()
        val r = run(visit(w, hooks = VisitHooks(saveToken = { saved += it })), "TOKEN")
        assertTrue(r is VisitResult.Sent)
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

    @Test fun aWalletLikePhantomThatRefusesTheSavedTokenIsAskedAgainInTheSameSessionAndRemembered() {
        val w = FakeWallet(refusesTokens = true)
        val mem = WalletMemory(MapKv())
        val saved = mutableListOf<String?>()
        run(visit(w, memory = mem, hooks = VisitHooks(saveToken = { saved += it })), "OLD")
        assertEquals("still ONE launch", 1, w.launches)
        assertEquals(listOf<String?>("OLD", null), w.authorizeCalls)
        assertEquals("one Connect screen and one approval in the one visit", 1, w.connectScreens)
        assertEquals(1, w.approvalScreens)
        assertNull("the refused token was discarded", saved.first())
        assertFalse("remembered for this wallet app", mem.tokenHonoured("app.fake.wallet"))
        // the next visit does not send the token at all: a plain authorize, then the request, in the same visit
        val w2 = FakeWallet(refusesTokens = true)
        run(visit(w2, memory = mem), "TOKEN")
        assertEquals(listOf<String?>(null), w2.authorizeCalls)
        assertEquals(1, w2.launches)
    }

    @Test fun theSavedTokenIsStillSentToAWalletThatHonoursIt() {
        val mem = WalletMemory(MapKv())
        val w = FakeWallet()
        run(visit(w, memory = mem), "TOKEN")
        run(visit(FakeWallet().also { it.authorizeCalls.clear() }, memory = mem), "TOKEN")
        assertTrue(mem.tokenHonoured("app.fake.wallet"))
        assertEquals(listOf<String?>("TOKEN"), w.authorizeCalls)
    }

    @Test fun aSecondRefusalIsAnErrorNotALoop() {
        val w = object : WalletTransport by FakeWallet() {
            var launches = 0
            var auth = 0
            override suspend fun launch() { launches++ }
            override suspend fun authorize(token: String?): WalletAuth { auth++; throw WalletException("no", WalletErrors.Kind.AuthorizationRefused, code = -1) }
        }
        val e = runCatching { runBlocking { WalletVisit(w, WalletTrace()).run(ByteArray(10), "T") } }.exceptionOrNull()
        assertTrue(e is WalletException)
        assertEquals(1, w.launches)
        assertEquals("one try with the token, one without, then it stops", 2, w.auth)
    }

    // ---------------------------------------------------------------- the two methods

    @Test fun anUnknownWalletStartsWithSignAndSendAndRemembersTheMethodThatGotAnAnswer() {
        val mem = WalletMemory(MapKv())
        val w = FakeWallet()
        val r = run(visit(w, memory = mem), "TOKEN")
        assertEquals(SignMethod.SendBySelf, r.method)
        assertEquals(SignMethod.SendBySelf, mem.method("app.fake.wallet"))
    }

    @Test fun aWalletThatSaysItCannotSignAndSendIsAskedToOnlySign() {
        val w = FakeWallet(caps = WalletCaps(false, null, "legacy", ""))
        val r = run(visit(w), "TOKEN")
        assertTrue(r is VisitResult.Signed)
        assertEquals(0, w.sendCalls)
        assertEquals(1, w.signOnlyCalls)
        assertEquals(1, w.launches)
    }

    @Test fun aRequestThatGetsNoAnswerEndsClearlyAndTryAgainUsesTheOtherMethodWhichIsThenRemembered() {
        val mem = WalletMemory(MapKv())
        val first = FakeWallet(sendNeverAnswers = true)
        val e = fails(visit(first, memory = mem, answerMs = 150))
        assertEquals(WalletErrors.Kind.NoResponse, e.kind)
        assertTrue("the request WAS sent: the chain must be read before another signature", e.requestSent)
        assertEquals(1, first.launches)
        assertNull("nothing is remembered from a request that got no answer", mem.method("app.fake.wallet"))
        // Try again: a fresh visit asks the OTHER way
        val second = FakeWallet(sendNeverAnswers = true)
        val r = run(visit(second, memory = mem, answerMs = 150), "TOKEN", override = SignMethod.SendBySelf.other())
        assertTrue(r is VisitResult.Signed)
        assertEquals(0, second.sendCalls)
        assertEquals(SignMethod.SignOnly, mem.method("app.fake.wallet"))
        // and from now on this wallet app is asked that way straight away
        val third = FakeWallet(sendNeverAnswers = true)
        val r3 = run(visit(third, memory = mem, answerMs = 150), "TOKEN")
        assertTrue(r3 is VisitResult.Signed)
        assertEquals(0, third.sendCalls)
    }

    @Test fun theWaitingHintNamesTheWalletAppAndComesTwelveSecondsAfterTheRequestWasSent() = runBlocking {
        var info: StallInfo? = null
        val w = FakeWallet(signDelayMs = 500, authDelayMs = 0)
        visit(w, hooks = VisitHooks(stalled = { info = it }), stallMs = 100).run(ByteArray(10), "TOKEN")
        assertEquals("Fakewallet", info!!.walletLabel)
        assertTrue("the request had been sent", info!!.requestSent)
        assertEquals(SignMethod.SendBySelf, info!!.method)
        assertEquals("and it did not open the wallet again", 1, w.launches)
    }

    @Test fun aQuickWalletNeverShowsTheHint() {
        var shown = 0
        run(visit(FakeWallet(), hooks = VisitHooks(stalled = { shown++ }), stallMs = 300), "TOKEN")
        assertEquals(0, shown)
    }

    @Test fun theCapabilitiesAndTheMethodAndTheTimesGoIntoTheTrace() {
        val trace = WalletTrace()
        run(visit(FakeWallet(caps = WalletCaps(true, 5, "legacy,0", "solana:cloneAuthorization")), trace), "TOKEN")
        val t = trace.render()
        assertTrue(t, t.contains("wallet capabilities: sign_and_send_transactions: true, max transactions per request: 5, transaction versions: legacy,0"))
        assertTrue(t.contains("method chosen: sign_and_send_transactions"))
        assertTrue(t.contains("wallet launched: Fakewallet (app.fake.wallet)"))
        val order = listOf("association started", "wallet launched", "connection established", "wallet capabilities", "method chosen", "authorize sent", "authorize result", "request sent", "wallet response", "session closed")
        var at = -1
        for (step in order) { val i = t.indexOf(step); assertTrue("missing or out of order: $step\n$t", i > at); at = i }
    }

    // ---------------------------------------------------------------- failures: was the request sent?

    @Test fun aFailureBeforeTheRequestWasSentSaysSoAndOpensTheWalletOnce() {
        val w = FakeWallet(connectFails = WalletException(WalletErrors.NO_RESPONSE, WalletErrors.Kind.NoResponse))
        val e = fails(visit(w))
        assertFalse(e.requestSent)
        assertEquals(1, w.launches)
        assertEquals(1, w.closed.size)
    }

    @Test fun aWalletThatClosesAfterTheRequestWasSentIsReportedAsSentSoTheChainIsRead() {
        val w = FakeWallet(signFails = WalletException(WalletErrors.SESSION_CLOSED, WalletErrors.Kind.SessionClosed))
        val e = fails(visit(w))
        assertTrue(e.requestSent)
        assertEquals(WalletErrors.Kind.SessionClosed, e.kind)
        assertEquals(1, w.launches)
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
            fails(visit(w))
            assertEquals("no automatic second launch", 1, w.launches)
            assertEquals("the session is closed by Vowed at the end, once", 1, w.closed.size)
        }
    }

    @Test fun nothingClosesTheSessionWhileTheWalletIsStillShowingItsScreen() = runBlocking {
        val w = FakeWallet(signDelayMs = 300)
        val job = launch { visit(w).run(ByteArray(10), "TOKEN") }
        delay(100)
        assertTrue(w.signPending)
        assertTrue("no close while waiting", w.closed.isEmpty())
        job.join()
        assertEquals(1, w.closed.size)
    }

    @Test fun tryAgainEndsTheRequestAndClosesTheSessionWithoutOpeningTheWalletAgain() = runBlocking {
        val w = FakeWallet(signDelayMs = 10_000)
        val trace = WalletTrace()
        val job = launch { runCatching { visit(w, trace).run(ByteArray(10), "TOKEN") } }
        delay(100)
        job.cancel() // the person tapped Try again
        job.join()
        assertEquals(1, w.launches)
        assertEquals("the cancelled request still closes its session", 1, w.closed.size)
        assertTrue(trace.render().contains("session closed"))
    }

    @Test fun aWalletThatNeverAnswersEndsTheVisitWithTheActionableMessage() {
        val w = FakeWallet(signDelayMs = 60_000)
        val trace = WalletTrace()
        val e = fails(WalletVisit(w, trace, answerTimeoutMs = 150))
        assertEquals(WalletErrors.Kind.NoResponse, e.kind)
        assertEquals(WalletErrors.NO_RESPONSE, e.message)
        assertEquals(1, w.launches)
        assertEquals(1, w.closed.size)
        assertTrue(trace.render().contains("the wallet did not answer within"))
    }

    @Test fun aSlowConnectScreenIsNotCutOffBeforeTheAnswerTimeout() {
        val w = FakeWallet(authDelayMs = 200)
        assertTrue(run(WalletVisit(w, WalletTrace(), answerTimeoutMs = 5_000), null) is VisitResult.Sent)
    }

    @Test fun aSlowAuthorizeIsReportedAsALikelyScreen() {
        val trace = WalletTrace()
        var now = 0L // the visit's own clock is injected, so 8 s pass without waiting
        val w = object : WalletTransport by FakeWallet() {
            override suspend fun authorize(token: String?): WalletAuth { now += 8_000; return WalletAuth("T", ByteArray(32)) }
            override suspend fun signAndSend(tx: ByteArray): ByteArray { now += 1_000; return sig }
        }
        runBlocking { WalletVisit(w, trace, clock = { now }).run(ByteArray(10), null) }
        assertTrue(trace.render().contains("it probably showed a screen"))
    }
}
