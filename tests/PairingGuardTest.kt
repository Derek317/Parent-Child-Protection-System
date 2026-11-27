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
        GuardEngine.passwordCheckRunning.set(false)
    }
    private fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList()
    private fun foreignInvite()=FamilySecurity.Invite(UUID.randomUUID().toString(),FamilySecurity.passwordVerifier("OtherParent928!"),FamilySecurity.newSigningIdentity().publicKey)
    private fun link(i:FamilySecurity.Invite)=Uri.parse("igguard://pair?version=43&token=${i.token}&pw=${FamilySecurity.encode(i.passwordVerifier)}&parent=${i.parentPublicKey}")

    /** Design 2.8: a new, unpaired phone refuses a full V43 invitation link with the plain message, and stays unpaired. */
    @Test fun aV43LinkOnAnUnpairedPhoneIsRefusedWithThePlainMessage() {
        AppConsent.accept(ctx,AppConsent.acknowledgments)
        val invite=foreignInvite()
        val c=Robolectric.buildActivity(MainActivity::class.java,Intent(Intent.ACTION_VIEW,link(invite))).setup()
        try {
            val sheet=ShadowDialog.getLatestDialog().window!!.decorView
            assertNotNull("the refusal is shown",sheet.findViewWithTag<View>("invite_refused"))
            assertTrue(all(sheet).filterIsInstance<android.widget.TextView>().any{it.text.toString().contains("請在家長手機選擇「加入子女」")})
            assertTrue("no confirmation button",all(sheet).filterIsInstance<Button>().none{it.text.toString().contains("確認配對") || it.text.toString().contains("加入此家庭")})
            assertEquals("unconfigured",GuardEngine.getRole(ctx));assertNotEquals(invite.token,prefs.getString("family_uuid_token",null))
        } finally {c.pause().stop().destroy()}
    }
    @Test fun newPairingRefusesAnInviteWithoutTheParentKey() {
        AppConsent.accept(ctx,AppConsent.acknowledgments)
        val token="01234567-89ab-cdef-0123-456789abcdef"
        val keyed=foreignInvite().copy(token=token)
        assertFalse("applyPairing refuses a keyless invite too",GuardEngine.applyPairing(ctx,keyed.copy(parentPublicKey="")))
        assertEquals("unconfigured",GuardEngine.getRole(ctx))
        val c=Robolectric.buildActivity(MainActivity::class.java,Intent(Intent.ACTION_VIEW,Uri.parse("igguard://pair?token=$token&pin=ParentPassword928"))).setup()
        try {
            val sheet=ShadowDialog.getLatestDialog()
            assertTrue("no confirmation sheet for a keyless link",sheet==null || all(sheet.window!!.decorView).filterIsInstance<Button>().none{it.text.toString().contains("確認配對")})
            assertEquals("unconfigured",GuardEngine.getRole(ctx))
        } finally {c.pause().stop().destroy()}
    }
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
        val invite=QrPairing.legacyInvite(ctx)
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
    @Test fun deepLinkOnUnlockedChildIsRefusedAndDoesNotSwitch() {
        pairedChild();val token=GuardEngine.getOrCreateFamilyToken(ctx)
        GuardEngine.grantTemporaryUnlock(ctx,15*60_000L)
        val c=Robolectric.buildActivity(MainActivity::class.java,Intent(Intent.ACTION_VIEW,link(foreignInvite()))).setup()
        try {
            val views=all(ShadowDialog.getLatestDialog().window!!.decorView)
            assertTrue(views.any{it.tag=="invite_refused"})
            assertTrue("nothing to confirm",views.filterIsInstance<Button>().none{it.text.toString().contains("確認配對")})
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
    @Test fun aSignedRoomDeletionResetAlsoEmptiesTheScoringStore() {
        val first=pairedChild();val token=GuardEngine.getOrCreateFamilyToken(ctx);val scope=GuardEngine.getChannelTopicForToken(token)
        fun riskRows()=FamilyEvidence(ctx).use{it.readableDatabase.rawQuery("SELECT COUNT(*) FROM risk_store",null).use{c->c.moveToFirst();c.getInt(0)}}
        fun seed(){
            val now=System.currentTimeMillis()
            FamilyEvidence(ctx).use{assertTrue(it.storeRiskMessage(scope,"m1","thread-1","label","incoming","scored text",now,now,1L,now))}
            assertEquals(1,riskRows())
        }
        // purgeChildFamily on its own, with the child role still set, must wipe the store.
        seed()
        GuardEngine::class.java.getDeclaredMethod("purgeChildFamily",android.content.Context::class.java,String::class.java).apply{isAccessible=true}.invoke(GuardEngine,ctx,token)
        assertEquals("the purge itself must empty the scoring store",0,riskRows())
    }
    private fun assertOldFamilyGone(token:String,scope:String) {
        assertEquals(0,FamilyEvidence(ctx).use{it.pendingCount(scope)})
        for(key in listOf("parent_command_public_key","parent_password_verifier","trusted_csv","flagged_offenders_csv","child_self_handle"))assertNull(key,prefs.getString(key,null))
        assertNotEquals(token,GuardEngine.getOrCreateFamilyToken(ctx))
        assertTrue(GuardEngine.applyPairing(ctx,foreignInvite()))
    }
    private fun seedOldFamily():Pair<String,String> {
        val token=GuardEngine.getOrCreateFamilyToken(ctx);val scope=GuardEngine.getChannelTopicForToken(token)
        FamilyEvidence(ctx).use{it.enqueue(scope,"unsent:0","cipher")}
        GuardEngine.setTrustedHandles(ctx,listOf("old_friend"));GuardEngine.setChildSelfHandle(ctx,"old_child")
        prefs.edit().putString("flagged_offenders_csv","old_flag").commit()
        return token to scope
    }
    @Test fun anOrdinarySignedResetAlsoPurgesTheOldFamily() {
        val first=pairedChild();val (token,scope)=seedOldFamily()
        deliverReset(first,purge=false)
        assertOldFamilyGone(token,scope)
    }
    @Test fun leavingTheChildRoleLocallyPurgesTheOldFamily() {
        pairedChild();val (token,scope)=seedOldFamily()
        GuardEngine.setRole(ctx,"unconfigured")
        assertOldFamilyGone(token,scope)
    }
    /** The QR join sheet on a paired child (C3): the parent password gates the join press, as the 5.2 re-pair did. */
    @Test fun qrJoinOnAPairedChildStartsOnlyWithTheRightPassword() {
        pairedChild();val token=GuardEngine.getOrCreateFamilyToken(ctx)
        val parent=FamilySecurity.newSigningIdentity()
        val qr=QrPairing.bareParent(parent).start(parent.publicKey,System.currentTimeMillis()).qrText
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        try {
            MainActivity::class.java.getDeclaredMethod("showChildPairingSheet").apply{isAccessible=true}.invoke(a)
            MainActivity::class.java.getDeclaredMethod("onQrScanned",String::class.java).apply{isAccessible=true}.invoke(a,qr)
            val views=all(ShadowDialog.getLatestDialog().window!!.decorView)
            val box=views.filterIsInstance<android.widget.EditText>().first{it.tag=="repair_password"}
            val join=views.filterIsInstance<Button>().first{it.tag=="join_family"}
            val link=MainActivity::class.java.getDeclaredField("childPairing").apply{isAccessible=true}
            fun childKeys()=TestKeyStore.privateKeys.keys.count{it.startsWith("ig_guard.child_signing")}
            val keysBefore=childKeys()
            box.setText("WrongPassword928!");join.performClick();settle{join.isEnabled}
            assertNull("a wrong password starts nothing",link.get(a))
            assertEquals("no child key is made",keysBefore,childKeys())
            box.setText("ParentPrivate928!");join.performClick();settle{link.get(a)!=null}
            assertNotNull("the right password starts the pairing",link.get(a))
            assertEquals("nothing is stored before the parent confirms",token,GuardEngine.getOrCreateFamilyToken(ctx))
        } finally {c.pause().stop().destroy()}
    }
    /** The password check runs off the main thread (PBKDF2 took ~2 s on an LG G7); its result is handled on it. */
    private fun settle(done:()->Boolean){
        val end=System.currentTimeMillis()+20_000
        while(true){org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();if(done()||System.currentTimeMillis()>end)break;Thread.sleep(5)}
        assertTrue("the password check finished",done())
    }
    /** A second press while the check is pending does nothing; a wrong password is counted once and the controls come back. */
    @Test fun qrJoinPressWhileThePasswordIsCheckedIsIgnored() {
        pairedChild()
        val parent=FamilySecurity.newSigningIdentity()
        val qr=QrPairing.bareParent(parent).start(parent.publicKey,System.currentTimeMillis()).qrText
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        try {
            MainActivity::class.java.getDeclaredMethod("showChildPairingSheet").apply{isAccessible=true}.invoke(a)
            MainActivity::class.java.getDeclaredMethod("onQrScanned",String::class.java).apply{isAccessible=true}.invoke(a,qr)
            val views=all(ShadowDialog.getLatestDialog().window!!.decorView)
            val box=views.filterIsInstance<android.widget.EditText>().first{it.tag=="repair_password"}
            val join=views.filterIsInstance<Button>().first{it.tag=="join_family"}
            box.setText("WrongPassword928!");join.performClick()
            // The main looper is paused: the result cannot be handled before the next idle, so this is the pending state.
            assertFalse("Join is disabled while checking",join.isEnabled);assertFalse("the password box too",box.isEnabled)
            assertEquals("核對中… · Checking…",join.text.toString())
            join.performClick() // performClick bypasses isEnabled, as a tap on a disabled button would not
            settle{join.isEnabled}
            assertEquals("one attempt, recorded once",1,prefs.getInt("pw_failed_count",0))
            assertTrue("the same error",views.filterIsInstance<android.widget.TextView>().any{it.text.toString()=="家長主密碼不正確。"})
            assertEquals("請重新輸入",box.error?.toString());assertTrue(box.isEnabled)
            assertEquals("加入此家庭 · Join this family",join.text.toString())
            assertNull("nothing started",MainActivity::class.java.getDeclaredField("childPairing").apply{isAccessible=true}.get(a))
        } finally {c.pause().stop().destroy()}
    }
    private fun joinSheet(a:MainActivity,qr:String):Pair<android.widget.EditText,Button>{
        MainActivity::class.java.getDeclaredMethod("showChildPairingSheet").apply{isAccessible=true}.invoke(a)
        MainActivity::class.java.getDeclaredMethod("onQrScanned",String::class.java).apply{isAccessible=true}.invoke(a,qr)
        val views=all(ShadowDialog.getLatestDialog().window!!.decorView)
        return views.filterIsInstance<android.widget.EditText>().first{it.tag=="repair_password"} to views.filterIsInstance<Button>().first{it.tag=="join_family"}
    }
    private fun shows(root:View,text:String)=all(root).filterIsInstance<android.widget.TextView>().any{it.text.toString()==text}
    /** Rotation or leaving and returning makes a new activity: it cannot start a second check while the first one runs. */
    @Test fun aSecondActivityCannotStartAJoinCheckWhileOneRuns() {
        pairedChild()
        val parent=FamilySecurity.newSigningIdentity()
        val qr=QrPairing.bareParent(parent).start(parent.publicKey,System.currentTimeMillis()).qrText
        val first=Robolectric.buildActivity(MainActivity::class.java).setup()
        val (box1,join1)=joinSheet(first.get(),qr)
        box1.setText("WrongPassword928!");join1.performClick()
        first.pause().stop().destroy() // the check's result is still pending on the main looper
        val c=Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val (box,join)=joinSheet(c.get(),qr)
            box.setText("WrongPassword928!");join.performClick()
            assertTrue("refused, and says why",shows(ShadowDialog.getLatestDialog().window!!.decorView,PASSWORD_CHECK_BUSY));assertTrue(join.isEnabled)
            settle{!GuardEngine.passwordCheckRunning.get()} // the destroyed activity's callback still runs and clears the guard
            assertEquals("one attempt, recorded once",1,prefs.getInt("pw_failed_count",0))
            join.performClick();settle{join.isEnabled && !GuardEngine.passwordCheckRunning.get()}
            assertEquals("after the check, the next press is an attempt again",2,prefs.getInt("pw_failed_count",0))
        } finally {c.pause().stop().destroy()}
    }
    /** The unlock page and the role-switch sheet refuse while a join check runs, so attempts stay in order. */
    @Test fun unlockPageAndRoleSheetMakeNoAttemptWhileAJoinCheckRuns() {
        pairedChild()
        val parent=FamilySecurity.newSigningIdentity()
        val qr=QrPairing.bareParent(parent).start(parent.publicKey,System.currentTimeMillis()).qrText
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        fun call(name:String,vararg args:Any?){MainActivity::class.java.declaredMethods.first{it.name==name && it.parameterCount==args.size}.apply{isAccessible=true}.invoke(a,*args)}
        fun unlock(text:String){
            call("navigateTo","unlock")
            all(a.window.decorView).filterIsInstance<android.widget.EditText>().first().setText(text)
            all(a.window.decorView).filterIsInstance<Button>().first{it.text.toString()=="驗證並暫時解鎖"}.performClick()
        }
        fun roleSheet(text:String):View{
            call("navigateTo","role");call("selectEntryRole","parent");call("continueEntryRole")
            val sheet=ShadowDialog.getLatestDialog().window!!.decorView
            all(sheet).filterIsInstance<android.widget.EditText>().first{it.tag=="role_password"}.setText(text)
            all(sheet).filterIsInstance<Button>().first{it.text.toString()=="驗證並繼續"}.performClick()
            return sheet
        }
        try {
            val (box,join)=joinSheet(a,qr)
            box.setText("WrongPassword928!");join.performClick() // pending: the main looper is paused
            unlock("ParentPrivate928!")
            assertFalse("the unlock page made no attempt",GuardEngine.isTemporarilyUnlocked(ctx));assertTrue(shows(a.window.decorView,PASSWORD_CHECK_BUSY))
            val sheet=roleSheet("ParentPrivate928!")
            assertTrue("the role sheet says why",shows(sheet,PASSWORD_CHECK_BUSY));assertEquals("child",GuardEngine.getRole(ctx))
            assertEquals("no attempt counted by either",0,prefs.getInt("pw_failed_count",0))
            settle{!GuardEngine.passwordCheckRunning.get()}
            assertEquals("only the join attempt",1,prefs.getInt("pw_failed_count",0))
            ShadowDialog.getShownDialogs().forEach{it.dismiss()}
            val again=roleSheet("WrongPassword928!");settle{!GuardEngine.passwordCheckRunning.get()}
            assertTrue("the role sheet checks again",shows(again,"家長主密碼不正確。"));assertEquals(2,prefs.getInt("pw_failed_count",0))
            ShadowDialog.getShownDialogs().forEach{it.dismiss()}
            unlock("ParentPrivate928!");settle{!GuardEngine.passwordCheckRunning.get()}
            assertTrue("the unlock page works again",GuardEngine.isTemporarilyUnlocked(ctx))
        } finally {c.pause().stop().destroy()}
    }
    /** The unlock page checks the parent password off the main thread, as the Join button does: disabled while checking. */
    @Test fun unlockPagePasswordPressWhileCheckedIsIgnored() {
        pairedChild()
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        try {
            MainActivity::class.java.getDeclaredMethod("navigateTo",String::class.java).apply{isAccessible=true}.invoke(a,"unlock")
            val box=all(a.window.decorView).filterIsInstance<android.widget.EditText>().first()
            val verify=all(a.window.decorView).filterIsInstance<Button>().first{it.text.toString()=="驗證並暫時解鎖"}
            box.setText("WrongPassword928!");verify.performClick()
            assertFalse("the button is disabled while checking",verify.isEnabled);assertFalse("the input too",box.isEnabled)
            assertEquals("核對中… · Checking…",verify.text.toString())
            verify.performClick()
            settle{verify.isEnabled}
            assertEquals("one attempt, recorded once",1,prefs.getInt("pw_failed_count",0))
            assertTrue("the same error",shows(a.window.decorView,"解鎖碼或家長主密碼不正確。"))
            assertEquals("請重新輸入",box.error?.toString());assertTrue(box.isEnabled)
            assertEquals("驗證並暫時解鎖",verify.text.toString());assertFalse(GuardEngine.isTemporarilyUnlocked(ctx))
            box.setText("ParentPrivate928!");verify.performClick()
            assertFalse("not unlocked before the check finishes",GuardEngine.isTemporarilyUnlocked(ctx))
            settle{GuardEngine.isTemporarilyUnlocked(ctx)}
            assertEquals(0,prefs.getInt("pw_failed_count",0))
        } finally {c.pause().stop().destroy()}
    }
    /** The role-change sheet checks the parent password off the main thread, as the Join button does: disabled while checking. */
    @Test fun roleSheetPasswordPressWhileCheckedIsIgnored() {
        pairedChild()
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        fun call(name:String,vararg args:Any?){MainActivity::class.java.declaredMethods.first{it.name==name && it.parameterCount==args.size}.apply{isAccessible=true}.invoke(a,*args)}
        try {
            call("navigateTo","role");call("selectEntryRole","parent");call("continueEntryRole")
            val sheet=ShadowDialog.getLatestDialog().window!!.decorView
            val box=all(sheet).filterIsInstance<android.widget.EditText>().first{it.tag=="role_password"}
            val verify=all(sheet).filterIsInstance<Button>().first{it.text.toString()=="驗證並繼續"}
            box.setText("WrongPassword928!");verify.performClick()
            assertFalse("the button is disabled while checking",verify.isEnabled);assertFalse("the password box too",box.isEnabled)
            assertEquals("核對中… · Checking…",verify.text.toString())
            verify.performClick()
            settle{verify.isEnabled}
            assertEquals("one attempt, recorded once",1,prefs.getInt("pw_failed_count",0))
            assertTrue("the same error",shows(sheet,"家長主密碼不正確。"))
            assertEquals("請重新輸入",box.error?.toString());assertTrue(box.isEnabled)
            assertEquals("驗證並繼續",verify.text.toString());assertEquals("child",GuardEngine.getRole(ctx))
            box.setText("ParentPrivate928!");verify.performClick()
            assertEquals("no change before the check finishes","child",GuardEngine.getRole(ctx))
            settle{GuardEngine.getRole(ctx)=="unconfigured"}
        } finally {c.pause().stop().destroy()}
    }
    /** The app died between the parent identity's "pending" marker and its key: "Add a child" shows the existing key error instead of
     *  crashing, and the identity is still refused, never silently replaced (FamilySecurity.loadOrCreateParentIdentity). */
    @Test fun addAChildWithAnInterruptedParentIdentityShowsTheKeyErrorInsteadOfCrashing() {
        GuardEngine.setRole(ctx,"parent");GuardEngine.setPin(ctx,"ParentPrivate928!")
        TestKeyStore.privateKeys.clear();TestKeyStore.certificates.clear()
        ctx.noBackupFilesDir.listFiles()?.filter{it.name.startsWith("ig_guard.parent_signing.")}?.forEach{it.delete()}
        java.io.File(ctx.noBackupFilesDir,"ig_guard.parent_signing.ec.v1.initialized").writeText("ig_guard.parent_signing.ec.v1\npending")
        prefs.edit().remove("parent_signing_public").remove("parent_signing_private").commit();GuardEngine.forgetParentIdentityCache()
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        try {
            MainActivity::class.java.getDeclaredMethod("showParentPairingSheet").apply{isAccessible=true}.invoke(a)
            val sheet=ShadowDialog.getLatestDialog().window!!.decorView
            assertTrue("the existing key error",shows(sheet,"未能使用此手機的簽署金鑰。\nThis phone's signing key is unavailable."))
            assertNull("no QR",sheet.findViewWithTag<View>("pairing_qr"))
            assertTrue("no parent key was made",TestKeyStore.privateKeys.keys.none{it.startsWith("ig_guard.parent_signing")})
            try{FamilySecurity.loadOrCreateParentIdentity(ctx,null,null);fail("still refused")}catch(_:SecurityException){}
        } finally {c.pause().stop().destroy()}
    }
    private fun act(a:MainActivity,name:String,vararg args:Any?){MainActivity::class.java.declaredMethods.first{it.name==name && it.parameterCount==args.size}.apply{isAccessible=true}.invoke(a,*args)}
    private fun pressUnlock(a:MainActivity,text:String):Button{
        act(a,"navigateTo","unlock")
        all(a.window.decorView).filterIsInstance<android.widget.EditText>().first().setText(text)
        return all(a.window.decorView).filterIsInstance<Button>().first{it.text.toString()=="驗證並暫時解鎖"}.also{it.performClick()}
    }
    private fun pressRoleSheet(a:MainActivity,text:String):Pair<View,Button>{
        act(a,"navigateTo","role");act(a,"selectEntryRole","parent");act(a,"continueEntryRole")
        val sheet=ShadowDialog.getLatestDialog().window!!.decorView
        all(sheet).filterIsInstance<android.widget.EditText>().first{it.tag=="role_password"}.setText(text)
        return sheet to all(sheet).filterIsInstance<Button>().first{it.text.toString()=="驗證並繼續"}.also{it.performClick()}
    }
    private fun childPairing(a:MainActivity)=MainActivity::class.java.getDeclaredField("childPairing").apply{isAccessible=true}.get(a)
    /** Locked out: neither password screen starts a check (guard never claimed, so no PBKDF2 thread) or uses an attempt, even with the right password. */
    @Test fun lockedOutUnlockPageAndRoleSheetStartNoCheckAndUseNoAttempt() {
        pairedChild()
        prefs.edit().putLong("pw_lockout_until_ms",System.currentTimeMillis()+5*60_000L).putInt("pw_failed_count",3).commit()
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        try {
            val unlock=pressUnlock(a,"ParentPrivate928!")
            assertFalse("no check claimed by the unlock page",GuardEngine.passwordCheckRunning.get())
            assertTrue(unlock.isEnabled);assertEquals("驗證並暫時解鎖",unlock.text.toString())
            assertTrue("says it is locked",all(a.window.decorView).filterIsInstance<android.widget.TextView>().any{it.text.toString().startsWith("請等 ")})
            val (sheet,verify)=pressRoleSheet(a,"ParentPrivate928!")
            assertFalse("no check claimed by the role sheet",GuardEngine.passwordCheckRunning.get())
            assertTrue(verify.isEnabled);assertEquals("驗證並繼續",verify.text.toString())
            assertTrue("says it is locked",all(sheet).filterIsInstance<android.widget.TextView>().any{it.text.toString().startsWith("輸入已鎖定")})
            repeat(100){org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();Thread.sleep(20)} // a started check would have finished
            assertFalse("the right password was never checked",GuardEngine.isTemporarilyUnlocked(ctx));assertEquals("child",GuardEngine.getRole(ctx))
            assertEquals("no attempt used, none cleared",3,prefs.getInt("pw_failed_count",0));assertTrue("still locked",GuardEngine.checkPasswordLockoutRemainingSec(ctx)>0)
        } finally {c.pause().stop().destroy()}
    }
    /** While the unlock page's check runs, a second press there, the role sheet and the QR Join are refused by the guard. */
    @Test fun whileTheUnlockPageChecksEveryOtherPasswordPressIsRefused() {
        pairedChild()
        val parent=FamilySecurity.newSigningIdentity()
        val qr=QrPairing.bareParent(parent).start(parent.publicKey,System.currentTimeMillis()).qrText
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        try {
            val unlock=pressUnlock(a,"WrongPassword928!") // pending: the main looper is paused
            assertTrue(GuardEngine.passwordCheckRunning.get())
            unlock.performClick()
            assertTrue("a second press on the page is refused",shows(a.window.decorView,PASSWORD_CHECK_BUSY))
            val (sheet,_)=pressRoleSheet(a,"ParentPrivate928!")
            assertTrue("the role sheet is refused",shows(sheet,PASSWORD_CHECK_BUSY))
            ShadowDialog.getShownDialogs().forEach{it.dismiss()}
            val (box,join)=joinSheet(a,qr);box.setText("ParentPrivate928!");join.performClick()
            assertTrue("the QR Join is refused",shows(ShadowDialog.getLatestDialog().window!!.decorView,PASSWORD_CHECK_BUSY));assertNull(childPairing(a))
            assertEquals("no attempt counted yet",0,prefs.getInt("pw_failed_count",0))
            settle{!GuardEngine.passwordCheckRunning.get()}
            assertEquals("only the first unlock attempt",1,prefs.getInt("pw_failed_count",0))
            assertFalse(GuardEngine.isTemporarilyUnlocked(ctx));assertEquals("child",GuardEngine.getRole(ctx));assertNull(childPairing(a))
        } finally {c.pause().stop().destroy()}
    }
    /** While the role sheet's check runs, a second press there, the unlock page and the QR Join are refused by the guard. */
    @Test fun whileTheRoleSheetChecksEveryOtherPasswordPressIsRefused() {
        pairedChild()
        val parent=FamilySecurity.newSigningIdentity()
        val qr=QrPairing.bareParent(parent).start(parent.publicKey,System.currentTimeMillis()).qrText
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        try {
            val (sheet,verify)=pressRoleSheet(a,"WrongPassword928!") // pending: the main looper is paused
            assertTrue(GuardEngine.passwordCheckRunning.get())
            verify.performClick()
            assertTrue("a second press on the sheet is refused",shows(sheet,PASSWORD_CHECK_BUSY))
            pressUnlock(a,"ParentPrivate928!")
            assertTrue("the unlock page is refused",shows(a.window.decorView,PASSWORD_CHECK_BUSY))
            val (box,join)=joinSheet(a,qr);box.setText("ParentPrivate928!");join.performClick()
            assertTrue("the QR Join is refused",shows(ShadowDialog.getLatestDialog().window!!.decorView,PASSWORD_CHECK_BUSY));assertNull(childPairing(a))
            assertEquals("no attempt counted yet",0,prefs.getInt("pw_failed_count",0))
            settle{!GuardEngine.passwordCheckRunning.get()}
            assertEquals("only the first role-sheet attempt",1,prefs.getInt("pw_failed_count",0))
            assertFalse(GuardEngine.isTemporarilyUnlocked(ctx));assertEquals("child",GuardEngine.getRole(ctx));assertNull(childPairing(a))
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
