package app.family.igguard

import android.app.AlertDialog
import android.view.View
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

/** Tier 2 UI: one dialog style, back links, text links, choice and checkbox controls, notices, notification wording. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class Tier2UiTest {
    private val ctx get() = RuntimeEnvironment.getApplication()
    private fun idle() = shadowOf(android.os.Looper.getMainLooper()).idle()
    private fun alert(a: MainActivity, critical: Boolean) =
        MainActivity::class.java.getDeclaredMethod("alert", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(a, critical) as AlertDialog.Builder
    private fun call(a: MainActivity, name: String) = MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(a)

    @Before fun reset() {
        ParentUiAuthFixture.autoApprove = true
        LocalVault.preferences(ctx).edit().clear().commit(); ctx.deleteDatabase("family_evidence.db")
        ctx.getSharedPreferences("ig_guard_consent", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        AppConsent.accept(ctx, AppConsent.acknowledgments)
        GuardEngine.setRole(ctx, "parent"); GuardEngine.setPin(ctx, "ParentPrivate928!")
    }

    @Test fun everyDialogIsThemedFromTheTokensAndKeepsItsGuards() {
        for (bright in listOf(true, false)) {
            GuardEngine.setBrightTheme(ctx, bright)
            val c = Robolectric.buildActivity(MainActivity::class.java).setup(); val a = c.get()
            try {
                val t = UiSupport.theme(a)
                for (critical in listOf(false, true)) {
                    val dialog = alert(a, critical).setTitle("T").setMessage("M").setNegativeButton("no", null).setPositiveButton("ok", null).show(); idle()
                    assertTrue(dialog.window!!.decorView.background is android.graphics.drawable.InsetDrawable)
                    assertEquals(android.graphics.Color.parseColor(UiSupport.token(t, "textSecondary")), dialog.findViewById<TextView>(android.R.id.message).currentTextColor)
                    assertEquals(android.graphics.Color.parseColor(UiSupport.token(t, "accentSeafoam")), dialog.getButton(AlertDialog.BUTTON_POSITIVE).currentTextColor)
                    for (which in intArrayOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE))
                        assertEquals("critical=$critical", critical, dialog.getButton(which).filterTouchesWhenObscured)
                    assertTrue("FLAG_SECURE", dialog.window!!.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE != 0)
                    call(a, "dismissUiDialogs")
                    assertFalse("still tracked for dismissal on pause and lock", dialog.isShowing)
                }
            } finally { c.pause().stop().destroy() }
        }
        GuardEngine.setBrightTheme(ctx, true)
    }
}
