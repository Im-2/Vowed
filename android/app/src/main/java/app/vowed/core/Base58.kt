package app.vowed.core

/** Bitcoin-alphabet base58, as used for Solana addresses and signatures. */
object Base58 {
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private val INDEXES = IntArray(128) { -1 }.also { for (i in ALPHABET.indices) it[ALPHABET[i].code] = i }

    fun encode(input: ByteArray): String {
        if (input.isEmpty()) return ""
        var zeros = 0
        while (zeros < input.size && input[zeros].toInt() == 0) zeros++
        val copy = input.copyOf()
        val out = CharArray(copy.size * 2)
        var outStart = out.size
        var start = zeros
        while (start < copy.size) {
            out[--outStart] = ALPHABET[divmod(copy, start, 256, 58)]
            if (copy[start].toInt() == 0) start++
        }
        while (outStart < out.size && out[outStart] == ALPHABET[0]) outStart++
        repeat(zeros) { out[--outStart] = ALPHABET[0] }
        return String(out, outStart, out.size - outStart)
    }

    fun decode(input: String): ByteArray {
        if (input.isEmpty()) return ByteArray(0)
        val input58 = ByteArray(input.length)
        for (i in input.indices) {
            val c = input[i]
            val digit = if (c.code < 128) INDEXES[c.code] else -1
            require(digit >= 0) { "invalid base58 character '$c' at $i" }
            input58[i] = digit.toByte()
        }
        var zeros = 0
        while (zeros < input58.size && input58[zeros].toInt() == 0) zeros++
        val decoded = ByteArray(input.length)
        var outStart = decoded.size
        var start = zeros
        while (start < input58.size) {
            decoded[--outStart] = divmod(input58, start, 58, 256).toByte()
            if (input58[start].toInt() == 0) start++
        }
        while (outStart < decoded.size && decoded[outStart].toInt() == 0) outStart++
        return decoded.copyOfRange(outStart - zeros, decoded.size)
    }

    private fun divmod(number: ByteArray, firstDigit: Int, base: Int, divisor: Int): Int {
        var remainder = 0
        for (i in firstDigit until number.size) {
            val digit = number[i].toInt() and 0xFF
            val temp = remainder * base + digit
            number[i] = (temp / divisor).toByte()
            remainder = temp % divisor
        }
        return remainder
    }
}
