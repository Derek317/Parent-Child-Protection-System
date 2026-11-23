package app.family.igguard

import android.animation.ValueAnimator
import android.app.Application
import android.os.Looper
import android.view.View
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
import java.util.UUID

/** The sliding tab highlight and the page fade: they always end where an instant render would, and never run when nothing changed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MotionTest {
    private val ctx: Application get() = RuntimeEnvironment.getApplication()
    private val durationScale = ValueAnimator::class.java.getDeclaredMethod("setDurationScale", Float::class.java).apply { isAccessible = true }
    private fun field(a: MainActivity, name: String): Any? = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(a)
    private fun running(a: MainActivity, name: String) = (field(a, name) as ValueAnimator?)?.isRunning == true
    private fun tab(a: MainActivity, d: String): View = UiSupport.all(a.window.decorView).single { it.tag == "nav_$d" }
    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))

    @Before fun reset() {
        ctx.deleteDatabase("family_evidence.db"); LocalVault.preferences(ctx).edit().clear().commit()
        GuardEngine.setFamilyToken(ctx, UUID.randomUUID().toString()); GuardEngine.setRole(ctx, "child")
        val id = UUID.randomUUID().toString()
        LocalVault.preferences(ctx).edit().putString("parent_command_public_key", FamilySecurity.newSigningIdentity().publicKey).putString("capture_policy_id", id).putString("capture_local_ack_id", id).commit()
    }
    private fun assertSettledOn(a: MainActivity, d: String) {
        assertFalse("tab highlight still moving", running(a, "navMotion")); assertFalse("page still fading", running(a, "pageMotion"))
        assertTrue(tab(a, d).isSelected); assertEquals(1, UiSupport.all(a.window.decorView).count { (it.tag as? String)?.startsWith("nav_") == true && it.isSelected })
        assertTrue(tab(a, d).width > 0)
        assertEquals("highlight not under $d", tab(a, d).left.toFloat(), field(a, "navPillX") as Float, .5f)
        val page = UiSupport.current(a); assertEquals(1f, page.alpha, 0f); assertEquals(0f, page.translationY, 0f)
    }

    @Test fun withAnimatorsOnSwitchesSlideAndRapidTapsEndOnTheLastTab() {
        durationScale.invoke(null, 1f)
        val c = Robolectric.buildActivity(MainActivity::class.java).setup(); val a = c.get()
        try {
            UiSupport.nav(a, "overview"); settle(); assertSettledOn(a, "overview")
            val start = field(a, "navPillX") as Float
            UiSupport.nav(a, "permissions")
            assertTrue("page should fade in", running(a, "pageMotion")); assertTrue(UiSupport.current(a).alpha < 1f)
            shadowOf(Looper.getMainLooper()).idle()
            // Robolectric may run the whole slide inside idle(); what matters is that one was started from the old tab to the new one.
            val slide = field(a, "navMotion") as ValueAnimator?
            assertNotNull("highlight should slide", slide)
            val from = slide!!.clone().apply { removeAllUpdateListeners(); setCurrentFraction(0f) }.animatedValue as Float
            assertEquals("slide should start under the old tab", start, from, .5f)
            settle(); assertSettledOn(a, "permissions"); assertNotEquals(start, field(a, "navPillX") as Float, .5f)
            for (d in listOf("overview", "permissions", "unlock", "permissions")) { UiSupport.nav(a, d); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(40)) }
            settle(); assertSettledOn(a, "permissions")
        } finally { c.pause().stop().destroy(); durationScale.invoke(null, 0f) }
    }
    @Test fun aTabThatMovesDuringTheSlideStillGetsTheHighlight() {
        durationScale.invoke(null, 1f)
        val c = Robolectric.buildActivity(MainActivity::class.java).setup(); val a = c.get()
        try {
            UiSupport.nav(a, "overview"); settle(); assertSettledOn(a, "overview")
            UiSupport.nav(a, "permissions"); shadowOf(Looper.getMainLooper()).idle()
            // Robolectric finishes the slide inside idle(), so the mid-slide moment is replayed: the tab moves (as a rotation
            // re-lays the bar out while the slide runs, whose layout pass does not re-place a running slide), then the slide ends.
            val slide = field(a, "navMotion") as ValueAnimator
            val target = tab(a, "permissions"); target.offsetLeftAndRight(120)
            slide.end()
            assertEquals("highlight lands where the tab is now", target.left.toFloat(), field(a, "navPillX") as Float, .5f)
        } finally { c.pause().stop().destroy(); durationScale.invoke(null, 0f) }
    }
    @Test fun withAnimatorsOffEverythingIsFinalAtOnce() {
        durationScale.invoke(null, 0f)
        val c = Robolectric.buildActivity(MainActivity::class.java).setup(); val a = c.get()
        try {
            UiSupport.nav(a, "overview"); shadowOf(Looper.getMainLooper()).idle()
            UiSupport.nav(a, "permissions"); assertNull(field(a, "pageMotion"))
            shadowOf(Looper.getMainLooper()).idle() // lays the bar out; no time passes
            assertNull(field(a, "navMotion")); assertSettledOn(a, "permissions")
        } finally { c.pause().stop().destroy() }
    }
    @Test fun aReRenderOfTheSamePageStartsNoMotion() {
        durationScale.invoke(null, 1f)
        val c = Robolectric.buildActivity(MainActivity::class.java).setup(); val a = c.get()
        try {
            UiSupport.nav(a, "permissions"); settle(); val slide = field(a, "navMotion")
            MainActivity::class.java.getDeclaredMethod("renderUi", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(a, true)
            assertNull(field(a, "pageMotion")); assertEquals(1f, UiSupport.current(a).alpha, 0f)
            shadowOf(Looper.getMainLooper()).idle()
            assertSame("no new slide", slide, field(a, "navMotion")); assertSettledOn(a, "permissions")
        } finally { c.pause().stop().destroy(); durationScale.invoke(null, 0f) }
    }
}
