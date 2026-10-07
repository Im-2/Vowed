package app.vowed

import app.vowed.core.Schedule
import app.vowed.data.Challenge
import app.vowed.data.Participant
import app.vowed.data.ProofResult
import app.vowed.data.ProofTarget
import app.vowed.proof.ProofKind
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigInteger

sealed interface Submission {
    data object Idle : Submission
    data object Working : Submission
    data class Accepted(val result: ProofResult) : Submission
    data class Rejected(val reason: String) : Submission
}

/** Everything the check-in screen needs about today's proof for one challenge. */
data class CheckInState(
    val pool: String,
    val challenge: Challenge,
    val me: Participant?,
    val kind: ProofKind,
    val day: Int,
    val dayOpen: Boolean,
    val doneAlready: Boolean,
    val target: ProofTarget,
    val params: Map<String, String>,
    val place: Pair<Double, Double>?,
    val submission: Submission = Submission.Idle,
    val lastPackage: JsonObject? = null,
) {
    val title: String get() = challenge.plan?.get("title")?.jsonPrimitive?.content ?: "Today's check-in"
}

/** Facts about one of my challenges that the home screen and detail screen show. */
class DayView(val day: Int, val doneBits: BigInteger, val streak: Int, val dayOpen: Boolean, val done: Boolean, val daysCompleted: Int)

object CheckInLogic {
    fun planTarget(plan: JsonObject?): ProofTarget? {
        val t = plan?.get("target")?.jsonObject ?: return null
        return ProofTarget(
            metric = t["metric"]?.jsonPrimitive?.content ?: return null,
            value = t["value"]?.jsonPrimitive?.doubleOrNull ?: return null,
            unit = t["unit"]?.jsonPrimitive?.content ?: return null,
            direction = t["direction"]?.jsonPrimitive?.content ?: return null,
        )
    }

    fun planKind(plan: JsonObject?): ProofKind? =
        plan?.get("proofMethods")?.jsonArray?.firstOrNull()?.jsonObject?.get("type")?.jsonPrimitive?.content?.let { n -> ProofKind.entries.firstOrNull { it.name == n } }

    fun planParams(plan: JsonObject?): Map<String, String> =
        plan?.get("proofMethods")?.jsonArray?.firstOrNull()?.jsonObject?.get("params")?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()

    /** Day index, whether today's window is open and whether it is already recorded, from the same schedule rules as the program. */
    fun dayView(c: Challenge, me: Participant?, now: Long): DayView {
        val tz = me?.tzOffsetMinutes ?: 0
        val raw = Schedule.dayIndexFor(c.isDemo, c.startTs, c.daySecs.toLong(), tz, now).toInt()
        val day = raw.coerceIn(0, c.durationDays - 1)
        val bits = me?.checkinBitmap?.let { runCatching { BigInteger(it) }.getOrNull() } ?: BigInteger.ZERO
        val (opens, closes) = Schedule.windowFor(c.isDemo, c.startTs, c.daySecs.toLong(), tz, day)
        val open = c.status == "Open" && raw in 0 until c.durationDays && now >= opens && now < closes
        return DayView(day, bits, Schedule.currentStreak(bits, raw.coerceAtMost(c.durationDays - 1), c.durationDays), open, bits.testBit(day), me?.daysCompleted ?: 0)
    }
}
