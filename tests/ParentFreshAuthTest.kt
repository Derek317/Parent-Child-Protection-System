package app.family.igguard

import android.app.Activity
import android.app.Application
import android.app.KeyguardManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * F1: an open parent session is not enough for secrets and destructive actions; each needs a FRESH device authentication.
 * The device-credential screen is the Android launcher for sdk 28 here; tests answer it through onActivityResult.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class ParentFreshAuthTest {
    private val ctx:Application get()=RuntimeEnvironment.getApplication()
    private val prefs get()=LocalVault.preferences(ctx)
    private val secret="ParentPrivate928!"
    @Before fun reset() {
        ParentUiAuthFixture.autoApprove=true
        prefs.edit().clear().commit();ctx.deleteDatabase("family_evidence.db")
        shadowOf(ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).setIsDeviceSecure(true)
        GuardEngine.setRole(ctx,"parent");GuardEngine.setPin(ctx,secret)
    }
    private fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList()
    private fun call(a:MainActivity,name:String,vararg args:Any?) {
        val m=MainActivity::class.java.declaredMethods.first{it.name==name && it.parameterCount==args.size}.apply{isAccessible=true};m.invoke(a,*args)
    }
    private fun answer(a:MainActivity,ok:Boolean) {
        val m=MainActivity::class.java.getDeclaredMethod("onActivityResult",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Intent::class.java).apply{isAccessible=true}
        m.invoke(a,9031,if(ok)Activity.RESULT_OK else Activity.RESULT_CANCELED,null)
    }
    private fun drain(a:MainActivity){while(shadowOf(a).nextStartedActivityForResult!=null){}}
    /** True when a device-authentication screen was just launched. */
    private fun prompted(a:MainActivity):Boolean = shadowOf(a).nextStartedActivityForResult?.requestCode==9031
    private fun texts(d:android.app.Dialog?):List<String> = if(d==null)emptyList() else all(d.window!!.decorView).filterIsInstance<TextView>().map{it.text.toString()}
    private fun openKeySheet(a:MainActivity):android.app.Dialog {
        call(a,"navigateTo","settings")
        all(a.window.decorView).first{it.isClickable && it.contentDescription?.toString()?.startsWith("家長主密碼與金鑰")==true}.performClick()
        return ShadowDialog.getLatestDialog()
    }
    private fun button(d:android.app.Dialog,text:String)=all(d.window!!.decorView).filterIsInstance<Button>().first{it.text.toString()==text}
    private fun shownSecret():Boolean = ShadowDialog.getShownDialogs().any{it.isShowing && texts(it).any{t->t.contains(secret)}}

    @Test fun masterPasswordIsShownOnlyAfterAFreshAuthenticationEachTime() {
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        try {
            val sheet=openKeySheet(a)
            drain(a)
            button(sheet,"查看家長主密碼").performClick()
            assertTrue("a fresh device authentication must be requested",prompted(a))
            assertFalse(shownSecret())
            answer(a,false)
            assertFalse("cancelled authentication must not reveal the password",shownSecret())
            assertFalse("a cancelled fresh authentication keeps the existing session usable",all(a.window.decorView).any{it.tag=="parent_privacy_lock"})
            button(openKeySheet(a),"查看家長主密碼").performClick();assertTrue(prompted(a))
            answer(a,true)
            assertTrue(shownSecret())
            ShadowDialog.getShownDialogs().forEach{it.dismiss()}
            button(openKeySheet(a),"查看家長主密碼").performClick()
            assertTrue("an earlier success must not be reused",prompted(a))
            assertFalse(shownSecret())
        } finally {c.pause().stop().destroy()}
    }
    @Test fun routineKeyRotationAndRemoteResetNeedFreshAuthenticationBeforeAnythingIsQueued() {
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        val oldTopic=GuardEngine.getChannelTopic(ctx)
        try {
            val pending={FamilyEvidence(ctx).use{it.pendingCount(oldTopic)}}
            val sheet=openKeySheet(a)
            button(sheet,"例行更新金鑰（不能修復秘密外洩）").performClick()
            assertTrue(prompted(a));assertEquals(0,pending());assertFalse(prefs.contains("pending_rotation_json"))
            answer(a,false);assertEquals(0,pending())
            button(openKeySheet(a),"例行更新金鑰（不能修復秘密外洩）").performClick();assertTrue(prompted(a))
            answer(a,true);assertTrue("rotation command queued after authentication",pending()>0)
            // remote reset: confirmation dialog first, then authentication, then the signed command
            val before=pending()
            button(openKeySheet(a),"遠端解除子女保護並重設身份").performClick()
            val confirm=ShadowDialog.getLatestDialog() as android.app.AlertDialog
            confirm.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick().also{shadowOf(android.os.Looper.getMainLooper()).idle()}
            assertTrue(prompted(a));assertEquals(before,pending())
            answer(a,false);assertEquals(before,pending())
        } finally {c.pause().stop().destroy()}
    }
    @Test fun switchingAwayFromParentNeedsFreshAuthenticationAndLeavingIsExplicit() {
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        try {
            val publicKey=GuardEngine.relayPublicKey(ctx)
            call(a,"navigateTo","role");call(a,"selectEntryRole","child")
            drain(a)
            call(a,"continueEntryRole")
            val dialog=ShadowDialog.getLatestDialog() as? android.app.AlertDialog
            if(dialog!=null && dialog.isShowing)dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick().also{shadowOf(android.os.Looper.getMainLooper()).idle()}
            assertTrue("leaving the parent role must request fresh authentication",prompted(a))
            assertEquals("parent",GuardEngine.getRole(ctx))
            answer(a,false);assertEquals("parent",GuardEngine.getRole(ctx));assertEquals(secret,prefs.getString("parent_master_password",null))
            call(a,"continueEntryRole")
            (ShadowDialog.getLatestDialog() as? android.app.AlertDialog)?.takeIf{it.isShowing}?.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.performClick().also{shadowOf(android.os.Looper.getMainLooper()).idle()}
            assertTrue(prompted(a));answer(a,true)
            assertEquals("parent",GuardEngine.getRole(ctx))   // the release sheet comes first (see ChildReleaseTest)
            all(ShadowDialog.getLatestDialog().window!!.decorView).filterIsInstance<Button>().first{it.text.toString()=="繼續"}.performClick()
            repeat(150){shadowOf(android.os.Looper.getMainLooper()).idle();if(all(ShadowDialog.getLatestDialog().window!!.decorView).filterIsInstance<Button>().any{it.text.toString()=="我已記下，繼續"})return@repeat;Thread.sleep(100)}
            all(ShadowDialog.getLatestDialog().window!!.decorView).filterIsInstance<Button>().first{it.text.toString()=="我已記下，繼續"}.performClick()
            assertEquals("unconfigured",GuardEngine.getRole(ctx))
            assertFalse("leaving explicitly removes the parent password",prefs.contains("parent_master_password"))
            assertFalse(prefs.contains("parent_signing_public"))
            assertNull(GuardEngine.pairingBlock(ctx))
            assertNotEquals(publicKey,"")
        } finally {c.pause().stop().destroy()}
    }
    @Test fun compromiseRecoveryNeedsFreshAuthenticationBeforeAnyChange() {
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        try {
            val token=GuardEngine.getOrCreateFamilyToken(ctx)
            button(openKeySheet(a),"秘密可能外洩：重新安全配對").performClick()
            val recovery=ShadowDialog.getLatestDialog()
            drain(a)
            button(recovery,"只建立新配對（不刪除中繼資料）").performClick()
            (ShadowDialog.getLatestDialog() as android.app.AlertDialog).getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick().also{shadowOf(android.os.Looper.getMainLooper()).idle()}
            assertTrue(prompted(a));assertEquals(token,GuardEngine.getOrCreateFamilyToken(ctx))
            answer(a,false);assertEquals(token,GuardEngine.getOrCreateFamilyToken(ctx))
        } finally {c.pause().stop().destroy()}
    }

    // ---- S8: actions that delete data or export the family secret also need a fresh authentication ----
    private fun idle()=shadowOf(android.os.Looper.getMainLooper()).idle()
    private fun alertPositive(){(ShadowDialog.getLatestDialog() as android.app.AlertDialog).getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();idle()}
    private fun seed(){
        GuardEngine.recordParentAlert(ctx,JSONObject().put("type","alert").put("event_id","e1").put("sender","peer").put("text","body").put("reason","stranger_message").put("ts_ms",System.currentTimeMillis()),"relay:e1")
    }
    @Test fun deleteAllEvidenceNeedsFreshAuthentication() {
        seed()
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        try {
            call(a,"navigateTo","alerts");idle()
            repeat(50){if(all(a.window.decorView).any{it is Button && it.text.toString()=="刪除此家庭的全部證據"})return@repeat;idle();Thread.sleep(50)}
            drain(a)
            all(a.window.decorView).filterIsInstance<Button>().first{it.text.toString()=="刪除此家庭的全部證據"}.performClick();idle()
            alertPositive()
            assertTrue(prompted(a));assertEquals("nothing deleted before authentication",1,GuardEngine.getParentAlerts(ctx).size)
            answer(a,false);assertEquals(1,GuardEngine.getParentAlerts(ctx).size)
            all(a.window.decorView).filterIsInstance<Button>().first{it.text.toString()=="刪除此家庭的全部證據"}.performClick();idle()
            alertPositive();assertTrue(prompted(a));answer(a,true)
            assertTrue(GuardEngine.getParentAlerts(ctx).isEmpty())
        } finally {c.pause().stop().destroy()}
    }
    @Test fun retentionChangeAndLegacyAdoptionNeedFreshAuthentication() {
        seed()
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        try {
            val scope=GuardEngine.getChannelTopic(ctx)
            call(a,"showRetentionSettings",MainActivity::class.java.getDeclaredMethod("currentTheme").apply{isAccessible=true}.invoke(a))
            drain(a)
            val retention=ShadowDialog.getLatestDialog()
            button(retention,"新記錄保留 7 天").performClick();idle()
            alertPositive()
            assertTrue(prompted(a));assertEquals(30,FamilyEvidence(ctx).use{it.retentionPreview(scope).retentionDays})
            answer(a,false);assertEquals(30,FamilyEvidence(ctx).use{it.retentionPreview(scope).retentionDays})
            button(retention,"新記錄保留 7 天").performClick();idle()
            alertPositive();assertTrue(prompted(a));answer(a,true)
            assertEquals(7,FamilyEvidence(ctx).use{it.retentionPreview(scope).retentionDays})
        } finally {c.pause().stop().destroy()}
    }
    @Test fun theTrustedListAcceptsFullWidthCommasAsSeparators() {
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        try {
            call(a,"navigateTo","family")
            drain(a)
            all(a.window.decorView).filterIsInstance<android.widget.EditText>().first{it.hint?.toString()=="輸入好友帳號，以逗號分隔"}.setText("alice，bob")
            all(a.window.decorView).filterIsInstance<Button>().first{it.text.toString()=="儲存並加密同步"}.performClick();idle()
            assertEquals(setOf("alice","bob"),GuardEngine.getTrustedHandles(ctx))
        } finally {c.pause().stop().destroy()}
    }
    @Test fun addingAChildWhileOneIsPairedWarnsFirst() {
        prefs.edit().putString("pinned_child_key",FamilySecurity.newSigningIdentity().publicKey).commit()
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        try {
            call(a,"navigateTo","family")
            drain(a)
            fun addChild()=all(a.window.decorView).first{it.isClickable && it.contentDescription?.toString()?.startsWith("加入子女")==true}.performClick().also{idle()}
            addChild()
            assertFalse("the warning comes before any authentication",prompted(a))
            val warning=ShadowDialog.getLatestDialog() as android.app.AlertDialog
            assertTrue(texts(warning).joinToString(),texts(warning).any{it.contains("解除並停止傳送") && it.contains("released and stop sending")})
            warning.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick();idle()
            assertFalse(prompted(a))
            addChild();alertPositive()
            assertTrue("accepted: the usual fresh authentication, then the QR",prompted(a))
        } finally {c.pause().stop().destroy()}
    }
    @Test fun addingAChildAndGeneratingAnUnlockCodeNeedFreshAuthentication() {
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        try {
            call(a,"navigateTo","family")
            drain(a)
            fun pairingSheet()=ShadowDialog.getShownDialogs().any{it.isShowing && texts(it).any{t->t.contains("Add a child")}}
            all(a.window.decorView).first{it.isClickable && it.contentDescription?.toString()?.startsWith("加入子女")==true}.performClick();idle()
            assertTrue("the QR sheet needs a fresh device authentication",prompted(a));assertFalse(pairingSheet())
            answer(a,false);assertFalse("no QR without it",pairingSheet())
            all(a.window.decorView).first{it.isClickable && it.contentDescription?.toString()?.startsWith("加入子女")==true}.performClick();idle()
            assertTrue(prompted(a));answer(a,true);idle()
            assertTrue(pairingSheet())
            ShadowDialog.getShownDialogs().forEach{it.dismiss()}
            // unlock code: no code is generated or pushed before authentication
            prefs.edit().remove("synced_15m_code").commit()
            all(a.window.decorView).first{it.isClickable && it.contentDescription?.toString()?.startsWith("15 分鐘臨時解鎖")==true}.performClick();idle()
            assertTrue(prompted(a));assertNull(prefs.getString("synced_15m_code",null))
            answer(a,true)
            assertNotNull(prefs.getString("synced_15m_code",null))
        } finally {c.pause().stop().destroy()}
    }

    @Test fun legacyRetentionAdoptionNeedsFreshAuthentication() {
        val scope=GuardEngine.getChannelTopic(ctx)
        FamilyEvidence(ctx).use{it.append(scope,"old",JSONObject().put("type","alert").put("event_id","old").put("sender","peer").put("text","old body").put("reason","stranger_message").put("ts_ms",System.currentTimeMillis()-90*24*3600_000L),legacy=true)}
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        try {
            call(a,"showRetentionSettings",MainActivity::class.java.getDeclaredMethod("currentTheme").apply{isAccessible=true}.invoke(a))
            drain(a)
            val retention=ShadowDialog.getLatestDialog()
            button(retention,"預覽後，將期限套用至舊記錄").performClick();idle()
            alertPositive();assertTrue(prompted(a))
            assertEquals("the legacy record is untouched before authentication",1,FamilyEvidence(ctx).use{it.retentionPreview(scope).legacyCount})
            answer(a,true)
            assertEquals(0,FamilyEvidence(ctx).use{it.retentionPreview(scope).legacyCount})
        } finally {c.pause().stop().destroy()}
    }
}
