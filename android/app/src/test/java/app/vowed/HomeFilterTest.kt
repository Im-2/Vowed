package app.vowed

import app.vowed.data.AppJson
import app.vowed.data.Challenge
import app.vowed.data.ExploreItem
import app.vowed.ui.HomeFilter
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val ME = "MeWallet11111111111111111111111111111111"
private const val NOW = 1_800_000_000L

private fun plan(title: String?, category: String = "study"): JsonObject? = title?.let { JsonObject(mapOf("title" to JsonPrimitive(it), "category" to JsonPrimitive(category))) }

private fun challenge(
    pool: String, title: String? = "Read for 30 minutes a day", status: String = "Open", participants: Int = 2, pot: String = "2000000",
    creator: String = "SomeoneElse", end: Long = NOW + 86_400, category: String = "study",
) = Challenge(
    pool = pool, creator = creator, mint = "m", vault = "v", kind = "Open", mode = "Soft", penaltyBps = 0, startTs = NOW - 86_400, endTs = end,
    joinDeadlineTs = NOW, settleAfterTs = end, durationDays = 7, requiredDays = 5, goalHash = "h", participantCount = participants, maxParticipants = 10,
    totalDeposits = pot, status = status, plan = plan(title, category),
)

/** The Home list rules: only my own, readable, non-empty challenges; finished ones go to "Past challenges". */
class HomeFilterTest {
    @Test fun aJoinedActivePoolIsShown() {
        val l = HomeFilter.split(listOf(challenge("p1")), ME, NOW) { setOf(ME, "x") }
        assertEquals(listOf("p1"), l.active.map { it.pool })
        assertTrue(l.past.isEmpty())
    }

    @Test fun aPoolIWasCreatorOfIsShownEvenWithoutLoadedDetail() {
        val l = HomeFilter.split(listOf(challenge("p1", creator = ME)), ME, NOW)
        assertEquals(1, l.active.size)
    }

    @Test fun aPoolWithoutAReadableTitleIsNotShownAnywhere() {
        val all = listOf(challenge("none", title = null), challenge("blank", title = "  "), challenge("addr", title = "Challenge 6yXB...PYWb"), challenge("full", title = "6yXBkQfJrZ1p9wD2uE4hVnS7cTgAaLmN3xKoPYWb"))
        val l = HomeFilter.split(all, ME, NOW)
        assertTrue(l.active.isEmpty())
        assertTrue(l.past.isEmpty())
    }

    @Test fun aSettledUntitledPoolIsStillHidden() {
        val l = HomeFilter.split(listOf(challenge("p", title = null, status = "Settled")), ME, NOW)
        assertTrue(l.active.isEmpty() && l.past.isEmpty())
    }

    @Test fun aPoolWithNoParticipantsAndAZeroPotIsNotShown() {
        val l = HomeFilter.split(listOf(challenge("empty", participants = 0, pot = "0", creator = ME)), ME, NOW)
        assertTrue(l.active.isEmpty() && l.past.isEmpty())
    }

    @Test fun aPoolWithMoneyButNoCountedParticipantsIsKept() {
        assertEquals(1, HomeFilter.split(listOf(challenge("p", participants = 0, pot = "5", creator = ME)), ME, NOW).active.size)
    }

    @Test fun settledSettlingVoidedAndEndedPoolsGoToPast() {
        val all = listOf(
            challenge("settled", status = "Settled"), challenge("settling", status = "Settling"), challenge("voided", status = "Voided"),
            challenge("ended", end = NOW - 10), challenge("running"),
        )
        val l = HomeFilter.split(all, ME, NOW) { setOf(ME) }
        assertEquals(listOf("running"), l.active.map { it.pool })
        assertEquals(setOf("settled", "settling", "voided", "ended"), l.past.map { it.pool }.toSet())
    }

    @Test fun aPoolIDidNotJoinAndDidNotCreateIsNotShownWhenTheDetailIsKnown() {
        val l = HomeFilter.split(listOf(challenge("p1")), ME, NOW) { setOf("someone", "else") }
        assertTrue(l.active.isEmpty())
        // when the detail is not loaded yet, the server's "mine" list is trusted
        assertEquals(1, HomeFilter.split(listOf(challenge("p1")), ME, NOW).active.size)
    }

    @Test fun hiddenPoolsAreNotShown() {
        val l = HomeFilter.split(listOf(challenge("a"), challenge("b")), ME, NOW, hidden = setOf("a"))
        assertEquals(listOf("b"), l.active.map { it.pool })
    }

    @Test fun addressLookingTextIsRecognisedAndRealTitlesAreNot() {
        assertTrue(HomeFilter.looksLikeAddress("Challenge 55DP...EcY2"))
        assertTrue(HomeFilter.looksLikeAddress("3fJ2…AQby"))
        assertFalse(HomeFilter.looksLikeAddress("Read for 30 minutes a day"))
        assertFalse(HomeFilter.looksLikeAddress("Walk 6000 steps"))
        assertNull(HomeFilter.readableTitle(challenge("x", title = "55DP...EcY2")))
    }

    @Test fun exploreFallsBackToACategoryNameNeverAnAddress() {
        fun item(title: String, category: String) = AppJson.decodeFromString<ExploreItem>(
            """{"pool":"p","title":"$title","category":"$category","mode":"Soft","mint":"m","tokenSymbol":"tUSDC","durationDays":7,"requiredDays":5,"startTs":1,"joinDeadlineTs":2,"participantCount":0,"maxParticipants":5}""",
        )
        assertEquals("Fitness challenge", HomeFilter.exploreTitle(item("", "fitness")))
        assertEquals("Screen time challenge", HomeFilter.exploreTitle(item("Challenge 6yXB...PYWb", "detox")))
        assertEquals("Custom challenge", HomeFilter.exploreTitle(item("  ", "whatever")))
        assertEquals("Read daily", HomeFilter.exploreTitle(item("Read daily", "study")))
    }

    @Test fun detailTitleFallsBackToACategoryNameToo() {
        assertEquals("Custom challenge", HomeFilter.displayTitle(challenge("p", title = null)))
        assertEquals("Study challenge", HomeFilter.displayTitle(challenge("p", title = "", category = "study")))
        assertEquals("Read for 30 minutes a day", HomeFilter.displayTitle(challenge("p")))
    }
}
