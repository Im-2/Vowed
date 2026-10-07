package app.vowed.letters

import java.math.BigInteger
import kotlinx.serialization.Serializable

/** When a letter is delivered. */
@Serializable
sealed class Trigger {
    /** After the person has completed this many days in a challenge. */
    @Serializable data class Milestone(val days: Int) : Trigger()

    /** After a run of check-ins is broken by a missed day. */
    @Serializable data object StreakBroken : Trigger()
}

/**
 * A letter to your future self. It lives only on this phone, encrypted with a key held in the Android Keystore (see [LetterStore]);
 * nothing about it is uploaded. Losing the phone loses the letters.
 */
@Serializable
data class Letter(
    val id: String,
    val createdAt: Long,
    val text: String,
    val trigger: Trigger,
    /** erase the letter once the person has read it */
    val deleteAfterReading: Boolean = false,
    val deliveredAt: Long? = null,
    /** why it was delivered, shown above the letter ("You reached day 7") */
    val deliveredBecause: String? = null,
)

/** What the app knows about the person's progress, derived from the same synced state as the Today screen. */
data class LetterProgress(
    /** the most days completed in a single challenge */
    val mostDaysCompleted: Int,
    /** true when a run of check-ins ended with a missed day */
    val streakBroken: Boolean,
    /** set for simulated progress (the debug buttons), so the reason says so */
    val simulated: Boolean = false,
)

object LetterRules {
    /** Letters whose trigger has been met and that were not delivered yet. */
    fun due(letters: List<Letter>, p: LetterProgress): List<Letter> = letters.filter { it.deliveredAt == null && met(it.trigger, p) }

    fun met(t: Trigger, p: LetterProgress): Boolean = when (t) {
        is Trigger.Milestone -> p.mostDaysCompleted >= t.days
        Trigger.StreakBroken -> p.streakBroken
    }

    fun reason(t: Trigger, p: LetterProgress): String {
        val base = when (t) {
            is Trigger.Milestone -> "You reached ${t.days} days."
            Trigger.StreakBroken -> "Your streak was broken."
        }
        return if (p.simulated) "SIMULATED: $base" else base
    }

    /** The streak is broken when a finished day was missed right after a day that was done. [bits] bit d is set when day d was checked in; [today] is the current day index (still open, so it cannot count as missed). */
    fun brokenAfterStreak(bits: BigInteger, today: Int): Boolean {
        for (d in 1 until today) if (!bits.testBit(d) && bits.testBit(d - 1)) return true
        return false
    }

    fun describe(t: Trigger): String = when (t) {
        is Trigger.Milestone -> "when I reach ${t.days} days"
        Trigger.StreakBroken -> "if my streak breaks"
    }
}
