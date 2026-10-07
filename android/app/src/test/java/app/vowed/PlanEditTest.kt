package app.vowed

import app.vowed.core.PlanHash
import app.vowed.goals.Edit
import app.vowed.goals.PlanEdit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlanEditTest {
    private val plan: JsonObject = Json.parseToJsonElement(
        """{"title":"Walk 8,000 steps a day","category":"steps","cadence":{"periodDays":1,"totalDays":7,"requiredDays":6},
        "target":{"metric":"steps","value":8000,"unit":"steps","direction":"atLeast"},
        "proofMethods":[{"type":"STEPS","params":{},"trustTier":"medium"}],"window":null,"difficulty":3,"verifiable":true,
        "unverifiableReason":null,"suggestedAlternative":null,"clarifyingQuestions":[]}""",
    ).jsonObject

    private val usage: JsonObject = Json.parseToJsonElement(
        """{"title":"Under 30 minutes a day on Instagram","category":"detox","cadence":{"periodDays":1,"totalDays":7,"requiredDays":6},
        "target":{"metric":"app-time","value":30,"unit":"minutes","direction":"atMost"},
        "proofMethods":[{"type":"USAGE_LIMIT","params":{"app":"Instagram"},"trustTier":"high"}],"window":null,"difficulty":3,"verifiable":true,
        "unverifiableReason":null,"suggestedAlternative":null,"clarifyingQuestions":[]}""",
    ).jsonObject

    @Test fun anEmptyEditChangesNothing() {
        assertEquals(PlanHash.canonicalJson(plan), PlanHash.canonicalJson(PlanEdit.apply(plan, Edit())))
    }

    @Test fun wholeNumbersStayWhole() {
        val e = PlanEdit.apply(plan, Edit(value = 9000.0))
        assertEquals(9000.0, PlanEdit.value(e), 0.0)
        // "9000", never "9000.0": the same text the server and the chain hash use
        assertEquals("9000", e["target"]!!.jsonObject["value"].toString())
        assertEquals("1.5", PlanEdit.apply(plan, Edit(value = 1.5))["target"]!!.jsonObject["value"].toString())
    }

    @Test fun daysAreEditedAndRequiredDaysNeverExceedTheLength() {
        val e = PlanEdit.apply(plan, Edit(totalDays = 10, requiredDays = 9))
        assertEquals(10, PlanEdit.totalDays(e))
        assertEquals(9, PlanEdit.requiredDays(e))
        assertEquals(3, PlanEdit.requiredDays(PlanEdit.apply(plan, Edit(totalDays = 3))).coerceAtMost(3))
        assertEquals(3, PlanEdit.requiredDays(PlanEdit.apply(plan, Edit(totalDays = 3, requiredDays = 50))))
    }

    @Test fun anEditChangesTheHashAndAnUntouchedPlanDoesNot() {
        val a = PlanHash.hash(plan).joinToString("") { "%02x".format(it) }
        val b = PlanHash.hash(PlanEdit.apply(plan, Edit(title = "Walk more"))).joinToString("") { "%02x".format(it) }
        val c = PlanHash.hash(PlanEdit.apply(plan, Edit())).joinToString("") { "%02x".format(it) }
        assertNotEquals(a, b)
        assertEquals(a, c)
    }

    @Test fun theAppNameIsOnlyChangedWhenThePlanHasOne() {
        assertEquals("Snapchat", PlanEdit.param(PlanEdit.apply(usage, Edit(app = "Snapchat")), "app"))
        assertNull(PlanEdit.param(PlanEdit.apply(plan, Edit(app = "Snapchat")), "app")) // a steps goal does not grow an app parameter
        assertNull(PlanEdit.param(PlanEdit.apply(plan, Edit(place = "Gym")), "place"))
    }

    @Test fun aBlankTitleKeepsTheOriginal() {
        assertEquals("Walk 8,000 steps a day", PlanEdit.title(PlanEdit.apply(plan, Edit(title = "   "))))
        assertEquals("Walk more", PlanEdit.title(PlanEdit.apply(plan, Edit(title = "  Walk more "))))
    }
}
