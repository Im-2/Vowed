package app.vowed.goals

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

enum class TrustTier(val label: String, val explanation: String) {
    High("High trust", "Proved by the phone's camera or app-usage data, signed by a hardware-backed key."),
    Medium("Medium trust", "Proved by phone sensors or a timer; harder to fake than a tap, easier than a camera."),
    Low("Low trust", "Self-reported. Stakes are capped very low."),
}

/** What a demo pool asks for instead, so a "day" of one or two minutes can actually be completed. */
class DemoTarget(val value: Int, val unit: String)

/** A ready-made goal. Free-form goals typed in plain words go through the backend parser (Phase 5); every plan has this same shape. */
class GoalTemplate(
    val id: String,
    val title: String,
    val summary: String,
    val category: String,
    val metric: String,
    val value: Int,
    val unit: String,
    val direction: String,
    val proofType: String,
    val tier: TrustTier,
    val difficulty: Int,
    val window: Pair<String, String>? = null,
    val proofParams: Map<String, String> = emptyMap(),
    val demo: DemoTarget? = null,
    /** True when the goal is about a place: the app asks for the spot and keeps its coordinates on the phone only. */
    val needsPlace: Boolean = false,
    /** True when the goal names an app the user can change (usage goals). */
    val needsApp: Boolean = false,
) {
    /** The structured plan the backend validates and hashes (see shared/goal-plan.schema.json). */
    fun plan(totalDays: Int, requiredDays: Int, isDemo: Boolean = false, params: Map<String, String> = emptyMap()): JsonObject {
        val v = if (isDemo && demo != null) demo.value else value
        val u = if (isDemo && demo != null) demo.unit else unit
        val merged = proofParams + params
        return buildJsonObject {
            val builtIn = proofParams["app"]
            val chosen = params["app"]
            put("title", if (builtIn != null && chosen != null) title.replace(builtIn, chosen) else title)
            put("category", category)
            putJsonObject("cadence") {
                put("periodDays", 1)
                put("totalDays", totalDays)
                put("requiredDays", requiredDays)
            }
            putJsonObject("target") {
                put("metric", metric)
                put("value", v)
                put("unit", u)
                put("direction", direction)
            }
            putJsonArray("proofMethods") {
                add(
                    buildJsonObject {
                        put("type", proofType)
                        putJsonObject("params") { merged.forEach { (k, value) -> put(k, value) } }
                        put("trustTier", tier.name.lowercase())
                    },
                )
            }
            if (window == null) put("window", JsonNull) else putJsonObject("window") {
                put("startLocalTime", window.first)
                put("endLocalTime", window.second)
            }
            put("difficulty", difficulty)
            put("verifiable", true)
            put("unverifiableReason", JsonNull)
            put("suggestedAlternative", JsonNull)
            put("clarifyingQuestions", JsonArray(emptyList()))
        }
    }
}

object Templates {
    val all: List<GoalTemplate> = listOf(
        GoalTemplate("squats", "20 squats a day", "Counted by the camera on your phone.", "fitness", "squats", 20, "reps", "atLeast", "CAMERA_POSE", TrustTier.High, 2, demo = DemoTarget(3, "reps")),
        GoalTemplate("steps", "Walk 8,000 steps a day", "Counted by your phone's step sensor.", "steps", "steps", 8000, "steps", "atLeast", "STEPS", TrustTier.Medium, 3, demo = DemoTarget(20, "steps")),
        GoalTemplate("focus", "Focus for 2 hours a day", "An in-app timer that pauses when you leave the app.", "study", "focus", 2, "hours", "atLeast", "FOCUS_TIMER", TrustTier.Medium, 3, window = "09:00" to "21:00", demo = DemoTarget(20, "seconds")),
        GoalTemplate("notiktok", "No TikTok after 10pm", "Checked against your phone's app-usage data.", "detox", "tiktok", 0, "minutes", "atMost", "NO_USE_WINDOW", TrustTier.High, 2, window = "22:00" to "23:59", proofParams = mapOf("app" to "TikTok"), needsApp = true),
        GoalTemplate("insta", "Under 30 minutes on Instagram", "Checked against your phone's app-usage data.", "detox", "instagram", 30, "minutes", "atMost", "USAGE_LIMIT", TrustTier.High, 3, proofParams = mapOf("app" to "Instagram"), needsApp = true),
        GoalTemplate("gym", "Be at the gym for 30 minutes", "Your phone checks that you are inside the place for that long. The spot stays on your phone.", "location", "gym", 30, "minutes", "atLeast", "GEOFENCE", TrustTier.Medium, 3, proofParams = mapOf("place" to "Gym", "radiusM" to "150"), demo = DemoTarget(10, "seconds"), needsPlace = true),
        GoalTemplate("read", "Read 20 pages", "You confirm it yourself. Lowest trust, so the stake cap is small.", "study", "pages", 20, "pages", "atLeast", "SELF_ATTEST", TrustTier.Low, 1),
    )

    fun byId(id: String) = all.first { it.id == id }
}
