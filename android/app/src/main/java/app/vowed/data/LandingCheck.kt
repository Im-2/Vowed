package app.vowed.data

import kotlinx.coroutines.delay

/** The server's answer to "did the step land on chain?" */
@kotlinx.serialization.Serializable data class CheckRequest(val pool: String, val kind: String)
@kotlinx.serialization.Serializable data class CheckResponse(val landed: Boolean, val detail: String, val signature: String? = null, val status: String? = null)
@kotlinx.serialization.Serializable data class SubmitRequest(val transaction: String)
@kotlinx.serialization.Serializable data class SubmitResponse(val signature: String, val alreadySubmitted: Boolean = false)

/** What the chain says about a step whose outcome the wallet never told Vowed. */
sealed interface Landing {
    /** it is on chain: the person is in; [signature] is a recent signature touching the pool, when the server found one */
    data class Landed(val signature: String?, val detail: String) : Landing

    /** the chain was read and the step is not there */
    data class NotLanded(val detail: String) : Landing

    /** the chain could not be read at all (server or network down) */
    data class Unknown(val reason: String) : Landing
}

/**
 * "Session closed or no answer after the request was sent" is an UNKNOWN outcome, not a failure: the wallet may have sent the transaction and then lost the
 * connection. Before "Didn't go through" is shown, and before another signature is asked for (which could stake twice), the server reads the chain
 * every [everyMs] for up to [totalMs] and says whether the step is there.
 */
class LandingCheck(
    private val check: suspend () -> CheckResponse,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val totalMs: Long = 20_000,
    private val everyMs: Long = 2_000,
    private val log: (String) -> Unit = {},
) {
    suspend fun poll(): Landing {
        var last: Landing = Landing.Unknown("not asked")
        var asked = 0L
        while (true) {
            last = try {
                val r = check()
                log("landing check ${asked / everyMs + 1}: ${if (r.landed) "LANDED" else "not on chain"} (${r.detail})${r.signature?.let { ", recent signature $it" } ?: ""}")
                if (r.landed) return Landing.Landed(r.signature, r.detail) else Landing.NotLanded(r.detail)
            } catch (e: ApiException) {
                log("landing check ${asked / everyMs + 1}: failed (${e.code}: ${e.message})")
                Landing.Unknown("${e.code}: ${e.message}")
            }
            if (asked + everyMs > totalMs) return last
            pause(everyMs)
            asked += everyMs
        }
    }
}
