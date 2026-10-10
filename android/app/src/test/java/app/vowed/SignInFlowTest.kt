package app.vowed

import app.vowed.data.ApiException
import app.vowed.data.ConnectStep
import app.vowed.data.NonceResponse
import app.vowed.data.SignInException
import app.vowed.data.SignInFlow
import app.vowed.data.SignInStep
import app.vowed.data.VerifyResponse
import app.vowed.wallet.SignInOutcome
import app.vowed.wallet.WalletErrors
import app.vowed.wallet.WalletException
import app.vowed.wallet.WalletTrace
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sign-in as the person lives it: how many times the wallet opens and what each failure says. A fake wallet counts its launches, so
 * "one visit per tap" is a tested fact and not a hope.
 */
class SignInFlowTest {
    private val nonce = NonceResponse("n1", "vowed-backend.onrender.com", "Sign in to Vowed.", "https://vowed-backend.onrender.com", "t0", "t1")
    private val address = "2YePEWRp8aTfqQnJHK2EBt4YkXRXWetzdmL8dDG7UFZf"

    private class Fake {
        var nonceCalls = 0
        var walletLaunches = 0
        var verifyCalls = 0
        var registerCalls = 0
        var registered = false
        var adopted = 0
        var clock = 1_000L
        var validFor = 600_000L
        var nonceFails: ApiException? = null
        var walletFails: Exception? = null
        val verifyFails = ArrayDeque<ApiException>()
        val registerFails = ArrayDeque<ApiException>()
        val steps = mutableListOf<ConnectStep>()
    }

    private fun flow(f: Fake) = SignInFlow(
        fetchNonce = { f.nonceCalls++; f.nonceFails?.let { throw it }; nonce },
        wallet = { f.walletLaunches++; f.walletFails?.let { throw it }; SignInOutcome(address, ByteArray(10), ByteArray(64), viaPayload = true, validUntilMs = f.clock + f.validFor) },
        verify = { f.verifyCalls++; f.verifyFails.removeFirstOrNull()?.let { throw it }; VerifyResponse("token", 1, address) },
        adopt = { f.adopted++ },
        registered = { f.registered },
        register = { f.registerCalls++; f.registerFails.removeFirstOrNull()?.let { throw it }; f.registered = true },
        now = { f.clock },
    )

    private fun run(fl: SignInFlow, f: Fake): String = runBlocking { fl.run(WalletTrace(), { f.steps += it }) }
    private fun fails(fl: SignInFlow, f: Fake): Throwable = runCatching { run(fl, f) }.exceptionOrNull() ?: error("expected a failure")

    @Test fun firstSignInOpensTheWalletExactlyOnceAndRegistersThePhoneWithoutIt() {
        val f = Fake()
        assertEquals(address, run(flow(f), f))
        assertEquals("one wallet visit", 1, f.walletLaunches)
        assertEquals(1, f.verifyCalls)
        assertEquals("registration needs no second wallet screen", 1, f.registerCalls)
        assertEquals(listOf(ConnectStep.Server, ConnectStep.Wallet, ConnectStep.SigningIn, ConnectStep.RegisteringDevice), f.steps)
    }

    @Test fun theNonceIsAskedForBeforeTheWalletOpens() {
        val f = Fake()
        f.nonceFails = ApiException(0, "network", "Vowed couldn't reach its server.", "SocketTimeoutException: timeout")
        val e = fails(flow(f), f) as SignInException
        assertEquals(SignInStep.Server, e.step)
        assertEquals("the wallet is never opened when the server cannot be reached", 0, f.walletLaunches)
        assertTrue(e.message!!.contains("took too long") || e.message!!.contains("before opening your wallet"))
    }

    @Test fun aServerTimeoutAfterTheWalletApprovedNeverSendsThePersonBackToTheWallet() {
        val f = Fake()
        f.verifyFails += ApiException(0, "network", "Vowed couldn't reach its server.", "SocketTimeoutException: timeout")
        val fl = flow(f)
        val e = fails(fl, f)
        assertTrue("a step failure, not a wallet or network-setup error", e is SignInException)
        e as SignInException
        assertEquals(SignInStep.Verify, e.step)
        assertTrue(e.message!!, e.message!!.startsWith("Wallet approved, but signing in with the Vowed server failed"))
        assertTrue(e.message!!.contains("took too long"))
        assertFalse(e.message!!.contains("Testnet"))
        assertTrue(fl.hasSavedProgress)
        // Try again: only the server step repeats
        assertEquals(address, run(fl, f))
        assertEquals("the wallet was opened once for the whole sign-in", 1, f.walletLaunches)
        assertEquals(2, f.verifyCalls)
        assertEquals(1, f.nonceCalls)
    }

