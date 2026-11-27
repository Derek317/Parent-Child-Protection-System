package app.family.igguard

import android.app.Application
import android.content.pm.PackageManager
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.security.KeyFactory
import java.security.KeyStore
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID

/** The child's Keystore key through the test provider (real EC maths, no hardware isolation; see VaultTestApplication). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ChildSigningKeyTest {
    private val ctx: Application get() = RuntimeEnvironment.getApplication()
    private val now = 1_800_000_000_000L
    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun verifies(publicKey: String, bytes: ByteArray, signature: ByteArray) = Signature.getInstance("SHA256withECDSA", "SunEC").run {
        initVerify(KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getUrlDecoder().decode(publicKey)))); update(bytes); verify(signature)
    }
    private fun rejects(action: () -> Unit) { try { action(); fail("must fail closed") } catch (_: SecurityException) { } }

    @Before fun reset() {
        TestKeyStore.privateKeys.clear(); TestKeyStore.certificates.clear(); TestKeyStore.strongBoxRequests.clear()
        TestKeyStore.failStrongBox = false; TestKeyStore.failEcGeneration = false; TestKeyStore.failEcSigning = false; TestKeyStore.failReads = false
        ctx.noBackupFilesDir.listFiles()?.filter { it.name.startsWith("ig_guard.child_signing.") }?.forEach { it.delete() }
    }

    @Test fun createLoadSignAndDelete() {
        val key = FamilySecurity.createChildSigningKey(ctx)
        assertTrue(key.alias.matches(Regex("ig_guard\\.child_signing\\.ec\\.v1\\.[0-9a-f-]{36}")))
        assertEquals(key.alias.removePrefix("ig_guard.child_signing.ec.v1."), UUID.fromString(key.alias.substringAfterLast(".v1.")).toString())
        assertTrue(PairingCrypto.validP256Key(key.publicKey) && FamilySecurity.validPublicKey(key.publicKey))
        assertNull((store().getKey(key.alias, null) as java.security.PrivateKey).encoded)
        assertFalse(TestKeyStore.ecAuthenticationRequired)
        assertEquals(key, FamilySecurity.loadChildSigningKey(ctx, key.alias))
        assertTrue(verifies(key.publicKey, "bytes".toByteArray(), FamilySecurity.signChildBytes("bytes".toByteArray(), key.alias)))

        val second = FamilySecurity.createChildSigningKey(ctx)
        assertNotEquals(key.alias, second.alias)
        assertNotEquals(key.publicKey, second.publicKey)
        FamilySecurity.deleteChildSigningKey(ctx, key.alias)
        assertFalse(store().containsAlias(key.alias))
        FamilySecurity.deleteChildSigningKey(ctx, key.alias)                     // deleting again is harmless
        rejects { FamilySecurity.loadChildSigningKey(ctx, key.alias) }
        rejects { FamilySecurity.signChildBytes("x".toByteArray(), key.alias) }
        assertEquals(second, FamilySecurity.loadChildSigningKey(ctx, second.alias))
        for (bad in listOf("ig_guard.parent_signing.ec.v1", "ig_guard.child_signing.ec.v1", "ig_guard.child_signing.ec.v1.not-a-uuid"))
            rejects { FamilySecurity.signChildBytes("x".toByteArray(), bad) }
    }

    @Test fun aLostOrSwappedKeyIsNeverReplacedSilently() {
        val key = FamilySecurity.createChildSigningKey(ctx)
        val other = FamilySecurity.createChildSigningKey(ctx)
        TestKeyStore.privateKeys[key.alias] = TestKeyStore.privateKeys.getValue(other.alias)
        TestKeyStore.certificates[key.alias] = TestKeyStore.certificates.getValue(other.alias)
        rejects { FamilySecurity.loadChildSigningKey(ctx, key.alias) }       // public key no longer the one recorded at creation
        TestKeyStore.privateKeys.remove(key.alias)
        rejects { FamilySecurity.loadChildSigningKey(ctx, key.alias) }       // missing key
        rejects { FamilySecurity.loadChildSigningKey(ctx, "ig_guard.child_signing.ec.v1.${UUID.randomUUID()}") } // never created here
        assertFalse(store().containsAlias(key.alias))                         // loading never generated a replacement
        assertEquals(other, FamilySecurity.loadChildSigningKey(ctx, other.alias))
    }

    @Test fun failedGenerationLeavesNothingBehind() {
        TestKeyStore.failEcGeneration = true
        rejects { FamilySecurity.createChildSigningKey(ctx) }
        assertTrue(store().aliases().toList().none { it.startsWith("ig_guard.child_signing.") })
        assertTrue(ctx.noBackupFilesDir.listFiles().orEmpty().none { it.name.startsWith("ig_guard.child_signing.") })
    }

    @Test fun strongBoxIsRequestedOnlyWhereThePhoneHasItAndFallsBack() {
        FamilySecurity.createChildSigningKey(ctx)
        assertEquals(listOf(false), TestKeyStore.strongBoxRequests.toList())
        shadowOf(ctx.packageManager).setSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE, true)
        TestKeyStore.strongBoxRequests.clear()
        FamilySecurity.createChildSigningKey(ctx)
        assertEquals(listOf(true), TestKeyStore.strongBoxRequests.toList())
        TestKeyStore.strongBoxRequests.clear(); TestKeyStore.failStrongBox = true
        val fallback = FamilySecurity.createChildSigningKey(ctx)
        assertEquals(listOf(true, false), TestKeyStore.strongBoxRequests.toList())
        assertEquals(fallback, FamilySecurity.loadChildSigningKey(ctx, fallback.alias))
    }

    @Config(sdk = [26])
    @Test fun worksOnApi26WithoutStrongBox() {
        val key = FamilySecurity.createChildSigningKey(ctx)
        assertEquals(listOf(false), TestKeyStore.strongBoxRequests.toList())
        assertTrue(verifies(key.publicKey, "api26".toByteArray(), FamilySecurity.signChildBytes("api26".toByteArray(), key.alias)))
    }

    @Test fun keystoreSignedRecordRoundTripsAndIsBoundToTheKey() {
        val parent = FamilySecurity.newSigningIdentity()
        val key = FamilySecurity.createChildSigningKey(ctx)
        val record = JSONObject().put("type", "alert").put("event_id", UUID.randomUUID().toString()).put("reason", "heartbeat_ok").put("ts_ms", now).toString()
        val wrapper = ChildRecords.wrapForParent(record, JSONObject(record).getString("event_id"), parent.publicKey, key.publicKey, { FamilySecurity.signChildBytes(it, key.alias) })
        assertEquals(record, wrapper.getString("child_signed"))
        val read = FamilySecurity.readChildRecord(wrapper, key.publicKey, parent.publicKey, now)!!
        assertEquals("alert", read.type); assertEquals(now, read.timeMs); assertEquals(JSONObject(record).getString("event_id"), read.id)
        val other = FamilySecurity.createChildSigningKey(ctx)
        assertNull(FamilySecurity.readChildRecord(wrapper, other.publicKey, parent.publicKey, now))
    }
}
