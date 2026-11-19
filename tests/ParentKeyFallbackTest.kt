package app.family.igguard

import android.app.Application
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Base64

/** B3: a failed Keystore import must never cut the parent off from the relay or from signing commands. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class ParentKeyFallbackTest {
    private val ctx:Application get()=RuntimeEnvironment.getApplication()
    private val prefs get()=LocalVault.preferences(ctx)
    private lateinit var legacy:FamilySecurity.SigningIdentity
    @Before fun setUp() {
        prefs.edit().clear().commit();ctx.deleteDatabase("family_evidence.db")
        TestKeyStore.privateKeys.clear();TestKeyStore.certificates.clear();TestKeyStore.failEcImport=false
        legacy=FamilySecurity.newSigningIdentity()
        GuardEngine.setRole(ctx,"parent");GuardEngine.setPin(ctx,"ParentPrivate928!")
        prefs.edit().putString("parent_signing_private",legacy.privateKey).putString("parent_signing_public",legacy.publicKey).commit()
        TestKeyStore.failEcImport=true
    }
    @After fun tearDown(){TestKeyStore.failEcImport=false}

    private fun queuedControl():JSONObject {
        val token=GuardEngine.getOrCreateFamilyToken(ctx)
        val frames=FamilyEvidence(ctx).use{it.due(GuardEngine.getChannelTopic(ctx),Long.MAX_VALUE,"parent")}.mapNotNull{GuardEngine.decryptPayload(token,it.wire)}
        val first=frames.first()
        val whole=if(first.optString("type")=="alert_fragment") {
            val bytes=frames.sortedBy{it.getInt("part")}.map{Base64.getDecoder().decode(it.getString("data"))}.reduce{a,b->a+b}
            JSONObject(String(bytes,Charsets.UTF_8))
        } else first
        return whole.getJSONObject("envelope")
    }
    @Test fun importFailureStillAuthenticatesToTheRelayWithTheExistingKey() {
        val token=GuardEngine.getOrCreateFamilyToken(ctx)
        assertEquals(legacy.publicKey,GuardEngine.relayPublicKey(ctx))
        assertTrue(GuardEngine.relayAuthorization(ctx,token).isNotEmpty())
        assertEquals("the legacy key is kept for the retry",legacy.privateKey,prefs.getString("parent_signing_private",null))
        assertTrue(TestKeyStore.privateKeys.isEmpty())
    }
    @Test fun importFailureStillSignsCommandsTheChildAccepts() {
        val old=GuardEngine.getOrCreateFamilyToken(ctx)
        GuardEngine.rotateKeyAndNotifyChild(ctx,false)
        val envelope=queuedControl()
        val command=FamilySecurity.readCommand(envelope,legacy.publicKey,GuardEngine.getChannelTopicForToken(old),System.currentTimeMillis())
        assertNotNull("the child pinned to the existing public key must accept the command",command)
        assertEquals("key_rotated",command!!.getString("type"))
    }
    @Test fun aLegacyKeyThatDoesNotMatchTheTrustedPublicKeyStillFailsClosed() {
        val other=FamilySecurity.newSigningIdentity()
        prefs.edit().putString("parent_signing_private",other.privateKey).commit()
        try{GuardEngine.relayPublicKey(ctx);fail("a mismatched key must not be used")}catch(_:Exception){}
        assertEquals(other.privateKey,prefs.getString("parent_signing_private",null))
    }

    @Test fun nextStartRetriesTheImportAndOnlyThenRemovesTheLegacyKey() {
        GuardEngine.forgetParentIdentityCache()
        assertEquals(legacy.publicKey,GuardEngine.relayPublicKey(ctx))
        assertTrue(GuardEngine.parentKeyMigrationPending(ctx))
        assertNotNull(prefs.getString("parent_signing_private",null))
        TestKeyStore.failEcImport=false
        GuardEngine.forgetParentIdentityCache()
        assertEquals("the trusted public key is preserved",legacy.publicKey,GuardEngine.relayPublicKey(ctx))
        assertFalse(GuardEngine.parentKeyMigrationPending(ctx))
        assertNull("legacy removed after the Keystore key proved to match",prefs.getString("parent_signing_private",null))
        assertEquals(1,TestKeyStore.privateKeys.size)
        val old=GuardEngine.getOrCreateFamilyToken(ctx)
        GuardEngine.rotateKeyAndNotifyChild(ctx,false)
        assertNotNull(FamilySecurity.readCommand(queuedControl(),legacy.publicKey,GuardEngine.getChannelTopicForToken(old),System.currentTimeMillis()))
    }
    @Test fun repeatedCallsResolveTheIdentityOnceAndDoNotWrite() {
        TestKeyStore.failEcImport=false
        GuardEngine.forgetParentIdentityCache()
        val before=GuardEngine.identityWrites
        repeat(30){GuardEngine.relayPublicKey(ctx);GuardEngine.relayAuthorization(ctx,GuardEngine.getOrCreateFamilyToken(ctx))}
        assertEquals("one commit for the migration, none afterwards",before+1,GuardEngine.identityWrites)
        TestKeyStore.privateKeys.clear()   // a cached identity is not re-read from the Keystore on every call
        GuardEngine.relayPublicKey(ctx)
        assertEquals(before+1,GuardEngine.identityWrites)
    }
    @Test fun parentScreenShowsANonBlockingWarningWhileTheImportIsPending() {
        GuardEngine.forgetParentIdentityCache()
        val c=org.robolectric.Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            fun texts(v:android.view.View):List<String> = (if(v is android.widget.TextView)listOf(v.text.toString()) else emptyList())+
                (if(v is android.view.ViewGroup)(0 until v.childCount).flatMap{texts(v.getChildAt(it))} else emptyList())
            val shown=texts(c.get().window.decorView)
            assertTrue(shown.any{it.contains("金鑰升級尚未完成")})
            assertTrue("the rest of the parent screen still renders",shown.any{it.contains("家人")})
        } finally {c.pause().stop().destroy()}
    }
}
