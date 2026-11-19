package app.family.igguard

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.Button
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class ReviewNitsTest {
    private val ctx:Application get()=RuntimeEnvironment.getApplication()
    private val prefs get()=LocalVault.preferences(ctx)
    @Before fun reset(){ParentUiAuthFixture.autoApprove=true;prefs.edit().clear().commit();ctx.deleteDatabase("family_evidence.db")}
    private fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList()
    private fun idle()=shadowOf(android.os.Looper.getMainLooper()).idle()

    @Test fun anUnlockCodeRestoredFromSavedStateDoesNotReappear() {
        GuardEngine.setRole(ctx,"parent")
        val state=android.os.Bundle()
        val c=Robolectric.buildActivity(MainActivity::class.java).setup()
        MainActivity::class.java.getDeclaredField("displayed15MinCode").apply{isAccessible=true}.set(c.get(),"ABC234")
        c.saveInstanceState(state);c.pause().stop().destroy()
        val restored=Robolectric.buildActivity(MainActivity::class.java).setup(state)
        try { assertEquals("",MainActivity::class.java.getDeclaredField("displayed15MinCode").apply{isAccessible=true}.get(restored.get())) }
        finally {restored.pause().stop().destroy()}
    }
    @Test fun perRecordDeleteConfirmationIsOverlayGuardedButStaysOnTheOpenSession() {
        GuardEngine.setRole(ctx,"parent")
        GuardEngine.recordParentAlert(ctx,JSONObject().put("type","alert").put("event_id","e1").put("sender","peer").put("text","body").put("reason","stranger_message").put("ts_ms",System.currentTimeMillis()),"relay:e1")
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        try {
            val stored=GuardEngine.getParentAlerts(ctx).first()
            MainActivity::class.java.declaredMethods.first{it.name=="showAlertDetails"}.apply{isAccessible=true}.invoke(a,stored,MainActivity::class.java.getDeclaredMethod("currentTheme").apply{isAccessible=true}.invoke(a))
            all(ShadowDialog.getLatestDialog().window!!.decorView).filterIsInstance<Button>().first{it.text.toString()=="刪除此記錄"}.performClick();idle()
            val confirm=ShadowDialog.getLatestDialog() as android.app.AlertDialog
            assertTrue(confirm.getButton(android.app.AlertDialog.BUTTON_POSITIVE).filterTouchesWhenObscured)
            confirm.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();idle()
            assertTrue("deleted without a fresh authentication",GuardEngine.getParentAlerts(ctx).isEmpty())
        } finally {c.pause().stop().destroy()}
    }
    @Test fun resumeCollectionConfirmationIsOverlayGuarded() {
        GuardEngine.setRole(ctx,"child");GuardEngine.withdrawCollection(ctx,false)
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        try {
            MainActivity::class.java.declaredMethods.first{it.name=="showCapturePrivacy"}.apply{isAccessible=true}.invoke(a,MainActivity::class.java.getDeclaredMethod("currentTheme").apply{isAccessible=true}.invoke(a))
            val resume=all(ShadowDialog.getLatestDialog().window!!.decorView).filterIsInstance<Button>().first{it.text.toString()=="清除舊待傳記錄並重新開始傳入警報"}
            assertTrue(resume.filterTouchesWhenObscured)
        } finally {c.pause().stop().destroy()}
    }
}
