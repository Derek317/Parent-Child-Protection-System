package app.family.igguard

import android.app.Activity
import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
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
 * On Android 8-10 the device-credential check is a separate activity started for a result: MainActivity is paused
 * BEFORE the result arrives, and the result is delivered before onResume. These tests run the real lifecycle.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class AuthLifecycleTest {
    private val ctx:Application get()=RuntimeEnvironment.getApplication()
    private val prefs get()=LocalVault.preferences(ctx)
    private val secret="ParentPrivate928!"
    @Before fun reset() {
        prefs.edit().clear().commit();ctx.deleteDatabase("family_evidence.db")
        shadowOf(ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).setIsDeviceSecure(true)
        GuardEngine.setRole(ctx,"parent");GuardEngine.setPin(ctx,secret)
    }
    private fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList()
    private fun idle()=shadowOf(android.os.Looper.getMainLooper()).idle()
    private fun result(a:MainActivity,ok:Boolean){
        MainActivity::class.java.getDeclaredMethod("onActivityResult",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Intent::class.java).apply{isAccessible=true}
            .invoke(a,9031,if(ok)Activity.RESULT_OK else Activity.RESULT_CANCELED,null)
    }
    private fun locked(a:MainActivity)=all(a.window.decorView).any{it.tag=="parent_privacy_lock"}
    private fun credentialPromptLaunched(a:MainActivity):Boolean{var found=false;while(true){val i=shadowOf(a).nextStartedActivityForResult?:break;if(i.requestCode==9031)found=true};return found}

    @Test fun unlockSucceedsWhenTheCredentialActivityPausesTheScreenBeforeTheResult() {
        ParentUiAuthFixture.autoApprove=false
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        try {
            assertTrue(locked(a))
            all(a.window.decorView).first{it.tag=="parent_authenticate"}.performClick()
            assertTrue(credentialPromptLaunched(a))
            c.pause().stop()                       // the credential screen covers MainActivity
            result(a,true)                          // delivered before onResume
            c.restart().start().resume().visible()
            assertFalse("the parent must be unlocked after a successful credential check",locked(a))
        } finally {c.pause().stop().destroy()}
    }
    @Test fun aCancelledCredentialCheckStillLeavesTheLock() {
        ParentUiAuthFixture.autoApprove=false
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        try {
            all(a.window.decorView).first{it.tag=="parent_authenticate"}.performClick();credentialPromptLaunched(a)
            c.pause().stop();result(a,false);c.restart().start().resume().visible()
            assertTrue(locked(a))
            all(a.window.decorView).first{it.tag=="parent_authenticate"}.performClick()
            assertTrue("a new attempt is possible",credentialPromptLaunched(a))
        } finally {c.pause().stop().destroy()}
    }
    @Test fun aFreshAuthenticationActionRunsWhenThePauseComesBeforeTheResult() {
        ParentUiAuthFixture.autoApprove=true
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        try {
            MainActivity::class.java.getDeclaredMethod("navigateTo",String::class.java).apply{isAccessible=true}.invoke(a,"settings")
            all(a.window.decorView).first{it.isClickable && it.contentDescription?.toString()?.startsWith("家長主密碼與金鑰")==true}.performClick()
            val sheet=ShadowDialog.getLatestDialog()
            credentialPromptLaunched(a)
            all(sheet.window!!.decorView).filterIsInstance<Button>().first{it.text.toString()=="查看家長主密碼"}.performClick()
            assertTrue(credentialPromptLaunched(a))
            c.pause().stop();result(a,true);idle()
            assertTrue("the secret is shown after the fresh check",ShadowDialog.getShownDialogs().any{d->d.isShowing && all(d.window!!.decorView).filterIsInstance<TextView>().any{it.text.toString().contains(secret)}})
            c.restart().start().resume().visible()
        } finally {c.pause().stop().destroy()}
    }
    @Test fun aStaleRequestIsClearedOnResumeSoTheNextAttemptWorks() {
        ParentUiAuthFixture.autoApprove=false
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        try {
            all(a.window.decorView).first{it.tag=="parent_authenticate"}.performClick();credentialPromptLaunched(a)
            // the credential activity was killed with no result at all
            c.pause().stop().restart().start().resume().visible()
            all(a.window.decorView).first{it.tag=="parent_authenticate"}.performClick()
            assertTrue("a request that never returned must not block a new one",credentialPromptLaunched(a))
        } finally {c.pause().stop().destroy()}
    }
}
