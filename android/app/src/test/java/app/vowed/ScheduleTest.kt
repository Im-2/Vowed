package app.vowed

import app.vowed.core.Schedule
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.math.BigInteger

/** The schedule must equal the backend's and the program's: all three read shared/test-vectors/day-index.json. */
class ScheduleTest {
    private val vectors: JsonObject = Json.parseToJsonElement(File("../../shared/test-vectors/day-index.json").readText()).jsonObject

    @Test fun dayIndexMatchesSharedVectors() {
        val list = vectors["day_index"]!!.jsonArray
        assertTrue(list.size > 100)
        for (v in list) {
            val o = v.jsonObject
            assertEquals(o.toString(), o["day_index"]!!.jsonPrimitive.long, Schedule.dayIndex(o["now"]!!.jsonPrimitive.long, o["start_ts"]!!.jsonPrimitive.long, o["tz"]!!.jsonPrimitive.int))
        }
    }

    @Test fun windowMatchesSharedVectors() {
        for (v in vectors["window"]!!.jsonArray) {
            val o = v.jsonObject
            val (opens, closes) = Schedule.windowBounds(o["start_ts"]!!.jsonPrimitive.long, o["tz"]!!.jsonPrimitive.int, o["day"]!!.jsonPrimitive.int)
            val now = o["now"]!!.jsonPrimitive.long
            assertEquals(o.toString(), o["ok"]!!.jsonPrimitive.boolean, now >= opens && now < closes)
        }
    }

    @Test fun demoDayIndexAndWindowMatchSharedVectors() {
        for (v in vectors["demo_day_index"]!!.jsonArray) {
            val o = v.jsonObject
            assertEquals(o.toString(), o["day_index"]!!.jsonPrimitive.long, Schedule.demoDayIndex(o["now"]!!.jsonPrimitive.long, o["start_ts"]!!.jsonPrimitive.long, o["day_secs"]!!.jsonPrimitive.long))
        }
        for (v in vectors["demo_window"]!!.jsonArray) {
            val o = v.jsonObject
            val (opens, closes) = Schedule.demoWindowBounds(o["start_ts"]!!.jsonPrimitive.long, o["day_secs"]!!.jsonPrimitive.long, o["day"]!!.jsonPrimitive.int)
            val now = o["now"]!!.jsonPrimitive.long
            assertEquals(o.toString(), o["ok"]!!.jsonPrimitive.boolean, now >= opens && now < closes)
        }
    }

    @Test fun streakSkipsAnUnfinishedToday() {
        val bits = BigInteger.valueOf(0b0111) // days 0,1,2 done
        assertEquals(3, Schedule.currentStreak(bits, 3, 7)) // today (3) not done: streak not broken
        assertEquals(3, Schedule.currentStreak(bits, 2, 7))
        assertEquals(0, Schedule.currentStreak(BigInteger.valueOf(0b0001), 3, 7)) // yesterday missed
        assertEquals(0, Schedule.currentStreak(BigInteger.ZERO, 0, 7))
    }
}
