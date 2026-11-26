package app.family.igguard

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** v2 relay authorizations against relay/test/protocol-vector.json (WebCrypto side). ECDSA is randomized: signatures are verified, never compared. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RelayAuthV2Test {
    private val vector = JSONObject(File(requireNotNull(System.getProperty("igguard.protocol.vector"))).readText())
    private val now = 1_800_000_000_000L
    private val decoder = Base64.getUrlDecoder()
    // Exact field sets and order of relay/src/protocol.js `shapes`.
    private val parentShape = listOf("v","role","room","scope","public_key","capability_hash","child_key","ts_ms","nonce","signature")
    private val childShape = listOf("v","role","room","scope","capability","child_key","ts_ms","nonce","signature")
    private fun s(key: String) = vector.getString(key)
    private fun decodeHeader(header: String): JSONObject {
        assertTrue(header.startsWith("IGGuard ") && header.length <= 2048)
        val body = header.removePrefix("IGGuard ")
        assertTrue(body.matches(Regex("[A-Za-z0-9_-]+")))
        return JSONObject(String(decoder.decode(body), Charsets.UTF_8))
    }
    private fun verifies(publicKey: String, text: String, signature: String) = Signature.getInstance("SHA256withECDSA", "SunEC").run {
        initVerify(KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(decoder.decode(publicKey)))); update(text.toByteArray(Charsets.UTF_8))
        verify(decoder.decode(signature))
    }
    /** Mirrors protocol.js rawSignature's outer checks: canonical unpadded base64url of a DER SEQUENCE of at most 72 bytes. */
    private fun assertRelayDer(signature: String) {
        assertTrue(signature.matches(Regex("[A-Za-z0-9_-]+")))
        val der = decoder.decode(signature)
        assertTrue(der.size in 8..72 && der[0] == 0x30.toByte() && der[1].toInt() == der.size - 2 && der[2] == 2.toByte())
        assertEquals(signature, Base64.getUrlEncoder().withoutPadding().encodeToString(der))
    }
    private fun childSigner(bytes: ByteArray) = Signature.getInstance("SHA256withECDSA", "SunEC").run {
        initSign(KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(decoder.decode(s("childPrivateKey"))))); update(bytes); sign()
    }
    private fun assertMatchesVector(auth: JSONObject, expected: JSONObject, shape: List<String>) {
        assertEquals(shape, auth.keys().asSequence().toList())
        for (field in shape.filter { it != "signature" }) assertEquals(field, expected.get(field), auth.get(field))
    }

    @Before fun reset() {
        TestKeyStore.privateKeys.clear(); TestKeyStore.certificates.clear(); TestKeyStore.strongBoxRequests.clear()
        TestKeyStore.failStrongBox = false; TestKeyStore.failEcGeneration = false; TestKeyStore.failEcSigning = false; TestKeyStore.failReads = false
    }

    @Test fun vectorsOwnV2SignaturesVerifyInKotlin() {
        val parent = vector.getJSONObject("auth_v2_parent"); val child = vector.getJSONObject("auth_v2_child")
        assertTrue(verifies(s("publicKey"), s("transcript_v2_parent"), parent.getString("signature")))
        assertTrue(verifies(s("childPublicKey"), s("transcript_v2_child"), child.getString("signature")))
        assertFalse(verifies(s("childPublicKey"), s("transcript_v2_parent"), parent.getString("signature")))
        // Reverse direction: the WebCrypto child auth is what our builder derives from the same inputs.
        assertEquals(childShape.toSet(), child.keys().asSequence().toSet())
        assertEquals(FamilyRelay.room(s("publicKey"), s("scope")), child.getString("room"))
        assertEquals(FamilyRelay.childCapability(s("token")), child.getString("capability"))
        assertEquals(s("capability_hash"), FamilyRelay.capabilityHash(child.getString("capability")))
        assertTrue(s("transcript_v2_child").endsWith("\n" + FamilyRelay.capabilityHash(child.getString("capability"))))
        assertRelayDer(parent.getString("signature")); assertRelayDer(child.getString("signature"))
    }

    @Test fun parentV2MatchesVectorFieldsAndSignsItsTranscript() {
        val identity = FamilySecurity.SigningIdentity(s("privateKey"), s("publicKey"))
        val header = FamilyRelay.parentAuthorizationV2(identity, s("scope"), s("token"), s("childPublicKey"), now, "12345678-1234-4234-9234-123456789abd")
        println("parent v2 header: $header")
        val auth = decodeHeader(header)
        assertMatchesVector(auth, vector.getJSONObject("auth_v2_parent"), parentShape)
        assertRelayDer(auth.getString("signature"))
        assertTrue(verifies(s("publicKey"), s("transcript_v2_parent"), auth.getString("signature")))
        assertFalse(header.contains(s("token")))
    }

    @Test fun childV2MatchesVectorFieldsAndSignsItsTranscript() {
        val child = FamilySecurity.ChildSigningKey("vector-only", s("childPublicKey"))
        val header = FamilyRelay.childAuthorizationV2(s("publicKey"), s("scope"), s("token"), child, now, "12345678-1234-4234-9234-123456789abe", ::childSigner)
        println("child v2 header: $header")
        val auth = decodeHeader(header)
        assertMatchesVector(auth, vector.getJSONObject("auth_v2_child"), childShape)
        assertRelayDer(auth.getString("signature"))
        assertTrue(verifies(s("childPublicKey"), s("transcript_v2_child"), auth.getString("signature")))
        assertFalse(header.contains(s("token")))
    }

    @Test fun childV2SignsWithTheKeystoreChildKeyByDefault() {
        val ctx: Application = RuntimeEnvironment.getApplication()
        val key = FamilySecurity.createChildSigningKey(ctx)
        try {
            val auth = decodeHeader(FamilyRelay.childAuthorizationV2(s("publicKey"), s("scope"), s("token"), key, now, "12345678-1234-4234-9234-123456789abe"))
            assertEquals(key.publicKey.trimEnd('='), auth.getString("child_key"))
            val transcript = s("transcript_v2_child")
            assertTrue(verifies(auth.getString("child_key"), transcript, auth.getString("signature")))
            assertFalse(verifies(s("childPublicKey"), transcript, auth.getString("signature")))
        } finally { FamilySecurity.deleteChildSigningKey(ctx, key.alias) }
    }

    @Test fun childKeyIsSentUnpaddedAndParentRefusesNonP256Keys() {
        val identity = FamilySecurity.SigningIdentity(s("privateKey"), s("publicKey"))
        val padded = s("childPublicKey") + "=="
        val auth = decodeHeader(FamilyRelay.parentAuthorizationV2(identity, s("scope"), s("token"), padded, now, "12345678-1234-4234-9234-123456789abd"))
        assertEquals(s("childPublicKey"), auth.getString("child_key"))
        assertTrue(verifies(s("publicKey"), s("transcript_v2_parent"), auth.getString("signature")))
        for (bad in listOf("", "not-a-key", s("childPublicKey").dropLast(4), s("childPrivateKey"))) {
            try { FamilyRelay.parentAuthorizationV2(identity, s("scope"), s("token"), bad, now); fail("accepted $bad") } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun readyFeatureDetection() {
        val cases = mapOf(
            """{"type":"ready","protocol":1,"role":"child","peer_online":false,"features":["child_proof"]}""" to true,
            """{"type":"ready","protocol":1,"features":["other","child_proof"]}""" to true,
            """{"type":"ready","protocol":1,"role":"child","peer_online":false}""" to false,
            """{"type":"ready","features":"child_proof"}""" to false,
            """{"type":"ready","features":{"child_proof":true}}""" to false,
            """{"type":"ready","features":[]}""" to false,
            """{"type":"ready","features":null}""" to false,
            """{"type":"ready","features":["CHILD_PROOF"]}""" to false,
            """{"type":"ready","features":[["child_proof"]]}""" to false,
            """{"type":"peer","features":["child_proof"]}""" to false,
            """{"features":["child_proof"]}""" to false,
        )
        for ((text, expected) in cases) assertEquals(text, expected, FamilyRelay.supportsChildProof(JSONObject(text)))
    }
}
