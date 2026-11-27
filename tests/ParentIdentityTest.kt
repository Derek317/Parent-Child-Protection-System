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
import java.security.KeyFactory
import java.security.KeyStore
import java.security.Signature
import java.security.cert.X509Certificate
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ParentIdentityTest {
    private val ctx: Application get() = RuntimeEnvironment.getApplication()
    private val decoder = Base64.getUrlDecoder()
    private val now = 1_800_000_000_000L
    private val scope = "ig_hk_aaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun load(legacy: String? = null, expected: String? = null) = FamilySecurity.loadOrCreateParentIdentity(ctx, legacy, expected)
    private fun delete() = FamilySecurity.deleteParentIdentity(ctx)
    private fun fresh() = FamilySecurity.stageFreshParentIdentity(ctx).also { FamilySecurity.activateParentIdentity(ctx, it) }
    private fun sign(bytes: ByteArray, reference: String) = FamilySecurity.signBytes(bytes, reference)
    private fun rejects(action: () -> Unit) {
        try { action(); fail("Unavailable or untrusted identity must fail closed") } catch (_: SecurityException) { }
    }
    private fun verifies(publicKey: String, bytes: ByteArray, signature: ByteArray): Boolean = Signature.getInstance("SHA256withECDSA", "SunEC").run {
        initVerify(KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(decoder.decode(publicKey))))
        update(bytes)
        verify(signature)
    }
    @Before fun resetIdentityStorage() {
        TestKeyStore.privateKeys.clear()
        TestKeyStore.certificates.clear()
        TestKeyStore.failEcGeneration = false
        TestKeyStore.failEcImport = false
        TestKeyStore.failEcSigning = false
        TestKeyStore.failReads = false
        ctx.noBackupFilesDir.listFiles()?.filter { it.name.startsWith("ig_guard.parent_signing.") }?.forEach { it.delete() }
    }

    @Test fun freshIdentityIsNonExportableStableAndCanSignInBackground() {
        val identity = load()
        val privateKey = store().aliases().toList().mapNotNull { store().getKey(it, null) }.filterIsInstance<java.security.PrivateKey>().single()
        assertNull(privateKey.encoded)
        assertFalse(TestKeyStore.ecAuthenticationRequired)
        assertTrue(identity.privateKey.startsWith("android-keystore:"))
        assertTrue(verifies(identity.publicKey, "background".toByteArray(), sign("background".toByteArray(), identity.privateKey)))
        assertEquals(identity, load(expected = identity.publicKey))
        assertEquals(identity, load())
    }

    @Test fun keystoreHandleSignsCommandsAndIndependentRelayTranscript() {
        val identity = load()
        val command = JSONObject().put("type", "sync_trusted").put("scope", scope)
            .put("command_id", UUID.randomUUID().toString()).put("ts_ms", now).put("trusted_csv", "friend_one")
        val envelope = FamilySecurity.signCommand(command, identity.privateKey)
        assertEquals("friend_one", FamilySecurity.readCommand(envelope, identity.publicKey, scope, now)!!.getString("trusted_csv"))
        val header = FamilyRelay.parentAuthorization(identity, scope, "synthetic-token-928", now, "identity-test-nonce")
        val auth = JSONObject(String(decoder.decode(header.removePrefix("IGGuard ")), Charsets.UTF_8))
        val transcript = listOf("igguard-relay-auth-v1", "parent", auth.getString("room"), scope, now.toString(), "identity-test-nonce", auth.getString("capability_hash")).joinToString("\n")
        assertTrue(verifies(identity.publicKey, transcript.toByteArray(), decoder.decode(auth.getString("signature"))))
        assertFalse(header.contains(identity.privateKey))
    }

    @Test fun importPreservesExactPinnedPublicKeyAndExistingCommandTrust() {
        val legacy = FamilySecurity.newSigningIdentity()
        val expected = legacy.publicKey.trimEnd('=')
        val oldSignature = sign("before migration".toByteArray(), legacy.privateKey)
        val migrated = load(legacy.privateKey, expected)
        assertEquals(expected, migrated.publicKey)
        assertNotEquals(legacy.privateKey, migrated.privateKey)
        assertTrue(verifies(expected, "before migration".toByteArray(), oldSignature))
        assertTrue(verifies(expected, "after migration".toByteArray(), sign("after migration".toByteArray(), migrated.privateKey)))
        val alias = TestKeyStore.privateKeys.keys.single()
        val certificate = store().getCertificate(alias) as X509Certificate
        certificate.checkValidity()
        certificate.verify(certificate.publicKey)
        assertArrayEquals(decoder.decode(expected), certificate.publicKey.encoded)
        assertNull(store().getKey(alias, null).encoded)
        assertFalse(TestKeyStore.ecAuthenticationRequired)
        assertEquals(migrated, load(expected = expected))
    }

    @Test fun mismatchedLegacyPrivateKeyIsRejectedWithoutCreatingIdentity() {
        val legacy = FamilySecurity.newSigningIdentity()
        rejects { load(legacy.privateKey, FamilySecurity.newSigningIdentity().publicKey) }
        assertTrue(TestKeyStore.privateKeys.isEmpty())
        assertEquals(legacy.publicKey, load(legacy.privateKey, legacy.publicKey).publicKey)
    }

    @Test fun malformedLegacyOrPublicKeyCannotCreateAReplacement() {
        val legacy = FamilySecurity.newSigningIdentity()
        rejects { load("invalid-pkcs8", legacy.publicKey) }
        rejects { load(legacy.privateKey, "invalid-public-key") }
        rejects { load(legacy.privateKey) }
        assertTrue(TestKeyStore.privateKeys.isEmpty())
    }

    @Test fun existingPublicKeyWithoutPrivateKeyNeverGenerates() {
        val expected = FamilySecurity.newSigningIdentity().publicKey
        rejects { load(expected = expected) }
        assertTrue(TestKeyStore.privateKeys.isEmpty())
    }

    @Test fun lostKeyFailsClosedEvenWhenCallerLosesPublicPreference() {
        val identity = load()
        store().deleteEntry(TestKeyStore.privateKeys.keys.single())
        rejects { load(expected = identity.publicKey) }
        rejects { load() }
        rejects { load(identity.privateKey, identity.publicKey) }
        rejects { sign("must not sign".toByteArray(), identity.privateKey) }
        assertTrue(TestKeyStore.privateKeys.isEmpty())
    }

    @Test fun unavailableKeystoreNeverChangesExistingIdentity() {
        val identity = load()
        val before = TestKeyStore.privateKeys.toMap()
        TestKeyStore.failReads = true
        try {
            rejects { load(expected = identity.publicKey) }
            rejects { sign("background".toByteArray(), identity.privateKey) }
            rejects { delete() }
            assertEquals(before, TestKeyStore.privateKeys)
        } finally { TestKeyStore.failReads = false }
        assertEquals(identity, load(expected = identity.publicKey))
    }

    @Test fun existingAliasCannotOverridePinnedPublicKey() {
        val identity = load()
        rejects { load(expected = FamilySecurity.newSigningIdentity().publicKey) }
        assertEquals(identity, load(expected = identity.publicKey))
    }

    @Test fun certificateAndPrivateKeyMismatchIsDetectedOnLoad() {
        val identity = load()
        val alias = TestKeyStore.privateKeys.keys.single()
        val original = TestKeyStore.privateKeys.getValue(alias)
        val attacker = FamilySecurity.newSigningIdentity()
        val wrongKey = TestEcPrivateKey(KeyFactory.getInstance("EC").generatePrivate(java.security.spec.PKCS8EncodedKeySpec(decoder.decode(attacker.privateKey))))
        TestKeyStore.privateKeys[alias] = wrongKey
        rejects { load(expected = identity.publicKey) }
        assertSame(wrongKey, TestKeyStore.privateKeys[alias])
        TestKeyStore.privateKeys[alias] = original
        assertEquals(identity, load(expected = identity.publicKey))
    }

    @Test fun generationFailureDoesNotFallBackToSoftwareOrRetryWithNewKey() {
        TestKeyStore.failEcGeneration = true
        rejects { load() }
        assertTrue(TestKeyStore.privateKeys.isEmpty())
        TestKeyStore.failEcGeneration = false
        rejects { load() }
        assertTrue(TestKeyStore.privateKeys.isEmpty())
    }

    @Test fun verifiedLegacyIdentityMatchesOnlyThePinnedPublicKey() {
        val legacy = FamilySecurity.newSigningIdentity()
        assertEquals(legacy, FamilySecurity.verifiedLegacyIdentity(legacy.privateKey, legacy.publicKey))
        rejects { FamilySecurity.verifiedLegacyIdentity(legacy.privateKey, FamilySecurity.newSigningIdentity().publicKey) }
    }

    /** Storage-layer contract: the import itself still fails closed; GuardEngine.parentIdentity falls back to the verified
     * legacy key (see ParentKeyFallbackTest) so reception is never blocked by this failure. */
    @Test fun importFailureRetainsLegacyTrustForRetry() {
        val legacy = FamilySecurity.newSigningIdentity()
        TestKeyStore.failEcImport = true
        rejects { load(legacy.privateKey, legacy.publicKey) }
        assertTrue(TestKeyStore.privateKeys.isEmpty())
        TestKeyStore.failEcImport = false
        rejects { load(expected = legacy.publicKey) }
        assertEquals(legacy.publicKey, load(legacy.privateKey, legacy.publicKey).publicKey)
    }

    @Test fun explicitDeletionInvalidatesHandleAndLeavesStorageKeysIntact() {
        LocalVault.initializeArchiveKeys(ctx)
        val storageKeys = TestKeyStore.keys.toMap()
        val identity = load()
        delete()
        rejects { sign("deleted".toByteArray(), identity.privateKey) }
        assertEquals(storageKeys, TestKeyStore.keys)
        rejects { load(expected = identity.publicKey) }
        assertNotEquals(identity.publicKey, load().publicKey)
    }

    @Test fun concurrentFirstLoadReturnsOneIdentity() {
        val pool = Executors.newFixedThreadPool(4)
        try {
            val identities = pool.invokeAll(List(4) { Callable { load() } }).map { it.get() }
            assertEquals(1, identities.toSet().size)
        } finally { pool.shutdownNow() }
    }

    @Test fun forgedKeystoreReferenceCannotSelectAnotherAliasOrSoftwareKey() {
        val identity = load()
        rejects { sign(byteArrayOf(1), identity.privateKey + ".other") }
        rejects { sign(byteArrayOf(1), "android-keystore:some.other.alias") }
    }

    @Test fun freshRecoverySwitchesActiveIdentityAfterVerificationAndRetainsOldKey() {
        val old = load()
        val fresh = fresh()
        assertNotEquals(old.publicKey, fresh.publicKey)
        assertNotEquals(old.privateKey, fresh.privateKey)
        assertEquals(fresh, load(expected = fresh.publicKey))
        assertEquals(fresh, load())
        rejects { load(expected = old.publicKey) }
        assertTrue(verifies(old.publicKey, byteArrayOf(1), sign(byteArrayOf(1), old.privateKey)))
        assertTrue(verifies(fresh.publicKey, byteArrayOf(2), sign(byteArrayOf(2), fresh.privateKey)))
    }

    @Test fun failedFreshGenerationPreservesWorkingOldIdentity() {
        val old = load()
        val before = TestKeyStore.privateKeys.toMap()
        TestKeyStore.failEcGeneration = true
        try { rejects { fresh() } }
        finally { TestKeyStore.failEcGeneration = false }
        assertEquals(before, TestKeyStore.privateKeys)
        assertEquals(old, load(expected = old.publicKey))
        assertTrue(verifies(old.publicKey, byteArrayOf(1), sign(byteArrayOf(1), old.privateKey)))
    }

    @Test fun failedFreshVerificationPreservesOldActiveRecordAndRemovesStagedKey() {
        val old = load()
        val before = TestKeyStore.privateKeys.toMap()
        TestKeyStore.failEcSigning = true
        try { rejects { fresh() } }
        finally { TestKeyStore.failEcSigning = false }
        assertEquals(before, TestKeyStore.privateKeys)
        assertEquals(old, load(expected = old.publicKey))
    }

    @Test fun missingActiveRecoveryKeyNeverFallsBackToRetiredIdentity() {
        val old = load()
        val fresh = fresh()
        store().deleteEntry(fresh.privateKey.removePrefix("android-keystore:"))
        rejects { load(expected = fresh.publicKey) }
        rejects { load() }
        assertTrue(verifies(old.publicKey, byteArrayOf(1), sign(byteArrayOf(1), old.privateKey)))
    }

    @Test fun explicitDeletionRemovesActiveAndRetiredParentIdentities() {
        val old = load()
        val fresh = fresh()
        delete()
        rejects { sign(byteArrayOf(1), old.privateKey) }
        rejects { sign(byteArrayOf(1), fresh.privateKey) }
        assertTrue(TestKeyStore.privateKeys.isEmpty())
    }
}
