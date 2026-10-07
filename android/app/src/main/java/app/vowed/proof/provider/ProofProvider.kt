package app.vowed.proof.provider

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * Open proof plug-ins (SPEC F9). A [ProofProvider] is anything that can say, with a signature, "this person did this much of this thing in
 * this window". Vowed hands the statement to its backend, which checks it (docs/proof-provider-spec.md). Only [SampleFocusProvider] ships
 * inside the app, to prove the interface; no third-party app is integrated, and none is claimed.
 */
interface ProofProvider {
    /** Stable id, lower case, for example "sample.focus". */
    val id: String

    /** Id of the signing key this provider currently uses; the public half must be registered with the backend. */
    val keyId: String

    /** A signed statement about [wallet] for the window [windowStart]..[windowEnd] (unix seconds). */
    fun attest(wallet: String, metric: String, value: Long, unit: String, windowStart: Long, windowEnd: Long, nonce: String, issuedAt: Long): SignedAttestation
}

/** The fields of an attestation (everything the signature covers). */
data class AttestationFields(
    val provider: String,
    val keyId: String,
    val wallet: String,
    val metric: String,
    val value: Long,
    val unit: String,
    val windowStart: Long,
    val windowEnd: Long,
    val nonce: String,
    val issuedAt: Long,
    val alg: String = "ES256",
)

data class SignedAttestation(val fields: AttestationFields, val signatureBase64: String) {
    /** The JSON body that is sent to POST /v1/attestations. */
    fun toJson(): String = buildString {
        append('{')
        append("\"v\":1,")
        append("\"provider\":${AttestationFormat.q(fields.provider)},")
        append("\"keyId\":${AttestationFormat.q(fields.keyId)},")
        append("\"wallet\":${AttestationFormat.q(fields.wallet)},")
        append("\"metric\":${AttestationFormat.q(fields.metric)},")
        append("\"value\":${fields.value},")
        append("\"unit\":${AttestationFormat.q(fields.unit)},")
        append("\"windowStart\":${fields.windowStart},")
        append("\"windowEnd\":${fields.windowEnd},")
        append("\"nonce\":${AttestationFormat.q(fields.nonce)},")
        append("\"issuedAt\":${fields.issuedAt},")
        append("\"alg\":${AttestationFormat.q(fields.alg)},")
        append("\"signature\":${AttestationFormat.q(signatureBase64)}")
        append('}')
    }
}

object AttestationFormat {
    const val PREFIX = "VOWED-ATTESTATION-v1\n"

    fun q(s: String): String = buildString {
        append('"')
        for (ch in s) when {
            ch == '"' -> append("\\\"")
            ch == '\\' -> append("\\\\")
            ch.code < 0x20 -> append("\\u%04x".format(ch.code))
            else -> append(ch)
        }
        append('"')
    }

    /**
     * The exact bytes that are signed: the prefix, then the JSON of every field except the signature with keys in alphabetical order and no
     * spaces. The backend builds the same bytes (JSON.stringify of the sorted object); the two are pinned together by
     * shared/test-vectors/attestation.json. In version 1 [AttestationFields.value] is a whole number, so both sides print it the same way.
     */
    fun canonicalBytes(f: AttestationFields): ByteArray {
        val body = "{" + listOf(
            "alg" to q(f.alg),
            "issuedAt" to f.issuedAt.toString(),
            "keyId" to q(f.keyId),
            "metric" to q(f.metric),
            "nonce" to q(f.nonce),
            "provider" to q(f.provider),
            "unit" to q(f.unit),
            "v" to "1",
            "value" to f.value.toString(),
            "wallet" to q(f.wallet),
            "windowEnd" to f.windowEnd.toString(),
            "windowStart" to f.windowStart.toString(),
        ).joinToString(",") { (k, v) -> "\"$k\":$v" } + "}"
        return (PREFIX + body).toByteArray(Charsets.UTF_8)
    }
}

/** Signs bytes with a P-256 key (SHA-256 with ECDSA, DER encoded). */
fun interface AttestationSigner {
    fun sign(bytes: ByteArray): ByteArray
}

/**
 * The sample provider: pretends to be a small "focus timer" app and signs a statement about focused seconds. Its key is created on this
 * phone in the Android Keystore the first time it is used and never leaves it; the public half is registered with the backend only on a
 * devnet server that has the sample provider switched on. There is no signing key anywhere in the repository or the APK.
 */
class SampleFocusProvider(private val signer: AttestationSigner, override val keyId: String = DEFAULT_KEY_ID) : ProofProvider {
    override val id = ID

    override fun attest(wallet: String, metric: String, value: Long, unit: String, windowStart: Long, windowEnd: Long, nonce: String, issuedAt: Long): SignedAttestation {
        require(value >= 0) { "a measured value cannot be negative" }
        val fields = AttestationFields(id, keyId, wallet, metric, value, unit, windowStart, windowEnd, nonce, issuedAt)
        return SignedAttestation(fields, Base64.getEncoder().encodeToString(signer.sign(AttestationFormat.canonicalBytes(fields))))
    }

    companion object {
        const val ID = "sample.focus"
        const val DEFAULT_KEY_ID = "k1"
        private const val ALIAS = "vowed-sample-provider-v1"

        /** Signer backed by the Android Keystore (creates the key on first use). */
        fun keystoreSigner(): AttestationSigner {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (!ks.containsAlias(ALIAS)) {
                val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
                gen.initialize(
                    KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .build(),
                )
                gen.generateKeyPair()
            }
            val key = ks.getKey(ALIAS, null) as PrivateKey
            return AttestationSigner { bytes -> Signature.getInstance("SHA256withECDSA").apply { initSign(key); update(bytes) }.sign() }
        }

        /** The public key (SPKI DER, base64) to register with the backend. */
        fun keystorePublicKeyBase64(): String {
            keystoreSigner() // makes sure the key exists
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            return Base64.getEncoder().encodeToString(ks.getCertificate(ALIAS).publicKey.encoded)
        }
    }
}
