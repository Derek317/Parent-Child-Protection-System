package app.family.igguard

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
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
import java.util.UUID

/** F9(a): consent and confirmation controls ignore touches delivered while another app's window covers them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class ObscuredTouchTest {
    private val ctx:Application get()=RuntimeEnvironment.getApplication()
    private val prefs get()=LocalVault.preferences(ctx)
    @Before fun reset() {
        ParentUiAuthFixture.autoApprove=true
        prefs.edit().clear().commit();ctx.deleteDatabase("family_evidence.db")
        ctx.getSharedPreferences("ig_guard_consent",android.content.Context.MODE_PRIVATE).edit().clear().commit()
        AppConsent.accept(ctx,AppConsent.acknowledgments)
    }
    private fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList()
    private fun button(root:View,text:String):Button = all(root).filterIsInstance<Button>().first{it.text.toString()==text}
    private fun assertGuarded(view:View,what:String)=assertTrue("$what must set filterTouchesWhenObscured",view.filterTouchesWhenObscured)
    private fun idle()=shadowOf(android.os.Looper.getMainLooper()).idle()
    private fun call(a:MainActivity,name:String,vararg args:Any?){MainActivity::class.java.declaredMethods.first{it.name==name && it.parameterCount==args.size}.apply{isAccessible=true}.invoke(a,*args)}
    private fun theme(a:MainActivity)=MainActivity::class.java.getDeclaredMethod("currentTheme").apply{isAccessible=true}.invoke(a)
    private fun dialogButton(positive:Boolean)=(ShadowDialog.getLatestDialog() as android.app.AlertDialog).getButton(if(positive)android.app.AlertDialog.BUTTON_POSITIVE else android.app.AlertDialog.BUTTON_NEGATIVE)

    @Test fun consentAcknowledgmentsAndButtonsAreGuarded() {
        ctx.getSharedPreferences("ig_guard_consent",android.content.Context.MODE_PRIVATE).edit().clear().commit()
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val root=c.get().findViewById<View>(android.R.id.content)
        try {
            assertGuarded(root.findViewWithTag("consent_accept"),"consent accept")
            assertFalse("decline stays usable under overlays",root.findViewWithTag<View>("consent_decline").filterTouchesWhenObscured)
        } finally {c.pause().stop().destroy()}
    }
    @Test fun pairingConfirmationIsGuarded() {
        val token=UUID.randomUUID().toString()
        val c=Robolectric.buildActivity(MainActivity::class.java,Intent(Intent.ACTION_VIEW,Uri.parse("igguard://pair?token=$token&pw=${FamilySecurity.encode("ParentPassword928!")}"))).setup()
        try { assertGuarded(button(ShadowDialog.getLatestDialog().window!!.decorView,"確認配對並切換為子女手機"),"pairing confirm") } finally {c.pause().stop().destroy()}
    }
    @Test fun twoWayCaptureConfirmationsAreGuarded() {
        GuardEngine.setRole(ctx,"child");prefs.edit().putString("capture_policy_id",UUID.randomUUID().toString()).commit()
        val child=Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            call(child.get(),"showFullCaptureNotice",theme(child.get()))
            assertGuarded(button(ShadowDialog.getLatestDialog().window!!.decorView,"我已了解，啟用雙向擷取"),"child capture confirm")
            assertFalse("non-critical buttons stay usable under overlays",button(ShadowDialog.getLatestDialog().window!!.decorView,"暫不啟用").filterTouchesWhenObscured)
        } finally {child.pause().stop().destroy()}
        GuardEngine.setRole(ctx,"parent")
        val parent=Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            call(parent.get(),"showFullCaptureSetup",theme(parent.get()))
            assertGuarded(button(ShadowDialog.getLatestDialog().window!!.decorView,"傳送雙向擷取設定"),"parent capture confirm")
        } finally {parent.pause().stop().destroy()}
    }
    @Test fun remoteResetControlsAreGuarded() {
        GuardEngine.setRole(ctx,"parent");GuardEngine.setPin(ctx,"ParentPrivate928!")
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        try {
            call(a,"navigateTo","settings")
            all(a.window.decorView).first{it.isClickable && it.contentDescription?.toString()?.startsWith("家長主密碼與金鑰")==true}.performClick()
            val sheet=ShadowDialog.getLatestDialog().window!!.decorView
            assertGuarded(button(sheet,"遠端解除子女保護並重設身份"),"remote reset entry")
            button(sheet,"遠端解除子女保護並重設身份").performClick();idle()
            assertGuarded(dialogButton(true),"remote reset confirm")
        } finally {c.pause().stop().destroy()}
    }
    @Test fun roleSwitchConfirmationsAreGuarded() {
        GuardEngine.setRole(ctx,"parent");GuardEngine.setPin(ctx,"ParentPrivate928!")
        val parent=Robolectric.buildActivity(MainActivity::class.java).setup();val a=parent.get()
        try {
            call(a,"navigateTo","role");call(a,"selectEntryRole","child");call(a,"renderUi",false)
            assertFalse("ordinary navigation is not filtered",all(a.window.decorView).first{it.tag=="continue_role"}.filterTouchesWhenObscured)
            call(a,"continueEntryRole");idle()
            assertGuarded(dialogButton(true),"leave parent confirm")
        } finally {parent.pause().stop().destroy()}
        GuardEngine.setRole(ctx,"child")
        val child=Robolectric.buildActivity(MainActivity::class.java).setup();val b=child.get()
        try {
            call(b,"navigateTo","role");call(b,"selectEntryRole","parent")
            call(b,"continueEntryRole")
            assertGuarded(button(ShadowDialog.getLatestDialog().window!!.decorView,"驗證並繼續"),"child role switch confirm")
        } finally {child.pause().stop().destroy()}
    }

    @Test fun scoresConsentAndHoldReleaseAreGuarded() {
        val tiny="tiny model file for tests".toByteArray()
        val sha=java.security.MessageDigest.getInstance("SHA-256").digest(tiny).joinToString(""){"%02x".format(it)}
        ctx.getSharedPreferences("ig_guard_scores_consent",android.content.Context.MODE_PRIVATE).edit().clear().commit()
        RiskModelStore.directory(ctx).deleteRecursively()
        GuardEngine.setFamilyToken(ctx,UUID.randomUUID().toString());GuardEngine.setRole(ctx,"child")
        val id=UUID.randomUUID().toString()
        prefs.edit().putString("parent_command_public_key",FamilySecurity.newSigningIdentity().publicKey).putString("capture_policy_id",id).putString("capture_local_ack_id",id).commit()
        RiskModelStore.memoryProbe={8L shl 30};RiskModelStore.abiProbe={true}
        RiskModelStore.expectation=RiskModelStore.Expectation(tiny.size.toLong(),sha)
        try {
            assertEquals(RiskImportResult.OK,RiskModelStore.importModel(ctx,java.io.ByteArrayInputStream(tiny),{},tiny.size.toLong(),sha))
            RiskLocal.receivePolicy(ctx,UUID.randomUUID().toString(),true,ScoresMethod.UPLOAD)
            var c=Robolectric.buildActivity(MainActivity::class.java).setup()
            try {
                UiSupport.nav(c.get(),"scores_consent")
                assertGuarded(all(c.get().window.decorView).single{it.tag=="scores_agree"},"scores agree")
                assertFalse("not now stays usable under overlays",all(c.get().window.decorView).single{it.tag=="scores_not_now"}.filterTouchesWhenObscured)
            } finally {c.pause().stop().destroy()}
            assertTrue(RiskLocal.accept(ctx));RiskLocal.receivePolicy(ctx,UUID.randomUUID().toString(),false)
            c=Robolectric.buildActivity(MainActivity::class.java).setup()
            try { assertGuarded(all(c.get().window.decorView).single{it.tag=="scores_hold_agree"},"scores hold allow") } finally {c.pause().stop().destroy()}
        } finally {
            RiskModelStore.abiProbe={android.os.Build.SUPPORTED_ABIS.contains("arm64-v8a")}
            RiskModelStore.expectation=RiskModelStore.Expectation(RiskModelSpec.FILE_BYTES,RiskModelSpec.FILE_SHA256)
            RiskModelStore.memoryProbe={c->val i=android.app.ActivityManager.MemoryInfo();c.getSystemService(android.app.ActivityManager::class.java)?.getMemoryInfo(i);i.totalMem}
            RiskModelStore.directory(ctx).deleteRecursively()
        }
    }
    @Test fun aFilteredTouchExplainsWhyAndOnlyCriticalControlsFilter() {
        ctx.getSharedPreferences("ig_guard_consent",android.content.Context.MODE_PRIVATE).edit().clear().commit()
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val root=c.get().findViewById<View>(android.R.id.content)
        try {
            val accept=root.findViewWithTag<Button>("consent_accept")
            val props=arrayOf(MotionEvent.PointerProperties().apply{id=0;toolType=MotionEvent.TOOL_TYPE_FINGER})
            val coords=arrayOf(MotionEvent.PointerCoords().apply{x=1f;y=1f})
            val event=MotionEvent.obtain(0,0,MotionEvent.ACTION_DOWN,1,props,coords,0,0,1f,1f,0,0,0,MotionEvent.FLAG_WINDOW_IS_OBSCURED)
            assertFalse(accept.onFilterTouchEventForSecurity(event))
            assertTrue(org.robolectric.shadows.ShadowToast.getTextOfLatestToast().contains("另一個 App"))
            val plain=MotionEvent.obtain(0,0,MotionEvent.ACTION_DOWN,1f,1f,0)
            assertTrue("an unobscured touch is accepted",accept.onFilterTouchEventForSecurity(plain))
        } finally {c.pause().stop().destroy()}
    }
    @Test fun criticalDialogsAreGuardedAndOrdinaryOnesAreNot() {
        GuardEngine.setRole(ctx,"parent");GuardEngine.setPin(ctx,"ParentPrivate928!")
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        try {
            val ordinary=MainActivity::class.java.getDeclaredMethod("alert",Boolean::class.javaPrimitiveType).apply{isAccessible=true}
            (ordinary.invoke(a,false) as android.app.AlertDialog.Builder).setMessage("x").setPositiveButton("ok",null).show();idle()
            assertFalse(dialogButton(true).filterTouchesWhenObscured)
        } finally {c.pause().stop().destroy()}
    }
}