    @Test fun aServerErrorKeepsTheApprovalAndNamesTheReason() {
        val f = Fake()
        f.verifyFails += ApiException(502, "http_502", "The server answered 502.")
        val fl = flow(f)
        val e = fails(fl, f) as SignInException
        assertEquals("HTTP 502, http_502: The server answered 502.", e.detail)
        assertEquals(address, run(fl, f))
        assertEquals(1, f.walletLaunches)
    }

    @Test fun anUsedOrExpiredNonceIsTheOnlyServerAnswerThatAsksTheWalletAgain() {
        val f = Fake()
        f.verifyFails += ApiException(401, "unauthorized", "nonce unknown, used or expired")
        val fl = flow(f)
        val e = fails(fl, f) as SignInException
        assertEquals(SignInStep.Verify, e.step)
        assertTrue(e.message!!.contains("approve once more"))
        assertFalse(fl.hasSavedProgress)
        assertEquals(address, run(fl, f))
        assertEquals("a spent approval cannot be reused: the wallet opens again", 2, f.walletLaunches)
    }

    @Test fun anExpiredSavedApprovalIsNotReused() {
        val f = Fake()
        f.verifyFails += ApiException(0, "network", "Vowed couldn't reach its server.", "SocketTimeoutException: timeout")
        val fl = flow(f)
        fails(fl, f)
        f.clock += f.validFor // the server's nonce has run out
        assertEquals(address, run(fl, f))
        assertEquals(2, f.walletLaunches)
        assertEquals(2, f.nonceCalls)
    }

    @Test fun aFailedRegistrationRetriesOnlyTheRegistration() {
        val f = Fake()
        f.registerFails += ApiException(500, "http_500", "The server answered 500.")
        val fl = flow(f)
        val e = fails(fl, f) as SignInException
        assertEquals(SignInStep.Register, e.step)
        assertTrue(e.message!!.startsWith("Registering this phone failed"))
        assertEquals(1, f.adopted)
        assertEquals(address, run(fl, f))
        assertEquals("neither the wallet nor the verify step repeats", 1, f.walletLaunches)
        assertEquals(1, f.verifyCalls)
        assertEquals(2, f.registerCalls)
        assertEquals(1, f.adopted)
    }

    @Test fun anAlreadyRegisteredPhoneIsNotRegisteredAgain() {
        val f = Fake()
        f.registered = true
        run(flow(f), f)
        assertEquals(0, f.registerCalls)
        assertEquals(1, f.walletLaunches)
    }

    @Test fun aDeclinedWalletIsOneLaunchAndNothingAutomaticFollows() {
        val f = Fake()
        f.walletFails = WalletException(WalletErrors.DECLINED, WalletErrors.Kind.Declined)
        val e = fails(flow(f), f)
        assertTrue(e is WalletException)
        assertEquals(1, f.walletLaunches)
        assertEquals(0, f.verifyCalls)
    }

    @Test fun aWalletNetworkMismatchPassesThroughUnchangedSoOnlyThatCaseShowsTheSetupScreen() {
        val f = Fake()
        f.walletFails = WalletException(WalletErrors.WRONG_NETWORK, WalletErrors.Kind.NetworkMismatch, code = -7)
        val e = fails(flow(f), f) as WalletException
        assertEquals(WalletErrors.Kind.NetworkMismatch, e.kind)
        assertEquals(1, f.walletLaunches)
    }

    @Test fun anUnexpectedWalletCrashIsAWalletStepFailureNotANetworkOne() {
        val f = Fake()
        f.walletFails = IllegalStateException("boom")
        val e = fails(flow(f), f) as SignInException
        assertEquals(SignInStep.Wallet, e.step)
        assertEquals(1, f.walletLaunches)
    }

    @Test fun everyFailurePathLaunchesTheWalletAtMostOncePerTap() {
        val cases = listOf<(Fake) -> Unit>(
            { it.verifyFails += ApiException(0, "network", "x", "timeout") },
            { it.verifyFails += ApiException(500, "http_500", "x") },
            { it.registerFails += ApiException(500, "http_500", "x") },
            { it.walletFails = WalletException("no", WalletErrors.Kind.Declined) },
            { it.walletFails = WalletException("closed", WalletErrors.Kind.SessionClosed) },
            { it.walletFails = WalletException("no answer", WalletErrors.Kind.NoResponse) },
        )
        for (setup in cases) {
            val f = Fake().also(setup)
            fails(flow(f), f)
            assertTrue("launches=${f.walletLaunches}", f.walletLaunches <= 1)
        }
    }
}
