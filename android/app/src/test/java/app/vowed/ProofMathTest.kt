package app.vowed

import app.vowed.core.PlanHash
import app.vowed.data.ProofTarget
import app.vowed.proof.DwellTracker
import app.vowed.proof.FocusTimer
import app.vowed.proof.ProofKind
import app.vowed.proof.ProofMath
import app.vowed.proof.UsageEvt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProofMathTest {
    @Test fun focusTimerOnlyCountsForegroundTime() {
        val t = FocusTimer()
        t.start(100)
        assertEquals(10L, t.focusedSeconds(110))
        t.pause(120) // app left the foreground
        assertEquals(20L, t.focusedSeconds(500)) // no growth while away
        t.resume(500)
        assertEquals(25L, t.focusedSeconds(505))
        t.pause(505); t.pause(600) // pausing twice changes nothing
        assertEquals(25L, t.focusedSeconds(700))
        assertEquals(100L, t.startedAt)
    }

    @Test fun focusTimerIgnoresResumeBeforeStart() {
        val t = FocusTimer()
        t.resume(50)
        assertEquals(0L, t.focusedSeconds(80))
        assertFalse(t.running)
    }

    @Test fun dwellCountsOnlyContinuousPresenceWithFreshFixes() {
        val d = DwellTracker(maxGap = 90)
        d.update(0, true); d.update(30, true); d.update(60, true)
        assertEquals(60L, d.dwellSeconds)
        d.update(80, false) // left
        d.update(100, true) // came back: the interval 80..100 does not count
        assertEquals(60L, d.dwellSeconds)
        d.update(130, true)
        assertEquals(90L, d.dwellSeconds)
        d.update(1000, true) // a long silence is not counted either
        assertEquals(90L, d.dwellSeconds)
    }

    @Test fun foregroundSecondsHandlesOverlapsAndOpenIntervals() {
        val pk = setOf("tik.tok")
        val ev = listOf(
            UsageEvt("tik.tok", 100, true), UsageEvt("tik.tok", 160, false), // 60 s
            UsageEvt("other", 170, true), UsageEvt("other", 900, false), // not watched
            UsageEvt("tik.tok", 200, true), // still open at the end
        )
        assertEquals(160L, ProofMath.foregroundSeconds(ev, pk, 0, 300))
        // the window starts while the app is already in the foreground
        assertEquals(30L, ProofMath.foregroundSeconds(listOf(UsageEvt("tik.tok", 150, false)), pk, 120, 400))
        // the window cuts an interval
        assertEquals(20L, ProofMath.foregroundSeconds(listOf(UsageEvt("tik.tok", 100, true), UsageEvt("tik.tok", 160, false)), pk, 140, 400))
        assertEquals(0L, ProofMath.foregroundSeconds(emptyList(), pk, 0, 100))
    }

    private fun target(v: Double, unit: String, dir: String) = ProofTarget("m", v, unit, dir)

    @Test fun metRulesMirrorTheBackend() {
        assertTrue(ProofMath.met(ProofKind.STEPS, target(8000.0, "steps", "atLeast"), mapOf("steps" to 8000)))
        assertFalse(ProofMath.met(ProofKind.STEPS, target(8000.0, "steps", "atLeast"), mapOf("steps" to 7999)))
        assertTrue(ProofMath.met(ProofKind.FOCUS_TIMER, target(2.0, "hours", "atLeast"), mapOf("focusedSeconds" to 7200)))
        assertFalse(ProofMath.met(ProofKind.FOCUS_TIMER, target(2.0, "hours", "atLeast"), mapOf("focusedSeconds" to 7199)))
        assertFalse(ProofMath.met(ProofKind.FOCUS_TIMER, target(2.0, "parsecs", "atLeast"), mapOf("focusedSeconds" to 9999))) // unknown unit
        assertTrue(ProofMath.met(ProofKind.GEOFENCE, target(30.0, "minutes", "atLeast"), mapOf("inside" to true, "dwellSeconds" to 1800)))
        assertFalse(ProofMath.met(ProofKind.GEOFENCE, target(30.0, "minutes", "atLeast"), mapOf("inside" to false, "dwellSeconds" to 5000)))
        assertTrue(ProofMath.met(ProofKind.USAGE_LIMIT, target(30.0, "minutes", "atMost"), mapOf("usageSeconds" to 1800)))
        assertFalse(ProofMath.met(ProofKind.USAGE_LIMIT, target(30.0, "minutes", "atMost"), mapOf("usageSeconds" to 1801)))
        assertTrue(ProofMath.met(ProofKind.NO_USE_WINDOW, target(0.0, "minutes", "atMost"), mapOf("usageSecondsInWindow" to 0)))
        assertFalse(ProofMath.met(ProofKind.NO_USE_WINDOW, target(0.0, "minutes", "atMost"), mapOf("usageSecondsInWindow" to 1)))
        assertTrue(ProofMath.met(ProofKind.SELF_ATTEST, target(1.0, "x", "atLeast"), mapOf("done" to true)))
        assertFalse(ProofMath.met(ProofKind.SELF_ATTEST, target(1.0, "x", "atLeast"), mapOf("done" to false)))
        assertFalse(ProofMath.met(ProofKind.STEPS, target(1.0, "steps", "atLeast"), emptyMap()))
    }

    @Test fun canonicalJsonMatchesTheBackendProofVectors() {
        val vectors = Json.parseToJsonElement(File("../../shared/test-vectors/proof-package.json").readText()).jsonArray
        assertTrue(vectors.size >= 5)
        for (v in vectors) {
            val o = v.jsonObject
            val pkg: JsonObject = o["package"]!!.jsonObject
            assertEquals(o["canonical"]!!.jsonPrimitive.content, PlanHash.canonicalJson(pkg))
        }
    }
}
