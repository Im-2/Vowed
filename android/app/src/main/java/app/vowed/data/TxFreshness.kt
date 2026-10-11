package app.vowed.data

/**
 * A legacy unsigned transaction is [signature count][signatures][message]; the message is [3 header bytes][account count][accounts][32-byte blockhash][instructions].
 * [sameExceptBlockhash] is true when two transactions are identical in everything but the blockhash, which is the only thing the server may change when it re-issues one.
 */
object TxFreshness {
    private fun shortvec(b: ByteArray, at: Int): Pair<Int, Int> {
        var value = 0
        var shift = 0
        var i = at
        while (true) {
            val byte = b[i].toInt() and 0xff
            value = value or ((byte and 0x7f) shl shift)
            i++
            if (byte and 0x80 == 0) break
            shift += 7
        }
        return value to i
    }

    /** the byte range [from, to) of the blockhash inside a legacy transaction, or null when the bytes do not parse */
    private fun blockhashRange(tx: ByteArray): IntRange? = try {
        val (sigs, afterCount) = shortvec(tx, 0)
        val message = afterCount + sigs * 64
        val (accounts, afterAccounts) = shortvec(tx, message + 3)
        val start = afterAccounts + accounts * 32
        if (start + 32 <= tx.size) start until start + 32 else null
    } catch (e: Exception) {
        null
    }

    fun sameExceptBlockhash(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        val ra = blockhashRange(a) ?: return false
        val rb = blockhashRange(b) ?: return false
        if (ra != rb) return false
        // the signature slots are ignored: both are unsigned copies; everything from the message on must match outside the blockhash
        val (sigs, afterCount) = shortvec(a, 0)
        val messageStart = afterCount + sigs * 64
        for (i in messageStart until a.size) {
            if (i in ra) continue
            if (a[i] != b[i]) return false
        }
        return true
    }
}
