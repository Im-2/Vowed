package app.vowed.wallet

/**
 * Turns what a wallet (or the Mobile Wallet Adapter library) reports into one plain sentence that tells the person what to do.
 * Wallets word the same problem in many ways, so this looks for the telltale words. Order matters: the most specific causes come first.
 */
object WalletErrors {
    const val CLOSED_BEFORE_CONNECTING = WalletSessionGate.CLOSED_BEFORE_CONNECTING
    const val LOCKED = "Open your wallet and unlock it, then try again. Nothing was signed."
    const val SLOW =
        "The wallet took too long to answer. Open your wallet and unlock it, give it a moment to start (the first start can take up to a minute), then try again."
    const val DECLINED = "The request was declined in the wallet, so nothing was signed. Tap the button again when you are ready to approve."
    const val EXPIRED = "The transaction expired before it was approved (they last about a minute). Try again and approve it right away."
    const val WRONG_NETWORK =
        "Your wallet is not on Solana devnet. Vowed uses devnet test tokens only: open your wallet's settings, switch it to devnet (see \"Using a real wallet\" in You), then try again."
    const val NO_FEE_FUNDS =
        "The wallet could not pay the network fee. It may be on the wrong network (Vowed uses Solana devnet) or have no devnet SOL: switch the wallet to devnet and tap \"Get test tokens\" in Vowed for a little SOL."

    /** True when the words say the wallet does not serve the chain we asked for (MWA error ERROR_CHAIN_NOT_SUPPORTED, code -7). */
    fun isWrongNetwork(message: String?): Boolean {
        val m = message?.lowercase() ?: return false
        return "chain_not_supported" in m || "chain not supported" in m || ("chain" in m && "not supported" in m) || "unsupported chain" in m ||
            ("cluster" in m && ("not supported" in m || "mismatch" in m)) || "-7" in Regex("""code[=: ]*-?\d+""").find(m)?.value.orEmpty()
    }

    fun explain(message: String?): String {
        val raw = message?.trim().orEmpty()
        val m = raw.lowercase()
        return when {
            WalletSessionGate.isAssociationFailure(raw) -> CLOSED_BEFORE_CONNECTING
            isWrongNetwork(raw) -> WRONG_NETWORK
            "usernotauthenticated" in m || "not authenticated" in m || "authentication" in m || "locked" in m || "biometric" in m || "keystore" in m && "auth" in m -> LOCKED
            "timed out" in m || "timeout" in m || "time out" in m || "failed establishing" in m -> SLOW
            "declin" in m || "reject" in m || "denied" in m || "cancel" in m || "not authorized" in m || "user refused" in m -> DECLINED
            "blockhash" in m || "payloads invalid" in m || "invalid for signing" in m || "expired" in m -> EXPIRED
            "debit an account" in m || "no record of a prior credit" in m || "insufficient funds for fee" in m || "insufficient lamports" in m || "simulation failed" in m && "fee" in m -> NO_FEE_FUNDS
            else -> "The wallet did not complete the request (${raw.ifBlank { "no detail" }}). If you were signing a transaction, try again and approve right away: a transaction expires after about a minute."
        }
    }
}
