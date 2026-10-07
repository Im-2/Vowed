package app.vowed.proof

import android.util.Base64
import app.vowed.core.PlanHash
import app.vowed.core.sha256
import app.vowed.data.BackendApi
import app.vowed.data.ProofResult
import app.vowed.data.ProofSession
import app.vowed.data.ProofSessionRequest
import app.vowed.device.DeviceKey
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Proof types, named exactly as the backend names them (backend/src/domain/plan.ts). */
enum class ProofKind(val trust: String, val label: String, val needsPermission: String?) {
    SELF_ATTEST("low", "Tap to confirm", null),
    FOCUS_TIMER("medium", "Focus timer", null),
    STEPS("medium", "Step counter", android.Manifest.permission.ACTIVITY_RECOGNITION),
    GEOFENCE("medium", "Location", android.Manifest.permission.ACCESS_FINE_LOCATION),
    USAGE_LIMIT("high", "App usage limit", null),
    NO_USE_WINDOW("high", "App-free window", null),
    CAMERA_POSE("high", "Camera reps", android.Manifest.permission.CAMERA),
}

/**
 * What a collector produced. Only [metrics] and the two timestamps go to the backend, plus a hash of [evidence]
 * (a short local summary that never leaves the phone). Metrics must be whole numbers, strings or booleans: they are
 * part of the signed canonical JSON and must be byte-identical on both sides.
 */
class Collected(val metrics: Map<String, Any>, val startedAt: Long, val endedAt: Long, val evidence: String)

/**
 * Runs the proof protocol of SPEC 7.4: open a session (the server returns a one-time nonce), build the package,
 * sign it with the Keystore proof key (no wallet prompt), submit it. The proof key is the one the stake was joined with.
 */
class ProofEngine(private val api: BackendApi, private val key: () -> DeviceKey, private val clock: () -> Long = { System.currentTimeMillis() / 1000 }) {
    suspend fun open(pool: String, dayIndex: Int, kind: ProofKind): ProofSession = api.proofSession(ProofSessionRequest(pool, dayIndex, kind.name))

    /** The signed package for [session]; separated from [submit] so tests can check it and the debug tools can replay it. */
    fun buildPackage(session: ProofSession, pool: String, kind: ProofKind, c: Collected): JsonObject {
        val dk = key()
        val unsigned = buildJsonObject {
            put("sessionId", session.sessionId)
            put("nonce", session.nonce)
            put("challengeId", pool)
            put("dayIndex", session.dayIndex)
            put("proofType", kind.name)
            put("metrics", buildJsonObject {
                for ((k, v) in c.metrics) when (v) {
                    is Boolean -> put(k, v)
                    is Int -> put(k, v)
                    is Long -> put(k, v)
                    is String -> put(k, v)
                    is List<*> -> put(k, kotlinx.serialization.json.JsonArray(v.map { JsonPrimitive(it.toString()) }))
                    else -> error("metric $k has an unsupported type; use whole numbers, strings or booleans")
                }
            })
            put("startedAt", c.startedAt)
            put("endedAt", c.endedAt)
            put("evidenceHash", sha256(c.evidence.toByteArray()).joinToString("") { "%02x".format(it) })
            put("deviceKeyId", dk.id())
        }
        val sig = dk.sign(PlanHash.canonicalJson(unsigned).toByteArray(Charsets.UTF_8))
        return JsonObject(unsigned + ("signature" to JsonPrimitive(Base64.encodeToString(sig, Base64.NO_WRAP))))
    }

    suspend fun submit(session: ProofSession, pool: String, kind: ProofKind, c: Collected): ProofResult =
        api.submitProof(buildPackage(session, pool, kind, c))

    /** Submits an already-built package again (used by the debug "replay" button to show the server refuses it). */
    suspend fun resubmit(pkg: JsonObject): ProofResult = api.submitProof(pkg)

    fun now(): Long = clock()
}
