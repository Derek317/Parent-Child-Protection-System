package app.family.igguard

import android.app.Activity
import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
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

/** S7: an unconfigured phone that still holds parent secrets can remove them in place, after a fresh device authentication. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class LeftoverParentDataTest {
    private val ctx:Application get()=RuntimeEnvironment.getApplication()
    private val prefs get()=LocalVault.preferences(ctx)
    @Before fun setUp() {
        prefs.edit().clear().commit();ctx.deleteDatabase("family_evidence.db")
        shadowOf(ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).setIsDeviceSecure(true)
        GuardEngine.setRole(ctx,"parent");GuardEngine.setPin(ctx,"ParentPrivate928!");GuardEngine.relayPublicKey(ctx)
        GuardEngine.setRole(ctx,"unconfigured")
    }
    private fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList()
    private fun answer(a:MainActivity,ok:Boolean){
        MainActivity::class.java.getDeclaredMethod("onActivityResult",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Intent::class.java).apply{isAccessible=true}.invoke(a,9031,if(ok)Activity.RESULT_OK else Activity.RESULT_CANCELED,null)
    }
    private fun idle()=shadowOf(Looper.getMainLooper()).idle()
    private fun foreign()=FamilySecurity.Invite(UUID.randomUUID().toString(),FamilySecurity.passwordVerifier("OtherParent928!"),FamilySecurity.newSigningIdentity().publicKey)
    @Test fun leftoverParentDataCanBeRemovedInPlaceOnlyAfterFreshAuthentication() {
        assertEquals(GuardEngine.PairingBlock.PARENT_DATA_PRESENT,GuardEngine.pairingBlock(ctx))
        assertFalse(GuardEngine.applyPairing(ctx,foreign()))
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        try {
            val row=all(a.window.decorView).first{it.isClickable && it.contentDescription?.toString()?.startsWith("移除此手機上的家長資料")==true}
            row.performClick();idle()
            while(shadowOf(a).nextStartedActivityForResult!=null){}
            (ShadowDialog.getLatestDialog() as android.app.AlertDialog).getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();idle()
            assertEquals("a device authentication must be requested",9031,shadowOf(a).nextStartedActivityForResult?.requestCode)
            assertTrue("nothing is removed before authentication",GuardEngine.parentSecretsPresent(ctx))
            answer(a,false);assertTrue(GuardEngine.parentSecretsPresent(ctx))
            row.performClick();idle()
            (ShadowDialog.getLatestDialog() as android.app.AlertDialog).getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();idle()
            answer(a,true)
            assertFalse(GuardEngine.parentSecretsPresent(ctx));assertNull(GuardEngine.pairingBlock(ctx))
            assertEquals("unconfigured",GuardEngine.getRole(ctx))
            assertTrue(GuardEngine.applyPairing(ctx,foreign()))
        } finally {c.pause().stop().destroy()}
    }
    @Test fun leavingTheParentRoleRemovesSecretsBeforeChangingRoleAndReportsFailure() {
        GuardEngine.setRole(ctx,"parent")
        TestKeyStore.failReads=true
        val ok=try{GuardEngine.leaveParentRole(ctx)}catch(_:Exception){false}
        TestKeyStore.failReads=false
        assertFalse("a failed identity deletion is reported, not thrown to the UI",ok)
    }

    @Test fun withoutAScreenLockTheLeftoverRemovalExplainsInsteadOfShowingADeadLockScreen() {
        shadowOf(ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).setIsDeviceSecure(false)
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        try {
            all(a.window.decorView).first{it.isClickable && it.contentDescription?.toString()?.startsWith("移除此手機上的家長資料")==true}.performClick();idle()
            (ShadowDialog.getLatestDialog() as android.app.AlertDialog).getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();idle()
            assertFalse("no parent lock screen on an unconfigured phone",all(a.window.decorView).any{it.tag=="parent_privacy_lock"})
            val texts=ShadowDialog.getShownDialogs().filter{it.isShowing}.flatMap{d->all(d.window!!.decorView).filterIsInstance<android.widget.TextView>().map{it.text.toString()}}
            assertTrue(texts.any{it.contains("螢幕鎖")})
            assertTrue(ShadowDialog.getShownDialogs().any{d->d.isShowing && all(d.window!!.decorView).filterIsInstance<android.widget.Button>().any{it.text.toString()=="開啟安全設定"}})
            assertTrue("data is untouched",GuardEngine.parentSecretsPresent(ctx))
            assertTrue("still on the role screen",all(a.window.decorView).any{it.tag=="entry_parent"})
        } finally {c.pause().stop().destroy()}
    }
}
