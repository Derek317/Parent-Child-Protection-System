package app.family.igguard

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
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
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class NativeDesignTest {
    private val ctx: Application get() = RuntimeEnvironment.getApplication()
    @Before fun reset() {
        ctx.getSharedPreferences("ig_guard_prefs",Context.MODE_PRIVATE).edit().clear().commit()
        GuardEngine.pauseTamperUntilMs=0
    }
    private fun all(v: View): List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))} else emptyList()
    private fun root(a:MainActivity)=a.window.decorView
    private fun invoke(a:MainActivity,name:String,value:String)=MainActivity::class.java.getDeclaredMethod(name,String::class.java).apply{isAccessible=true}.invoke(a,value)
    private fun text(a:MainActivity)=all(root(a)).filterIsInstance<TextView>().map{it.text.toString()}
    @Test fun freshLaunchOnlyShowsRoleChoice() {
        val c=Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
        assertTrue(text(c.get()).contains("我是家長"))
        assertTrue(text(c.get()).contains("這是子女手機"))
        assertFalse(all(root(c.get())).any{it is android.widget.EditText})
        assertEquals("unconfigured",GuardEngine.getRole(ctx))
        c.pause().stop().destroy()
    }
    @Test fun pairedChildEntryDoesNotChangeRoleAndParentNeedsVerification() {
        GuardEngine.setRole(ctx,"child")
        val c=Robolectric.buildActivity(MainActivity::class.java).create()
        val a=c.get()
        // Render the entry screen without starting background network polling in this unit test.
        MainActivity::class.java.getDeclaredMethod("renderUi",Boolean::class.javaPrimitiveType).apply{isAccessible=true}.invoke(a,false)
        assertTrue(text(a).contains("我是家長"))
        invoke(a,"selectEntryRole","parent")
        MainActivity::class.java.getDeclaredMethod("continueEntryRole").apply{isAccessible=true}.invoke(a)
        assertEquals("child",GuardEngine.getRole(ctx))
        assertFalse(GuardEngine.isTemporarilyUnlocked(ctx))
        c.destroy()
    }
    @Test fun roleChoiceDoesNotSeedTrustedAccounts() {
        assertTrue(GuardEngine.getTrustedHandles(ctx).isEmpty())
    }
    @Test fun configuredParentHasBottomNavigationAndLiveEmptyState() {
        GuardEngine.setRole(ctx,"parent")
        val c=Robolectric.buildActivity(MainActivity::class.java).create()
        invoke(c.get(),"navigateTo","overview")
        assertTrue(text(c.get()).contains("提醒"))
        assertTrue(text(c.get()).contains("家庭"))
        assertTrue(text(c.get()).contains("等待子女手機回報"))
        assertFalse(text(c.get()).any{it.contains("晴晴")||it.contains("ching.sample")||it.contains("R7K2M9")})
        c.destroy()
    }
    private fun deliverPairing(a:MainActivity) {
        val token=java.util.UUID.randomUUID().toString()
        val pin=Base64.encodeToString(GuardEngine.generateRandomStrongPassword().toByteArray(),Base64.NO_WRAP or Base64.URL_SAFE)
        val intent=Intent(Intent.ACTION_VIEW,Uri.parse("igguard://pair?token=$token&pw=$pin"))
        MainActivity::class.java.getDeclaredMethod("onNewIntent",Intent::class.java).apply{isAccessible=true}.invoke(a,intent)
        MainActivity::class.java.getDeclaredMethod("renderUi",Boolean::class.javaPrimitiveType).apply{isAccessible=true}.invoke(a,false)
    }
    private fun openParentKeySheet(a:MainActivity):android.app.Dialog {
        invoke(a,"navigateTo","settings")
        all(root(a)).first{it.isClickable && it.contentDescription?.toString()?.startsWith("家長主密碼與金鑰")==true}.performClick()
        return ShadowDialog.getLatestDialog()
    }
    @Test fun pairingRoleChangeDismissesPrivilegedParentSheet() {
        GuardEngine.setRole(ctx,"parent")
        val c=Robolectric.buildActivity(MainActivity::class.java).create()
        val d=openParentKeySheet(c.get());assertTrue(d.isShowing)
        deliverPairing(c.get())
        assertEquals("child",GuardEngine.getRole(ctx));assertFalse(d.isShowing)
        c.destroy()
    }
    @Test fun pairingRoleChangeDismissesNestedRescueConfirmation() {
        GuardEngine.setRole(ctx,"parent")
        val c=Robolectric.buildActivity(MainActivity::class.java).create()
        val d=openParentKeySheet(c.get())
        all(d.window!!.decorView).filterIsInstance<Button>().first{it.text.toString()=="遠端解除子女保護並重設身份"}.performClick()
        val confirm=ShadowAlertDialog.getLatestAlertDialog();assertTrue(confirm.isShowing)
        deliverPairing(c.get())
        assertFalse(confirm.isShowing);assertFalse(d.isShowing)
        c.destroy()
    }
    @Test fun staleParentActionCannotReadNewChildPasswordBeforeRefresh() {
        GuardEngine.setRole(ctx,"parent")
        val c=Robolectric.buildActivity(MainActivity::class.java).create()
        val d=openParentKeySheet(c.get())
        val reveal=all(d.window!!.decorView).filterIsInstance<Button>().first{it.text.toString()=="查看家長主密碼"}
        val secret=GuardEngine.generateRandomStrongPassword()
        GuardEngine.setPin(ctx,secret);GuardEngine.setRole(ctx,"child")
        reveal.performClick()
        assertFalse(d.isShowing)
        val latest=ShadowDialog.getLatestDialog()
        assertFalse(latest.isShowing && all(latest.window!!.decorView).filterIsInstance<TextView>().any{it.text.toString()==secret})
        c.destroy()
    }
    @Test fun passwordSuggestionIsGeneratedWithoutCreatingFamily() {
        val c=Robolectric.buildActivity(MainActivity::class.java).create()
        invoke(c.get(),"navigateTo","parent_setup")
        val generate=all(root(c.get())).filterIsInstance<Button>().firstOrNull{it.text.toString()=="產生高強度密碼"}
        assertNotNull(generate);generate!!.performClick()
        val password=all(root(c.get())).filterIsInstance<android.widget.EditText>().first().text.toString()
        assertTrue(GuardEngine.isStrongPasswordValid(password))
        assertEquals("unconfigured",GuardEngine.getRole(ctx))
        c.destroy()
    }
}
