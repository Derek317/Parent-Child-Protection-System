package app.family.igguard

import android.app.Application
import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.security.KeyStore

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LocalVaultTest {
    private val ctx: Application get() = RuntimeEnvironment.getApplication()
    private val raw get() = ctx.getSharedPreferences("ig_guard_prefs", Context.MODE_PRIVATE)
    @Before fun reset() { raw.edit().clear().commit(); LocalVault.initializeArchiveKeys(ctx) }
    private fun rejected(action: () -> Unit) {
        try { action(); fail("Unreadable ciphertext must fail closed") } catch (_: SecurityException) { }
    }
    @Test fun authenticatedEncryptionUsesFreshNoncesAndBindsItsPurpose() {
        val a = LocalVault.seal(ctx, "one", "synthetic text")
        val b = LocalVault.seal(ctx, "one", "synthetic text")
        assertNotEquals(a, b)
        assertEquals("synthetic text", LocalVault.open(ctx, "one", a))
        rejected { LocalVault.open(ctx, "two", a) }
        val index = a.length / 2
        rejected { LocalVault.open(ctx, "one", a.replaceRange(index, index + 1, if (a[index] == 'A') "B" else "A")) }
        rejected { LocalVault.open(ctx, "one", "synthetic text") }
    }
    @Test fun aRememberedValueNeedsNoKeystoreUntilForgottenAndIsBoundToItsPurpose() {
        val value = LocalVault.seal(ctx, "one", "synthetic text")
        assertEquals("synthetic text", LocalVault.open(ctx, "one", value, remember = true))
        TestKeyStore.failReads = true
        try {
            assertEquals("the unchanged value is served without the Keystore", "synthetic text", LocalVault.open(ctx, "one", value, remember = true))
            rejected { LocalVault.open(ctx, "two", value, remember = true) }
            rejected { LocalVault.open(ctx, "one", value) }   // message text and other callers never use the remembered copy
            LocalVault.forgetOpened()
            rejected { LocalVault.open(ctx, "one", value, remember = true) }
        } finally { TestKeyStore.failReads = false }
    }
    @Test fun lostKeyDoesNotRegenerateDuringDecryption() {
        val value = LocalVault.seal(ctx, "one", "synthetic text")
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        store.deleteEntry("ig_guard.local_storage.aes.v1")
        rejected { LocalVault.open(ctx, "one", value) }
        rejected { LocalVault.seal(ctx, "one", "new content") }
        rejected { LocalVault.initializeArchiveKeys(ctx) }
        assertFalse(store.containsAlias("ig_guard.local_storage.aes.v1"))
    }
    @Test fun losingKeyBlocksPreferenceWritesWithoutReplacingOtherCiphertext() {
        val prefs = LocalVault.preferences(ctx)
        prefs.edit().putString("checkpoint_logs", "existing log").putString("parent_master_password", "synthetic928").commit()
        val before = raw.all.toMap()
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        store.deleteEntry("ig_guard.local_storage.aes.v1")
        rejected { GuardEngine.clearCheckpointLogs(ctx) }
        assertEquals(before, raw.all)
        assertFalse(store.containsAlias("ig_guard.local_storage.aes.v1"))
    }
    @Test fun legacyMigrationAndEditorChainsPreserveValuesWithoutPlaintext() {
        raw.edit().putString("parent_master_password", "igv1:valid legacy password928").putInt("counter", 7).commit()
        val prefs = LocalVault.preferences(ctx)
        assertEquals("igv1:valid legacy password928", prefs.getString("parent_master_password", null))
        assertEquals(7, prefs.getInt("counter", 0))
        prefs.edit().putString("first", "secret one").putInt("counter", 9).putString("second", "secret two").commit()
        assertEquals("secret one", prefs.getString("first", null))
        assertEquals("secret two", prefs.getString("second", null))
        assertEquals(9, prefs.getInt("counter", 0))
        assertTrue(raw.getString("first", "")!!.startsWith("igv1:"))
        assertFalse(prefs.all.containsKey("_local_vault_version"))
    }
    @Test fun corruptEncryptedPreferenceIsNotReinterpretedAsLegacyPlaintext() {
        LocalVault.preferences(ctx).edit().putString("parent_master_password", "synthetic928").commit()
        raw.edit().putString("parent_master_password", "broken ciphertext").commit()
        rejected { LocalVault.preferences(ctx).getString("parent_master_password", null) }
        assertEquals("broken ciphertext", raw.getString("parent_master_password", null))
    }
    @Test fun unavailableKeystoreLeavesLegacyPreferencesUntouched() {
        raw.edit().putString("parent_master_password", "synthetic928").commit()
        TestKeyStore.failReads = true
        try { rejected { LocalVault.preferences(ctx) } } finally { TestKeyStore.failReads = false }
        assertEquals("synthetic928", raw.getString("parent_master_password", null))
        assertFalse(raw.contains("_local_vault_version"))
    }
    @Test fun blindIndexesAreStableAndPurposeSeparated() {
        val value = LocalVault.blindIndex(ctx, "sender", "synthetic")
        assertEquals(value, LocalVault.blindIndex(ctx, "sender", "synthetic"))
        assertNotEquals(value, LocalVault.blindIndex(ctx, "thread", "synthetic"))
        assertNotEquals(value, LocalVault.blindIndex(ctx, "sender", "other"))
    }
}
