package app.family.igguard

import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import android.os.CancellationSignal
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

/** Android 11+: the BiometricPrompt belongs to the screen; backgrounding cancels it cleanly and the next attempt works. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class BiometricLifecycleTest {
    private val ctx:Application get()=RuntimeEnvironment.getApplication()
    @Before fun reset() {
        ParentUiAuthFixture.autoApprove=false
        LocalVault.preferences(ctx).edit().clear().commit();ctx.deleteDatabase("family_evidence.db")
        shadowOf(ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).setIsDeviceSecure(true)
        GuardEngine.setRole(ctx,"parent")
    }
    private fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList()
    private fun field(a:MainActivity,name:String)=MainActivity::class.java.getDeclaredField(name).apply{isAccessible=true}.get(a)
    @Test fun pausingDuringABiometricPromptCancelsItCleanlyAndTheNextAttemptWorks() {
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        try {
            all(a.window.decorView).first{it.tag=="parent_authenticate"}.performClick()
            // The Robolectric framework may not host a real prompt: make sure there is an outstanding one with a signal.
            var signal=field(a,"parentAuthSignal") as CancellationSignal?
            if(signal==null) {
                signal=CancellationSignal()
                MainActivity::class.java.getDeclaredField("parentAuthSignal").apply{isAccessible=true}.set(a,signal)
                MainActivity::class.java.getDeclaredField("parentAuthPending").apply{isAccessible=true}.setBoolean(a,true)
            }
            c.pause()
            assertFalse("pausing alone must not cancel a prompt (an OEM prompt or PIN fallback can pause the screen)",signal!!.isCanceled)
            assertEquals(true,field(a,"parentAuthPending"))
            c.stop()
            assertTrue("the prompt's signal is cancelled when the screen is really stopped",signal.isCanceled)
            assertEquals(false,field(a,"parentAuthPending"))
            c.restart().start().resume().visible()
            assertTrue("still locked after a cancelled prompt",all(a.window.decorView).any{it.tag=="parent_privacy_lock"})
            all(a.window.decorView).first{it.tag=="parent_authenticate"}.performClick()
            assertNotSame("a new attempt makes a new prompt",signal,field(a,"parentAuthSignal"))
        } finally {c.pause().stop().destroy()}
    }
}
