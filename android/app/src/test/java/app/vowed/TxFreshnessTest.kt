package app.vowed

import app.vowed.data.TxFreshness
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A re-issued transaction may differ from the reviewed one in the blockhash only. */
class TxFreshnessTest {
    /** an unsigned legacy transaction: 1 signature slot, header, 3 accounts, blockhash, 1 instruction */
    private fun tx(blockhash: Byte, data: Byte = 7, payer: Byte = 1): ByteArray {
        val sigs = byteArrayOf(1) + ByteArray(64)
        val header = byteArrayOf(1, 0, 1)
        val accounts = byteArrayOf(3) + ByteArray(32) { payer } + ByteArray(32) { 2 } + ByteArray(32) { 3 }
        val hash = ByteArray(32) { blockhash }
        val ix = byteArrayOf(1, 2, 2, 0, 1, 2, data, data)
        return sigs + header + accounts + hash + ix
    }

    @Test fun onlyTheBlockhashMayDiffer() {
        assertTrue(TxFreshness.sameExceptBlockhash(tx(10), tx(11)))
        assertTrue(TxFreshness.sameExceptBlockhash(tx(10), tx(10)))
    }

    @Test fun anotherInstructionOrAccountIsNotAccepted() {
        assertFalse("different instruction data", TxFreshness.sameExceptBlockhash(tx(10, data = 7), tx(11, data = 8)))
        assertFalse("different fee payer", TxFreshness.sameExceptBlockhash(tx(10, payer = 1), tx(11, payer = 9)))
    }

    @Test fun garbageIsNotAccepted() {
        assertFalse(TxFreshness.sameExceptBlockhash(ByteArray(5), ByteArray(5)))
        assertFalse(TxFreshness.sameExceptBlockhash(tx(1), tx(1).copyOf(40)))
    }
}
