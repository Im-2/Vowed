package app.vowed

import app.vowed.data.FaucetBalances
import app.vowed.data.FaucetToken
import app.vowed.data.MetaConfig
import app.vowed.data.StakeToken
import app.vowed.data.StakeTokens
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which token a challenge is staked in: the choices, the one-token-per-pool rule, amounts in the right decimals. */
class StakeTokensTest {
    private val tusdc = "C6pXRRmoHsf7Mqa1ZrW3JfspyknhSR1cRqHMUqao63Hv"
    private val tskr = "J6X9udvWis7jYpVHic3Kn5iTG3ac6gbBeFixnYKPUkHB"
    private val circle = StakeTokens.CIRCLE_USDC_MINT
    private val faucet = listOf(FaucetToken("tUSDC", "Test USDC", tusdc, "20000000", 6), FaucetToken("tSKR", "Test SKR", tskr, "20000000", 6))

    private fun cfg(allowed: List<String>, demo: List<String>) = MetaConfig("o", "t", 0, false, "100000000", "7200", allowed, true, "20000000", demo)

    @Test fun bothTestTokensAreOfferedForNormalAndDemoPoolsAndCircleIsNot() {
        val c = cfg(listOf(circle, tusdc, tskr), listOf(tusdc, tskr))
        assertEquals(listOf("tUSDC", "tSKR"), StakeTokens.options(c, faucet, demoPool = false).map { it.symbol })
        assertEquals(listOf("tUSDC", "tSKR"), StakeTokens.options(c, faucet, demoPool = true).map { it.symbol })
        assertTrue(StakeTokens.options(c, faucet, false).none { it.mint == circle })
    }

    @Test fun onlyTokensTheProgramAcceptsForThisKindOfPoolAreOffered() {
        // a program that lists tSKR for normal pools only: demo pools offer test USDC alone (the wrong token is never selectable)
        val c = cfg(listOf(tusdc, tskr), listOf(tusdc))
        assertEquals(listOf("tUSDC", "tSKR"), StakeTokens.options(c, faucet, false).map { it.symbol })
        assertEquals(listOf("tUSDC"), StakeTokens.options(c, faucet, true).map { it.symbol })
        // a faucet token that the program does not list at all is dropped
        assertTrue(StakeTokens.options(cfg(listOf(tusdc), listOf(tusdc)), faucet, false).none { it.symbol == "tSKR" })
        assertTrue(StakeTokens.options(null, faucet, false).isEmpty())
        assertTrue(StakeTokens.options(c, emptyList(), false).isEmpty())
    }

    @Test fun testUsdcIsTheDefaultAndAStalePickFallsBack() {
        val opts = StakeTokens.options(cfg(listOf(tskr, tusdc), listOf(tskr, tusdc)), faucet, false)
        assertEquals("tUSDC", opts.first().symbol)
        assertEquals("tUSDC", StakeTokens.chosen(opts, null)?.symbol)
        assertEquals("tSKR", StakeTokens.chosen(opts, tskr)?.symbol)
        assertEquals("tUSDC", StakeTokens.chosen(opts, "SomethingNoLongerOffered")?.symbol)
        assertNull(StakeTokens.chosen(emptyList(), tskr))
    }

    @Test fun amountsUseTheTokensOwnDecimals() {
        val t = StakeToken("tSKR", "Test SKR", tskr, 6)
        assertEquals(BigInteger("2500000"), t.parse("2.5"))
        assertEquals(BigInteger("1"), t.parse("0.000001"))
        assertEquals(BigInteger("100000000"), t.parse("100"))
        assertNull("more decimals than the token has", t.parse("0.0000001"))
        assertNull(t.parse("-1"))
        assertNull(t.parse("abc"))
        assertNull(t.parse(""))
        assertEquals("12.5", t.format("12500000"))
        assertEquals("0.000001", t.format("1"))
        assertEquals("0", t.format("0"))
        // a token with 9 decimals (a different mint) is handled by its own decimals, not by an assumed 6
        val nine = StakeToken("X", "X", "x", 9)
        assertEquals(BigInteger("1000000000"), nine.parse("1"))
        assertEquals("1.5", nine.format("1500000000"))
    }

    @Test fun theBalanceShownIsTheBalanceOfTheChosenToken() {
        val b = FaucetBalances(tUSDC = "129900000", tSKR = "20000000")
        assertEquals("129900000", StakeToken("tUSDC", "", tusdc).balanceOf(b))
        assertEquals("20000000", StakeToken("tSKR", "", tskr).balanceOf(b))
        assertNull(StakeToken("USDC (devnet)", "", circle).balanceOf(b))
        assertNull(StakeToken("tSKR", "", tskr).balanceOf(null))
    }

    @Test fun everyPoolIsShownInItsOwnTokenNeverAsABareAddress() {
        assertEquals("tSKR", StakeTokens.symbolFor(tskr, faucet))
        assertEquals("tUSDC", StakeTokens.symbolFor(tusdc, faucet))
        assertEquals("USDC (devnet)", StakeTokens.symbolFor(circle, faucet))
        assertEquals("AbCd…WxYz", StakeTokens.symbolFor("AbCdEfGhIjKlMnOpQrStUvWxYz", faucet))
        assertEquals("2.5 tSKR", StakeTokens.amountText("2500000", tskr, faucet))
        assertEquals("0.75 tUSDC", StakeTokens.amountText("750000", tusdc, faucet))
        assertEquals("1.5 AbCd…WxYz", StakeTokens.amountText("1500000", "AbCdEfGhIjKlMnOpQrStUvWxYz", faucet))
        // before the faucet answered, the amount is still right (6 decimals), only the name falls back to a short address
        assertEquals("2.5 C6pX…63Hv", StakeTokens.amountText("2500000", tusdc, emptyList()))
    }

    @Test fun testSkrIsLabelledAsNotTheRealSkr() {
        assertTrue(StakeTokens.HONESTY.contains("test tokens we created"))
        assertTrue(StakeTokens.HONESTY.contains("not the real SKR token"))
        assertTrue(StakeToken("tSKR", "", tskr).isSkr)
        assertFalse(StakeToken("tUSDC", "", tusdc).isSkr)
    }
}
