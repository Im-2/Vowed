package app.vowed

import app.vowed.data.ApiException
import app.vowed.data.BackendApi
import app.vowed.data.ConnectionResult
import app.vowed.data.NetworkSupport
import app.vowed.data.NonceResponse
import app.vowed.data.SignInNonce
import app.vowed.wallet.WalletErrors
import app.vowed.wallet.WalletSetupLogic
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** The "Unable to resolve host" fixes: error wording, retry rules, the nonce fetched before the wallet opens, Check connection, and the wallet setup rules. */
class ConnectivityTest {
    // ---------------------------------------------------------------- retry and wording

    /** A resolver that fails [failures] times with the same text a phone gives, then answers with the local test server. */
    private class FlakyDns(var failures: Int) : Dns {
        var lookups = 0
        override fun lookup(hostname: String): List<InetAddress> {
            lookups++
            if (failures > 0) {
                failures--
                throw UnknownHostException("Unable to resolve host \"$hostname\": No address associated with hostname")
            }
            return Dns.SYSTEM.lookup("localhost")
        }
    }

    private fun api(server: MockWebServer, dns: Dns, wait: Long = 0) =
        BackendApi(baseUrl = { "http://vowed-test.local:${server.port}" }, client = OkHttpClient.Builder().dns(dns).build(), retryWaitMs = wait)

    private fun healthBody() = MockResponse().setBody("""{"ok":true,"network":"devnet","uptimeSecs":5}""")

    @Test fun aDnsFailureIsRetriedTwiceAtMostAndThenWorks() = runBlocking {
        val server = MockWebServer().apply { enqueue(healthBody()); start() }
        try {
            val dns = FlakyDns(2)
            val h = api(server, dns).health()
            assertTrue(h.ok)
            assertEquals("two failures then one success is three lookups", 3, dns.lookups)
        } finally { server.shutdown() }
    }

