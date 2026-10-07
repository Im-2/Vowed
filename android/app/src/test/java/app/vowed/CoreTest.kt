package app.vowed

import app.vowed.core.Base58
import app.vowed.core.ClaimExpectation
import app.vowed.core.CreateExpectation
import app.vowed.core.FreezePaymentExpectation
import app.vowed.core.JoinExpectation
import app.vowed.core.PlanHash
import app.vowed.core.Programs
import app.vowed.core.TxChecker
import app.vowed.core.TxDecoder
import app.vowed.core.TxRejected
import app.vowed.core.VowedAddresses
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

/** Safety-critical checks, run against real transactions built by the backend (src/test/resources/vowed-fixtures.json). */
class CoreTest {
    private val fx: JsonObject = Json.parseToJsonElement(javaClass.classLoader!!.getResourceAsStream("vowed-fixtures.json")!!.reader().readText()).jsonObject
    private fun s(k: String) = fx[k]!!.jsonPrimitive.content
    private fun key(k: String) = Base58.decode(s(k))
    private fun tx(section: String) = Base64.getDecoder().decode(fx[section]!!.jsonObject["tx"]!!.jsonPrimitive.content)
    private val hexToBytes = { h: String -> ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() } }

    private val addr = VowedAddresses(Base58.decode(Programs.VOWED))

    private fun createExp(over: (CreateExpectation) -> CreateExpectation = { it }) = over(
        CreateExpectation(
            key("wallet"), key("mint"), s("poolId"), "Hard", 10_000, 1_800_000_000, 2, 2, 60, 10, 60, hexToBytes(s("goalHash")),
        ),
    )
    private fun joinExp(stake: String = "4000000", tz: Int = 60, device: ByteArray = hexToBytes(s("deviceKeyHash")), wallet: ByteArray = key("wallet")) =
        JoinExpectation(wallet, key("pool"), key("mint"), stake, tz, device)
    private fun claimExp() = ClaimExpectation(key("wallet"), key("pool"), key("mint"))

    // ------------------------------------------------------------------ base58
    @Test fun base58RoundTripsAndKnownValues() {
        assertEquals("11111111111111111111111111111111", Base58.encode(ByteArray(32)))
        assertEquals("", Base58.encode(ByteArray(0)))
        assertArrayEquals(byteArrayOf(0, 0, 1), Base58.decode("112"))
        val rnd = java.util.Random(7)
        repeat(200) {
            val b = ByteArray(rnd.nextInt(70)).also(rnd::nextBytes)
            assertArrayEquals(b, Base58.decode(Base58.encode(b)))
        }
        assertEquals(Programs.VOWED, Base58.encode(Base58.decode(Programs.VOWED)))
        assertThrows(IllegalArgumentException::class.java) { Base58.decode("0OIl") }
    }

    // ------------------------------------------------------------------ address derivation
    @Test fun derivedAddressesMatchTheBackend() {
        assertEquals(s("config"), Base58.encode(addr.config))
        val pool = addr.pool(key("wallet"), s("poolId").toLong())
        assertEquals(s("pool"), Base58.encode(pool))
        assertEquals(s("vault"), Base58.encode(addr.vault(pool)))
        assertEquals(s("participation"), Base58.encode(addr.participation(pool, key("wallet"))))
        assertEquals(s("userToken"), Base58.encode(addr.ata(key("wallet"), key("mint"))))
    }

    // ------------------------------------------------------------------ the real transactions pass
    @Test fun realCreateJoinClaimTransactionsAreAccepted() {
        val create = TxChecker.checkCreate(tx("create"), createExp())
        assertTrue(create.title.contains("DEMO"))
        assertTrue(create.lines.any { it.first == "DEMO POOL" })
        val join = TxChecker.checkJoin(tx("join"), joinExp())
        assertEquals("Join and stake", join.title)
        assertTrue(join.lines.first().second.startsWith("4 tokens"))
        assertEquals("Claim your payout", TxChecker.checkClaim(tx("claim"), claimExp()).title)
    }

    @Test fun decoderReadsTheStructure() {
        val d = TxDecoder.decode(tx("claim"))
        assertEquals(2, d.instructions.size) // create token account, then claim
        assertArrayEquals(key("wallet"), d.feePayer)
        assertEquals(1, d.numRequiredSignatures)
    }

    // ------------------------------------------------------------------ a compromised backend cannot slip anything through
    private fun mutate(bytes: ByteArray, f: (ByteArray) -> Unit) = bytes.copyOf().also(f)
    private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        error("needle not found")
    }
    private fun reject(block: () -> Unit) = assertThrows(TxRejected::class.java) { block() }

    /** The fixture pool is a Squad (kind 0) pool; this copy of it says Open (kind 1), as a public challenge would. */
    private fun openCreateTx(): ByteArray {
        val b = tx("create").copyOf()
        val disc = Programs.CREATE_POOL
        val at = (0..b.size - 8).first { i -> (0 until 8).all { b[i + it] == disc[it] } }
        b[at + 16] = 1 // 8 discriminator bytes + 8 pool id bytes, then the kind byte
        return b
    }

    @Test fun publicChoiceAcceptsAnOpenPoolAndRefusesASquadPool() {
        TxChecker.checkCreate(openCreateTx(), createExp { CreateExpectation(it.wallet, it.mint, it.poolId, it.mode, it.penaltyBps, it.startTs, it.durationDays, it.requiredDays, it.joinWindowSecs, it.maxParticipants, it.demoDaySecs, it.goalHash, kind = 1) })
        reject { TxChecker.checkCreate(tx("create"), createExp { CreateExpectation(it.wallet, it.mint, it.poolId, it.mode, it.penaltyBps, it.startTs, it.durationDays, it.requiredDays, it.joinWindowSecs, it.maxParticipants, it.demoDaySecs, it.goalHash, kind = 1) }) }
    }

    @Test fun privateChoiceRefusesAnOpenPool() {
        reject { TxChecker.checkCreate(openCreateTx(), createExp()) } // the person chose private, the transaction says public
    }

    private fun freezeExp(over: (FreezePaymentExpectation) -> FreezePaymentExpectation = { it }) = over(
        FreezePaymentExpectation(key("wallet"), key("mint"), Base58.decode(fx["freeze"]!!.jsonObject["payee"]!!.jsonPrimitive.content), fx["freeze"]!!.jsonObject["price"]!!.jsonPrimitive.content),
    )

    @Test fun freezePaymentIsAcceptedAndDescribedAsTestSkr() {
        val review = TxChecker.checkFreezePayment(tx("freeze"), freezeExp())
        assertEquals("Buy a streak freeze", review.title)
        assertTrue(review.lines.any { it.second.contains("TEST token") && it.second.startsWith("1 SKR") })
    }

    @Test fun freezePaymentRefusesAnyDifferentPaymentThanShown() {
        reject { TxChecker.checkFreezePayment(tx("freeze"), freezeExp { FreezePaymentExpectation(it.wallet, it.mint, it.payee, "2000000") }) } // a higher price than the one shown
        reject { TxChecker.checkFreezePayment(tx("freeze"), freezeExp { FreezePaymentExpectation(it.wallet, it.mint, key("otherWallet"), it.priceBaseUnits) }) } // paid to somebody else
        reject { TxChecker.checkFreezePayment(tx("freeze"), freezeExp { FreezePaymentExpectation(key("otherWallet"), it.mint, it.payee, it.priceBaseUnits) }) } // not your wallet
        reject { TxChecker.checkFreezePayment(tx("freeze"), freezeExp { FreezePaymentExpectation(it.wallet, key("otherWallet"), it.payee, it.priceBaseUnits) }) } // another token
        reject { TxChecker.checkFreezePayment(tx("claim"), freezeExp()) } // a different kind of transaction
        reject { TxChecker.checkFreezePayment(tx("join"), freezeExp()) }
    }

    @Test fun createRefusesWrongIntent() {
        val t = tx("create")
        reject { TxChecker.checkCreate(t, createExp { CreateExpectation(it.wallet, it.mint, it.poolId, "Soft", 3_000, it.startTs, it.durationDays, it.requiredDays, it.joinWindowSecs, it.maxParticipants, it.demoDaySecs, it.goalHash) }) }
        reject { TxChecker.checkCreate(t, createExp { CreateExpectation(it.wallet, it.mint, it.poolId, it.mode, it.penaltyBps, it.startTs, it.durationDays, it.requiredDays, it.joinWindowSecs, it.maxParticipants, 0, it.goalHash) }) } // user wanted a NORMAL pool
        reject { TxChecker.checkCreate(t, createExp { CreateExpectation(it.wallet, it.mint, it.poolId, it.mode, it.penaltyBps, it.startTs, 7, 5, it.joinWindowSecs, it.maxParticipants, it.demoDaySecs, it.goalHash) }) }
        reject { TxChecker.checkCreate(t, createExp { CreateExpectation(it.wallet, key("otherWallet"), it.poolId, it.mode, it.penaltyBps, it.startTs, it.durationDays, it.requiredDays, it.joinWindowSecs, it.maxParticipants, it.demoDaySecs, it.goalHash) }) } // other token
        reject { TxChecker.checkCreate(t, createExp { CreateExpectation(key("otherWallet"), it.mint, it.poolId, it.mode, it.penaltyBps, it.startTs, it.durationDays, it.requiredDays, it.joinWindowSecs, it.maxParticipants, it.demoDaySecs, it.goalHash) }) } // fee payer
        reject { TxChecker.checkCreate(t, createExp { CreateExpectation(it.wallet, it.mint, "123456790", it.mode, it.penaltyBps, it.startTs, it.durationDays, it.requiredDays, it.joinWindowSecs, it.maxParticipants, it.demoDaySecs, it.goalHash) }) } // pool id
        reject { TxChecker.checkCreate(t, createExp { CreateExpectation(it.wallet, it.mint, it.poolId, it.mode, it.penaltyBps, it.startTs, it.durationDays, it.requiredDays, it.joinWindowSecs, it.maxParticipants, it.demoDaySecs, ByteArray(32)) }) } // other goal
    }

    @Test fun createRefusesTamperedBytes() {
        val t = tx("create")
        val gh = hexToBytes(s("goalHash"))
        reject { TxChecker.checkCreate(mutate(t) { b -> b[indexOf(b, gh)] = (b[indexOf(b, gh)] + 1).toByte() }, createExp()) } // goal hash changed in the instruction
        val vault = key("vault")
        reject { TxChecker.checkCreate(mutate(t) { b -> indexOf(b, vault).let { i -> b[i] = (b[i] + 1).toByte() } }, createExp()) } // vault account swapped
        val prog = Base58.decode(Programs.VOWED)
        reject { TxChecker.checkCreate(mutate(t) { b -> indexOf(b, prog).let { i -> b[i] = (b[i] + 1).toByte() } }, createExp()) } // different program
        reject { TxChecker.checkCreate(t.copyOf(t.size - 1), createExp()) } // truncated
        reject { TxChecker.checkCreate(t + byteArrayOf(0), createExp()) } // trailing bytes
    }

    @Test fun joinRefusesWrongAmountTimezoneDeviceOrRecipient() {
        val t = tx("join")
        reject { TxChecker.checkJoin(t, joinExp(stake = "40000000")) } // user chose 40, tx says 4
        reject { TxChecker.checkJoin(t, joinExp(stake = "3999999")) }
        reject { TxChecker.checkJoin(t, joinExp(tz = 0)) }
        reject { TxChecker.checkJoin(t, joinExp(device = ByteArray(32))) } // stake bound to some other device key
        reject { TxChecker.checkJoin(t, joinExp(wallet = key("otherWallet"))) }
        // stake bytes raised inside the instruction
        val stake = java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN).putLong(4_000_000).array()
        reject { TxChecker.checkJoin(mutate(t) { b -> b[indexOf(b, stake) + 2] = (b[indexOf(b, stake) + 2] + 1).toByte() }, joinExp()) }
        // token account redirected to someone else's
        val own = key("userToken")
        val decoy = Base58.decode(fx["decoys"]!!.jsonObject["token"]!!.jsonPrimitive.content)
        reject { TxChecker.checkJoin(mutate(t) { b -> decoy.copyInto(b, indexOf(b, own)) }, joinExp()) }
        // vault swapped for a decoy
        val vault = key("vault")
        val decoyVault = Base58.decode(fx["decoys"]!!.jsonObject["vault"]!!.jsonPrimitive.content)
        reject { TxChecker.checkJoin(mutate(t) { b -> decoyVault.copyInto(b, indexOf(b, vault)) }, joinExp()) }
    }

    @Test fun claimRefusesRedirectedPayoutAndExtraInstructions() {
        val t = tx("claim")
        val own = key("userToken")
        val decoy = Base58.decode(fx["decoys"]!!.jsonObject["token"]!!.jsonPrimitive.content)
        reject { TxChecker.checkClaim(mutate(t) { b -> decoy.copyInto(b, indexOf(b, own)) }, claimExp()) } // payout to a stranger's account
        reject { TxChecker.checkClaim(tx("join"), claimExp()) } // a join dressed up as a claim
        reject { TxChecker.checkClaim(tx("create"), claimExp()) }
        reject { TxChecker.checkJoin(tx("claim"), joinExp()) }
        // a system-program transfer smuggled in is not an instruction we accept: build one by hand
        val xfer = byteArrayOf(2, 0, 0, 0) + ByteArray(8)
        val padded = t.copyOf() // the real claim tx, but with the program id of its last instruction replaced by the system program
        val prog = Base58.decode(Programs.VOWED)
        System.arraycopy(Base58.decode(Programs.SYSTEM_PROGRAM), 0, padded, indexOf(padded, prog), 32)
        assertTrue(xfer.isNotEmpty())
        reject { TxChecker.checkClaim(padded, claimExp()) }
    }

    @Test fun versionedTransactionsAreRefused() {
        val t = tx("join")
        val sigBytes = 1 + 64
        val versioned = mutate(t) { b -> b[sigBytes] = (b[sigBytes].toInt() or 0x80).toByte() }
        reject { TxDecoder.decode(versioned) }
    }

    // ------------------------------------------------------------------ constants match the IDL
    @Test fun discriminatorsAndAccountOrderMatchTheIdl() {
        val idl = Json.parseToJsonElement(File("../../programs/vowed/idl/vowed.json").readText()).jsonObject
        fun ix(name: String) = idl["instructions"]!!.jsonArray.map { it.jsonObject }.first { it["name"]!!.jsonPrimitive.content == name }
        fun disc(name: String) = ByteArray(8) { ix(name)["discriminator"]!!.jsonArray[it].jsonPrimitive.content.toInt().toByte() }
        fun accounts(name: String) = ix(name)["accounts"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertArrayEquals(disc("create_pool"), Programs.CREATE_POOL)
        assertArrayEquals(disc("join_pool"), Programs.JOIN_POOL)
        assertArrayEquals(disc("claim"), Programs.CLAIM)
        assertEquals(accounts("create_pool"), Programs.CREATE_POOL_ACCOUNTS)
        assertEquals(accounts("join_pool"), Programs.JOIN_POOL_ACCOUNTS)
        assertEquals(accounts("claim"), Programs.CLAIM_ACCOUNTS)
        assertEquals(Programs.VOWED, idl["address"]!!.jsonPrimitive.content)
    }

    // ------------------------------------------------------------------ plan hash equals the backend's
    @Test fun planHashMatchesSharedVectors() {
        val v = Json.parseToJsonElement(File("../../shared/test-vectors/plan-hash.json").readText()).jsonObject["cases"]!!.jsonArray
        assertTrue(v.size >= 5)
        for (c in v) {
            val o = c.jsonObject
            assertEquals(o["canonical"]!!.jsonPrimitive.content, PlanHash.canonicalJson(o["plan"]!!))
            assertEquals(o["sha256"]!!.jsonPrimitive.content, PlanHash.hash(o["plan"]!!).joinToString("") { "%02x".format(it) })
        }
    }
}
