package app.vowed.perks

import app.vowed.core.Schedule
import app.vowed.data.ChallengeDetail
import java.math.BigInteger

/** A missed day that a streak freeze could still cover. */
data class FreezeOption(val pool: String, val title: String, val dayIndex: Int)

/**
 * Which days can be frozen, by the same rules as the server (which decides in the end): the challenge is still running, the day is over
 * and past its check-in window, was missed, is not frozen yet, is at most [LOOKBACK_DAYS] days old, and the per-challenge limit is not used up.
 */
object FreezeOptions {
    const val LOOKBACK_DAYS = 3
    const val MAX_PER_POOL = 2

    fun forDetail(d: ChallengeDetail, wallet: String?, title: String, now: Long): List<FreezeOption> {
        val c = d.challenge
        val me = d.participants.firstOrNull { it.wallet == wallet } ?: return emptyList()
        if (c.status != "Open" || me.status != "Active") return emptyList()
        val done = runCatching { BigInteger(me.checkinBitmap.ifBlank { "0" }) }.getOrDefault(BigInteger.ZERO)
        val frozen = runCatching { BigInteger(me.frozenBitmap.ifBlank { "0" }) }.getOrDefault(BigInteger.ZERO)
        if (frozen.bitCount() >= MAX_PER_POOL) return emptyList()
        val today = Schedule.dayIndexFor(c.isDemo, c.startTs, c.daySecs.toLong(), me.tzOffsetMinutes, now).toInt()
        val out = ArrayList<FreezeOption>()
        for (day in maxOf(0, today - LOOKBACK_DAYS) until minOf(today, c.durationDays)) {
            if (done.testBit(day) || frozen.testBit(day)) continue
            val closes = Schedule.windowFor(c.isDemo, c.startTs, c.daySecs.toLong(), me.tzOffsetMinutes, day).second
            if (now < closes) continue
            out += FreezeOption(c.pool, title, day)
        }
        return out
    }
}
