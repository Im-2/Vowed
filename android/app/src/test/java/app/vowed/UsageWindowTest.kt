package app.vowed

import app.vowed.ui.onboardingIdeas
import app.vowed.ui.usageWindowFor
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class UsageWindowTest {
    private val zone = ZoneId.of("UTC")
    private fun at(day: Int, h: Int, m: Int = 0) = LocalDate.of(2026, 6, day).atTime(h, m).atZone(zone).toEpochSecond()

    @Test fun sameDayWindowFinished() {
        val w = usageWindowFor(LocalTime.of(22, 0), LocalTime.of(23, 59), at(10, 23, 59) + 60, zone)
        assertEquals(at(10, 22), w.from)
        assertEquals(at(10, 23, 59), w.to)
        assertTrue(w.finished)
    }

    @Test fun sameDayWindowStillRunning() {
        val w = usageWindowFor(LocalTime.of(22, 0), LocalTime.of(23, 59), at(10, 23), zone)
        assertFalse(w.finished)
        assertEquals(at(10, 23), w.to)
    }

    @Test fun overnightWindowEndsNextMorning() {
        // 23:00 to 06:00 checked at 07:00: yesterday's window, 7 hours long, finished
        val w = usageWindowFor(LocalTime.of(23, 0), LocalTime.of(6, 0), at(11, 7), zone)
        assertEquals(at(10, 23), w.from)
        assertEquals(at(11, 6), w.to)
        assertTrue(w.finished)
    }

    @Test fun overnightWindowMidway() {
        val w = usageWindowFor(LocalTime.of(23, 0), LocalTime.of(6, 0), at(11, 2), zone)
        assertFalse(w.finished)
        assertEquals(at(10, 23), w.from)
    }

    @Test fun onboardingIdeasAreMixed() {
        repeat(50) { seed ->
            val ideas = onboardingIdeas(seed.toLong())
            assertEquals(4, ideas.size)
            assertEquals(4, ideas.map { it.first }.toSet().size)
        }
    }
}
