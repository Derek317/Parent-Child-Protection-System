package app.family.igguard

import android.app.Application
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

/** F1(b): nothing from the archive, evidence or settings renders before parent authentication; copies are marked sensitive. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class ParentLockedViewsTest {
    private val ctx:Application get()=RuntimeEnvironment.getApplication()
    private val marker="PRIVATE-EVIDENCE-MARKER"
    @Before fun reset() {
        ParentUiAuthFixture.autoApprove=false
        LocalVault.preferences(ctx).edit().clear().commit();ctx.deleteDatabase("family_evidence.db")
        GuardEngine.setRole(ctx,"parent");GuardEngine.setPin(ctx,"ParentPrivate928!")
        GuardEngine.setChildSelfHandle(ctx,"child.handle")
        GuardEngine.setTrustedHandles(ctx,listOf("trusted_marker_friend"))
        GuardEngine.recordParentAlert(ctx,JSONObject().put("type","alert").put("event_id","e1").put("sender","sender-marker").put("text",marker)
            .put("reason","stranger_message").put("ts_ms",System.currentTimeMillis()),"relay:e1")
    }
    private fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList()
    private fun text(v:View)=all(v).filterIsInstance<TextView>().joinToString("\n"){it.text.toString()}
    private fun field(a:MainActivity,name:String)=MainActivity::class.java.getDeclaredField(name).apply{isAccessible=true}
    private fun render(a:MainActivity){MainActivity::class.java.getDeclaredMethod("renderUi",Boolean::class.javaPrimitiveType).apply{isAccessible=true}.invoke(a,false)}
    private val leaks=listOf(marker,"sender-marker","trusted_marker_friend","child.handle","ParentPrivate928!")
    private fun assertLocked(a:MainActivity,what:String) {
        val shown=text(a.window.decorView)
        assertTrue("$what: lock screen expected",all(a.window.decorView).any{it.tag=="parent_privacy_lock"})
        for(leak in leaks)assertFalse("$what leaked '$leak'",shown.contains(leak))
        assertFalse("$what: navigation must not be present",all(a.window.decorView).any{it.tag=="nav_alerts"})
        assertTrue("$what: no dialog may be showing",ShadowDialog.getShownDialogs().none{it.isShowing})
    }

    @Test fun everyParentSectionRendersOnlyTheLockBeforeAuthentication() {
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        try {
            for(section in listOf("overview","alerts","family","settings","role","permissions","unlock","parent_setup","child_setup","bogus")) {
                field(a,"section").set(a,section);render(a);assertLocked(a,"section $section")
            }
        } finally {c.pause().stop().destroy()}
    }
    @Test fun sheetsAlertsAndEvidenceNotificationsCannotBypassTheLock() {
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        try {
            val showSheet=MainActivity::class.java.declaredMethods.first{it.name=="showSheet"}.apply{isAccessible=true}
            val sheet:(android.widget.LinearLayout,android.app.Dialog)->Unit = { box:android.widget.LinearLayout, _:android.app.Dialog -> box.addView(TextView(ctx).apply{text=marker}) }
            showSheet.invoke(a,"證據",sheet)
            assertLocked(a,"sheet")
            val details=MainActivity::class.java.declaredMethods.first{it.name=="showAlertDetails"}.apply{isAccessible=true}
            val stored=GuardEngine.getParentAlerts(ctx).first()
            details.invoke(a,stored,MainActivity::class.java.getDeclaredMethod("currentTheme").apply{isAccessible=true}.invoke(a))
            assertLocked(a,"evidence details")
            val incoming=Intent("app.family.igguard.OPEN_EVIDENCE").putExtra("evidence_id",stored.optString("_id"))
            MainActivity::class.java.getDeclaredMethod("onNewIntent",Intent::class.java).apply{isAccessible=true}.invoke(a,incoming)
            assertLocked(a,"evidence notification")
            MainActivity::class.java.getDeclaredMethod("onNewIntent",Intent::class.java).apply{isAccessible=true}.invoke(a,Intent(Intent.ACTION_VIEW,android.net.Uri.parse("igguard://pair?token=01234567-89ab-cdef-0123-456789abcdef&pw=x")))
            assertLocked(a,"pairing link")
        } finally {c.pause().stop().destroy()}
    }
    @Test fun failedOrStaleAuthenticationKeepsTheLockAndSectionsStayHidden() {
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        try {
            val complete=MainActivity::class.java.getDeclaredMethod("completeParentAuthentication",Boolean::class.javaPrimitiveType).apply{isAccessible=true}
            field(a,"parentAuthSession").set(a,GuardEngine.captureRelaySession(ctx));complete.invoke(a,false)
            field(a,"section").set(a,"alerts");render(a);assertLocked(a,"after failed authentication")
            field(a,"parentAuthSession").set(a,null);complete.invoke(a,true)
            assertLocked(a,"authentication without a pending request")
            field(a,"parentAuthSession").set(a,GuardEngine.captureRelaySession(ctx));complete.invoke(a,true)
            assertFalse("after real success the lock is gone",all(a.window.decorView).any{it.tag=="parent_privacy_lock"})
            assertTrue(all(a.window.decorView).any{it.tag=="nav_alerts"})
        } finally {c.pause().stop().destroy()}
    }
    /** Copying the invite and generating an unlock code ask for a fresh device authentication (see ParentFreshAuthTest). */
    private fun reauthenticate(a:MainActivity) {
        MainActivity::class.java.getDeclaredMethod("onActivityResult",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Intent::class.java).apply{isAccessible=true}.invoke(a,9031,android.app.Activity.RESULT_OK,null)
    }
    private fun clipboardExtras():android.os.PersistableBundle? =
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClipDescription?.extras
    private fun unlockedActivity():org.robolectric.android.controller.ActivityController<MainActivity> {
        ParentUiAuthFixture.autoApprove=true
        shadowOf(ctx.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager).setIsDeviceSecure(true)
        return Robolectric.buildActivity(MainActivity::class.java).setup()
    }
    @Test fun copiedMessageTextInvitesAndUnlockCodesAreMarkedSensitive() {
        val c=unlockedActivity();val a=c.get()
        try {
            val clip=ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            fun assertSensitive(label:String,contains:String) {
                assertTrue("$label: clipboard must carry text",clip.primaryClip!!.getItemAt(0).text.toString().contains(contains))
                assertTrue("$label: clip must be flagged sensitive",clipboardExtras()?.getBoolean("android.content.extra.IS_SENSITIVE")==true)
            }
            // evidence text
            val stored=GuardEngine.getParentAlerts(ctx).first()
            MainActivity::class.java.declaredMethods.first{it.name=="showAlertDetails"}.apply{isAccessible=true}
                .invoke(a,stored,MainActivity::class.java.getDeclaredMethod("currentTheme").apply{isAccessible=true}.invoke(a))
            val details=ShadowDialog.getLatestDialog()
            all(details.window!!.decorView).filterIsInstance<Button>().first{it.text.toString()=="複製完整記錄"}.performClick()
            assertSensitive("message text",marker)
            clip.setPrimaryClip(android.content.ClipData.newPlainText("x","y"))
            details.dismiss()
            // invite
            MainActivity::class.java.getDeclaredMethod("navigateTo",String::class.java).apply{isAccessible=true}.invoke(a,"family")
            all(a.window.decorView).first{it.isClickable && it.contentDescription?.toString()?.startsWith("邀請子女配對")==true}.performClick()
            val invite=ShadowDialog.getLatestDialog()
            all(invite.window!!.decorView).filterIsInstance<Button>().first{it.text.toString()=="複製配對訊息"}.performClick()
            reauthenticate(a)
            assertSensitive("invite","igguard://pair")
            clip.setPrimaryClip(android.content.ClipData.newPlainText("x","y"))
            // 15-minute unlock code
            all(a.window.decorView).first{it.isClickable && it.contentDescription?.toString()?.startsWith("15 分鐘臨時解鎖")==true}.performClick()
            reauthenticate(a)
            val code=ShadowDialog.getLatestDialog()
            all(code.window!!.decorView).filterIsInstance<Button>().first{it.text.toString()=="複製解鎖碼"}.performClick()
            assertSensitive("unlock code","")
            assertTrue(clip.primaryClip!!.getItemAt(0).text.toString().length==6)
        } finally {c.pause().stop().destroy()}
    }
}
