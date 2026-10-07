package app.vowed.core

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

fun sha256(vararg parts: ByteArray): ByteArray {
    val md = MessageDigest.getInstance("SHA-256")
    parts.forEach { md.update(it) }
    return md.digest()
}

/** Program-derived address derivation, identical to Solana's `find_program_address`. Used to verify every account in a transaction. */
object Pda {
    private val P: BigInteger = BigInteger.ONE.shiftLeft(255) - BigInteger.valueOf(19)
    private val D: BigInteger = BigInteger.valueOf(-121665).mod(P) * BigInteger.valueOf(121666).modInverse(P) % P
    private val MARKER = "ProgramDerivedAddress".toByteArray()

    /** True when the 32 bytes decode to a point on the ed25519 curve (such a value can never be a PDA). */
    fun isOnCurve(bytes: ByteArray): Boolean {
        require(bytes.size == 32)
        val le = bytes.copyOf()
        le[31] = (le[31].toInt() and 0x7F).toByte() // clear the sign bit of x
        val y = BigInteger(1, le.reversedArray())
        val y2 = y.multiply(y).mod(P)
        val u = (y2 - BigInteger.ONE).mod(P) // y^2 - 1
        val v = (D.multiply(y2) + BigInteger.ONE).mod(P) // d*y^2 + 1
        if (v.signum() == 0) return false
        val x2 = u.multiply(v.modInverse(P)).mod(P)
        if (x2.signum() == 0) return true
        return x2.modPow((P - BigInteger.ONE).shiftRight(1), P) == BigInteger.ONE // Euler's criterion: x2 is a square
    }

    fun find(seeds: List<ByteArray>, programId: ByteArray): Pair<ByteArray, Int> {
        for (bump in 255 downTo 0) {
            val hash = sha256(*seeds.toTypedArray(), byteArrayOf(bump.toByte()), programId, MARKER)
            if (!isOnCurve(hash)) return hash to bump
        }
        error("no viable bump seed")
    }

    fun u64le(v: Long): ByteArray = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array()
}

/** Addresses of the Vowed program and the SPL token program, all derived locally. */
class VowedAddresses(val programId: ByteArray) {
    val config: ByteArray get() = Pda.find(listOf("config".toByteArray()), programId).first
    fun pool(creator: ByteArray, poolId: Long) = Pda.find(listOf("pool".toByteArray(), creator, Pda.u64le(poolId)), programId).first
    fun vault(pool: ByteArray) = Pda.find(listOf("vault".toByteArray(), pool), programId).first
    fun participation(pool: ByteArray, user: ByteArray) = Pda.find(listOf("part".toByteArray(), pool, user), programId).first

    /** Associated token account address of `owner` for `mint`. */
    fun ata(owner: ByteArray, mint: ByteArray): ByteArray =
        Pda.find(listOf(owner, Base58.decode(Programs.TOKEN_PROGRAM), mint), Base58.decode(Programs.ASSOCIATED_TOKEN_PROGRAM)).first
}
