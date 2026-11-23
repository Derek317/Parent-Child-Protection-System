package app.family.igguard

import android.app.Application
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.security.KeyStore

/** Child signing keys left behind when the process died mid-pairing are removed at app start (GuardEngine.removeOrphanedChildKeys). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class OrphanChildKeyTest {
    private val ctx: Application get() = RuntimeEnvironment.getApplication()
    private val prefs get() = LocalVault.preferences(ctx)
    private val parentAlias = "ig_guard.parent_signing.ec.v1"
    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun markers(alias: String) = ctx.noBackupFilesDir.listFiles().orEmpty().filter { it.name.startsWith("$alias.initialized") }
    private fun gone(alias: String) = !store().containsAlias(alias) && markers(alias).isEmpty()
    private fun present(alias: String) = store().containsAlias(alias) && markers(alias).isNotEmpty()
    private fun parentPhone() { GuardEngine.setRole(ctx, "parent"); GuardEngine.setPin(ctx, QrPairing.PASSWORD); GuardEngine.relayPublicKey(ctx) }

    @Before fun reset() {
        prefs.edit().clear().commit(); ctx.deleteDatabase("family_evidence.db")
        TestKeyStore.privateKeys.clear(); TestKeyStore.certificates.clear()
        TestKeyStore.failEcGeneration = false; TestKeyStore.failEcSigning = false
        GuardEngine.forgetLivePairingsForTest() // machines other tests left mid-pairing in this JVM are not this test's
        ctx.noBackupFilesDir.listFiles()?.filter { it.name.startsWith("ig_guard.") }?.forEach { it.delete() }
    }

    @Test fun anOrphanKeyAndMarkerAreRemovedAtAppStartInEveryRole() {
        for (role in listOf("parent", "unconfigured")) {
            if (role == "parent") parentPhone()
            val parentKey = if (role == "parent") GuardEngine.relayPublicKey(ctx) else null
            val orphan = FamilySecurity.createChildSigningKey(ctx).alias
            assertTrue(present(orphan))
            val c = Robolectric.buildActivity(MainActivity::class.java).setup()
            try {
                assertTrue("$role: orphan removed at start", gone(orphan))
                if (parentKey != null) {
                    assertTrue("the parent identity survives", store().containsAlias(parentAlias) && File(ctx.noBackupFilesDir, "$parentAlias.initialized").exists())
                    assertEquals(parentKey, GuardEngine.relayPublicKey(ctx))
                }
            } finally { c.pause().stop().destroy() }
            prefs.edit().clear().commit()
        }
    }

    @Test fun aMarkerWithoutKeyAndAKeyWithoutMarkerAreRemoved() {
        parentPhone()
        val markerOnly = FamilySecurity.createChildSigningKey(ctx).alias
        TestKeyStore.privateKeys.remove(markerOnly); TestKeyStore.certificates.remove(markerOnly)
        val keyOnly = FamilySecurity.createChildSigningKey(ctx).alias
        markers(keyOnly).forEach { it.delete() }
        assertEquals(setOf(markerOnly, keyOnly), FamilySecurity.childSigningKeyAliases(ctx))
        GuardEngine.removeOrphanedChildKeys(ctx)
        assertTrue(gone(markerOnly)); assertTrue(gone(keyOnly))
        assertTrue(FamilySecurity.childSigningKeyAliases(ctx).isEmpty())
        assertTrue(store().containsAlias(parentAlias))
    }

    @Test fun theCurrentPairingsKeySurvives() {
        val r = QrPairing.pairChild(ctx)
        val orphan = FamilySecurity.createChildSigningKey(ctx).alias
        GuardEngine.removeOrphanedChildKeys(ctx)
        assertTrue(gone(orphan))
        assertTrue(present(r.childKeyAlias)); assertEquals(r.childKeyAlias, GuardEngine.childKeyAlias(ctx))
        assertEquals(r.childKeyAlias, FamilySecurity.loadChildSigningKey(ctx, r.childKeyAlias).alias)
    }

    @Test fun nothingIsDeletedWhileAPairingIsActive() {
        parentPhone()
        val orphan = FamilySecurity.createChildSigningKey(ctx).alias
        val parent = GuardEngine.newParentPairing(ctx)
        GuardEngine.removeOrphanedChildKeys(ctx)
        assertTrue("a parent pairing is running", present(orphan))
        parent.cancel()
        // A child pairing whose K exists but is not stored yet.
        GuardEngine.setRole(ctx, "unconfigured")
        val identity = FamilySecurity.newSigningIdentity(); val now = System.currentTimeMillis()
        val child = GuardEngine.newChildPairing(ctx)
        assertNotNull(child.start(QrPairing.bareParent(identity).start(identity.publicKey, now).qrText, "Pixel 7", now))
        val inFlight = FamilySecurity.childSigningKeyAliases(ctx).single { it != orphan }
        GuardEngine.removeOrphanedChildKeys(ctx)
        assertTrue("a child pairing is running", present(orphan) && present(inFlight))
        child.cancel()
        GuardEngine.removeOrphanedChildKeys(ctx)
        assertTrue(gone(orphan)); assertTrue(gone(inFlight))
        assertTrue(store().containsAlias(parentAlias))
    }
}
