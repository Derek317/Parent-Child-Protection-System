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

    private fun backLinks(a: MainActivity) = UiSupport.all(UiSupport.current(a)).filter { it.tag == "back_link" }
    private fun settle() = shadowOf(android.os.Looper.getMainLooper()).idleFor(600, java.util.concurrent.TimeUnit.MILLISECONDS)
    private fun section(a: MainActivity) = MainActivity::class.java.getDeclaredField("section").apply { isAccessible = true }.get(a) as String

    @Test fun subPagesHaveOneBackLinkThatActsLikeSystemBack() {
        val c = Robolectric.buildActivity(MainActivity::class.java).setup(); val a = c.get()
        try {
            UiSupport.nav(a, "settings"); settle()
            assertEquals(1, backLinks(a).size); backLinks(a).single().performClick(); settle()
            assertEquals("overview", section(a))
        } finally { c.pause().stop().destroy() }
        GuardEngine.setRole(ctx, "child")
        val d = Robolectric.buildActivity(MainActivity::class.java).setup(); val b = d.get()
        try {
            for (page in listOf("permissions", "unlock", "settings")) {
                UiSupport.nav(b, page); settle()
                assertEquals(page, 1, backLinks(b).size); backLinks(b).single().performClick(); settle()
                assertEquals(page, "overview", section(b))
            }
        } finally { d.pause().stop().destroy() }
    }

    @Test fun theChangeNoticeBackLinkLeavesTheNoticePendingLikeSystemBack() {
        val tiny = "tiny model file for tests".toByteArray()
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(tiny).joinToString("") { "%02x".format(it) }
        ctx.getSharedPreferences("ig_guard_scores_consent", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        RiskModelStore.directory(ctx).deleteRecursively(); RiskModelStore.memoryProbe = { 8L shl 30 }; RiskModelStore.abiProbe = { true }
        GuardEngine.setFamilyToken(ctx, java.util.UUID.randomUUID().toString()); GuardEngine.setRole(ctx, "child")
        val id = java.util.UUID.randomUUID().toString()
        LocalVault.preferences(ctx).edit().putString("parent_command_public_key", FamilySecurity.newSigningIdentity().publicKey).putString("capture_policy_id", id).putString("capture_local_ack_id", id).commit()
        RiskModelStore.expectation = RiskModelStore.Expectation(tiny.size.toLong(), sha)
        try {
            RiskModelStore.importModel(ctx, java.io.ByteArrayInputStream(tiny), {}, tiny.size.toLong(), sha); RiskLocal.changed(ctx)
            RiskLocal.receivePolicy(ctx, java.util.UUID.randomUUID().toString(), true); assertTrue(RiskLocal.accept(ctx))
            assertEquals("on", RiskLocal.pendingChangeNotice(ctx))
            val c = Robolectric.buildActivity(MainActivity::class.java).setup(); val a = c.get()
            try {
                UiSupport.nav(a, "scores_change"); settle()
                backLinks(a).single().performClick(); settle()
                assertEquals("overview", section(a)); assertEquals("on", RiskLocal.pendingChangeNotice(ctx))
            } finally { c.pause().stop().destroy() }
        } finally {
            RiskModelStore.abiProbe = { android.os.Build.SUPPORTED_ABIS.contains("arm64-v8a") }
            RiskModelStore.expectation = RiskModelStore.Expectation(RiskModelSpec.FILE_BYTES, RiskModelSpec.FILE_SHA256)
            RiskModelStore.memoryProbe = { c -> val i = android.app.ActivityManager.MemoryInfo(); c.getSystemService(android.app.ActivityManager::class.java)?.getMemoryInfo(i); i.totalMem }
            RiskModelStore.directory(ctx).deleteRecursively()
        }
    }

    private fun fill(v: View): Int = (((v.background as android.graphics.drawable.RippleDrawable).getDrawable(0) as android.graphics.drawable.StateListDrawable).current as android.graphics.drawable.GradientDrawable).color!!.defaultColor

    @Test fun textActionsAreTrueTextLinksAndDeletesKeepTheDangerColour() {
        val c = Robolectric.buildActivity(MainActivity::class.java).setup(); val a = c.get()
        try {
            val t = UiSupport.theme(a); val min = (48 * a.resources.displayMetrics.density).toInt()
            UiSupport.nav(a, "alerts"); settle()
            val links = UiSupport.all(UiSupport.current(a)).filterIsInstance<android.widget.Button>().filter { it.text.toString() in setOf("重新同步可用記錄", "刪除此家庭的全部證據") }
            assertEquals(2, links.size)
            for (link in links) { assertEquals(link.text.toString(), 0, android.graphics.Color.alpha(fill(link))); assertTrue(link.minimumHeight >= min) }
            assertEquals(android.graphics.Color.parseColor(UiSupport.token(t, "warmCoral")), links.single { it.text.startsWith("刪除") }.currentTextColor)
            assertEquals(android.graphics.Color.parseColor(UiSupport.token(t, "accentSeafoam")), links.single { it.text.startsWith("重新") }.currentTextColor)
            links.single { it.text.startsWith("刪除") }.performClick(); idle()
            assertTrue("the delete still asks first, with the obscured-touch guard", (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).filterTouchesWhenObscured)
        } finally { c.pause().stop().destroy() }
    }

    private fun checked(v: View): Boolean = android.view.accessibility.AccessibilityNodeInfo.obtain().also { v.onInitializeAccessibilityNodeInfo(it) }.let { it.isCheckable && it.isChecked }

    @Test fun choicesAnnounceTheirStateAndTheMethodChooserHasNoGlyphsOrPreTickedBox() {
        val c = Robolectric.buildActivity(MainActivity::class.java).setup(); val a = c.get()
        try {
            UiSupport.nav(a, "settings"); settle()
            val modes = UiSupport.all(UiSupport.current(a)).filterIsInstance<android.widget.Button>().filter { it.text.toString() in setOf("淺色模式", "深色模式") }
            assertEquals(listOf(true, false), modes.map { checked(it) })
            assertEquals(listOf(true, false), modes.map { it.contentDescription.toString().endsWith("已選") })
        } finally { c.pause().stop().destroy() }
        ShotSupport.parentReset(); ShotSupport.report()
        val d = ShotSupport.open("family")
        try {
            UiSupport.all(d.get().window.decorView).first { it.tag == "scores_mode_switch" }.performClick(); idle()
            val sheet = ShadowDialog.getLatestDialog().window!!.decorView
            assertTrue(UiSupport.texts(sheet).none { it.contains("●") || it.contains("○") })
            val upload = UiSupport.all(sheet).single { it.tag == "scores_method_upload" }; val capture = UiSupport.all(sheet).single { it.tag == "scores_method_capture" }
            assertTrue(checked(upload)); assertFalse(checked(capture))
            capture.performClick(); idle()
            val box = UiSupport.all(ShadowDialog.getLatestDialog().window!!.decorView).single { it.tag == "scores_capture_ack" } as android.widget.CheckBox
            assertFalse("the warning box starts unticked", box.isChecked)
        } finally { d.pause().stop().destroy() }
    }

    @Test fun theConsentBoxesStayRealUntickedCheckBoxesWithTheAllThreeRule() {
        ctx.getSharedPreferences("ig_guard_consent", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        val c = Robolectric.buildActivity(MainActivity::class.java).setup(); val page = c.get().findViewById<View>(android.R.id.content)
        try {
            val boxes = AppConsent.acknowledgments.map { page.findViewWithTag<android.widget.CheckBox>(it) }
            assertTrue(boxes.none { it.isChecked }); assertFalse(page.findViewWithTag<View>("consent_accept").isEnabled)
            boxes.take(2).forEach { it.isChecked = true }; assertFalse(page.findViewWithTag<View>("consent_accept").isEnabled)
            boxes.last().isChecked = true; assertTrue(page.findViewWithTag<View>("consent_accept").isEnabled)
        } finally { c.pause().stop().destroy() }
    }

    @Test fun emptyAndWarningStatesAreNoticeCardsWithAnIconAndUnchangedText() {
        GuardEngine.setFamilyToken(ctx, java.util.UUID.randomUUID().toString())
        val c = Robolectric.buildActivity(MainActivity::class.java).setup(); val a = c.get()
        try {
            UiSupport.nav(a, "alerts"); val views = UiSupport.views(a, "evidence_loading")
            val text = views.filterIsInstance<TextView>().single { it.text.toString() == "尚無符合條件的對話記錄。" }
            val line = text.parent as android.widget.LinearLayout
            assertTrue("an icon beside the text", line.getChildAt(0) is android.widget.ImageView)
            assertTrue("inside a card", (line.parent as View).background is android.graphics.drawable.GradientDrawable)
            UiSupport.nav(a, "family"); settle()
            assertTrue(UiSupport.texts(UiSupport.current(a)).contains("尚未加入信任好友。請確認對方身份後，再加入名單。"))
        } finally { c.pause().stop().destroy() }
    }

    @Test fun aLockedParentGetsNoBackLinkPastTheLock() {
        ParentUiAuthFixture.autoApprove = false
        try {
            val c = Robolectric.buildActivity(MainActivity::class.java).setup(); val a = c.get()
            try {
                UiSupport.nav(a, "settings"); settle()
                assertTrue(UiSupport.all(a.window.decorView).none { it.tag == "back_link" })
                assertTrue(UiSupport.all(a.window.decorView).any { it.tag == "parent_privacy_lock" })
            } finally { c.pause().stop().destroy() }
        } finally { ParentUiAuthFixture.autoApprove = true }
    }
}
