package app.vowed

import app.vowed.perks.FreezeOptions
import app.vowed.proof.provider.AttestationFormat
import app.vowed.proof.provider.AttestationSigner
import app.vowed.proof.provider.SampleFocusProvider
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderTest {
    private val vector: JsonObject = Json.parseToJsonElement(javaClass.classLoader!!.getResourceAsStream("attestation-vector.json")!!.reader().readText()).jsonObject
    private val att = vector["attestation"]!!.jsonObject
    private fun s(k: String) = att[k]!!.jsonPrimitive.content

    private fun fieldsFromVector() = app.vowed.proof.provider.AttestationFields(
        s("provider"), s("keyId"), s("wallet"), s("metric"), att["value"]!!.jsonPrimitive.long, s("unit"),
        att["windowStart"]!!.jsonPrimitive.long, att["windowEnd"]!!.jsonPrimitive.long, s("nonce"), att["issuedAt"]!!.jsonPrimitive.long, s("alg"),
    )

    private fun verify(spkiB64: String, bytes: ByteArray, sigB64: String): Boolean {
        val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(spkiB64)))
        return Signature.getInstance("SHA256withECDSA").apply { initVerify(pub); update(bytes) }.verify(Base64.getDecoder().decode(sigB64))
    }

    @Test fun phoneBuildsTheSameSignedBytesAsTheBackend() {
        assertEquals(vector["canonicalUtf8"]!!.jsonPrimitive.content, AttestationFormat.canonicalBytes(fieldsFromVector()).toString(Charsets.UTF_8))
    }

    @Test fun backendSignatureVerifiesOnThePhoneAndChangesAreCaught() {
        val spki = vector["publicKeySpkiBase64"]!!.jsonPrimitive.content
        assertTrue(verify(spki, AttestationFormat.canonicalBytes(fieldsFromVector()), s("signature")))
        assertFalse(verify(spki, AttestationFormat.canonicalBytes(fieldsFromVector().copy(value = 1501)), s("signature")))
    }

    @Test fun sampleProviderSignsWhatARegisteredKeyCanVerify() {
        val kp = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val signer = AttestationSigner { b -> Signature.getInstance("SHA256withECDSA").apply { initSign(kp.private); update(b) }.sign() }
        val provider = SampleFocusProvider(signer)
        val out = provider.attest("walletAddress1111111111111111111111111", "focus_seconds", 1500, "seconds", 1000, 2600, "AAECAwQFBgcICQoLDA0ODw==", 2605)
        val spki = Base64.getEncoder().encodeToString(kp.public.encoded)
        assertTrue(verify(spki, AttestationFormat.canonicalBytes(out.fields), out.signatureBase64))
        val body = Json.parseToJsonElement(out.toJson()).jsonObject
        assertEquals(1, body["v"]!!.jsonPrimitive.int)
        assertEquals("sample.focus", body["provider"]!!.jsonPrimitive.content)
        assertEquals(1500L, body["value"]!!.jsonPrimitive.long)
        assertEquals(13, body.size) // v + 11 fields + signature
    }

    @Test fun quotesAreEscapedInTheSignedBytes() {
        val tricky = "a" + '"' + "b" + '\\' + "c"
        val f = fieldsFromVector().copy(unit = tricky)
        val expected = "\"unit\":\"a" + '\\' + '"' + "b" + '\\' + '\\' + "c\""
        assertTrue(AttestationFormat.canonicalBytes(f).toString(Charsets.UTF_8).contains(expected))
    }

    @Test fun freezeRulesMatchTheServer() {
        assertEquals(3, FreezeOptions.LOOKBACK_DAYS)
        assertEquals(2, FreezeOptions.MAX_PER_POOL)
    }
}
