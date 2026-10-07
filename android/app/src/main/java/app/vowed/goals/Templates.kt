package app.vowed.goals

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

enum class TrustTier(val label: String, val explanation: String) {
    High("High trust", "Proved by the phone's camera or app-usage data, signed by a hardware-backed key."),
    Medium("Medium trust", "Proved by phone sensors or a timer; harder to fake than a tap, easier than a camera."),
    Low("Low trust", "Self-reported. Stakes are capped very low."),
}

/** A ready-made goal. Free-form goals typed in plain words arrive later; for now every plan comes from a template. */
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
    val defaultDays: Int = 3,
    val defaultRequired: Int = 2,
) {
    /** The structured plan the backend validates and hashes (see shared/goal-plan.schema.json). */
    fun plan(totalDays: Int, requiredDays: Int): JsonObject = buildJsonObject {
        put("title", title)
        put("category", category)
        putJsonObject("cadence") {
            put("periodDays", 1)
            put("totalDays", totalDays)
            put("requiredDays", requiredDays)
        }
        putJsonObject("target") {
            put("metric", metric)
            put("value", value)
            put("unit", unit)
            put("direction", direction)
        }
        putJsonArray("proofMethods") {
            add(
                buildJsonObject {
                    put("type", proofType)
                    putJsonObject("params") { proofParams.forEach { (k, v) -> put(k, v) } }
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

object Templates {
    val all: List<GoalTemplate> = listOf(
        GoalTemplate("squats", "20 squats a day", "Counted by the camera on your phone.", "fitness", "squats", 20, "reps", "atLeast", "CAMERA_POSE", TrustTier.High, 2),
        GoalTemplate("steps", "Walk 8,000 steps a day", "Counted by your phone's step sensor.", "steps", "steps", 8000, "steps", "atLeast", "STEPS", TrustTier.Medium, 3),
        GoalTemplate("focus", "Focus for 2 hours a day", "An in-app timer that pauses when you leave the app.", "study", "focus", 2, "hours", "atLeast", "FOCUS_TIMER", TrustTier.Medium, 3, window = "09:00" to "21:00"),
        GoalTemplate("notiktok", "No TikTok after 10pm", "Checked against your phone's app-usage data.", "detox", "tiktok", 0, "minutes", "atMost", "NO_USE_WINDOW", TrustTier.High, 2, window = "22:00" to "23:59", proofParams = mapOf("app" to "TikTok")),
    )

    fun byId(id: String) = all.first { it.id == id }
}
