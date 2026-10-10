package app.vowed.data

import app.vowed.core.TxChecker
import java.math.BigDecimal
import java.math.BigInteger

/**
 * The tokens a challenge can be staked in. A pool has ONE stake token, chosen when it is created; people who join stake that same token.
 *
 * On devnet there are two of OUR test tokens, both created for this project, both classic SPL tokens with 6 decimals (read from the mint
 * accounts, see docs/verified-facts.md): test USDC (tUSDC) and test SKR (tSKR). tSKR is NOT the real SKR token. Circle's devnet USDC is also on
 * the program's allow list, but nobody can get it from the app, so it is not offered as a choice (it still shows correctly if a pool uses it).
 * Pure logic, so the selector rules are tested without a phone.
 */
data class StakeToken(val symbol: String, val name: String, val mint: String, val decimals: Int = 6) {
    val isSkr: Boolean get() = symbol == StakeTokens.TSKR

    /** "12.5" for 12,500,000 base units. */
    fun format(baseUnits: String): String = runCatching { TxChecker.formatUnits(BigInteger(baseUnits), decimals) }.getOrDefault(baseUnits)

    /** Whole and decimal text to base units; null when it is not a number, has more decimals than the token, or is negative. */
    fun parse(text: String): BigInteger? =
        runCatching { BigDecimal(text.trim()).movePointRight(decimals).toBigIntegerExact() }.getOrNull()?.takeIf { it.signum() >= 0 }

    /** This wallet's balance of the token in base units, from the faucet status, or null when it is not known. */
    fun balanceOf(b: FaucetBalances?): String? = when (symbol) {
        StakeTokens.TUSDC -> b?.tUSDC
        StakeTokens.TSKR -> b?.tSKR
        else -> null
    }
}

object StakeTokens {
    const val TUSDC = "tUSDC"
    const val TSKR = "tSKR"
    const val CIRCLE_USDC_MINT = "4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU"

    const val HONESTY =
        "TEST TOKENS: tUSDC and tSKR are test tokens we created on Solana devnet. They have no value. tSKR is not the real SKR token."

    /**
     * The choices for a new challenge: our test tokens that the program accepts for this kind of pool (a demo pool has its own, shorter list).
     * When the faucet has not answered yet, [faucetTokens] is empty and the list is empty: the screen asks the faucet first.
     */
    fun options(cfg: MetaConfig?, faucetTokens: List<FaucetToken>, demoPool: Boolean): List<StakeToken> {
        if (cfg == null) return emptyList()
        val accepted = (if (demoPool) cfg.demoMints else cfg.allowedMints).toSet()
        return faucetTokens.filter { it.mint in accepted }.map { StakeToken(it.symbol, it.name, it.mint, it.decimals) }
            .sortedBy { if (it.symbol == TUSDC) 0 else 1 } // test USDC first, as the default
    }

    /** The token with this mint (any pool, any screen), or null when it is not one we know. */
    fun byMint(mint: String, faucetTokens: List<FaucetToken>): StakeToken? =
        faucetTokens.firstOrNull { it.mint == mint }?.let { StakeToken(it.symbol, it.name, it.mint, it.decimals) }
            ?: if (mint == CIRCLE_USDC_MINT) StakeToken("USDC (devnet)", "Circle devnet USDC", mint, 6) else null

    /** A short name for any mint, never a bare address when we know the token. */
    fun symbolFor(mint: String, faucetTokens: List<FaucetToken>): String = byMint(mint, faucetTokens)?.symbol ?: "${mint.take(4)}…${mint.takeLast(4)}"

    /** "12.5 tSKR" for a pool's amount, using that pool's token and its decimals. */
    fun amountText(baseUnits: String, mint: String, faucetTokens: List<FaucetToken>): String {
        val t = byMint(mint, faucetTokens)
        return if (t != null) "${t.format(baseUnits)} ${t.symbol}" else "${runCatching { TxChecker.formatUnits(BigInteger(baseUnits)) }.getOrDefault(baseUnits)} ${symbolFor(mint, faucetTokens)}"
    }

    /** Which token the selector shows as chosen: the person's pick when it is still on offer, else the first (test USDC). */
    fun chosen(options: List<StakeToken>, pickedMint: String?): StakeToken? = options.firstOrNull { it.mint == pickedMint } ?: options.firstOrNull()
}
