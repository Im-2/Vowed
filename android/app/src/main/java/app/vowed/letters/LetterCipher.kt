package app.vowed.letters

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts and authenticates a byte array. The stored form is a fresh 12-byte IV followed by the AES-256-GCM ciphertext and tag. */
class LetterCipher(private val key: () -> SecretKey) {
    private val random = SecureRandom()

    fun encrypt(plain: ByteArray): ByteArray {
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
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
        fun keystoreKey(): SecretKey {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
            val gen = javax.crypto.KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            gen.init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            return gen.generateKey()
        }
    }
}
