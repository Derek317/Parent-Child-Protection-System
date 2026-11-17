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

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class CompromiseRecoveryTest {
    private val ctx:Application get()=RuntimeEnvironment.getApplication()
    @Before fun reset(){LocalVault.preferences(ctx).edit().clear().commit();ctx.deleteDatabase("family_evidence.db");GuardEngine.setRole(ctx,"parent")}
    private fun recover():String {
        val method=GuardEngine::class.java.getDeclaredMethod("createFreshFamilyForRecovery",android.content.Context::class.java)
        return method.invoke(GuardEngine,ctx) as String
    }
    @Test fun freshRecoveryDoesNotSendNewSecretsThroughOldChannelAndKeepsLocalArchive(){
        val old=GuardEngine.getOrCreateFamilyToken(ctx);val scope=GuardEngine.getChannelTopic(ctx)
        val oldPublic=GuardEngine.relayPublicKey(ctx)
        FamilyEvidence(ctx).use{db->
            db.append(scope,"old-record",JSONObject().put("type","alert").put("text","retained old family record").put("reason","stranger_message").put("ts_ms",System.currentTimeMillis()))
            db.enqueue(scope,"pending:0",GuardEngine.encryptPayload(old,"{\"private\":true}"),role="parent",kind="control")
        }
        val fresh=recover()
        assertNotEquals(old,fresh);assertNotEquals(oldPublic,GuardEngine.relayPublicKey(ctx))
        assertEquals("parent",GuardEngine.getRole(ctx))
        FamilyEvidence(ctx).use{db->assertEquals(0,db.pendingCount(scope));assertNotNull(db.find(scope,"old-record"));assertEquals(0,db.pendingCount(GuardEngine.getChannelTopic(ctx)))}
        assertFalse(LocalVault.preferences(ctx).contains("pending_rotation_json"))
        assertFalse(LocalVault.preferences(ctx).contains("parent_signing_private"))
    }
    @Test fun interruptedRecoveryReplaysBeforeAndAfterKeyActivationWithoutLosingArchive(){
        for(activated in listOf(false,true)) {
            reset()
            val old=GuardEngine.getOrCreateFamilyToken(ctx);val scope=GuardEngine.getChannelTopic(ctx)
            FamilyEvidence(ctx).use{db->
                db.append(scope,"before-crash",JSONObject().put("type","alert").put("reason","stranger_message").put("text","old archive").put("ts_ms",System.currentTimeMillis()))
                db.enqueue(scope,"old-pending:0","old cipher")
            }
            val staged=FamilySecurity.stageFreshParentIdentity(ctx)
            val fresh=java.util.UUID.randomUUID().toString()
            val journal=JSONObject().put("old_token",old).put("new_token",fresh)
                .put("signing_ref",staged.privateKey).put("public_key",staged.publicKey)
            assertTrue(LocalVault.preferences(ctx).edit().putString("fresh_family_recovery_json",journal.toString()).commit())
            if(activated)FamilySecurity.activateParentIdentity(ctx,staged)
            // The next ordinary lookup must finish the interrupted transaction, then stay idempotent.
            assertEquals(fresh,GuardEngine.getOrCreateFamilyToken(ctx))
            assertEquals(staged.publicKey,GuardEngine.relayPublicKey(ctx))
            assertEquals(fresh,GuardEngine.getOrCreateFamilyToken(ctx))
            assertFalse(LocalVault.preferences(ctx).contains("fresh_family_recovery_json"))
            FamilyEvidence(ctx).use{db->assertNotNull(db.find(scope,"before-crash"));assertEquals(0,db.pendingCount(scope))}
        }
    }
    @Test fun failedFreshKeyGenerationRetainsWorkingOldFamily(){
        val old=GuardEngine.getOrCreateFamilyToken(ctx);val oldPublic=GuardEngine.relayPublicKey(ctx)
        TestKeyStore.failEcGeneration=true
        try {recover();fail("Expected failure")}catch(_:java.lang.reflect.InvocationTargetException){}finally{TestKeyStore.failEcGeneration=false}
        assertEquals(old,GuardEngine.getOrCreateFamilyToken(ctx));assertEquals(oldPublic,GuardEngine.relayPublicKey(ctx))
    }
}
