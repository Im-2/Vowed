package app.vowed.data

import app.vowed.core.TxChecker
import java.math.BigInteger

/** Plain-language lines about what a faucet claim did. Pure, so it is tested without a phone. */
object FaucetText {
    /** Lamports as SOL, up to 4 decimals without trailing zeros: 10000000 -> "0.01". */
    fun sol(lamports: String): String {
        val v = runCatching { BigInteger(lamports) }.getOrDefault(BigInteger.ZERO)
        return TxChecker.formatUnits(v, 9).let { s -> if (s.contains('.') && s.substringAfter('.').length > 4) s.substringBefore('.') + "." + s.substringAfter('.').take(4).trimEnd('0').trimEnd('.') else s }
    }

    fun solLine(g: SolGift?): String = when (g?.status) {
        "sent" -> " Plus ${sol(g.lamports)} SOL (devnet) for network fees."
        "failed" -> " The devnet SOL for network fees could not be sent right now; tap again later."
        "skipped" -> when (g.reason) {
            "already_has_sol" -> " Your wallet already has devnet SOL, so none was sent."
            "daily_cap", "total_cap" -> " The devnet SOL gift is used up for now."
            else -> ""
        }
        else -> ""
    }

    fun claimMessage(r: FaucetClaim): String {
        val usdc = BigInteger(r.minted.tUSDC)
        val skr = BigInteger(r.minted.tSKR)
        val tokens = if (usdc.signum() == 0 && skr.signum() == 0) "No test tokens this time (you claimed today)." else "Sent ${TxChecker.formatUnits(usdc)} tUSDC and ${TxChecker.formatUnits(skr)} tSKR (test tokens)."
        return tokens + solLine(r.sol)
    }
}
