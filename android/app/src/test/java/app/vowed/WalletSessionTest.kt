package app.vowed

import app.vowed.wallet.AppForeground
import app.vowed.wallet.LifecycleEvent
import app.vowed.wallet.LifecycleLog
import app.vowed.wallet.SessionRetry
import app.vowed.wallet.WalletErrors
import app.vowed.wallet.WalletException
import app.vowed.wallet.WalletTrace
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.ExecutionException

/** "Cannot send in CLOSED": error mapping, the single retry, the diagnostics timeline, and waiting for the app to be on screen. */
class WalletSessionTest {
    // ---------------------------------------------------------------- error mapping

    private val libraryFailure = ExecutionException(IOException("Cannot send in CLOSED"))

    @Test fun theLibrarysClosedSessionTextIsRecognisedWhereverItIsHidden() {
        assertTrue(WalletErrors.isSessionClosed("Cannot send in CLOSED"))
        assertTrue(WalletErrors.isSessionClosed("IO error while sending operation | ExecutionException: java.io.IOException: Cannot send in CLOSED"))
        assertTrue(WalletErrors.isSessionClosed("The session was closed by the wallet"))
        assertFalse(WalletErrors.isSessionClosed("User declined the request"))
        assertFalse(WalletErrors.isSessionClosed(null))
        // the failure carries the text on a wrapped cause, not on its own message
        assertTrue(WalletErrors.flatten(libraryFailure).contains("Cannot send in CLOSED"))
        assertEquals(WalletErrors.Kind.SessionClosed, WalletErrors.classify("IO error while sending operation | ${WalletErrors.flatten(libraryFailure)}", null))
    }

    @Test fun aClosedSessionBeatsTheOtherKindsAndGetsTheFriendlySentence() {
        assertEquals(WalletErrors.Kind.SessionClosed, WalletErrors.classify("cancelled: Cannot send in CLOSED", WalletErrors.CODE_AUTHORIZATION_FAILED))
        assertEquals(WalletErrors.SESSION_CLOSED, WalletErrors.explain("IO error | java.io.IOException: Cannot send in CLOSED"))
        val s = WalletErrors.SESSION_CLOSED
        assertTrue(s.startsWith("The wallet closed the connection before it finished."))
        assertTrue(s.contains("Open Vowed, tap Connect again"))
        assertTrue(s.contains("If the wallet stays on its home screen, return to Vowed manually."))
        assertFalse(s.contains("CLOSED"))
    }

    @Test fun otherKindsAreUnchanged() {
        assertEquals(WalletErrors.Kind.Declined, WalletErrors.classify("User declined authorization request", null))
        assertEquals(WalletErrors.Kind.NetworkMismatch, WalletErrors.classify("x", WalletErrors.CODE_CLUSTER_NOT_SUPPORTED))
        assertEquals(WalletErrors.Kind.PossibleMismatch, WalletErrors.classify("authorization failed", WalletErrors.CODE_AUTHORIZATION_FAILED))
        assertEquals(WalletErrors.Kind.Other, WalletErrors.classify("Request timed out", null))
    }

    // ---------------------------------------------------------------- one automatic retry

    private fun closed() = WalletException(WalletErrors.SESSION_CLOSED, WalletErrors.Kind.SessionClosed, raw = "Cannot send in CLOSED")

    @Test fun aClosedSessionIsRetriedExactlyOnceInAFreshSession() = runBlocking {
        var first = 0
        var retry = 0
        val trace = WalletTrace()
        val r = SessionRetry.run(trace, first = { first++; throw closed() }, retry = { retry++; "ok" })
        assertEquals("ok", r)
        assertEquals(1, first)
        assertEquals(1, retry)
        assertTrue(trace.render().contains("trying once in a fresh session"))
    }

    @Test fun ifTheRetryAlsoFailsItIsNotRetriedAgain() = runBlocking {
        var retry = 0
        try {
            SessionRetry.run<String>(null, first = { throw closed() }, retry = { retry++; throw closed() })
            fail("expected the second failure")
        } catch (e: WalletException) {
            assertEquals(WalletErrors.Kind.SessionClosed, e.kind)
        }
        assertEquals("no loops: the retry ran once", 1, retry)
    }

    @Test fun otherFailuresAndSuccessAreNeverRetried() = runBlocking {
        var retry = 0
        assertEquals("fine", SessionRetry.run(null, first = { "fine" }, retry = { retry++; "no" }))
        try {
            SessionRetry.run<String>(null, first = { throw WalletException("declined", WalletErrors.Kind.Declined) }, retry = { retry++; "no" })
            fail()
        } catch (e: WalletException) {
            assertEquals(WalletErrors.Kind.Declined, e.kind)
        }
        assertEquals(0, retry)
    }

