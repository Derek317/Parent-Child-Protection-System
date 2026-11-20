package app.family.igguard

import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The role page is one-time: a phone that already has a role opens on its normal first screen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class ColdStartScreenTest {
    private val ctx:Application get()=RuntimeEnvironment.getApplication()
    @Before fun reset() {
        ParentUiAuthFixture.autoApprove=true
        LocalVault.preferences(ctx).edit().clear().commit();ctx.deleteDatabase("family_evidence.db")
        shadowOf(ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).setIsDeviceSecure(true)
    }
    private fun coldSection():String {
        val c=Robolectric.buildActivity(MainActivity::class.java).setup()
        // A resumed activity keeps a repeating UI timer and its relay session: leaving it alive disturbs whichever test runs next.
        try { return MainActivity::class.java.getDeclaredField("section").apply{isAccessible=true}.get(c.get()) as String }
        finally { c.pause().stop().destroy() }
    }
    @Test fun configuredParentColdStartOpensOnOverview(){GuardEngine.setRole(ctx,"parent");GuardEngine.setPin(ctx,"ParentPrivate928!");assertEquals("overview",coldSection())}
    @Test fun configuredChildColdStartDoesNotOpenOnRolePage(){GuardEngine.setRole(ctx,"child");assertEquals("overview",coldSection())}
    @Test fun unconfiguredPhoneColdStartOpensOnRolePage(){assertEquals("role",coldSection())}
}
