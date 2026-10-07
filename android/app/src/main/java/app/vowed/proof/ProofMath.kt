package app.vowed.proof

import app.vowed.data.ProofTarget

/** Counts only the time the app is in the foreground. [now] is injected so the logic is testable without a clock. */
class FocusTimer {
    private var accumulated = 0L
    private var runningSince: Long? = null
    var startedAt: Long? = null
        private set

    val running: Boolean get() = runningSince != null

    fun start(now: Long) {
        if (startedAt == null) startedAt = now
        if (runningSince == null) runningSince = now
    }

    /** Called when the app leaves the foreground: time stops counting. */
    fun pause(now: Long) {
        runningSince?.let { accumulated += (now - it).coerceAtLeast(0) }
        runningSince = null
    }

    fun resume(now: Long) {
        if (startedAt != null && runningSince == null) runningSince = now
    }

    fun focusedSeconds(now: Long): Long = accumulated + (runningSince?.let { (now - it).coerceAtLeast(0) } ?: 0)

    fun reset() {
        accumulated = 0
        runningSince = null
        startedAt = null
    }
}

/** Time spent inside a place. A gap between two fixes longer than [maxGap] seconds is not counted (the phone may have left). */
class DwellTracker(private val maxGap: Long = 90) {
    private var lastTs: Long? = null
    private var lastInside = false
    var dwellSeconds = 0L
        private set
    var startedAt: Long? = null
        private set

    fun update(now: Long, inside: Boolean) {
        if (startedAt == null) startedAt = now
        val prev = lastTs
        if (prev != null && lastInside && inside && now - prev <= maxGap) dwellSeconds += now - prev
        lastTs = now
        lastInside = inside
    }
}

/** One foreground or background transition read from UsageEvents. */
class UsageEvt(val pkg: String, val ts: Long, val foreground: Boolean)

object ProofMath {
    /** Seconds the given packages spent in the foreground between [from] and [to] (all unix seconds). */
    fun foregroundSeconds(events: List<UsageEvt>, packages: Set<String>, from: Long, to: Long): Long {
        var total = 0L
        val since = HashMap<String, Long>()
        for (e in events.sortedBy { it.ts }) {
            if (e.pkg !in packages) continue
            if (e.foreground) {
                since.putIfAbsent(e.pkg, e.ts)
            } else {
                val s = since.remove(e.pkg) ?: from // already in the foreground when the window opened
                total += (minOf(e.ts, to) - maxOf(s, from)).coerceAtLeast(0)
            }
        }
        for ((_, s) in since) total += (to - maxOf(s, from)).coerceAtLeast(0) // still in the foreground at the end
        return total
    }

    fun toSeconds(value: Double, unit: String): Double? = when (unit.lowercase()) {
        "s", "sec", "second", "seconds" -> value
        "min", "minute", "minutes" -> value * 60
        "h", "hr", "hour", "hours" -> value * 3600
        else -> null
    }

    /**
     * Whether the metrics satisfy the plan target. Mirrors backend evaluateProof for the parts a phone can know;
     * the server stays the judge.
     */
    fun met(kind: ProofKind, target: ProofTarget, metrics: Map<String, Any>): Boolean {
        fun n(k: String): Double? = (metrics[k] as? Number)?.toDouble()
        return when (kind) {
            ProofKind.SELF_ATTEST -> metrics["done"] == true
            ProofKind.STEPS -> (n("steps") ?: return false) >= target.value
            ProofKind.FOCUS_TIMER -> (n("focusedSeconds") ?: return false) >= (toSeconds(target.value, target.unit) ?: return false)
            ProofKind.GEOFENCE -> metrics["inside"] == true && (n("dwellSeconds") ?: return false) >= (toSeconds(target.value, target.unit) ?: return false)
            ProofKind.USAGE_LIMIT -> (n("usageSeconds") ?: return false) <= (toSeconds(target.value, target.unit) ?: return false)
            ProofKind.NO_USE_WINDOW -> (n("usageSecondsInWindow") ?: return false) <= (toSeconds(target.value, target.unit) ?: return false)
            ProofKind.CAMERA_POSE -> metrics["livenessPassed"] == true && (n("reps") ?: return false) >= target.value
        }
    }
}
