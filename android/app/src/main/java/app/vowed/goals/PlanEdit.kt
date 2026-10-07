package app.vowed.goals

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** What the user may change on the preview screen. The server validates the result again before anything is staked. */
class Edit(
    val title: String? = null,
    val value: Double? = null,
    val totalDays: Int? = null,
    val requiredDays: Int? = null,
    /** the app a usage goal watches */
    val app: String? = null,
    /** the name of the place for a place goal (the coordinates are never part of the plan) */
    val place: String? = null,
)

object PlanEdit {
    /** Whole numbers stay whole ("20", not "20.0") so the plan reads the same on the phone, the server and the chain hash. */
    fun number(v: Double): JsonPrimitive = if (v % 1.0 == 0.0 && kotlin.math.abs(v) < 9e15) JsonPrimitive(v.toLong()) else JsonPrimitive(v)

    fun apply(plan: JsonObject, e: Edit): JsonObject {
        val cadence = plan["cadence"]!!.jsonObject
        val total = e.totalDays ?: cadence["totalDays"]!!.jsonPrimitive.content.toInt()
        val required = (e.requiredDays ?: cadence["requiredDays"]!!.jsonPrimitive.content.toInt()).coerceIn(1, total)
        val target = plan["target"]!!.jsonObject
        val method = plan["proofMethods"]!!.jsonArray[0].jsonObject
        val params = method["params"]!!.jsonObject
        return JsonObject(
            plan + mapOf(
                "title" to JsonPrimitive(e.title?.trim()?.takeIf { it.isNotEmpty() } ?: plan["title"]!!.jsonPrimitive.content),
                "cadence" to buildJsonObject {
                    put("periodDays", JsonPrimitive(1))
                    put("totalDays", JsonPrimitive(total))
                    put("requiredDays", JsonPrimitive(required))
                },
                "target" to JsonObject(target + ("value" to (e.value?.let { number(it) } ?: target["value"]!!))),
                "proofMethods" to buildJsonArray {
                    add(
                        JsonObject(
                            method + ("params" to JsonObject(
                                params +
                                    (if (e.app != null && "app" in params) mapOf("app" to JsonPrimitive(e.app.trim())) else emptyMap()) +
                                    (if (e.place != null && "place" in params) mapOf("place" to JsonPrimitive(e.place.trim())) else emptyMap()),
                            )),
                        ),
                    )
                },
            ),
        )
    }

    fun title(plan: JsonObject): String = plan["title"]!!.jsonPrimitive.content
    fun value(plan: JsonObject): Double = plan["target"]!!.jsonObject["value"]!!.jsonPrimitive.doubleOrNull ?: 0.0
    fun unit(plan: JsonObject): String = plan["target"]!!.jsonObject["unit"]!!.jsonPrimitive.content
    fun totalDays(plan: JsonObject): Int = plan["cadence"]!!.jsonObject["totalDays"]!!.jsonPrimitive.content.toInt()
    fun requiredDays(plan: JsonObject): Int = plan["cadence"]!!.jsonObject["requiredDays"]!!.jsonPrimitive.content.toInt()
    fun proofType(plan: JsonObject): String = plan["proofMethods"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content
    fun param(plan: JsonObject, key: String): String? = plan["proofMethods"]!!.jsonArray[0].jsonObject["params"]!!.jsonObject[key]?.jsonPrimitive?.content
    fun element(plan: JsonObject, key: String): JsonElement? = plan[key]
}
