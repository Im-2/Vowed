package app.vowed

import app.vowed.letters.Letter
import app.vowed.letters.LetterCipher
import app.vowed.letters.LetterProgress
import app.vowed.letters.LetterRules
import app.vowed.letters.LetterStore
import app.vowed.letters.Trigger
import java.io.File
import java.math.BigInteger
import javax.crypto.KeyGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LettersTest {
    private fun cipher() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().let { k -> LetterCipher { k } }
    private fun letter(id: String, t: Trigger, deleteAfter: Boolean = false) = Letter(id, 1_000, "Dear me, keep going. $id", t, deleteAfter)
    private fun bits(vararg days: Int) = days.fold(BigInteger.ZERO) { a, d -> a.setBit(d) }

    @Test fun milestoneLetterIsDueOnlyOnceTheDaysAreReached() {
        val ls = listOf(letter("a", Trigger.Milestone(7)))
        assertTrue(LetterRules.due(ls, LetterProgress(6, false)).isEmpty())
        assertEquals(listOf("a"), LetterRules.due(ls, LetterProgress(7, false)).map { it.id })
        assertEquals(listOf("a"), LetterRules.due(ls, LetterProgress(12, false)).map { it.id })
    }

    @Test fun brokenStreakLetterIsDueOnlyWhenTheStreakBroke() {
        val ls = listOf(letter("b", Trigger.StreakBroken))
        assertTrue(LetterRules.due(ls, LetterProgress(3, false)).isEmpty())
        assertEquals(listOf("b"), LetterRules.due(ls, LetterProgress(3, true)).map { it.id })
    }

    @Test fun deliveredLettersAreNotDeliveredAgain() {
        val ls = listOf(letter("a", Trigger.Milestone(3)).copy(deliveredAt = 5))
        assertTrue(LetterRules.due(ls, LetterProgress(9, true)).isEmpty())
    }

    @Test fun streakBreakNeedsADoneDayBeforeAMissedFinishedDay() {
        assertFalse(LetterRules.brokenAfterStreak(bits(), 3)) // never started: nothing to break
        assertFalse(LetterRules.brokenAfterStreak(bits(0, 1, 2), 3)) // all done
        assertFalse(LetterRules.brokenAfterStreak(bits(0, 1), 2)) // day 2 is today and still open
        assertTrue(LetterRules.brokenAfterStreak(bits(0, 1), 4)) // day 2 and 3 passed without a check-in
        assertTrue(LetterRules.brokenAfterStreak(bits(0, 2), 3)) // day 1 missed after day 0
        assertFalse(LetterRules.brokenAfterStreak(bits(1, 2), 3)) // only the very first day was missed: no streak existed yet
    }

    @Test fun simulatedDeliveriesAreLabelled() {
        assertTrue(LetterRules.reason(Trigger.Milestone(7), LetterProgress(7, false, simulated = true)).startsWith("SIMULATED"))
        assertFalse(LetterRules.reason(Trigger.Milestone(7), LetterProgress(7, false)).contains("SIMULATED"))
    }

    @Test fun storeKeepsLettersEncryptedAndReturnsThem() {
        val f = File.createTempFile("letters", ".bin").apply { deleteOnExit() }
        val store = LetterStore(f, cipher())
        store.add(letter("a", Trigger.Milestone(7)))
        store.add(letter("b", Trigger.StreakBroken, deleteAfter = true))
        assertEquals(listOf("a", "b"), store.all().map { it.id })
        assertFalse("plain text must not appear in the file", f.readBytes().decodeToString().contains("Dear me"))
        store.delete("a")
        assertEquals(listOf("b"), store.all().map { it.id })
    }

    @Test fun deliveryMarksTheLetterAndDeleteAfterReadingErasesIt() {
        val f = File.createTempFile("letters", ".bin").apply { deleteOnExit() }
        val store = LetterStore(f, cipher())
        store.add(letter("a", Trigger.Milestone(3), deleteAfter = true))
        store.add(letter("b", Trigger.Milestone(3), deleteAfter = false))
        val delivered = store.deliver(setOf("a", "b"), 99) { "You reached day 3" }
        assertEquals(2, delivered.size)
        assertEquals(99L, store.all().first { it.id == "a" }.deliveredAt)
        assertTrue(store.deliver(setOf("a"), 120) { "again" }.all { it.deliveredAt == 99L }) // delivered once
        store.finishedReading("a"); store.finishedReading("b")
        assertNull(store.all().firstOrNull { it.id == "a" })
        assertNotNull(store.all().firstOrNull { it.id == "b" })
    }

    @Test fun aTamperedFileOrAWrongKeyReadsAsEmptyInsteadOfCrashing() {
        val f = File.createTempFile("letters", ".bin").apply { deleteOnExit() }
        val store = LetterStore(f, cipher())
        store.add(letter("a", Trigger.StreakBroken))
        val bytes = f.readBytes(); bytes[bytes.size - 3] = (bytes[bytes.size - 3] + 1).toByte(); f.writeBytes(bytes)
        assertTrue(store.all().isEmpty())
        val g = File.createTempFile("letters", ".bin").apply { deleteOnExit() }
        LetterStore(g, cipher()).add(letter("x", Trigger.StreakBroken))
        assertTrue(LetterStore(g, cipher()).all().isEmpty()) // a different key
    }

    @Test fun cipherUsesAFreshIvAndRejectsShortInput() {
        val c = cipher()
        val a = c.encrypt("same".encodeToByteArray()); val b = c.encrypt("same".encodeToByteArray())
        assertFalse(a.contentEquals(b))
        assertEquals("same", c.decrypt(a).decodeToString())
        try { c.decrypt(ByteArray(5)); fail() } catch (_: IllegalArgumentException) { }
    }
}