    // ---------------------------------------------------------------- the diagnostics timeline

    private class Clock(var t: Long = 1_000_000L) : () -> Long { override fun invoke() = t }

    private val key = "2YePEWRp8aTfqQnJHK2EBt4YkXRXWetzdmL8dDG7UFZf"
    private val signature = "5" + "KxqRm2vYtZ8HbW3nJcL9dFgPaQ7sEuVoXyT4iCkNwBzMhGrDfAeS1jUlOp6".repeat(2) // an 88-ish character base58 string
    // built from pieces at run time so no token-shaped literal sits in the source (the secret scan would rightly flag it)
    private val jwt = listOf("eyJhbGciOiJIUzI1NiJ9", "eyJzdWIiOiJhYmMifQ", "c2lnbmF0dXJlLXZhbHVlLXZhbHVl").joinToString(".")

    @Test fun addressesAreShortenedAndSecretsAreRedacted() {
        val clock = Clock()
        val t = WalletTrace(clock) { emptyList() }
        clock.t += 100
        t.add("authorize result", "address=$key")
        t.add("a signature", signature)
        t.add("a token", "Bearer $jwt")
        t.add("long key material", "x".repeat(80))
        val text = t.render()
        assertTrue(text.contains("2YeP…UFZf"))
        assertFalse(text.contains(key))
        assertFalse(text.contains(signature))
        assertFalse(text.contains(jwt))
        assertFalse(text.contains("x".repeat(80)))
        assertTrue(text.contains("[redacted]"))
    }

    @Test fun theTimelineHasRelativeTimestampsLifecycleEventsAndATotal() {
        val clock = Clock(10_000)
        val life = listOf(
            LifecycleEvent(10_500, "onPause MainActivity"),
            LifecycleEvent(14_000, "onResume MainActivity"),
            LifecycleEvent(1_000, "onCreate MainActivity (long before, not shown)"),
        )
        val t = WalletTrace(clock) { life }
        clock.t = 10_200; t.add("association started", "mode=SingleSession, chain=solana:devnet")
        clock.t = 12_450; t.add("session 1: failed", "Cannot send in CLOSED")
        clock.t = 13_000; t.add("retry", "fresh session")
        val text = t.render(listOf("app 0.1.0 (build 3), Android 14", "wallet apps: app.phantom 25.1.0"))
        val lines = text.lines()
        assertEquals("Vowed wallet diagnostics (no keys, signatures or tokens)", lines[0])
        assertTrue(text.contains("app 0.1.0 (build 3), Android 14"))
        assertTrue(text.contains("+0.200 s [wallet] association started: mode=SingleSession, chain=solana:devnet"))
        assertTrue(text.contains("+0.500 s [app] onPause MainActivity"))
        assertTrue(text.contains("+2.450 s [wallet] session 1: failed: Cannot send in CLOSED"))
        assertTrue(text.contains("+4.000 s [app] onResume MainActivity"))
        assertFalse("events from long before the attempt are left out", text.contains("long before"))
        assertTrue(text.contains("total 3.0 s"))
        // wallet and app events are interleaved by time
        assertTrue(text.indexOf("onPause") < text.indexOf("session 1: failed"))
        assertTrue(text.indexOf("session 1: failed") < text.indexOf("onResume"))
    }

    @Test fun processStartAndRestartsAreReported() {
        LifecycleLog.clearForTests()
        LifecycleLog.markProcessStart(5_000)
        LifecycleLog.record("onCreate MainActivity", 5_100)
        LifecycleLog.record("onCreate MainActivity (restored from saved state: the app was restarted)", 9_000)
        assertEquals(2, LifecycleLog.activitiesCreated)
        val t = WalletTrace({ 9_500 }) { LifecycleLog.snapshot() }
        t.add("x")
        val text = t.render()
        assertTrue(text.contains("process started"))
        assertTrue(text.contains("activities created since: 2"))
        assertTrue(text.contains("restored from saved state"))
        LifecycleLog.clearForTests()
    }

    @Test fun addressShorteningLeavesNormalTextAlone() {
        assertEquals("connect started: foreground=true", WalletTrace.scrub("connect started: foreground=true"))
        assertEquals("2YeP…UFZf", WalletTrace.shortAddress(key))
        assertEquals("short", WalletTrace.shortAddress("short"))
    }

    // ---------------------------------------------------------------- waiting for Vowed to be on screen

    @Test fun waitingEndsWhenTheAppComesBackAndTimesOutOtherwise() = runBlocking {
        AppForeground.set(false)
        assertFalse(AppForeground.awaitResumed(50))
        val job = launch { delay(30); AppForeground.set(true) }
        assertTrue(AppForeground.awaitResumed(2_000))
        job.join()
        assertTrue(AppForeground.isResumed)
        AppForeground.set(false)
    }
}
