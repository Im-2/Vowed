package app.vowed.core

/**
 * Day schedule of a challenge, identical to backend/src/domain/{time,schedule}.ts and the program's math.rs.
 * Normal pools: real 24-hour days in the participant's timezone. DEMO POOLS: short "days" counted from the start time.
 * Both are checked against shared/test-vectors/day-index.json by the unit tests.
 */
object Schedule {
    const val DAY_SECONDS = 86_400L
    const val CHECKIN_GRACE_SECS = 7_200L

    fun localDayNumber(ts: Long, tzOffsetMinutes: Int): Long = Math.floorDiv(ts + tzOffsetMinutes * 60L, DAY_SECONDS)

    fun dayIndex(now: Long, startTs: Long, tzOffsetMinutes: Int): Long =
        localDayNumber(now, tzOffsetMinutes) - localDayNumber(startTs, tzOffsetMinutes)

    fun windowBounds(startTs: Long, tzOffsetMinutes: Int, day: Int): Pair<Long, Long> {
        val day0 = localDayNumber(startTs, tzOffsetMinutes) * DAY_SECONDS - tzOffsetMinutes * 60L
        val opens = day0 + day * DAY_SECONDS
        return opens to (opens + DAY_SECONDS + CHECKIN_GRACE_SECS)
    }

    fun demoGrace(daySecs: Long): Long = daySecs / 4

    fun demoDayIndex(now: Long, startTs: Long, daySecs: Long): Long = Math.floorDiv(now - startTs, daySecs)

    fun demoWindowBounds(startTs: Long, daySecs: Long, day: Int): Pair<Long, Long> {
        val opens = startTs + day * daySecs
        return opens to (opens + daySecs + demoGrace(daySecs))
    }

    fun windowFor(isDemo: Boolean, startTs: Long, daySecs: Long, tz: Int, day: Int) =
        if (isDemo) demoWindowBounds(startTs, daySecs, day) else windowBounds(startTs, tz, day)

    fun dayIndexFor(isDemo: Boolean, startTs: Long, daySecs: Long, tz: Int, now: Long): Long =
        if (isDemo) demoDayIndex(now, startTs, daySecs) else dayIndex(now, startTs, tz)

    /** Consecutive completed days ending at today (or yesterday if today is not done yet). */
    fun currentStreak(bitmap: java.math.BigInteger, today: Int, durationDays: Int): Int {
        var day = minOf(today, durationDays - 1)
        if (day >= 0 && !bitmap.testBit(day)) day -= 1
        var streak = 0
        while (day >= 0) {
            if (bitmap.testBit(day)) streak++ else break
            day--
        }
        return streak
    }
}
