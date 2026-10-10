package app.vowed

import app.vowed.data.AppJson
import app.vowed.data.Preflight
import app.vowed.data.PreflightText
import app.vowed.data.TxResponse
import app.vowed.wallet.SessionRetry
import app.vowed.wallet.WalletErrors
import app.vowed.wallet.WalletException
import app.vowed.wallet.WalletTrace
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The checks before the wallet is opened, and the words for each way a transaction can fail. */
class PreflightTest {
    private fun preflight(reasons: String, solEnough: Boolean = false) = AppJson.decodeFromString<Preflight>(
        """{"kind":"join","ok":${reasons == "[]"},"sol":{"balance":"2500000","needed":"2005000","enough":$solEnough,"fee":"5000","rent":"1000000","keep":"1000000"},
            "token":null,"reasons":$reasons,"somethingNew":1}""",
    )

    private val lowSol = """[{"code":"low_sol","message":"Your wallet needs a little devnet SOL for network fees. Tap Get test tokens to receive some."}]"""

    @Test fun readsTheServersAnswerAndTheSimulationNote() {
        val p = preflight(lowSol)
        assertFalse(p.ok)
        assertEquals("low_sol", p.reasons.single().code)
        assertEquals("2005000", p.sol.needed)
        val tx = AppJson.decodeFromString<TxResponse>("""{"transaction":"AA==","blockhash":"b","lastValidBlockHeight":1,"pool":"p","summary":{},"simulation":{"ok":true,"skipped":false,"unitsConsumed":52000}}""")
        assertEquals(52_000L, tx.simulation?.unitsConsumed)
        val old = AppJson.decodeFromString<TxResponse>("""{"transaction":"AA==","blockhash":"b","lastValidBlockHeight":1,"pool":"p","summary":{}}""")
        assertEquals(null, old.simulation)
    }

    @Test fun lowSolBeforeTheGiftIsReceivedPointsToGetTestTokens() {
        val p = preflight(lowSol)
        assertEquals("Your wallet needs a little devnet SOL for network fees. Tap Get test tokens to receive some.", PreflightText.message(p, false))
        assertEquals(PreflightText.message(p, false), PreflightText.message(p, null))
        assertTrue(PreflightText.offersFaucet(p))
    }

    @Test fun afterTheOneTimeGiftTheMessageDoesNotPromiseMoreSol() {
        val msg = PreflightText.message(preflight(lowSol), true)
        assertTrue(msg.contains("it has 0.0025 SOL"))
        assertTrue(msg.contains("about 0.002 SOL"))
        assertTrue(msg.contains("sent once per wallet and was already sent"))
        assertFalse(msg.contains("Tap Get test tokens to receive some"))
    }

    @Test fun missingTokensAreNamedAndEveryReasonIsListed() {
        val p = preflight("""[{"code":"low_sol","message":"A."},{"code":"no_token_account","message":"Your wallet has no test tokens yet. Tap Get test tokens to receive some."}]""")
        val msg = PreflightText.message(p, false)
        assertTrue(msg.startsWith("A. Your wallet has no test tokens yet"))
        assertTrue(PreflightText.offersFaucet(p))
    }

    @Test fun anOkAnswerHasNoMessageAndNoButton() {
        val p = preflight("[]", solEnough = true)
        assertTrue(p.ok)
        assertEquals("", PreflightText.message(p, false))
        assertFalse(PreflightText.offersFaucet(p))
    }

    // ---------------------------------------------------------------- the words for each failure

    @Test fun eachFailureHasItsOwnPlainSentence() {
        assertEquals(WalletErrors.Kind.SessionClosed, WalletErrors.classify("IO error | IOException: Cannot send in CLOSED", null))
        assertEquals(WalletErrors.Kind.Declined, WalletErrors.classify("User did not authorize signing", null))
        assertEquals(WalletErrors.Kind.Declined, WalletErrors.classify("anything", WalletErrors.CODE_NOT_SIGNED))
        assertEquals(WalletErrors.SESSION_CLOSED, WalletErrors.explain("Cannot send in CLOSED"))
        assertEquals(WalletErrors.DECLINED, WalletErrors.explain("User did not authorize signing"))
        assertEquals(WalletErrors.EXPIRED, WalletErrors.explain("Transaction payloads invalid"))
        assertEquals(WalletErrors.NO_FEE_FUNDS, WalletErrors.explain("Attempt to debit an account but found no record of a prior credit."))
        assertTrue(WalletErrors.NO_RESPONSE.startsWith("Your wallet did not answer."))
        assertTrue(WalletErrors.NO_RESPONSE.contains("Testnet Mode"))
        for (m in listOf(WalletErrors.NO_RESPONSE, WalletErrors.SESSION_CLOSED, WalletErrors.DECLINED)) assertFalse(m.contains("Exception"))
    }

    // ---------------------------------------------------------------- one retry for a transaction too

    @Test fun aTransactionThatMeetsAClosedSessionIsTriedOnceMoreInAFreshSession() = runBlocking {
        var opened = 0
        val trace = WalletTrace()
        val sig = SessionRetry.run(
            trace,
            first = { opened++; throw WalletException(WalletErrors.SESSION_CLOSED, WalletErrors.Kind.SessionClosed, raw = "Cannot send in CLOSED") },
            retry = { opened++; byteArrayOf(1, 2, 3) },
        )
        assertEquals(2, opened)
        assertEquals(3, sig.size)
        assertTrue(trace.render().contains("trying once in a fresh session"))
    }

    @Test fun aWalletThatNeverAnswersIsNotRetried() = runBlocking {
        var opened = 0
        val e = runCatching {
            SessionRetry.run<ByteArray>(null, first = { opened++; throw WalletException(WalletErrors.NO_RESPONSE, WalletErrors.Kind.NoResponse) }, retry = { opened++; byteArrayOf() })
        }.exceptionOrNull() as WalletException
        assertEquals(WalletErrors.Kind.NoResponse, e.kind)
        assertEquals(1, opened)
    }

    @Test fun theTransactionTraceNamesKindSizeSimulationAndMethodWithoutSecrets() {
        val t = WalletTrace({ 1_000L }) { emptyList() }
        t.add("transaction", "kind=join, 214 bytes, wallet method sign_and_send_transactions, server simulation: ok, 52000 compute units")
        t.add("authorized", "accounts=1, address=2YePEWRp8aTfqQnJHK2EBt4YkXRXWetzdmL8dDG7UFZf")
        t.add("request sent", "sign_and_send_transactions (1 transaction)")
        t.add("session 1: failed", "kind=SessionClosed; ExecutionException: IOException: Cannot send in CLOSED")
        val text = t.render()
        assertTrue(text.contains("kind=join, 214 bytes, wallet method sign_and_send_transactions"))
        assertTrue(text.contains("server simulation: ok, 52000 compute units"))
        assertTrue(text.contains("2YeP…UFZf"))
        assertFalse(text.contains("2YePEWRp8aTfqQnJHK2EBt4YkXRXWetzdmL8dDG7UFZf"))
        assertTrue(text.contains("Cannot send in CLOSED"))
    }
}
