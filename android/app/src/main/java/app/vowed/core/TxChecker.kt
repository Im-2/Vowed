package app.vowed.core

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** What the user is about to sign, in plain words. `lines` are label/value pairs for the review screen. */
data class TxReview(val title: String, val lines: List<Pair<String, String>>)

class CreateExpectation(
    val wallet: ByteArray,
    val mint: ByteArray,
    val poolId: String,
    val mode: String,
    val penaltyBps: Int,
    val startTs: Long,
    val durationDays: Int,
    val requiredDays: Int,
    val joinWindowSecs: Long,
    val maxParticipants: Int,
    val demoDaySecs: Int,
    /** SHA-256 of the canonical plan JSON, computed on the phone from the goal the user is looking at. */
    val goalHash: ByteArray,
    /** 0 = Squad (private), 1 = Open (listed in Explore). The pool kind in the transaction must be exactly the one the person chose. */
    val kind: Int = 0,
)

class JoinExpectation(
    val wallet: ByteArray,
    val pool: ByteArray,
    val mint: ByteArray,
    val stakeBaseUnits: String,
    val tzOffsetMinutes: Int,
    val deviceKeyHash: ByteArray,
)

class ClaimExpectation(val wallet: ByteArray, val pool: ByteArray, val mint: ByteArray)

/**
 * Independent check of a transaction built by the backend. It re-derives every account address on the phone and compares the
 * instruction arguments with what the user chose, so a compromised or buggy backend cannot get a different transaction signed.
 * Anything unexpected throws [TxRejected]; the review screen then shows the reason and never calls the wallet.
 */
object TxChecker {
    private val vowed = Base58.decode(Programs.VOWED)
    private val tokenProgram = Base58.decode(Programs.TOKEN_PROGRAM)
    private val systemProgram = Base58.decode(Programs.SYSTEM_PROGRAM)
    private val ataProgram = Base58.decode(Programs.ASSOCIATED_TOKEN_PROGRAM)
    private val addr = VowedAddresses(vowed)

    // account roles: S = signer, W = writable
    private const val R = 0
    private const val W = 1
    private const val S = 2
    private const val SW = 3

    fun checkCreate(txBytes: ByteArray, exp: CreateExpectation): TxReview {
        val tx = TxDecoder.decode(txBytes)
        requireFeePayer(tx, exp.wallet)
        if (tx.instructions.size != 1) throw TxRejected("expected exactly one instruction")
        val ix = tx.instructions[0]
        requireProgram(ix, vowed, "the Vowed program")
        requireDiscriminator(ix, Programs.CREATE_POOL, "create a pool")

        val poolIdLong = BigInteger(exp.poolId).toLong()
        val pool = addr.pool(exp.wallet, poolIdLong)
        requireAccounts(
            ix, Programs.CREATE_POOL_ACCOUNTS,
            listOf(exp.wallet, addr.config, pool, exp.mint, addr.vault(pool), tokenProgram, systemProgram),
            listOf(SW, R, W, R, W, R, R),
        )
        val d = ByteBuffer.wrap(ix.data).order(ByteOrder.LITTLE_ENDIAN)
        if (ix.data.size != 78) throw TxRejected("unexpected instruction data length")
        d.position(8)
        val poolId = d.long
        val kind = d.get().toInt()
        val mode = d.get().toInt()
        val penalty = d.short.toInt() and 0xFFFF
        val start = d.long
        val duration = d.get().toInt() and 0xFF
        val required = d.get().toInt() and 0xFF
        val goalHash = ByteArray(32).also { d.get(it) }
        val joinWindow = d.long
        val maxParticipants = d.int
        val demoDay = d.int
        if (poolId != poolIdLong) throw TxRejected("pool id does not match the pool address")
        if (kind != exp.kind) throw TxRejected(if (exp.kind == 1) "this pool is not public, but you chose public" else "unexpected pool kind: this would be a public pool, but you chose private")
        val expectedMode = if (exp.mode == "Hard") 1 else 0
        if (mode != expectedMode) throw TxRejected("penalty mode differs from the one you chose")
        if (penalty != exp.penaltyBps) throw TxRejected("penalty differs from the one you chose")
        if (start != exp.startTs) throw TxRejected("start time differs from the one you chose")
        if (duration != exp.durationDays || required != exp.requiredDays) throw TxRejected("challenge length differs from your goal")
        if (!goalHash.contentEquals(exp.goalHash)) throw TxRejected("the goal in the transaction is not the goal you are looking at")
        if (joinWindow != exp.joinWindowSecs) throw TxRejected("join window differs from the one you chose")
        if (maxParticipants != exp.maxParticipants) throw TxRejected("participant limit differs from the one you chose")
        if (demoDay != exp.demoDaySecs) throw TxRejected(if (exp.demoDaySecs == 0) "this would create a demo pool, but you asked for a normal one" else "day length differs from the one you chose")

        return TxReview(
            if (demoDay != 0) "Create a DEMO pool" else "Create a pool",
            buildList {
                add("Token" to Base58.encode(exp.mint).short())
                add("Penalty if you fail" to if (exp.mode == "Hard") "lose the whole stake (Hard)" else "${exp.penaltyBps / 100}% of the stake (Soft)")
                add("Length" to "${exp.durationDays} days, at least ${exp.requiredDays} must be completed")
                if (demoDay != 0) add("DEMO POOL" to "each day lasts $demoDay seconds; for demonstration only, with test money")
                add("Pool address" to Base58.encode(pool).short())
                add("What you pay" to "network fee and account rent in SOL; no tokens move until you join")
            },
        )
    }

