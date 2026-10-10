package app.vowed.wallet

/**
 * Turns what a wallet (or the Mobile Wallet Adapter library) reports into one plain sentence that tells the person what to do.
 * Wallets word the same problem in many ways, so this looks for the telltale words. Order matters: the most specific causes come first.
 */
object WalletErrors {
    /** MWA error codes (ProtocolContract in mobile-wallet-adapter-common 2.2.0): -1 authorization failed, -7 cluster (chain) not supported. */
    const val CODE_AUTHORIZATION_FAILED = -1
    const val CODE_CLUSTER_NOT_SUPPORTED = -7

    /** What a failed wallet request most likely means for the person. */
    enum class Kind {
        /** the wallet is on another network than the one Vowed asked for: the setup screen is shown */
        NetworkMismatch,
        /** the wallet refused to authorize and did not say the person declined: possibly the same cause, the setup screen is shown with softer words */
        PossibleMismatch,
        Declined,
        /** the wallet closed the Mobile Wallet Adapter session before the app finished (for example the library's "Cannot send in CLOSED") */
        SessionClosed,
        Other,
    }

    private val declineWords = listOf("declin", "reject", "denied", "cancel", "user refused", "user denied")

    private fun saidNo(m: String) = declineWords.any { it in m }

    /** Classifies a failure by the MWA error code when there is one (the reliable signal) and by the wording otherwise. */
    fun classify(message: String?, code: Int?): Kind {
        val m = message?.lowercase()?.replace('_', ' ').orEmpty()
        return when {
            isSessionClosed(m) -> Kind.SessionClosed
            code == CODE_CLUSTER_NOT_SUPPORTED || isWrongNetwork(message) || mentionsNetworkSwitch(m) -> Kind.NetworkMismatch
            saidNo(m) -> Kind.Declined
            code == CODE_AUTHORIZATION_FAILED -> Kind.PossibleMismatch
            else -> Kind.Other
        }
    }

    /** The library's text when a request is sent after the wallet closed the session, and the wording of similar closures. */
    fun isSessionClosed(text: String?): Boolean {
        val m = text?.lowercase()?.replace('_', ' ') ?: return false
        return "cannot send in" in m || "session closed" in m || "session was closed" in m || "connection closed" in m || "socket closed" in m || "websocket closed" in m
    }

    /** The messages of an exception and of everything it wraps, joined, so a closed session is found wherever the library hid it. */
    fun flatten(e: Throwable?): String {
        val parts = ArrayList<String>()
        var t = e
        var depth = 0
        while (t != null && depth++ < 6) {
            parts.add("${t.javaClass.simpleName}: ${t.message ?: "no message"}")
            t = t.cause
        }
        return parts.joinToString(" <- ")
    }

    private fun mentionsNetworkSwitch(m: String): Boolean =
        "testnet mode" in m || ("network" in m && ("mainnet" in m || "devnet" in m || "testnet" in m)) || ("devnet" in m && ("not supported" in m || "unsupported" in m || "mismatch" in m))

    const val CLOSED_BEFORE_CONNECTING = WalletSessionGate.CLOSED_BEFORE_CONNECTING
    const val SESSION_CLOSED =
        "The wallet closed the connection before it finished. Open Vowed, tap Connect again, and approve in your wallet right away. If the wallet stays on its home screen, return to Vowed manually."
    const val LOCKED = "Open your wallet and unlock it, then try again. Nothing was signed."
    const val SLOW =
        "The wallet took too long to answer. Open your wallet and unlock it, give it a moment to start (the first start can take up to a minute), then try again."
    const val DECLINED = "The request was declined in the wallet, so nothing was signed. Tap the button again when you are ready to approve."
    const val EXPIRED = "The transaction expired before it was approved (they last about a minute). Try again and approve it right away."
    const val WRONG_NETWORK =
        "Your wallet is on the real network. Switch it to Testnet Mode (Vowed uses a practice network with no real money) and try again."
    const val POSSIBLE_MISMATCH =
        "Your wallet did not connect. If it is on the real network, switch it to Testnet Mode (Vowed uses a practice network with no real money) and try again."
    const val NO_FEE_FUNDS =
        "The wallet could not pay the network fee. It may be on the wrong network (Vowed uses Solana devnet) or have no devnet SOL: switch the wallet to devnet and tap \"Get test tokens\" in Vowed for a little SOL."

    /** True when the words say the wallet does not serve the chain we asked for (MWA error ERROR_CHAIN_NOT_SUPPORTED, code -7). */
    fun isWrongNetwork(message: String?): Boolean {
        val m = message?.lowercase()?.replace('_', ' ') ?: return false
        return "chain not supported" in m || ("chain" in m && "not supported" in m) || "unsupported chain" in m ||
            ("cluster" in m && ("not supported" in m || "mismatch" in m)) || "-7" in Regex("""code[=: ]*-?\d+""").find(m)?.value.orEmpty()
    }

    fun explain(message: String?): String {
        val raw = message?.trim().orEmpty()
        val m = raw.lowercase().replace('_', ' ')
        return when {
            isSessionClosed(m) -> SESSION_CLOSED
            WalletSessionGate.isAssociationFailure(raw) -> CLOSED_BEFORE_CONNECTING
            isWrongNetwork(raw) || mentionsNetworkSwitch(m) -> WRONG_NETWORK
            "usernotauthenticated" in m || "not authenticated" in m || "authentication" in m || "locked" in m || "biometric" in m || "keystore" in m && "auth" in m -> LOCKED
            "timed out" in m || "timeout" in m || "time out" in m || "failed establishing" in m -> SLOW
            "declin" in m || "reject" in m || "denied" in m || "cancel" in m || "not authorized" in m || "user refused" in m -> DECLINED
            "blockhash" in m || "payloads invalid" in m || "invalid for signing" in m || "expired" in m -> EXPIRED
            "debit an account" in m || "no record of a prior credit" in m || "insufficient funds for fee" in m || "insufficient lamports" in m || "simulation failed" in m && "fee" in m -> NO_FEE_FUNDS
            else -> "The wallet did not complete the request (${raw.ifBlank { "no detail" }}). If you were signing a transaction, try again and approve right away: a transaction expires after about a minute."
        }
    }
}
