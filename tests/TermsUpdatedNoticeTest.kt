package app.family.igguard

import android.app.Application
import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import org.json.JSONArray
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

/** S6: people who accepted an earlier revision are told once that the Terms changed; nothing is re-asked or locked. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class TermsUpdatedNoticeTest {
    private val ctx:Application get()=RuntimeEnvironment.getApplication()
    private val consent get()=ctx.getSharedPreferences("ig_guard_consent",Context.MODE_PRIVATE)
    @Before fun reset(){consent.edit().clear().commit();LocalVault.preferences(ctx).edit().clear().commit();ctx.deleteDatabase("family_evidence.db")}
    private fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList()
    private fun acceptOlderRevision(version:String="2026-11-18-v2") {
        val record=JSONObject().put("accepted",true).put("version",version).put("sha256","a".repeat(64))
            .put("accepted_at",System.currentTimeMillis()).put("app_version","4.9-beta.3").put("acknowledgments",JSONArray(AppConsent.acknowledgments.toList()))
        consent.edit().putString("receipt",LocalVault.seal(ctx,"consent_receipt",record.toString())).commit()
    }
    private fun dialogTexts():List<String>{
        val d=ShadowDialog.getLatestDialog()?:return emptyList()
        return all(d.window!!.decorView).filterIsInstance<TextView>().map{it.text.toString()}
    }
    @Test fun olderReceiptShowsAOneTimeDismissibleNoticeAndStaysAccepted() {
        acceptOlderRevision()
        assertTrue(AppConsent.hasAccepted(ctx))
        val first=Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            assertTrue(dialogTexts().any{it.contains("條款已更新")||it.contains("使用條款已更新")})
            assertTrue("no re-consent is requested",AppConsent.hasAccepted(ctx))
            val view=all(ShadowDialog.getLatestDialog().window!!.decorView).filterIsInstance<Button>().first{it.text.toString()=="查看條款"}
            view.performClick();shadowOf(Looper.getMainLooper()).idle()
            assertTrue("the link shows the current text with the original receipt",dialogTexts().any{it.contains("2026-11-18-v2")})
        } finally {first.pause().stop().destroy()}
        ShadowDialog.reset()
        val second=Robolectric.buildActivity(MainActivity::class.java).setup()
        try { assertFalse("shown only once",dialogTexts().any{it.contains("條款已更新")||it.contains("使用條款已更新")}) } finally {second.pause().stop().destroy()}
    }
    /** The real 6.0.2 upgrade: a receipt for 6.0/6.0.1's revision gets the notice once and is never asked to consent again. */
    @Test fun v8ReceiptShowsTheNoticeOnceWithoutNewConsent() {
        acceptOlderRevision("2026-11-24-v8")
        assertNotEquals("2026-11-24-v8",AppConsent.VERSION)
        val first=Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            assertTrue(dialogTexts().any{it.contains("條款已更新")||it.contains("使用條款已更新")})
            assertNull("no consent page",first.get().findViewById<View>(android.R.id.content).findViewWithTag<Button>("consent_accept"))
            assertTrue(AppConsent.hasAccepted(ctx))
            assertEquals("the original receipt is kept","2026-11-24-v8",AppConsent.receipt(ctx)!!.version)
        } finally {first.pause().stop().destroy()}
        ShadowDialog.reset()
        val second=Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            assertFalse("shown only once",dialogTexts().any{it.contains("條款已更新")||it.contains("使用條款已更新")})
            assertNull(second.get().findViewById<View>(android.R.id.content).findViewWithTag<Button>("consent_accept"))
            assertTrue(AppConsent.hasAccepted(ctx))
        } finally {second.pause().stop().destroy()}
    }
    @Test fun currentReceiptShowsNoNotice() {
        AppConsent.accept(ctx,AppConsent.acknowledgments)
        val c=Robolectric.buildActivity(MainActivity::class.java).setup()
        try { assertFalse(dialogTexts().any{it.contains("條款已更新")||it.contains("使用條款已更新")}) } finally {c.pause().stop().destroy()}
    }
}
