package app.vowed

import app.vowed.data.ApiException
import app.vowed.data.BackendApi
import app.vowed.data.FaucetClaim
import app.vowed.data.FaucetText
import app.vowed.data.AppJson
import app.vowed.data.KeyValue
import app.vowed.data.Session
import app.vowed.data.SessionKeeper
import app.vowed.data.SessionStore
import app.vowed.data.VerifyResponse
import app.vowed.letters.LetterCipher
import app.vowed.wallet.WalletErrors
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.KeyGenerator

private class MapKv : KeyValue {
    val map = HashMap<String, String>()
    override fun get(key: String) = map[key]
    override fun put(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
}

private const val W = "11111111111111111111111111111112"

/** (a) the saved session, (b) the server forgetting the phone, (c) wallet messages, and the faucet lines. */
class SessionTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private var now = 1_000_000L
    private fun store(kv: MapKv = MapKv(), k: javax.crypto.SecretKey = key) = SessionStore(kv, LetterCipher { k }) { now }

    // ---------------------------------------------------------------- (a) persisted session

    @Test fun savedSessionComesBackAfterARestartAndIsEncryptedOnDisk() {
        val kv = MapKv()
        store(kv).save(Session(W, "SECRET.JWT.TOKEN", now + 3_600))
        val onDisk = kv.map.values.joinToString()
        assertFalse("the token must not be readable in storage", onDisk.contains("SECRET") || onDisk.contains(W))
        val back = store(kv).load(W)
        assertEquals("SECRET.JWT.TOKEN", back?.token)
    }

    @Test fun expiredSessionIsNotRestoredAndIsWiped() {
        val kv = MapKv()
        store(kv).save(Session(W, "t", now + 100))
        now += 101
        assertNull(store(kv).load(W))
        assertTrue(kv.map.isEmpty())
    }

    @Test fun sessionOfAnotherWalletIsNotRestored() {
        val kv = MapKv()
        store(kv).save(Session(W, "t", now + 100))
        assertNull(store(kv).load("SomeoneElse"))
    }

    @Test fun tamperedOrForeignKeyDataIsRejectedAndWiped() {
        val kv = MapKv()
        store(kv).save(Session(W, "t", now + 100))
        val other = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertNull("another key (for example a backup restored on another phone) cannot read it", store(kv, other).load(W))
        assertTrue(kv.map.isEmpty())
        store(kv).save(Session(W, "t", now + 100))
        val k = kv.map.keys.first()
        val raw = java.util.Base64.getDecoder().decode(kv.map[k])
        raw[raw.size - 1] = (raw[raw.size - 1] + 1).toByte()
        kv.map[k] = java.util.Base64.getEncoder().encodeToString(raw)
        assertNull(store(kv).load(W))
    }

    private fun keeper(st: SessionStore, tokenSink: MutableList<String?>, refresh: suspend () -> VerifyResponse) =
        SessionKeeper(st, { tokenSink.add(it) }, refresh, { now })

    @Test fun restoreSetsTheTokenWithoutTheWallet() {
        val st = store()
        st.save(Session(W, "saved", now + 7_200))
        val sink = mutableListOf<String?>()
        val k = keeper(st, sink) { error("not needed") }
        assertTrue(k.restore(W))
        assertEquals("saved", sink.last())
        assertFalse(keeper(store(MapKv()), mutableListOf()) { error("x") }.restore(W))
        assertFalse(keeper(st, mutableListOf()) { error("x") }.restore(null))
    }

    @Test fun aSessionWithPlentyOfTimeLeftIsNotRefreshed() = runBlocking {
        val st = store()
        var calls = 0
        val k = keeper(st, mutableListOf()) { calls++; VerifyResponse("new", now + 21_600, W) }
        k.adopt(VerifyResponse("old", now + 5 * 3_600, W))
        assertEquals(SessionKeeper.Outcome.NotNeeded, k.refreshIfNeeded())
        assertEquals(0, calls)
    }

