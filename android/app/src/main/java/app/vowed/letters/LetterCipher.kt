package app.vowed.letters

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts and authenticates a byte array. The stored form is a fresh 12-byte IV followed by the AES-256-GCM ciphertext and tag. */
class LetterCipher(private val key: () -> SecretKey) {
    fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        // Let the cipher pick the IV: the Android Keystore refuses an IV chosen by the caller ("Caller-provided IV not permitted"), and a
        // software key makes a fresh random one too.
        c.init(Cipher.ENCRYPT_MODE, key())
        val iv = c.iv
        check(iv.size == IV_BYTES) { "unexpected IV size" }
        return iv + c.doFinal(plain)
    }

    /** Throws when the data was changed or the key is not the one that encrypted it. */
    fun decrypt(stored: ByteArray): ByteArray {
        require(stored.size > IV_BYTES + TAG_BITS / 8) { "letter data is too short" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, stored.copyOfRange(0, IV_BYTES)))
        return c.doFinal(stored, IV_BYTES, stored.size - IV_BYTES)
    }

    companion object {
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        private const val ALIAS = "vowed-letters-v1"

        /** The key lives in the Android Keystore (hardware-backed where the phone has it) and never leaves it. */
        fun keystoreKey(alias: String = ALIAS): SecretKey {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getKey(alias, null) as? SecretKey)?.let { return it }
            val gen = javax.crypto.KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            gen.init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            return gen.generateKey()
        }
    }
}
