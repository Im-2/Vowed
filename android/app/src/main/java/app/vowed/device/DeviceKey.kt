package app.vowed.device

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import app.vowed.core.sha256
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * The phone's proof key: an ECDSA P-256 key created inside the Android Keystore (StrongBox when available) and never exported.
 * The stake commits to the SHA-256 of its public key, and every daily proof is signed with it (Phase 4). The Keystore certificate chain
 * is sent to the backend so it can verify, with Google's roots, that the key really lives in hardware. One key per wallet.
 */
class DeviceKey(private val context: Context, wallet: String) {
    private val alias = "vowed_proof_${wallet.take(12)}"
    private val ks: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    val exists: Boolean get() = ks.containsAlias(alias)

    fun delete() {
        if (exists) ks.deleteEntry(alias)
    }

    /**
     * Creates the key bound to the server's attestation [challenge]. Returns true when the key was created with attestation;
     * false when the device cannot attest (the key is then created without a challenge and the backend caps its trust).
     */
    fun generate(challenge: ByteArray?): Boolean {
        delete()
        if (challenge != null) {
            val attempts = if (hasStrongBox()) listOf(true, false) else listOf(false)
            for (strongBox in attempts) {
                try {
                    create(challenge, strongBox)
                    return true
                } catch (e: StrongBoxUnavailableException) {
                    delete()
                } catch (e: Exception) {
                    delete()
                    break // this device cannot attest: fall through to a plain key
                }
            }
        }
        create(null, false)
        return false
    }

    private fun create(challenge: ByteArray?, strongBox: Boolean) {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .apply {
                if (challenge != null) setAttestationChallenge(challenge)
                if (strongBox && Build.VERSION.SDK_INT >= 28) setIsStrongBoxBacked(true)
            }
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply { initialize(spec) }.generateKeyPair()
    }

    private fun hasStrongBox(): Boolean = Build.VERSION.SDK_INT >= 28 && context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)

    /** SubjectPublicKeyInfo DER of the public key (what the backend registers and hashes). */
    fun publicKeySpki(): ByteArray = ks.getCertificate(alias).publicKey.encoded

    /** Hex SHA-256 of the SPKI: the device id the stake is bound to. */
    fun id(): String = sha256(publicKeySpki()).joinToString("") { "%02x".format(it) }

    /** Keystore certificate chain, leaf first, DER. Only meaningful when the key was created with an attestation challenge. */
    fun certificateChain(): List<ByteArray> = ks.getCertificateChain(alias)?.map { it.encoded } ?: emptyList()

    /** ECDSA/SHA-256 signature (DER) over [data]. Used for proof packages (Phase 4). */
    fun sign(data: ByteArray): ByteArray {
        val key = ks.getKey(alias, null) as PrivateKey
        return Signature.getInstance("SHA256withECDSA").apply { initSign(key); update(data) }.sign()
    }
}