    @Test fun aSessionThatIsAboutToEndIsRefreshedQuietlyAndSaved() = runBlocking {
        val kv = MapKv()
        val st = store(kv)
        val sink = mutableListOf<String?>()
        val k = keeper(st, sink) { VerifyResponse("new", now + 21_600, W) }
        k.adopt(VerifyResponse("old", now + 3_600, W))
        assertEquals(SessionKeeper.Outcome.Refreshed, k.refreshIfNeeded())
        assertEquals("new", sink.last())
        assertEquals("new", store(kv).load(W)?.token)
    }

    @Test fun aRefusedRefreshEndsTheSessionButAMissingNetworkDoesNot() = runBlocking {
        val kv = MapKv()
        val st = store(kv)
        val sink = mutableListOf<String?>()
        val offline = keeper(st, sink) { throw ApiException(0, "network", "offline") }
        offline.adopt(VerifyResponse("old", now + 3_600, W))
        assertEquals(SessionKeeper.Outcome.Offline, offline.refreshIfNeeded())
        assertNotNull(store(kv).load(W))
        val refused = keeper(st, sink) { throw ApiException(401, "unauthorized", "the session is too old; sign in with the wallet again") }
        refused.adopt(VerifyResponse("old", now + 3_600, W))
        assertEquals(SessionKeeper.Outcome.Expired, refused.refreshIfNeeded())
        assertNull(sink.last())
        assertNull(store(kv).load(W))
    }

    @Test fun aStorageFailureNeverBlocksSigningIn() {
        val broken = SessionStore(MapKv(), LetterCipher { error("keystore unavailable") }) { now }
        val sink = mutableListOf<String?>()
        val k = SessionKeeper(broken, { sink.add(it) }, { error("x") }, { now })
        k.adopt(VerifyResponse("tok", now + 3_600, W))
        assertEquals("tok", sink.last())
        assertEquals("tok", k.current?.token)
        assertFalse(k.restore(W))
    }

    @Test fun forgetClearsTokenAndStorage() {
        val kv = MapKv()
        val sink = mutableListOf<String?>()
        val k = keeper(store(kv), sink) { error("x") }
        k.adopt(VerifyResponse("t", now + 100, W))
        k.forget()
        assertNull(sink.last())
        assertTrue(kv.map.isEmpty())
        assertNull(k.current)
    }

    // ---------------------------------------------------------------- (b) the server forgot the phone

