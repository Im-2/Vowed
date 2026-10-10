package app.vowed.data

import app.vowed.wallet.SignInOutcome
import app.vowed.wallet.WalletException
import app.vowed.wallet.WalletTrace

/** The steps of signing in, each with its own failure message. Only [Wallet] can ever be a wallet or network problem. */
enum class SignInStep(val label: String) {
    Server("Reaching the Vowed server"),
    Wallet("Wallet approval"),
    Verify("Signing in with the Vowed server"),
    Register("Registering this phone"),
}

/** A step of the sign-in failed. [message] is plain language that names the step; [detail] is the short non-secret reason for "Details". */
class SignInException(val step: SignInStep, message: String, val detail: String?, cause: Throwable? = null) : Exception(message, cause)

/**
 * The sign-in as separate steps with one rule between them: **once the wallet has answered, the wallet is not asked again for a failure that
 * happened afterwards.**
 *
 *  1. the server's nonce (asked BEFORE the wallet opens, so nothing needs the network while the wallet is in front),
 *  2. ONE wallet visit (connect and sign-in together),
 *  3. the server checks the signature (/v1/auth/verify) and returns a token,
 *  4. the phone's proof key is registered (no wallet screen while the sign-in is fresh).
 *
 * The wallet's answer is kept in memory in [pending] and the server's token in [verified]; a retry of [run] starts at the first step that has not
 * finished. The wallet opens again only when its answer is missing or the server's nonce has run out.
 */
class SignInFlow(
    private val fetchNonce: suspend () -> NonceResponse,
    private val wallet: suspend (NonceResponse) -> SignInOutcome,
    private val verify: suspend (SignInOutcome) -> VerifyResponse,
    private val adopt: (VerifyResponse) -> Unit,
    private val registered: (String) -> Boolean,
    private val register: suspend (String) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var pending: SignInOutcome? = null
    private var verified: VerifyResponse? = null

    /** True while a wallet answer or a server token from an earlier try is being kept (a retry will not open the wallet). */
    val hasSavedProgress: Boolean get() = pending != null || verified != null

    /** Forgets everything kept (Reset connection, disconnect). */
    fun clear() { pending = null; verified = null }

    suspend fun run(trace: WalletTrace, onStep: (ConnectStep) -> Unit = {}): String {
        var result = verified
        if (result == null) {
            var out = pending?.takeIf { it.validUntilMs - now() > MIN_VALID_MS }
            if (out == null) {
                pending = null
                onStep(ConnectStep.Server)
                val nonce = timed(trace, "server: sign-in nonce") {
                    try {
                        fetchNonce()
                    } catch (e: ApiException) {
                        throw SignInException(SignInStep.Server, serverText("Vowed could not reach its server before opening your wallet", e), reason(e), e)
                    }
                }
                onStep(ConnectStep.Wallet)
                out = timed(trace, "wallet visit (connect and sign in)") {
                    try {
                        wallet(nonce)
                    } catch (e: WalletException) {
                        throw e
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        throw SignInException(SignInStep.Wallet, "The wallet step did not finish: ${e.message ?: e.javaClass.simpleName}", "${e.javaClass.simpleName}: ${e.message}", e)
                    }
                }
                pending = out
            } else {
                trace.add("reusing the wallet's earlier sign-in", "the wallet is NOT opened again; only the server step is repeated")
            }
            onStep(ConnectStep.SigningIn)
            result = timed(trace, "server: verify signature") {
                try {
                    verify(out)
                } catch (e: ApiException) {
                    if (e.status == 401 && (e.message.contains("nonce", true) || e.message.contains("expired", true) || e.message.contains("domain", true))) {
                        pending = null // the approval cannot be used again; the next try asks the wallet once more
                        throw SignInException(
                            SignInStep.Verify,
                            "Wallet approved, but the Vowed server did not accept the sign-in (${e.message}). Tap Try again and approve once more in your wallet.",
                            reason(e), e,
                        )
                    }
                    throw SignInException(SignInStep.Verify, serverText("Wallet approved, but signing in with the Vowed server failed", e) + " Your approval is kept: Try again will not open the wallet.", reason(e), e)
                }
            }
            adopt(result)
            verified = result
            pending = null
        }
        onStep(ConnectStep.RegisteringDevice)
        if (!registered(result.wallet)) {
            timed(trace, "register this phone") {
                try {
                    register(result.wallet)
                } catch (e: WalletException) {
                    throw SignInException(SignInStep.Register, "Registering this phone needed another wallet approval and it did not complete: ${e.message}", e.raw, e)
                } catch (e: ApiException) {
                    throw SignInException(SignInStep.Register, serverText("Registering this phone failed", e) + " You are signed in: Try again will not open the wallet.", reason(e), e)
                }
            }
        }
        verified = null
        return result.wallet
    }

    private suspend fun <T> timed(trace: WalletTrace, name: String, block: suspend () -> T): T {
        val t0 = now()
        trace.add("$name: started")
        try {
            val r = block()
            trace.add("$name: ok", "${now() - t0} ms")
            return r
        } catch (e: Throwable) {
            val why = when (e) {
                is SignInException -> "${e.step.name}; ${e.detail ?: e.message}"
                is WalletException -> "kind=${e.kind}${if (e.code != null) ", wallet error code ${e.code}" else ""}"
                else -> e.javaClass.simpleName
            }
            trace.add("$name: FAILED", "${now() - t0} ms; $why")
            throw e
        }
    }

    companion object {
        /** a saved wallet answer is not used when the server's nonce runs out within this many milliseconds */
        const val MIN_VALID_MS = 10_000L

        /** "HTTP 401, nonce unknown, used or expired" - short, no secrets (the server's message is plain text). */
        fun reason(e: ApiException): String = if (e.status == 0) (e.detail ?: e.message) else "HTTP ${e.status}, ${e.code}: ${e.message}"

        fun isTimeout(e: ApiException): Boolean {
            val d = "${e.detail} ${e.message}".lowercase()
            return "timeout" in d || "timed out" in d
        }

        /** "<what failed> (<reason>)." with the cold-start wording when the server did not answer in time. */
        fun serverText(what: String, e: ApiException): String = when {
            isTimeout(e) -> "$what: the Vowed server took too long to answer (a server that was asleep can need up to a minute to wake up)."
            e.status == 0 -> "$what: ${e.message}"
            else -> "$what (${e.message}, HTTP ${e.status})."
        }
    }
}