    fun checkJoin(txBytes: ByteArray, exp: JoinExpectation): TxReview {
        val tx = TxDecoder.decode(txBytes)
        requireFeePayer(tx, exp.wallet)
        if (tx.instructions.size != 1) throw TxRejected("expected exactly one instruction")
        val ix = tx.instructions[0]
        requireProgram(ix, vowed, "the Vowed program")
        requireDiscriminator(ix, Programs.JOIN_POOL, "join a pool")
        requireAccounts(
            ix, Programs.JOIN_POOL_ACCOUNTS,
            listOf(exp.wallet, addr.config, exp.pool, addr.participation(exp.pool, exp.wallet), exp.mint, addr.vault(exp.pool), addr.ata(exp.wallet, exp.mint), tokenProgram, systemProgram),
            listOf(SW, R, W, W, R, W, W, R, R),
        )
        if (ix.data.size != 50) throw TxRejected("unexpected instruction data length")
        val d = ByteBuffer.wrap(ix.data).order(ByteOrder.LITTLE_ENDIAN)
        d.position(8)
        val stake = BigInteger(java.lang.Long.toUnsignedString(d.long))
        val tz = d.short.toInt()
        val deviceHash = ByteArray(32).also { d.get(it) }
        if (stake != BigInteger(exp.stakeBaseUnits)) throw TxRejected("the amount in the transaction ($stake) is not the stake you chose (${exp.stakeBaseUnits})")
        if (tz != exp.tzOffsetMinutes) throw TxRejected("timezone differs from your phone's")
        if (!deviceHash.contentEquals(exp.deviceKeyHash)) throw TxRejected("the stake would be bound to a different device key than this phone's")
        return TxReview(
            "Join and stake",
            listOf(
                "Stake" to "${formatUnits(stake)} tokens, held by the program until the challenge is settled",
                "Pool" to Base58.encode(exp.pool).short(),
                "Token" to Base58.encode(exp.mint).short(),
                "Bound to" to "this phone's proof key",
                "What you pay" to "the stake, plus network fee and account rent in SOL",
            ),
        )
    }

    fun checkClaim(txBytes: ByteArray, exp: ClaimExpectation): TxReview {
        val tx = TxDecoder.decode(txBytes)
        requireFeePayer(tx, exp.wallet)
        val own = addr.ata(exp.wallet, exp.mint)
        val ixs = tx.instructions
        if (ixs.size !in 1..2) throw TxRejected("expected a claim, optionally preceded by creating your token account")
        if (ixs.size == 2) {
            val ata = ixs[0]
            requireProgram(ata, ataProgram, "the associated token account program")
            if (!ata.data.contentEquals(byteArrayOf(1))) throw TxRejected("unexpected token-account instruction")
            requireAccounts(ata, listOf("payer", "ata", "owner", "mint", "system", "token"), listOf(exp.wallet, own, exp.wallet, exp.mint, systemProgram, tokenProgram), listOf(SW, W, SW, R, R, R))
        }
        val ix = ixs.last()
        requireProgram(ix, vowed, "the Vowed program")
        requireDiscriminator(ix, Programs.CLAIM, "claim")
        if (ix.data.size != 8) throw TxRejected("unexpected instruction data length")
        requireAccounts(
            ix, Programs.CLAIM_ACCOUNTS,
            listOf(exp.wallet, exp.pool, addr.participation(exp.pool, exp.wallet), exp.mint, addr.vault(exp.pool), own, tokenProgram),
            listOf(SW, W, W, R, W, W, R),
        )
        return TxReview(
            "Claim your payout",
            listOf(
                "Pays out to" to "your own token account (${Base58.encode(own).short()})",
                "Pool" to Base58.encode(exp.pool).short(),
                "What you pay" to "network fee in SOL",
            ),
        )
    }

    // ------------------------------------------------------------------ helpers

    private fun requireFeePayer(tx: DecodedTransaction, wallet: ByteArray) {
        if (!tx.feePayer.contentEquals(wallet)) throw TxRejected("the fee payer is not your wallet")
        if (tx.numRequiredSignatures != 1) throw TxRejected("the transaction needs signatures other than yours")
    }

    private fun requireProgram(ix: DecodedInstruction, expected: ByteArray, name: String) {
        if (!ix.programId.contentEquals(expected)) throw TxRejected("the transaction calls a program that is not $name")
    }

    private fun requireDiscriminator(ix: DecodedInstruction, expected: ByteArray, what: String) {
        if (ix.data.size < 8 || !ix.data.copyOfRange(0, 8).contentEquals(expected)) throw TxRejected("the instruction is not \"$what\"")
    }

    private fun requireAccounts(ix: DecodedInstruction, names: List<String>, expected: List<ByteArray>, roles: List<Int>) {
        if (ix.accounts.size != expected.size) throw TxRejected("unexpected number of accounts (${ix.accounts.size}, expected ${expected.size})")
        for (i in expected.indices) {
            if (!ix.accounts[i].contentEquals(expected[i])) throw TxRejected("account \"${names[i]}\" is not the address derived for it")
            val signer = roles[i] and S != 0
            val writable = roles[i] and W != 0
            if (ix.isSigner[i] != signer) throw TxRejected("account \"${names[i]}\" has an unexpected signer flag")
            if (ix.isWritable[i] != writable) throw TxRejected("account \"${names[i]}\" has an unexpected write permission")
        }
    }

    private fun String.short() = if (length > 12) "${take(5)}…${takeLast(5)}" else this

    /** Test tokens have 6 decimals. */
    fun formatUnits(baseUnits: BigInteger, decimals: Int = 6): String {
        val s = baseUnits.toString().padStart(decimals + 1, '0')
        val whole = s.dropLast(decimals)
        val frac = s.takeLast(decimals).trimEnd('0')
        return if (frac.isEmpty()) whole else "$whole.$frac"
    }
}
