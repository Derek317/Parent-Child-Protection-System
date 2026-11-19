package app.family.igguard

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import org.json.JSONObject
import org.junit.After
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

/** S2/S3: the parent hears about a child leaving the family or stopping collection, before transfer stops. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class ChildNoticeTest {
    private val ctx:Application get()=RuntimeEnvironment.getApplication()
    private val prefs get()=LocalVault.preferences(ctx)
    @Before fun setUp() {
        prefs.edit().clear().commit();ctx.deleteDatabase("family_evidence.db")
        GuardEngine.setRole(ctx,"parent");GuardEngine.setPin(ctx,"ParentPrivate928!")
        val invite=GuardEngine.parsePairingString(GuardEngine.buildPairingInviteText(ctx))!!
        GuardEngine.setRole(ctx,"unconfigured")
        prefs.edit().remove("parent_master_password").remove("parent_share_verifier").remove("parent_signing_public").commit()
        assertTrue(GuardEngine.applyPairing(ctx,invite))
    }
    @After fun clear(){GuardEngine.beforeFamilySwitch=null}
    private fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList()
    private fun idle()=shadowOf(Looper.getMainLooper()).idle()
    private fun waitFor(what:String,cond:()->Boolean){repeat(150){idle();if(cond())return;Thread.sleep(100)};fail("timed out: $what")}
    private fun call(a:MainActivity,name:String,vararg args:Any?){MainActivity::class.java.declaredMethods.first{it.name==name && it.parameterCount==args.size}.apply{isAccessible=true}.invoke(a,*args)}
    private fun queued():List<JSONObject>{
        val token=GuardEngine.getOrCreateFamilyToken(ctx)
        return FamilyEvidence(ctx).use{it.due(GuardEngine.getChannelTopic(ctx),Long.MAX_VALUE)}.mapNotNull{GuardEngine.decryptPayload(token,it.wire)}
    }
    @Test fun childLeavingTheFamilyWithThePasswordNotifiesTheParentFirst() {
        var seen:List<JSONObject> = emptyList()
        GuardEngine.beforeFamilySwitch={seen=queued()}
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        try {
            call(a,"navigateTo","role");call(a,"selectEntryRole","parent");call(a,"continueEntryRole")
            val sheet=ShadowDialog.getLatestDialog().window!!.decorView
            all(sheet).filterIsInstance<EditText>().first{it.tag=="role_password"}.setText("ParentPrivate928!")
            all(sheet).filterIsInstance<Button>().first{it.text.toString()=="驗證並繼續"}.performClick()
            waitFor("role change"){GuardEngine.getRole(ctx)=="unconfigured"}
            val notice=seen.filter{it.optString("reason")=="tamper_attempt"}
            assertEquals("a notice must be queued before the phone becomes unconfigured",1,notice.size)
            assertTrue(notice[0].optString("text").contains("離開"))
        } finally {c.pause().stop().destroy()}
    }
    @Test fun wrongPasswordSendsNoNoticeAndKeepsTheRole() {
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        try {
            call(a,"navigateTo","role");call(a,"selectEntryRole","parent");call(a,"continueEntryRole")
            val sheet=ShadowDialog.getLatestDialog().window!!.decorView
            all(sheet).filterIsInstance<EditText>().first{it.tag=="role_password"}.setText("WrongPassword928!")
            all(sheet).filterIsInstance<Button>().first{it.text.toString()=="驗證並繼續"}.performClick();idle()
            assertEquals("child",GuardEngine.getRole(ctx));assertTrue(queued().none{it.optString("reason")=="tamper_attempt"})
        } finally {c.pause().stop().destroy()}
    }
    @Test fun withdrawingCollectionSendsOneContentFreeNoticeBeforeTransferStops() {
        var seen:List<JSONObject> = emptyList()
        GuardEngine.beforeFamilySwitch={seen=queued()}
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        try {
            call(a,"showCapturePrivacy",MainActivity::class.java.getDeclaredMethod("currentTheme").apply{isAccessible=true}.invoke(a))
            all(ShadowDialog.getLatestDialog().window!!.decorView).filterIsInstance<Button>().first{it.text.toString()=="停止擷取及傳送，並捨棄待傳內容"}.performClick()
            waitFor("withdrawal"){!GuardEngine.collectionAllowed(ctx)}
            val notice=seen.filter{it.optString("reason")=="tamper_attempt"}
            assertEquals(1,notice.size)
            assertTrue(notice[0].optString("text").contains("停止擷取"))
            assertFalse(GuardEngine.transferAllowed(ctx))
        } finally {c.pause().stop().destroy()}
    }
}