    @Test fun deviceNotRegisteredFromTheServerTriggersTheRecoveryHookOnce() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":{"code":"device_not_registered","message":"register the device you joined with before proving"}}"""))
        server.start()
        try {
            val api = BackendApi(baseUrl = { server.url("/").toString() })
            var hits = 0
            api.onDeviceUnknown = { hits++ }
            val e = runCatching { api.challenges() }.exceptionOrNull() as ApiException
            assertEquals("device_not_registered", e.code)
            assertEquals(1, hits)
        } finally { server.shutdown() }
    }

    @Test fun otherErrorsDoNotTriggerTheDeviceHookButA401WithATokenSignalsAnEndedSession() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":{"code":"other","message":"x"}}"""))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"code":"unauthorized","message":"invalid or expired token"}}"""))
        server.start()
        try {
            val api = BackendApi(baseUrl = { server.url("/").toString() })
            api.token = "t"
            var device = 0
            var unauthorized = 0
            api.onDeviceUnknown = { device++ }
            api.onUnauthorized = { unauthorized++ }
            runCatching { api.challenges() }
            runCatching { api.challenges() }
            assertEquals(0, device)
            assertEquals(1, unauthorized)
        } finally { server.shutdown() }
    }

    // ---------------------------------------------------------------- (c) wallet messages

    @Test fun lockedWalletGetsAClearInstruction() {
        assertEquals(WalletErrors.LOCKED, WalletErrors.explain("UserNotAuthenticatedException: User not authenticated"))
        assertEquals(WalletErrors.LOCKED, WalletErrors.explain("Authentication failed"))
        assertTrue(WalletErrors.LOCKED.startsWith("Open your wallet and unlock it, then try again"))
    }

    @Test fun slowColdStartAndApprovalTimeoutsAreExplained() {
        assertEquals(WalletErrors.CLOSED_BEFORE_CONNECTING, WalletErrors.explain("Failed establishing local association with wallet endpoint"))
        assertEquals(WalletErrors.SLOW, WalletErrors.explain("Wallet did not respond: operation timeout"))
        assertEquals(WalletErrors.SLOW, WalletErrors.explain("Request timed out"))
        assertEquals(WalletErrors.CLOSED_BEFORE_CONNECTING, WalletErrors.explain("Local association cancelled before connected"))
    }

    @Test fun declinedAndExpiredAreExplained() {
        assertEquals(WalletErrors.DECLINED, WalletErrors.explain("User declined the request"))
        assertEquals(WalletErrors.EXPIRED, WalletErrors.explain("payloads invalid for signing"))
        assertEquals(WalletErrors.EXPIRED, WalletErrors.explain("Blockhash not found"))
    }

    @Test fun wrongNetworkIsRecognisedAndExplained() {
        assertTrue(WalletErrors.isWrongNetwork("ERROR_CHAIN_NOT_SUPPORTED"))
        assertTrue(WalletErrors.isWrongNetwork("The wallet does not support the requested chain: not supported"))
        assertEquals(WalletErrors.WRONG_NETWORK, WalletErrors.explain("Authorization failed: chain not supported"))
        assertTrue(WalletErrors.WRONG_NETWORK.contains("devnet"))
        assertFalse(WalletErrors.isWrongNetwork("User declined"))
    }

    @Test fun noFeeFundsPointsToDevnetSol() {
        assertEquals(WalletErrors.NO_FEE_FUNDS, WalletErrors.explain("Attempt to debit an account but found no record of a prior credit."))
    }

    @Test fun anythingElseKeepsTheDetail() {
        assertTrue(WalletErrors.explain("weird thing").contains("weird thing"))
        assertTrue(WalletErrors.explain(null).contains("no detail"))
    }

    // ---------------------------------------------------------------- faucet lines

    private fun claim(sol: String?) = AppJson.decodeFromString<FaucetClaim>(
        """{"signature":"s","minted":{"tUSDC":"20000000","tSKR":"20000000"},"nextClaimAt":1,"label":"TEST"${if (sol != null) ""","sol":$sol""" else ""}}""",
    )

    @Test fun claimMessageMentionsTheSolGiftOnlyWhenSent() {
        assertEquals("Sent 20 tUSDC and 20 tSKR (test tokens). Plus 0.01 SOL (devnet) for network fees.", FaucetText.claimMessage(claim("""{"status":"sent","lamports":"10000000"}""")))
        assertEquals("Sent 20 tUSDC and 20 tSKR (test tokens).", FaucetText.claimMessage(claim(null)))
        assertEquals("Sent 20 tUSDC and 20 tSKR (test tokens).", FaucetText.claimMessage(claim("""{"status":"off","lamports":"10000000"}""")))
        assertTrue(FaucetText.claimMessage(claim("""{"status":"failed","lamports":"1","reason":"sol_faucet_empty"}""")).contains("could not be sent"))
        assertTrue(FaucetText.claimMessage(claim("""{"status":"skipped","lamports":"1","reason":"already_has_sol"}""")).contains("already has devnet SOL"))
    }

    @Test fun solFormatting() {
        assertEquals("0.01", FaucetText.sol("10000000"))
        assertEquals("0", FaucetText.sol("0"))
        assertEquals("1.5", FaucetText.sol("1500000000"))
        assertEquals("0.0123", FaucetText.sol("12345678"))
    }
}
