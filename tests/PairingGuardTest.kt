package app.family.igguard

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast
import java.util.UUID

/** F3: pairing links never act on a parent phone, and never delete parent secrets as a side effect. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class PairingGuardTest {
    private val ctx: Application get()=RuntimeEnvironment.getApplication()
    private val prefs get()=LocalVault.preferences(ctx)
    @Before fun reset() {
        prefs.edit().clear().commit();ctx.deleteDatabase("family_evidence.db")
        ParentUiAuthFixture.autoApprove=true
    }
    private fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList()
    private fun foreignInvite()=FamilySecurity.Invite(UUID.randomUUID().toString(),FamilySecurity.passwordVerifier("OtherParent928!"),FamilySecurity.newSigningIdentity().publicKey)
    private fun link(i:FamilySecurity.Invite)=Uri.parse("igguard://pair?version=43&token=${i.token}&pw=${FamilySecurity.encode(i.passwordVerifier)}&parent=${i.parentPublicKey}")

    @Test fun parentPhoneRefusesPairingAndKeepsEverySecret() {
        GuardEngine.setRole(ctx,"parent");GuardEngine.setPin(ctx,"ParentPrivate928!")
        val token=GuardEngine.getOrCreateFamilyToken(ctx)
        val publicKey=GuardEngine.relayPublicKey(ctx)
        assertFalse(GuardEngine.applyPairing(ctx,foreignInvite()))
        assertEquals("parent",GuardEngine.getRole(ctx));assertEquals(token,GuardEngine.getOrCreateFamilyToken(ctx))
        assertEquals("ParentPrivate928!",prefs.getString("parent_master_password",null))
        assertEquals(publicKey,GuardEngine.relayPublicKey(ctx))
    }
    @Test fun pairingLinkOnParentPhoneShowsPlainMessageAndNoConfirmation() {
        GuardEngine.setRole(ctx,"parent");GuardEngine.setPin(ctx,"ParentPrivate928!")
        val token=GuardEngine.getOrCreateFamilyToken(ctx)
        val intent=Intent(Intent.ACTION_VIEW,link(foreignInvite()))
        val c=Robolectric.buildActivity(MainActivity::class.java,intent).setup()
        try {
            assertTrue(ShadowToast.getTextOfLatestToast().contains("離開家庭"))
            val sheet=ShadowDialog.getLatestDialog()
            assertTrue(sheet==null || all(sheet.window!!.decorView).filterIsInstance<Button>().none{it.text.toString().contains("確認配對")})
            assertEquals("parent",GuardEngine.getRole(ctx));assertEquals(token,GuardEngine.getOrCreateFamilyToken(ctx))
            assertEquals("ParentPrivate928!",prefs.getString("parent_master_password",null))
        } finally {c.pause().stop().destroy()}
    }
    @Test fun unconfiguredPhoneStillHoldingParentSecretsDoesNotSilentlyDeleteThem() {
        GuardEngine.setRole(ctx,"parent");GuardEngine.setPin(ctx,"ParentPrivate928!")
        val publicKey=GuardEngine.relayPublicKey(ctx)
        GuardEngine.setRole(ctx,"unconfigured")
        assertFalse(GuardEngine.applyPairing(ctx,foreignInvite()))
        assertEquals("ParentPrivate928!",prefs.getString("parent_master_password",null))
        assertEquals(publicKey,prefs.getString("parent_signing_public",null))
    }
    @Test fun cleanUnconfiguredPhoneCanStillPair() {
        assertTrue(GuardEngine.applyPairing(ctx,foreignInvite()))
        assertEquals("child",GuardEngine.getRole(ctx))
    }

    // ---- F2(a): a temporary unlock alone never allows re-pairing a child phone ----
    private fun pairedChild():FamilySecurity.Invite {
        GuardEngine.setRole(ctx,"parent");GuardEngine.setPin(ctx,"ParentPrivate928!")
        val invite=GuardEngine.parsePairingString(GuardEngine.buildPairingInviteText(ctx))!!
        GuardEngine.setRole(ctx,"unconfigured")
        prefs.edit().remove("parent_master_password").remove("parent_share_verifier").remove("parent_signing_public").commit()
        assertTrue(GuardEngine.applyPairing(ctx,invite));assertEquals("child",GuardEngine.getRole(ctx))
        return invite
    }
    @Test fun temporaryUnlockAloneCannotRepairAChildPhone() {
        val first=pairedChild();val token=GuardEngine.getOrCreateFamilyToken(ctx)
        GuardEngine.grantTemporaryUnlock(ctx,15*60_000L);assertTrue(GuardEngine.isTemporarilyUnlocked(ctx))
        assertFalse(GuardEngine.applyPairing(ctx,foreignInvite()))
        assertEquals(token,GuardEngine.getOrCreateFamilyToken(ctx));assertEquals(first.parentPublicKey,prefs.getString("parent_command_public_key",null))
    }
    @Test fun deepLinkOnUnlockedChildAsksForTheParentPasswordAndDoesNotSwitchWithoutIt() {
        pairedChild();val token=GuardEngine.getOrCreateFamilyToken(ctx)
        GuardEngine.grantTemporaryUnlock(ctx,15*60_000L)
        val c=Robolectric.buildActivity(MainActivity::class.java,Intent(Intent.ACTION_VIEW,link(foreignInvite()))).setup()
        try {
            val sheet=ShadowDialog.getLatestDialog()
            val views=all(sheet.window!!.decorView)
            assertTrue("the confirmation must ask for the parent password",views.any{it is android.widget.EditText})
            views.filterIsInstance<Button>().first{it.text.toString().contains("確認配對")}.performClick()
            assertEquals(token,GuardEngine.getOrCreateFamilyToken(ctx));assertEquals("child",GuardEngine.getRole(ctx))
        } finally {c.pause().stop().destroy()}
    }

    @After fun clearHook(){GuardEngine.beforeFamilySwitch=null}
    private fun queuedAlerts(token:String,topic:String):List<org.json.JSONObject> =
        FamilyEvidence(ctx).use{it.due(topic,Long.MAX_VALUE)}.mapNotNull{GuardEngine.decryptPayload(token,it.wire)}
    @Test fun correctParentPasswordRepairsAfterQueueingATamperAlertToTheOldFamily() {
        pairedChild();val oldToken=GuardEngine.getOrCreateFamilyToken(ctx);val oldTopic=GuardEngine.getChannelTopic(ctx)
        val other=foreignInvite();var seen:List<org.json.JSONObject> = emptyList()
        GuardEngine.beforeFamilySwitch={seen=queuedAlerts(oldToken,oldTopic)}
        assertTrue(GuardEngine.applyPairing(ctx,other,"ParentPrivate928!"))
        assertEquals("child",GuardEngine.getRole(ctx));assertEquals(other.token,GuardEngine.getOrCreateFamilyToken(ctx))
        val notice=seen.filter{it.optString("reason")=="tamper_attempt"}
        assertEquals("notice must be queued to the old family before switching",1,notice.size)
        assertTrue(notice[0].optString("text").contains("另一個家庭"))
    }
    @Test fun wrongOrMissingPasswordNeverRepairsAndRepeatedFailuresLockOut() {
        pairedChild();val token=GuardEngine.getOrCreateFamilyToken(ctx)
        assertFalse(GuardEngine.applyPairing(ctx,foreignInvite(),null))
        assertFalse(GuardEngine.applyPairing(ctx,foreignInvite(),""))
        repeat(5){assertFalse(GuardEngine.applyPairing(ctx,foreignInvite(),"WrongPassword928!"))}
        assertTrue(GuardEngine.checkPasswordLockoutRemainingSec(ctx)>0)
        assertFalse("lockout also blocks the right password",GuardEngine.applyPairing(ctx,foreignInvite(),"ParentPrivate928!"))
        assertEquals(token,GuardEngine.getOrCreateFamilyToken(ctx))
    }
    @Test fun signedRemoteResetLetsTheUnconfiguredChildPairWithoutAPassword() {
        val first=pairedChild()
        val key=FamilySecurity.loadOrCreateParentIdentity(ctx,null,first.parentPublicKey).privateKey
        val command=org.json.JSONObject().put("type","remote_reset_child").put("scope",GuardEngine.getChannelTopic(ctx))
            .put("command_id",UUID.randomUUID().toString()).put("ts_ms",System.currentTimeMillis())
        val wire=GuardEngine.encryptPayload(GuardEngine.getOrCreateFamilyToken(ctx),FamilySecurity.signCommand(command,key).toString())
        val line=org.json.JSONObject().put("event","message").put("id",UUID.randomUUID().toString()).put("message",wire)
        assertEquals(RelayConsumption.CONSUMED,GuardEngine.processIncomingRelayLine(ctx,line.toString(),"child",null))
        assertEquals("unconfigured",GuardEngine.getRole(ctx))
        assertTrue(GuardEngine.applyPairing(ctx,foreignInvite()))
        assertEquals("child",GuardEngine.getRole(ctx))
    }
    private fun deliverReset(first:FamilySecurity.Invite,purge:Boolean) {
        val key=FamilySecurity.loadOrCreateParentIdentity(ctx,null,first.parentPublicKey).privateKey
        val command=org.json.JSONObject().put("type","remote_reset_child").put("scope",GuardEngine.getChannelTopic(ctx))
            .put("command_id",UUID.randomUUID().toString()).put("ts_ms",System.currentTimeMillis())
        if(purge)command.put("purge_family",true)
        val wire=GuardEngine.encryptPayload(GuardEngine.getOrCreateFamilyToken(ctx),FamilySecurity.signCommand(command,key).toString())
        val line=org.json.JSONObject().put("event","message").put("id",UUID.randomUUID().toString()).put("message",wire)
        assertEquals(RelayConsumption.CONSUMED,GuardEngine.processIncomingRelayLine(ctx,line.toString(),"child",null))
        assertEquals("unconfigured",GuardEngine.getRole(ctx))
    }
    @Test fun aSignedRoomDeletionResetPurgesTheChildsKeysAndUnsentRecords() {
        val first=pairedChild();val token=GuardEngine.getOrCreateFamilyToken(ctx);val scope=GuardEngine.getChannelTopicForToken(token)
        FamilyEvidence(ctx).use{it.enqueue(scope,"unsent:0","cipher")}
        deliverReset(first,purge=true)
        assertEquals("Nothing queued for a deleted room may stay",0,FamilyEvidence(ctx).use{it.pendingCount(scope)})
        assertNull(prefs.getString("parent_command_public_key",null));assertNull(prefs.getString("parent_password_verifier",null))
        assertNotEquals("The deleted room's secret must not survive on the child",token,GuardEngine.getOrCreateFamilyToken(ctx))
        assertTrue(GuardEngine.applyPairing(ctx,foreignInvite()))
    }
    @Test fun anOrdinarySignedResetKeepsUnsentRecordsAndTheFamilySecret() {
        val first=pairedChild();val token=GuardEngine.getOrCreateFamilyToken(ctx);val scope=GuardEngine.getChannelTopicForToken(token)
        FamilyEvidence(ctx).use{it.enqueue(scope,"unsent:0","cipher")}
        deliverReset(first,purge=false)
        assertEquals(1,FamilyEvidence(ctx).use{it.pendingCount(scope)})
        assertEquals(token,GuardEngine.getOrCreateFamilyToken(ctx))
    }
    @Test fun deepLinkConfirmationRepairsOnlyWithTheRightPassword() {
        pairedChild();val token=GuardEngine.getOrCreateFamilyToken(ctx);val other=foreignInvite()
        val c=Robolectric.buildActivity(MainActivity::class.java,Intent(Intent.ACTION_VIEW,link(other))).setup()
        try {
            val views=all(ShadowDialog.getLatestDialog().window!!.decorView)
            val box=views.filterIsInstance<android.widget.EditText>().first{it.tag=="repair_password"}
            val confirm=views.filterIsInstance<Button>().first{it.text.toString().contains("確認配對")}
            fun press(pw:String,until:()->Boolean){
                box.setText(pw);confirm.performClick()
                repeat(80){org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();if(until())return;Thread.sleep(100)}
            }
            press("WrongPassword928!"){confirm.isEnabled}
            assertEquals(token,GuardEngine.getOrCreateFamilyToken(ctx))
            press("ParentPrivate928!"){GuardEngine.getOrCreateFamilyToken(ctx)==other.token}
            assertEquals(other.token,GuardEngine.getOrCreateFamilyToken(ctx))
        } finally {c.pause().stop().destroy()}
    }

    // ---- F9(b): one rate-limited alert, however often another app fires the link ----
    private fun fire(a:MainActivity,i:FamilySecurity.Invite)=MainActivity::class.java.getDeclaredMethod("onNewIntent",Intent::class.java).apply{isAccessible=true}.invoke(a,Intent(Intent.ACTION_VIEW,link(i)))
    @Test fun repeatedLinksAtALockedChildRaiseOneRateLimitedAlert() {
        pairedChild();val token=GuardEngine.getOrCreateFamilyToken(ctx);val topic=GuardEngine.getChannelTopic(ctx)
        var mono=9_000_000L;var wall=System.currentTimeMillis()
        GuardEngine.monotonicClock={mono};GuardEngine.wallClock={wall};GuardEngine.bootMarker={"5"}
        val c=Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            fun alerts()=queuedAlerts(token,topic).count{it.optString("reason")=="tamper_attempt" && it.optString("sender")=="Deep-Link Guard"}
            repeat(5){fire(c.get(),foreignInvite());mono+=61_000;wall+=61_000;ShadowDialog.getShownDialogs().forEach{it.dismiss()}}
            assertEquals("five links within five minutes must produce one alert",1,alerts())
            mono+=61*60_000L;wall+=61*60_000L
            fire(c.get(),foreignInvite())
            assertEquals("a new window may alert once more",2,alerts())
            assertEquals(token,GuardEngine.getOrCreateFamilyToken(ctx))
        } finally {
            GuardEngine.wallClock={System.currentTimeMillis()};GuardEngine.monotonicClock={android.os.SystemClock.elapsedRealtime()};GuardEngine.bootMarker={"-1"}
            c.pause().stop().destroy()
        }
    }
}
