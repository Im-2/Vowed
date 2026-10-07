package app.vowed

import app.vowed.data.AppJson
import app.vowed.data.FaucetClaim
import app.vowed.data.FaucetStatus
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The app must read exactly what the backend sends (shapes copied from docs/openapi.json). */
class FaucetModelTest {
    @Test fun readsAnEnabledStatus() {
        val json = """{"enabled":true,"network":"devnet","label":"TEST TOKENS: tUSDC and tSKR exist only on Solana devnet and have no real value.",
            "tokens":[{"symbol":"tUSDC","name":"Test USDC","mint":"C6pX","amount":"20000000","decimals":6},{"symbol":"tSKR","name":"Test SKR","mint":"J6X9","amount":"20000000","decimals":6}],
            "canClaim":false,"nextClaimAt":1791462020,"claimsLeftToday":199,"balances":{"tUSDC":"129900000","tSKR":"20000000"},"somethingNew":1}"""
        val st = AppJson.decodeFromString<FaucetStatus>(json)
        assertTrue(st.enabled)
        assertTrue(st.label.startsWith("TEST TOKENS"))
        assertEquals(listOf("tUSDC", "tSKR"), st.tokens.map { it.symbol })
        assertFalse(st.canClaim)
        assertEquals(1791462020L, st.nextClaimAt)
        assertEquals("129900000", st.balances.tUSDC)
    }

    @Test fun readsADisabledStatus() {
        val st = AppJson.decodeFromString<FaucetStatus>("""{"enabled":false,"network":"devnet","label":"x","tokens":[],"canClaim":false,"nextClaimAt":0,"claimsLeftToday":0,"balances":{"tUSDC":"0","tSKR":"0"}}""")
        assertFalse(st.enabled)
        assertTrue(st.tokens.isEmpty())
    }

    @Test fun readsAClaimResult() {
        val c = AppJson.decodeFromString<FaucetClaim>("""{"signature":"abc","minted":{"tUSDC":"20000000","tSKR":"20000000"},"nextClaimAt":5,"label":"TEST TOKENS"}""")
        assertEquals("20000000", c.minted.tUSDC)
        assertEquals(5L, c.nextClaimAt)
    }
}
