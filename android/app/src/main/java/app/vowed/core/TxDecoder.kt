package app.vowed.core

/** Thrown when a transaction does not match what the user asked for. The wallet is never opened in that case. */
class TxRejected(message: String) : Exception(message)

class DecodedInstruction(
    val programId: ByteArray,
    val accounts: List<ByteArray>,
    val isSigner: List<Boolean>,
    val isWritable: List<Boolean>,
    val data: ByteArray,
)

class DecodedTransaction(
    val signatureCount: Int,
    val numRequiredSignatures: Int,
    val accountKeys: List<ByteArray>,
    val recentBlockhash: ByteArray,
    val instructions: List<DecodedInstruction>,
) {
    val feePayer: ByteArray get() = accountKeys[0]
}

/** Strict decoder for Solana legacy transactions. Versioned transactions, lookup tables and trailing bytes are refused. */
object TxDecoder {
    private class Reader(val b: ByteArray) {
        var pos = 0
        fun u8(): Int {
            if (pos >= b.size) throw TxRejected("transaction is truncated")
            return b[pos++].toInt() and 0xFF
        }
        fun take(n: Int): ByteArray {
            if (n < 0 || pos + n > b.size) throw TxRejected("transaction is truncated")
            return b.copyOfRange(pos, pos + n).also { pos += n }
        }
        fun compactU16(): Int {
            var value = 0
            for (shift in 0 until 3) {
                val byte = u8()
                value = value or ((byte and 0x7F) shl (7 * shift))
                if (byte and 0x80 == 0) return value
            }
            throw TxRejected("invalid compact length")
        }
        val remaining: Int get() = b.size - pos
    }

    fun decode(bytes: ByteArray): DecodedTransaction {
        val r = Reader(bytes)
        val sigCount = r.compactU16()
        if (sigCount > 8) throw TxRejected("too many signatures")
        r.take(sigCount * 64)
        val first = bytes.getOrNull(r.pos)?.toInt()?.and(0xFF) ?: throw TxRejected("transaction has no message")
        if (first and 0x80 != 0) throw TxRejected("versioned transactions are not accepted")
        val numRequired = r.u8()
        val numReadonlySigned = r.u8()
        val numReadonlyUnsigned = r.u8()
        val keyCount = r.compactU16()
        if (keyCount == 0 || keyCount > 64) throw TxRejected("unexpected number of accounts")
        val keys = List(keyCount) { r.take(32) }
        if (numRequired < 1 || numRequired > keyCount || numReadonlySigned >= numRequired) throw TxRejected("invalid message header")
        if (sigCount != numRequired) throw TxRejected("signature count does not match the header")
        val blockhash = r.take(32)
        val ixCount = r.compactU16()
        if (ixCount == 0 || ixCount > 8) throw TxRejected("unexpected number of instructions")
        val instructions = List(ixCount) {
            val programIndex = r.u8()
            if (programIndex >= keyCount) throw TxRejected("instruction refers to a missing program")
            val accountCount = r.compactU16()
            val indices = List(accountCount) {
                val i = r.u8()
                if (i >= keyCount) throw TxRejected("instruction refers to a missing account")
                i
            }
            val data = r.take(r.compactU16())
            fun signer(i: Int) = i < numRequired
            fun writable(i: Int) = if (i < numRequired) i < numRequired - numReadonlySigned else i < keyCount - numReadonlyUnsigned
            DecodedInstruction(keys[programIndex], indices.map { keys[it] }, indices.map(::signer), indices.map(::writable), data)
        }
        if (r.remaining != 0) throw TxRejected("transaction has unexpected trailing data")
        return DecodedTransaction(sigCount, numRequired, keys, blockhash, instructions)
    }
}
