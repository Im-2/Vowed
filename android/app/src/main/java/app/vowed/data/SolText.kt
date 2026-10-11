package app.vowed.data

import java.math.BigInteger

/** The words and the arithmetic for "what this costs in SOL", pure so it is tested without a phone. */
object SolText {
    /** Lamports as SOL, rounded UP to 4 decimals (a short wallet never reads as enough): 5404960 -> "0.0055". */
    fun sol(lamports: String): String {
        val v = runCatching { BigInteger(lamports) }.getOrDefault(BigInteger.ZERO)
        val tenThousandths = (v + BigInteger.valueOf(99_999)) / BigInteger.valueOf(100_000)
        val whole = tenThousandths / BigInteger.valueOf(10_000)
        val frac = (tenThousandths % BigInteger.valueOf(10_000)).toString().padStart(4, '0').trimEnd('0')
        return if (frac.isEmpty()) whole.toString() else "$whole.$frac"
    }

    /** The lines of the review screen: what the step costs in SOL. */
    fun lines(c: SolCosts): List<Pair<String, String>> = listOf(
        "Network fee" to "about ${sol(c.fee)} SOL",
        "Account rent" to (if (BigInteger(c.rent).signum() == 0) "none" else "about ${sol(c.rent)} SOL, paid once and not refunded in this test version"),
        "Your wallet must keep" to "about ${sol(c.keep)} SOL",
        "Total SOL needed" to "about ${sol(c.total)} SOL (your wallet has ${sol(c.balance)})",
    )

    /** The message when the wallet holds less than [c] needs; null when it is enough. [canGetSol]: the faucet can still send SOL to this wallet. */
    fun shortMessage(c: SolCosts, canGetSol: Boolean?): String? {
        if (c.enough) return null
        val base = "Your wallet needs about ${sol(c.total)} SOL on devnet for fees and rent. You have ${sol(c.balance)} SOL."
        return if (canGetSol == false) "$base The free test SOL for this wallet is used up for now: ask the project team, or use Solana's devnet faucet (faucet.solana.com), then try again." else "$base Tap Get test SOL."
    }
}
