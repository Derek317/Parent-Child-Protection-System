package app.family.igguard

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class FamilyRelayTest {
    private val vector = JSONObject(File(requireNotNull(System.getProperty("igguard.protocol.vector"))).readText())

    @Test fun roomAndCapabilityMatchIndependentWebCryptoVector() {
        assertEquals(vector.getString("room"), FamilyRelay.room(vector.getString("publicKey"),vector.getString("scope")))
        assertEquals(vector.getString("capability"), FamilyRelay.childCapability(vector.getString("token")))
        assertEquals(vector.getString("capability_hash"), FamilyRelay.capabilityHash(vector.getString("capability")))
        assertNotEquals(vector.getString("room"),FamilyRelay.room(vector.getString("publicKey"),"ig_hk_aaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
    }

    @Test fun parentAuthorizationContainsVerifiedDomainSeparatedRoutingProof() {
        val identity=FamilySecurity.SigningIdentity(vector.getString("privateKey"),vector.getString("publicKey"))
        val header=FamilyRelay.parentAuthorization(identity,vector.getString("scope"),vector.getString("token"),1_800_000_000_000L,"12345678-1234-4234-9234-123456789abc")
        val auth=JSONObject(String(java.util.Base64.getUrlDecoder().decode(header.removePrefix("IGGuard "))))
        assertEquals(vector.getString("room"),auth.getString("room"))
        assertEquals(vector.getString("capability_hash"),auth.getString("capability_hash"))
        val key=java.security.KeyFactory.getInstance("EC").generatePublic(java.security.spec.X509EncodedKeySpec(java.util.Base64.getUrlDecoder().decode(identity.publicKey)))
        val signature=java.security.Signature.getInstance("SHA256withECDSA").apply{initVerify(key);update(vector.getString("transcript").toByteArray())}
        assertTrue(signature.verify(java.util.Base64.getUrlDecoder().decode(auth.getString("signature"))))
        assertFalse(header.contains(vector.getString("token")))
    }

    @Test fun endpointRejectsCredentialsQueriesCleartextAndNonPublicReleaseHosts() {
        assertEquals("wss://ig-guard.example.workers.dev/v1/rooms/"+"a".repeat(64),FamilyRelay.socketUrl("https://ig-guard.example.workers.dev","a".repeat(64)))
        for(endpoint in listOf("", "http://example.com", "https://user:pass@example.com", "https://example.com/?token=x", "https://example.com/path", "https://localhost", "https://127.0.0.1")) {
            try { FamilyRelay.socketUrl(endpoint,"a".repeat(64));fail("Unsafe endpoint accepted") }catch(_:IllegalArgumentException){}
        }
    }
}
