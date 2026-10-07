package app.vowed.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Canonical JSON (keys sorted recursively, no whitespace) and the SHA-256 of a GoalPlan. Identical to
 * backend/src/domain/plan.ts: both are checked against shared/test-vectors/plan-hash.json.
 * Plans only contain strings, booleans, null and whole numbers, which is what keeps the two implementations byte-identical.
 */
object PlanHash {
    fun canonicalJson(e: JsonElement): String = when (e) {
        is JsonNull -> "null"
        is JsonPrimitive -> if (e.isString) quote(e.content) else e.content
        is JsonArray -> e.joinToString(",", "[", "]") { canonicalJson(it) }
        is JsonObject -> e.entries.sortedBy { it.key }
            .joinToString(",", "{", "}") { quote(it.key) + ":" + canonicalJson(it.value) }
    }

    fun hash(plan: JsonElement): ByteArray = sha256(canonicalJson(plan).toByteArray(Charsets.UTF_8))

    /** Same escaping as JavaScript's JSON.stringify for strings. */
    private fun quote(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\b' -> sb.append("\\b")
                c == '\u000C' -> sb.append("\\f")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }
}
