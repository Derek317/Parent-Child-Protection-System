package app.family.igguard

import android.animation.ValueAnimator
import android.app.Application
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Looper
import android.view.View
import android.view.ViewOverlay
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ThemeRevealTest {
    private val ctx: Application get() = RuntimeEnvironment.getApplication()
    // Robolectric runs with animators off (scale 0); the hidden setter turns them on so the reveal path runs.
    private val durationScale = ValueAnimator::class.java.getDeclaredMethod("setDurationScale", Float::class.java).apply { isAccessible = true }
    private val switch = MainActivity::class.java.getDeclaredMethod("switchTheme", Boolean::class.java, View::class.java).apply { isAccessible = true }
    private val reveal = MainActivity::class.java.getDeclaredField("themeReveal").apply { isAccessible = true }
    @Before fun reset() { LocalVault.preferences(ctx).edit().clear().commit(); ctx.deleteDatabase("family_evidence.db") }

    @Test fun rapidThemeSwitchesEndOnThePrefWithItsPaletteAndNoOverlay() {
        GuardEngine.setBrightTheme(ctx, true)
        durationScale.invoke(null, 1f)
        val c = Robolectric.buildActivity(MainActivity::class.java).setup(); val a = c.get()
        try {
            val overlayEmpty = { ViewOverlay::class.java.getDeclaredMethod("isEmpty").apply { isAccessible = true }.invoke(a.window.decorView.overlay) as Boolean }
            val toggle = { UiSupport.all(a.window.decorView).first { it.contentDescription == "切換淺色或深色模式" } }
            switch.invoke(a, false, toggle()); switch.invoke(a, true, toggle()); switch.invoke(a, false, toggle())
            assertTrue("the reveal should be running", !overlayEmpty() && reveal.get(a) != null)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            assertFalse(GuardEngine.isBrightTheme(ctx))
            val canvas = UiSupport.token(UiSupport.theme(a), "bgCanvas")
            assertEquals("#0B1724", canvas)
            val shell = MainActivity::class.java.getDeclaredField("screenShell").apply { isAccessible = true }.get(a) as View
            assertEquals(Color.parseColor(canvas), (shell.background as ColorDrawable).color)
            assertTrue("overlay left behind", overlayEmpty())
            assertNull(reveal.get(a))
        } finally { c.pause().stop().destroy(); durationScale.invoke(null, 0f) }
    }
    @Test fun pausingAParentScreenEndsTheRevealSoTheLockIsNotCoveredByTheOldScreen() {
        ParentUiAuthFixture.autoApprove = true
        AppConsent.accept(ctx, AppConsent.acknowledgments)
        GuardEngine.setRole(ctx, "parent"); GuardEngine.setPin(ctx, "ParentPrivate928!"); GuardEngine.setBrightTheme(ctx, true)
        durationScale.invoke(null, 1f)
        val c = Robolectric.buildActivity(MainActivity::class.java).setup(); val a = c.get()
        try {
            val overlayEmpty = { ViewOverlay::class.java.getDeclaredMethod("isEmpty").apply { isAccessible = true }.invoke(a.window.decorView.overlay) as Boolean }
            switch.invoke(a, false, a.window.decorView)
            assertTrue("the reveal should be running", !overlayEmpty() && reveal.get(a) != null)
            c.pause()
            assertTrue("old parent pixels left over the lock", overlayEmpty())
            assertNull(reveal.get(a))
            assertNotNull(a.window.decorView.findViewWithTag<View>("parent_privacy_lock"))
        } finally { c.stop().destroy(); durationScale.invoke(null, 0f) }
    }
    @Test fun withAnimatorsOffTheSwitchIsInstantAndLeavesNoOverlay() {
        durationScale.invoke(null, 0f)
        GuardEngine.setBrightTheme(ctx, false)
        val c = Robolectric.buildActivity(MainActivity::class.java).setup(); val a = c.get()
        try {
            val toggle = UiSupport.all(a.window.decorView).first { it.contentDescription == "切換淺色或深色模式" }
            switch.invoke(a, true, toggle)
            assertTrue(GuardEngine.isBrightTheme(ctx)); assertEquals("#E2EAF3", UiSupport.token(UiSupport.theme(a), "bgCanvas"))
            assertNull(reveal.get(a))
            assertTrue(ViewOverlay::class.java.getDeclaredMethod("isEmpty").apply { isAccessible = true }.invoke(a.window.decorView.overlay) as Boolean)
        } finally { c.pause().stop().destroy() }
    }
}
