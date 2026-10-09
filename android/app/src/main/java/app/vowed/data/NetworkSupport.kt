package app.vowed.data

import app.vowed.core.TxChecker
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Network failures, in one place so they can be tested without a phone.
 *
 * Why this exists: on real phones the sign-in sometimes failed with "Unable to resolve host ... No address associated with hostname"
 * even though the phone's internet was fine. That text is what Android gives an app whose network access is blocked at that moment (for
 * example an app that is in the background while another app, the wallet, is in front, on a phone with Data Saver or battery limits).
 * The sign-in now asks the server for its nonce BEFORE the wallet opens, so nothing needs the network while the wallet is in front
 * (see [SignInNonce]); these helpers add one quiet retry for DNS and connect failures and say plainly what happened.
 */
object NetworkSupport {
    const val UNREACHABLE =
        "Vowed couldn't reach its server. Check your internet connection and try again. The server may take up to a minute to wake up."

    /** Up to this many extra tries after the first, for DNS and connect failures only. No loops. */
    const val MAX_RETRIES = 2
    const val RETRY_WAIT_MS = 1_000L

    /** True for a failure of the connection itself (the request never reached the server), which is safe to try again. */
    fun isRetryable(e: IOException): Boolean = when (e) {
        is UnknownHostException, is ConnectException, is NoRouteToHostException -> true
        is SocketTimeoutException -> e.message?.contains("connect", ignoreCase = true) == true
        else -> false
    }

    /** The raw technical text for the "Details" expander. */
    fun detail(e: Throwable): String = "${e.javaClass.simpleName}: ${e.message ?: "no message"}"

    fun unreachable(e: Throwable): ApiException = ApiException(0, "network", UNREACHABLE, detail(e))
}

/** What "Check connection" found. */
data class ConnectionResult(val ok: Boolean, val summary: String, val detail: String?) {
    companion object {
        fun passed(ms: Long, network: String?) = ConnectionResult(true, "Connected. The server answered in ${TxChecker.formatMillis(ms)}${if (network != null) " (network: $network)" else ""}.", null)

        /** [e] is whatever went wrong calling /v1/health. */
        fun failed(e: Throwable): ConnectionResult = when {
            e is ApiException && e.code == "network" -> ConnectionResult(false, NetworkSupport.UNREACHABLE, e.detail)
            e is ApiException -> ConnectionResult(false, "The server answered, but not as expected: ${e.message}", "HTTP ${e.status}, code ${e.code}")
            else -> ConnectionResult(false, "The check could not finish.", NetworkSupport.detail(e))
        }
    }
}

/**
 * The sign-in nonce, fetched while Vowed is on screen. [prepare] asks the server for an "open" nonce BEFORE the wallet opens; if that
 * fails (for example an older server), [forWallet] falls back to asking after the wallet has said which address it is.
 */
class SignInNonce private constructor(private val prefetched: NonceResponse?) {
    val wasPrefetched: Boolean get() = prefetched != null

    suspend fun forWallet(address: String, fetch: suspend (String) -> NonceResponse): NonceResponse = prefetched ?: fetch(address)

    companion object {
        suspend fun prepare(fetchOpen: suspend () -> NonceResponse): SignInNonce = SignInNonce(runCatching { fetchOpen() }.getOrNull())
    }
}
