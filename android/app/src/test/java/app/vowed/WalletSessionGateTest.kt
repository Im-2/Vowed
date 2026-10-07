package app.vowed

import app.vowed.wallet.WalletSessionGate
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletSessionGateTest {
    private class Clock { var t = 1_000L }

    private fun gate(clock: Clock, sleeps: MutableList<Long>) = WalletSessionGate(minGapMs = 1_500, retryDelayMs = 2_000, now = { clock.t }, sleep = { sleeps += it; clock.t += it })

    @Test fun secondSessionWaitsForTheGapAfterTheFirst() = runBlocking {
        val clock = Clock(); val sleeps = mutableListOf<Long>()
        val g = gate(clock, sleeps)
        g.run<String>({ false }) { clock.t += 300; "first" }
        clock.t += 400 // the app builds the next request
        g.run<String>({ false }) { "second" }
        assertEquals(listOf(1_100L), sleeps) // 1500 gap minus the 400 that already passed
    }

    @Test fun noWaitBeforeTheFirstSessionOrWhenEnoughTimeHasPassed() = runBlocking {
        val clock = Clock(); val sleeps = mutableListOf<Long>()
        val g = gate(clock, sleeps)
        g.run<String>({ false }) { "a" }
        clock.t += 5_000
        g.run<String>({ false }) { "b" }
        assertTrue(sleeps.isEmpty())
    }

    @Test fun aFailedConnectionStepIsRetriedOnceAfterAPause() = runBlocking {
        val clock = Clock(); val sleeps = mutableListOf<Long>()
        var calls = 0
        val r = gate(clock, sleeps).run<String>({ it == "assoc-failed" }) { if (++calls == 1) "assoc-failed" else "ok" }
        assertEquals("ok", r); assertEquals(2, calls); assertEquals(listOf(2_000L), sleeps)
    }

    @Test fun aSecondConnectionFailureIsReportedNotRetriedAgain() = runBlocking {
        val clock = Clock(); val sleeps = mutableListOf<Long>()
        var calls = 0
        val r = gate(clock, sleeps).run<String>({ it == "assoc-failed" }) { calls++; "assoc-failed" }
        assertEquals("assoc-failed", r); assertEquals(2, calls)
    }

    @Test fun aDecisionByTheUserIsNeverRetried() = runBlocking {
        val clock = Clock(); val sleeps = mutableListOf<Long>()
        var calls = 0
        val r = gate(clock, sleeps).run<String>({ it == "assoc-failed" }) { calls++; "declined" }
        assertEquals("declined", r); assertEquals(1, calls); assertTrue(sleeps.isEmpty())
    }

    @Test fun sessionsNeverOverlap() = runBlocking {
        val g = WalletSessionGate(minGapMs = 0, retryDelayMs = 0)
        var running = 0; var maxRunning = 0
        val jobs = (1..5).map { async { g.run<Int>({ false }) { running++; maxRunning = maxOf(maxRunning, running); delay(20); running--; it } } }
        jobs.forEach { it.await() }
        assertEquals(1, maxRunning)
    }

    @Test fun recognisesOnlyConnectionStepFailures() {
        assertTrue(WalletSessionGate.isAssociationFailure("Local association was cancelled before connected"))
        assertTrue(WalletSessionGate.isAssociationFailure("Failed establishing local association with wallet"))
        assertTrue(WalletSessionGate.isAssociationFailure("Timed out waiting for local association to be ready"))
        assertFalse(WalletSessionGate.isAssociationFailure("User declined authorization request"))
        assertFalse(WalletSessionGate.isAssociationFailure("Transaction was rejected: simulation failed"))
        assertFalse(WalletSessionGate.isAssociationFailure(null))
    }
}