    @Test fun afterTwoRetriesItGivesUpWithTheFriendlyMessageAndKeepsTheRawTextForDetails() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            val dns = FlakyDns(99)
            val e = runCatching { api(server, dns).health() }.exceptionOrNull() as ApiException
            assertEquals("network", e.code)
            assertEquals(NetworkSupport.UNREACHABLE, e.message)
            assertTrue(e.detail!!.contains("Unable to resolve host"))
            assertEquals("the first try plus exactly two retries", 3, dns.lookups)
        } finally { server.shutdown() }
    }

    @Test fun theFriendlyMessageDoesNotShowTheHostnameError() {
        assertFalse(NetworkSupport.UNREACHABLE.contains("resolve", ignoreCase = true))
        assertFalse(NetworkSupport.UNREACHABLE.contains("hostname", ignoreCase = true))
        assertEquals("Vowed couldn't reach its server. Check your internet connection and try again. The server may take up to a minute to wake up.", NetworkSupport.UNREACHABLE)
    }

    @Test fun onlyDnsAndConnectFailuresAreRetried() {
        assertTrue(NetworkSupport.isRetryable(UnknownHostException("x")))
        assertTrue(NetworkSupport.isRetryable(ConnectException("Failed to connect")))
        assertTrue(NetworkSupport.isRetryable(SocketTimeoutException("connect timed out")))
        assertFalse("a read timeout means the server got the request", NetworkSupport.isRetryable(SocketTimeoutException("timeout")))
        assertFalse(NetworkSupport.isRetryable(java.io.IOException("unexpected end of stream")))
    }

    @Test fun aServerAnswerIsNeverRetried() = runBlocking {
        val server = MockWebServer().apply { enqueue(MockResponse().setResponseCode(500).setBody("""{"error":{"code":"boom","message":"no"}}""")); start() }
        try {
            val e = runCatching { api(server, FlakyDns(0)).health() }.exceptionOrNull() as ApiException
            assertEquals(500, e.status)
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }

    // ---------------------------------------------------------------- Check connection

    @Test fun checkConnectionPassAndFailText() {
        val ok = ConnectionResult.passed(350, "devnet")
        assertTrue(ok.ok)
        assertEquals("Connected. The server answered in 0.4 s (network: devnet).", ok.summary)
        val down = ConnectionResult.failed(NetworkSupport.unreachable(UnknownHostException("Unable to resolve host \"x\": No address associated with hostname")))
        assertFalse(down.ok)
        assertEquals(NetworkSupport.UNREACHABLE, down.summary)
        assertTrue(down.detail!!.contains("UnknownHostException"))
        val odd = ConnectionResult.failed(ApiException(502, "bad_gateway", "Bad gateway"))
        assertTrue(odd.summary.contains("not as expected"))
    }

    // ---------------------------------------------------------------- the nonce fetched before the wallet opens

    private val n = NonceResponse("abc", "vowed.app", "s", "https://vowed.app", "t0", "t1")

    @Test fun aPrefetchedNonceMeansNoNetworkCallWhileTheWalletIsInFront() = runBlocking {
        var calls = 0
        val nonce = SignInNonce.prepare { n }
        assertTrue(nonce.wasPrefetched)
        val got = nonce.forWallet("WALLET") { calls++; n }
        assertEquals(n, got)
        assertEquals(0, calls)
    }

    @Test fun ifThePrefetchFailsTheOldWayIsUsedOnce() = runBlocking {
        var calls = 0
        val nonce = SignInNonce.prepare { throw ApiException(400, "bad_request", "old server") }
        assertFalse(nonce.wasPrefetched)
        assertNotNull(nonce.forWallet("WALLET") { calls++; n })
        assertEquals(1, calls)
    }

    // ---------------------------------------------------------------- wallet network mismatch

    @Test fun clusterNotSupportedCodeIsAMismatchWhateverTheMessage() {
        assertEquals(WalletErrors.Kind.NetworkMismatch, WalletErrors.classify("something odd", WalletErrors.CODE_CLUSTER_NOT_SUPPORTED))
        assertEquals(WalletErrors.Kind.NetworkMismatch, WalletErrors.classify("ERROR_CLUSTER_NOT_SUPPORTED", null))
        assertEquals(WalletErrors.Kind.NetworkMismatch, WalletErrors.classify("Please switch to Testnet Mode in settings", null))
        assertEquals(WalletErrors.Kind.NetworkMismatch, WalletErrors.classify("This network (mainnet) does not match devnet", null))
    }

    @Test fun anAuthorizationRefusalWithoutADeclineIsAPossibleMismatchButADeclineIsNot() {
        assertEquals(WalletErrors.Kind.PossibleMismatch, WalletErrors.classify("authorization failed", WalletErrors.CODE_AUTHORIZATION_FAILED))
        assertEquals(WalletErrors.Kind.Declined, WalletErrors.classify("User declined authorization request", WalletErrors.CODE_AUTHORIZATION_FAILED))
        assertEquals(WalletErrors.Kind.Declined, WalletErrors.classify("Request cancelled", null))
        assertEquals(WalletErrors.Kind.Other, WalletErrors.classify("Request timed out", null))
        assertEquals(WalletErrors.Kind.Other, WalletErrors.classify(null, null))
    }

    @Test fun theMismatchMessageIsFriendlyAndMentionsTheSwitch() {
        assertEquals(WalletErrors.WRONG_NETWORK, WalletErrors.explain("ERROR_CLUSTER_NOT_SUPPORTED"))
        assertTrue(WalletErrors.WRONG_NETWORK.startsWith("Your wallet is on the real network. Switch it to Testnet Mode"))
        assertFalse(WalletErrors.WRONG_NETWORK.contains("ERROR"))
        assertTrue(WalletErrors.POSSIBLE_MISMATCH.contains("Testnet Mode"))
        assertTrue(WalletErrors.WRONG_NETWORK.contains("no real money"))
    }

    // ---------------------------------------------------------------- when the setup screen is shown

    @Test fun firstTimeOnAPracticeNetworkShowsTheScreen() {
        assertEquals(WalletSetupLogic.Reason.First, WalletSetupLogic.reasonToShow(true, false, null))
    }

    @Test fun afterASuccessfulConnectionItIsNotShownAgain() {
        assertNull(WalletSetupLogic.reasonToShow(true, true, null))
    }

    @Test fun aMismatchShowsItAgainEvenForExperiencedUsers() {
        assertEquals(WalletSetupLogic.Reason.Mismatch, WalletSetupLogic.reasonToShow(true, true, WalletErrors.Kind.NetworkMismatch))
        assertEquals(WalletSetupLogic.Reason.Mismatch, WalletSetupLogic.reasonToShow(true, false, WalletErrors.Kind.NetworkMismatch))
        assertEquals(WalletSetupLogic.Reason.PossibleMismatch, WalletSetupLogic.reasonToShow(true, false, WalletErrors.Kind.PossibleMismatch))
    }

    @Test fun otherFailuresDoNotShowIt() {
        assertNull(WalletSetupLogic.reasonToShow(true, true, WalletErrors.Kind.Declined))
        assertNull(WalletSetupLogic.reasonToShow(true, true, WalletErrors.Kind.Other))
        assertEquals("a declined first try still counts as first time", WalletSetupLogic.Reason.First, WalletSetupLogic.reasonToShow(true, false, WalletErrors.Kind.Declined))
    }

    @Test fun aRealNetworkBuildNeverShowsIt() {
        assertNull(WalletSetupLogic.reasonToShow(false, false, WalletErrors.Kind.NetworkMismatch))
        assertTrue(WalletSetupLogic.isPracticeNetwork("devnet"))
        assertTrue(WalletSetupLogic.isPracticeNetwork(null))
        assertFalse(WalletSetupLogic.isPracticeNetwork("mainnet-beta"))
    }

    @Test fun theScreenTextSaysItIsPracticeMoney() {
        for (r in WalletSetupLogic.Reason.values()) {
            val m = WalletSetupLogic.message(r)
            assertTrue(m, m.contains("no real money") || m.contains("practice network"))
        }
    }
}
