package app.family.igguard

import android.app.Application
import android.content.Context
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class ProtocolRoundTripTest {
    private val ctx:Application get()=RuntimeEnvironment.getApplication()
    private val prefs get()=ctx.getSharedPreferences("ig_guard_prefs",Context.MODE_PRIVATE)
    @Before fun reset(){prefs.edit().clear().commit();GuardEngine.pauseTamperUntilMs=0}
    private fun pairedChild():Pair<String,String> {
        GuardEngine.setRole(ctx,"parent");GuardEngine.setPin(ctx,"ParentPrivate928!")
        val invite=GuardEngine.parsePairingString(GuardEngine.buildPairingInviteText(ctx))!!
        val privateKey=prefs.getString("parent_signing_private",null)!!
        assertTrue(GuardEngine.applyPairing(ctx,invite))
        return privateKey to invite.token
    }
    private fun command(type:String)=JSONObject().put("type",type).put("scope",GuardEngine.getChannelTopic(ctx))
        .put("command_id",UUID.randomUUID().toString()).put("ts_ms",System.currentTimeMillis())
    private fun receive(command:JSONObject,privateKey:String) {
        val token=GuardEngine.getOrCreateFamilyToken(ctx)
        val encrypted=GuardEngine.encryptPayload(token,FamilySecurity.signCommand(command,privateKey).toString())
        val line=JSONObject().put("event","message").put("id",UUID.randomUUID().toString()).put("message",encrypted)
        GuardEngine.processIncomingRelayLine(ctx,line.toString(),"child",null)
    }
    @Test fun newInviteKeepsMasterPasswordUsableWithoutStoringItOnChild() {
        pairedChild()
        assertTrue(GuardEngine.verifyParentPassword(ctx,"ParentPrivate928!"))
        assertFalse(GuardEngine.verifyParentPassword(ctx,"WrongPassword928!"))
        assertFalse(prefs.contains("parent_master_password"))
        assertFalse(prefs.contains("parent_signing_private"))
        assertTrue(GuardEngine.hasSecureParentCommands(ctx))
    }
    @Test fun parentAndChildShowSameCodeAndChangingAuthorityChangesIt() {
        GuardEngine.setRole(ctx,"parent");GuardEngine.setPin(ctx,"ParentPrivate928!")
        val invite=GuardEngine.parsePairingString(GuardEngine.buildPairingInviteText(ctx))!!
        val parentCode=GuardEngine.getSafetyCode(ctx)
        assertEquals(parentCode,GuardEngine.getSafetyCode(ctx))
        val forged=invite.copy(parentPublicKey=FamilySecurity.newSigningIdentity().publicKey)
        assertNotEquals(parentCode,FamilySecurity.safetyCode(forged.token,forged.passwordVerifier,forged.parentPublicKey))
        assertTrue(GuardEngine.applyPairing(ctx,invite))
        assertEquals(parentCode,GuardEngine.getSafetyCode(ctx))
    }
    @Test fun signedCommandsSyncTrustAndProvisionAnExpiringCode() {
        val (key,_)=pairedChild()
        receive(command("sync_trusted").put("trusted_csv","friend_one,friend_two"),key)
        assertEquals(setOf("friend_one","friend_two"),GuardEngine.getTrustedHandles(ctx))
        receive(command("unlock_code").put("code","234ABC").put("exp",System.currentTimeMillis()+14*60_000L),key)
        assertTrue(GuardEngine.verify15MinUnlockCode(ctx,"234ABC"))
        assertFalse(GuardEngine.verify15MinUnlockCode(ctx,"234ABD"))
    }
    @Test fun replayedCommandCannotRevertNewerTrustedListEvenWithNewRelayId() {
        val (key,_)=pairedChild()
        val first=command("sync_trusted").put("trusted_csv","old_friend")
        receive(first,key)
        val next=command("sync_trusted").put("trusted_csv","new_friend").put("ts_ms",first.getLong("ts_ms"))
        receive(next,key);receive(first,key)
        assertEquals(setOf("new_friend"),GuardEngine.getTrustedHandles(ctx))
    }
    @Test fun lockedChildCannotReplaceFamilyEvenWithValidInvite() {
        pairedChild();val token=GuardEngine.getOrCreateFamilyToken(ctx)
        val other=FamilySecurity.Invite(UUID.randomUUID().toString(),FamilySecurity.passwordVerifier("OtherParent928!"),FamilySecurity.newSigningIdentity().publicKey)
        assertFalse(GuardEngine.applyPairing(ctx,other));assertEquals(token,GuardEngine.getOrCreateFamilyToken(ctx))
    }
    @Test fun parallelEncryptionDoesNotMixKeysAcrossFamilies() {
        val pool=Executors.newFixedThreadPool(4)
        try {
            val jobs=(0..3).map{n->pool.submit {
                val token="family-token-$n"
                repeat(25){i->
                    val wire=GuardEngine.encryptPayload(token,JSONObject().put("n",n).put("i",i).toString())
                    assertEquals(n,GuardEngine.decryptPayload(token,wire)!!.getInt("n"))
                    assertNull(GuardEngine.decryptPayload("wrong-token",wire))
                }
            }}
            jobs.forEach{it.get()}
        }finally{pool.shutdownNow()}
    }
}
