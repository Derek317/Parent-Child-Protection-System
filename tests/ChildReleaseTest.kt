package app.family.igguard

import android.app.Activity
import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Looper
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
import java.util.UUID

/** S1: leaving the parent role or recovering from compromise must not strand the paired child. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class ChildReleaseTest {
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
    private fun call(a:MainActivity,name:String,vararg args:Any?){MainActivity::class.java.declaredMethods.first{it.name==name && it.parameterCount==args.size}.apply{isAccessible=true}.invoke(a,*args)}
    private fun idle()=shadowOf(Looper.getMainLooper()).idle()
    private fun answer(a:MainActivity){
        MainActivity::class.java.getDeclaredMethod("onActivityResult",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Intent::class.java).apply{isAccessible=true}.invoke(a,9031,Activity.RESULT_OK,null)
    }
    private fun waitFor(what:String,cond:()->Boolean){repeat(150){idle();if(cond())return;Thread.sleep(100)};fail("timed out: $what")}
    private fun latest():android.app.Dialog=ShadowDialog.getLatestDialog()
    private fun button(text:String)=all(latest().window!!.decorView).filterIsInstance<Button>().firstOrNull{it.text.toString()==text}
    private fun texts()=all(latest().window!!.decorView).filterIsInstance<TextView>().map{it.text.toString()}
    private fun confirmLeave(a:MainActivity) {
        call(a,"navigateTo","role");call(a,"selectEntryRole","child");call(a,"continueEntryRole");idle()
        (latest() as android.app.AlertDialog).getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();idle()
        answer(a)
    }
    private fun pendingWires(topic:String)=FamilyEvidence(ctx).use{it.due(topic,Long.MAX_VALUE,"parent")}.map{it.wire}
    private fun deliverToChild(wires:List<String>) {
        for(wire in wires){
            val line=JSONObject().put("event","message").put("id",UUID.randomUUID().toString()).put("message",wire)
            GuardEngine.processIncomingRelayLine(ctx,line.toString(),"child",null)
        }
    }
    private fun foreign()=FamilySecurity.Invite(UUID.randomUUID().toString(),FamilySecurity.passwordVerifier("OtherParent928!"),FamilySecurity.newSigningIdentity().publicKey)

    @Test fun leavingTheParentRoleSendsAResetShowsThePasswordAndFreesTheChild() {
        val invite=GuardEngine.parsePairingString(GuardEngine.buildPairingInviteText(ctx))!!
        val oldTopic=GuardEngine.getChannelTopic(ctx)
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        try {
            confirmLeave(a)
            assertEquals("the role must not change before the release step is finished","parent",GuardEngine.getRole(ctx))
            assertTrue("the plain explanation names the password",texts().any{it.contains(secret)})
            assertTrue(texts().any{it.contains("仍受保護")})
            button("繼續")!!.performClick()
            waitFor("release result"){button("我已記下，繼續")!=null}
            val wires=pendingWires(oldTopic)
            assertTrue("a signed reset command is queued for the child",wires.isNotEmpty())
            button("我已記下，繼續")!!.performClick();idle()
            assertEquals("unconfigured",GuardEngine.getRole(ctx));assertFalse(prefs.contains("parent_master_password"))
            // The child side: bound to the old family and unable to leave without the (now deleted) password...
            assertTrue(GuardEngine.applyPairing(ctx,invite))
            assertFalse(GuardEngine.applyPairing(ctx,foreign()))
            // ...until the signed reset arrives.
            deliverToChild(wires)
            assertEquals("unconfigured",GuardEngine.getRole(ctx))
            assertTrue(GuardEngine.applyPairing(ctx,foreign()))
        } finally {c.pause().stop().destroy()}
    }
    @Test fun leavingWithoutSendingStillShowsThePasswordNeededOnTheChild() {
        val oldTopic=GuardEngine.getChannelTopic(ctx)
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        try {
            confirmLeave(a)
            all(latest().window!!.decorView).filterIsInstance<android.widget.CheckBox>().first{it.tag=="release_child"}.isChecked=false
            button("繼續")!!.performClick()
            waitFor("result"){button("我已記下，繼續")!=null}
            assertTrue(pendingWires(oldTopic).isEmpty())
            assertTrue(texts().any{it.contains("輸入")&&it.contains("主密碼")})
            button("我已記下，繼續")!!.performClick();idle()
            assertEquals("unconfigured",GuardEngine.getRole(ctx))
        } finally {c.pause().stop().destroy()}
    }
    @Test fun compromiseRecoveryAlsoReleasesTheChildBeforeDeletingThePassword() {
        val invite=GuardEngine.parsePairingString(GuardEngine.buildPairingInviteText(ctx))!!
        val oldTopic=GuardEngine.getChannelTopic(ctx)
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        try {
            call(a,"navigateTo","settings")
            all(a.window.decorView).first{it.isClickable && it.contentDescription?.toString()?.startsWith("家長主密碼與金鑰")==true}.performClick()
            button("秘密可能外洩：重新安全配對")!!.performClick()
            button("只建立新配對（不刪除中繼資料）")!!.performClick();idle()
            (latest() as android.app.AlertDialog).getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();idle()
            answer(a)
            assertTrue(texts().any{it.contains(secret)})
            assertEquals(invite.token,GuardEngine.getOrCreateFamilyToken(ctx))
            button("繼續")!!.performClick()
            waitFor("release result"){button("我已記下，繼續")!=null}
            val wires=pendingWires(oldTopic)
            assertTrue(wires.isNotEmpty())
            button("我已記下，繼續")!!.performClick();idle()
            assertNotEquals("the family really was replaced",invite.token,GuardEngine.getOrCreateFamilyToken(ctx))
            assertNotEquals("the old master password is gone (a new family gets a new one)",secret,prefs.getString("parent_master_password",null))
            // simulate the child that still holds the old family
            GuardEngine.setRole(ctx,"unconfigured")
            prefs.edit().remove("parent_signing_public").remove("parent_master_password").remove("parent_share_verifier").commit()
            assertTrue(GuardEngine.applyPairing(ctx,invite))
            assertFalse(GuardEngine.applyPairing(ctx,foreign()))
            deliverToChild(wires)
            assertEquals("unconfigured",GuardEngine.getRole(ctx))
            assertTrue(GuardEngine.applyPairing(ctx,foreign()))
        } finally {c.pause().stop().destroy()}
    }
}
