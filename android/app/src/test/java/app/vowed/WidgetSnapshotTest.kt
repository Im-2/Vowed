package app.vowed

import app.vowed.ui.coachStarterText
import app.vowed.widget.WidgetSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetSnapshotTest {
    private fun s(due: Int, done: Int, streak: Int, signedIn: Boolean = true) = WidgetSnapshot(signedIn, due, done, streak, 0)

    @Test fun headlineFollowsTodaysState() {
        assertEquals("Open Vowed to sign in", s(0, 0, 0, signedIn = false).headline())
        assertEquals("1 check-in to do today", s(1, 0, 0).headline())
        assertEquals("3 check-ins to do today", s(3, 2, 5).headline())
        assertEquals("All done today", s(0, 2, 5).headline())
        assertEquals("Nothing due right now", s(0, 0, 0).headline())
    }

    @Test fun streakLineIsSingularOrPlural() {
        assertEquals("No streak yet", s(0, 0, 0).streakLine())
        assertEquals("Streak 1 day", s(0, 0, 1).streakLine())
        assertEquals("Streak 7 days", s(0, 0, 7).streakLine())
    }

    @Test fun everyCoachCategoryHasAStarterGoalThatIsNotACameraGoalByDefault() {
        for (c in listOf("study", "steps", "fitness", "detox", "sleep", "location", "custom")) assertTrue(coachStarterText(c).length > 10)
        assertTrue(coachStarterText("custom").contains("read"))
    }
}
