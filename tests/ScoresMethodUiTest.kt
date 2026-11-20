package app.family.igguard

import android.app.Activity
import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.CheckBox
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowDialog
import java.io.IOException
import java.net.*
import java.util.UUID

/** The parent chooses the collection method: warning for capture, fresh authentication for every method change, nothing sent until then. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ScoresMethodUiTest {
    private val ctx: Application get() = RuntimeEnvironment.getApplication()
    private val scope get() = GuardEngine.getChannelTopic(ctx)
    private val token get() = GuardEngine.getOrCreateFamilyToken(ctx)
    private var original: ProxySelector? = null

    @Before fun reset() {
        ParentUiAuthFixture.autoApprove = true
        RuntimeEnvironment.setFontScale(1f)
        ctx.deleteDatabase("family_evidence.db"); LocalVault.preferences(ctx).edit().clear().commit()
        GuardEngine.setFamilyToken(ctx, UUID.randomUUID().toString()); GuardEngine.setRole(ctx, "parent")
        original = original ?: ProxySelector.getDefault()
        ProxySelector.setDefault(object : ProxySelector() {
            override fun select(u: URI) = if (u.host == "ntfy.sh") listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 9))) else original?.select(u) ?: listOf(Proxy.NO_PROXY)
            override fun connectFailed(u: URI, a: SocketAddress, e: IOException) {}
        })
    }
    @After fun cleanup() { ProxySelector.setDefault(original); original = null; ScoresEngineBridge.reset() }

    private fun validReport(policyId: String? = GuardEngine.scoresModePolicy(ctx)?.policyId) {
        val sent = System.currentTimeMillis() - 1000
        val state = ChildScoresState(policyId, "valid", "accepted", false, null, sent - 100, 0, 0, true)
        FamilyEvidence(ctx).use { it.storeScoresReport(scope, ParsedScoresReport(UUID.randomUUID().toString(), sent, state, emptyList()), System.currentTimeMillis()) }
    }
    /** The device lock is set after the start: a secure lock at start-up makes the Robolectric looper spin here. */
    private fun launch(): ActivityController<MainActivity> = Robolectric.buildActivity(MainActivity::class.java).setup().also { shadowOf(ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).setIsDeviceSecure(true); UiSupport.nav(it.get(), "family") }
    private fun dialogViews(): List<View> = UiSupport.all(ShadowDialog.getLatestDialog().window!!.decorView)
    private fun tagged(tag: String) = dialogViews().filter { it.tag == tag }
    private fun openSwitch(c: ActivityController<MainActivity>) { UiSupport.all(c.get().window.decorView).single { it.tag == "scores_mode_switch" }.performClick() }
    private fun promptedForAuth(a: MainActivity) = shadowOf(a).nextStartedActivityForResult?.requestCode == 9031
    private fun answer(a: MainActivity, ok: Boolean) {
        MainActivity::class.java.getDeclaredMethod("onActivityResult", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Intent::class.java).apply { isAccessible = true }
            .invoke(a, 9031, if (ok) Activity.RESULT_OK else Activity.RESULT_CANCELED, null)
    }
    private fun queuedPolicies(): List<JSONObject> = FamilyEvidence(ctx).use { it.due(scope, Long.MAX_VALUE, "parent") }
        .mapNotNull { GuardEngine.decryptPayload(token, it.wire)?.optJSONObject("envelope") }.map { JSONObject(it.getString("payload")) }.filter { it.optString("type") == "scores_mode_policy" }

    @Test fun uploadIsOfferedFirstNeedsNoWarningAndIsSentWithItsMethod() {
        validReport()
        val c = launch()
        try {
            openSwitch(c)
            assertTrue(tagged("scores_capture_ack").isEmpty()); assertTrue(UiSupport.texts(ShadowDialog.getLatestDialog().window!!.decorView).none { it.contains(ScoresUi.WARNING_EN_TITLE) })
            assertTrue(tagged("scores_method_upload").single().contentDescription.toString().contains("已選"))
            assertTrue(tagged("scores_on_confirm").single().isEnabled)
            tagged("scores_on_confirm").single().performClick(); answer(c.get(), true)
            val policy = GuardEngine.scoresModePolicy(ctx)!!
            assertTrue(policy.enabled); assertEquals(ScoresMethod.UPLOAD, policy.method)
            assertEquals(ScoresMethod.UPLOAD, queuedPolicies().single().getString("method"))
        } finally { c.pause().stop().destroy() }
    }

    @Test fun uploadFromOffNeedsAFreshAuthenticationToo() {
        validReport()
        val c = launch()
        try {
            openSwitch(c)
            tagged("scores_on_confirm").single().performClick()
            assertTrue(promptedForAuth(c.get())); assertNull("nothing is sent before the authentication", GuardEngine.scoresModePolicy(ctx))
            answer(c.get(), true)
            assertEquals(ScoresMethod.UPLOAD, GuardEngine.scoresModePolicy(ctx)!!.method)
        } finally { c.pause().stop().destroy() }
    }

    @Test fun turningOffNeedsAFreshAuthentication() {
        validReport(); assertTrue(GuardEngine.sendScoresModePolicy(ctx, true, ScoresMethod.UPLOAD)); validReport()
        val c = launch()
        try {
            openSwitch(c)
            org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick(); shadowOf(android.os.Looper.getMainLooper()).idle()
            assertTrue(promptedForAuth(c.get())); assertEquals("nothing is sent before the authentication", true, GuardEngine.scoresModePolicy(ctx)!!.enabled)
            answer(c.get(), true); assertEquals(false, GuardEngine.scoresModePolicy(ctx)!!.enabled)
        } finally { c.pause().stop().destroy() }
    }

    @Test fun capturePutsTheWarningWordForWordBeforeTheCheckboxAndSendsNothingUntilItIsTicked() {
        validReport()
        val c = launch()
        try {
            openSwitch(c)
            tagged("scores_method_capture").single().performClick()
            val text = UiSupport.texts(ShadowDialog.getLatestDialog().window!!.decorView)
            for (needle in listOf(ScoresUi.WARNING_ZH_TITLE, ScoresUi.WARNING_ZH_BODY, ScoresUi.WARNING_EN_TITLE, ScoresUi.WARNING_EN_BODY)) assertTrue(needle.take(30), text.contains(needle))
            // The wording is Derek's; pin it so an edit shows up here.
            assertEquals("自動擷取可能違反 Instagram 使用條款", ScoresUi.WARNING_ZH_TITLE)
            assertEquals("Automatic capture may breach Instagram's Terms of Use", ScoresUi.WARNING_EN_TITLE)
            assertTrue(ScoresUi.WARNING_ZH_BODY.startsWith("此方式會讀取子女手機上的 Instagram 通知及對話畫面。") && ScoresUi.WARNING_ZH_BODY.endsWith("如不接受，請改用「手動上載」。"))
            assertTrue(ScoresUi.WARNING_EN_BODY.startsWith("This method reads Instagram notifications and conversation screens on your child's phone.") && ScoresUi.WARNING_EN_BODY.endsWith("If you do not accept this, choose Manual upload."))
            val box = tagged("scores_capture_ack").single() as CheckBox
            assertFalse("never pre-ticked", box.isChecked)
            val confirm = tagged("scores_on_confirm").single()
            assertFalse(confirm.isEnabled)
            confirm.performClick()
            assertNull("nothing is sent before the box is ticked", GuardEngine.scoresModePolicy(ctx)); assertFalse(promptedForAuth(c.get()))
            box.isChecked = true
            assertTrue(tagged("scores_on_confirm").single().isEnabled)
            tagged("scores_on_confirm").single().performClick()
            assertNull("capture needs a fresh authentication first", GuardEngine.scoresModePolicy(ctx)); assertTrue(promptedForAuth(c.get()))
            answer(c.get(), false)
            assertNull("a cancelled authentication sends nothing", GuardEngine.scoresModePolicy(ctx))
            // Going back to upload and again to capture asks for the tick again.
            if (ShadowDialog.getLatestDialog().isShowing) {
                tagged("scores_method_upload").single().performClick(); tagged("scores_method_capture").single().performClick()
                assertFalse((tagged("scores_capture_ack").single() as CheckBox).isChecked); assertFalse(tagged("scores_on_confirm").single().isEnabled)
            }
        } finally { c.pause().stop().destroy() }
    }

    @Test fun captureIsSentOnlyAfterAFreshAuthenticationAndWithoutAMethodField() {
        validReport()
        val c = launch()
        try {
            openSwitch(c)
            tagged("scores_method_capture").single().performClick()
            (tagged("scores_capture_ack").single() as CheckBox).isChecked = true
            tagged("scores_on_confirm").single().performClick()
            assertTrue(promptedForAuth(c.get())); assertNull(GuardEngine.scoresModePolicy(ctx))
            answer(c.get(), true)
            val policy = GuardEngine.scoresModePolicy(ctx)!!
            assertTrue(policy.enabled); assertEquals(ScoresMethod.CAPTURE, policy.method)
            assertFalse(queuedPolicies().single().has("method"))
        } finally { c.pause().stop().destroy() }
    }

    @Test fun changingTheMethodNeedsAFreshAuthenticationAndIsANewPolicy() {
        validReport(); assertTrue(GuardEngine.sendScoresModePolicy(ctx, true, ScoresMethod.UPLOAD)); validReport()
        val first = GuardEngine.scoresModePolicy(ctx)!!
        val c = launch()
        try {
            val views = UiSupport.all(c.get().window.decorView)
            assertTrue(views.single { it.tag == "scores_method_line" }.let { (it as android.widget.TextView).text.toString().contains("手動上載") })
            views.single { it.tag == "scores_method_change" }.performClick()
            assertFalse("the method already in force cannot be sent again", tagged("scores_on_confirm").single().isEnabled)
            tagged("scores_method_capture").single().performClick()
            assertTrue(tagged("scores_capture_warning").isNotEmpty())
            (tagged("scores_capture_ack").single() as CheckBox).isChecked = true
            tagged("scores_on_confirm").single().performClick()
            assertTrue(promptedForAuth(c.get())); assertEquals("nothing changes before the authentication", first.policyId, GuardEngine.scoresModePolicy(ctx)!!.policyId)
            answer(c.get(), true)
            val second = GuardEngine.scoresModePolicy(ctx)!!
            assertNotEquals(first.policyId, second.policyId); assertEquals(ScoresMethod.CAPTURE, second.method); assertTrue(second.enabled)
        } finally { c.pause().stop().destroy() }
        // And back to upload: no warning, still a fresh authentication, another new policy.
        validReport()
        val d = launch()
        try {
            UiSupport.all(d.get().window.decorView).single { it.tag == "scores_method_change" }.performClick()
            assertTrue(tagged("scores_capture_ack").isEmpty())
            val before = GuardEngine.scoresModePolicy(ctx)!!.policyId
            tagged("scores_on_confirm").single().performClick()
            assertTrue(promptedForAuth(d.get())); assertEquals(before, GuardEngine.scoresModePolicy(ctx)!!.policyId)
            answer(d.get(), true)
            assertNotEquals(before, GuardEngine.scoresModePolicy(ctx)!!.policyId); assertEquals(ScoresMethod.UPLOAD, GuardEngine.scoresModePolicy(ctx)!!.method)
        } finally { d.pause().stop().destroy() }
    }

    @Test fun aRefusedMethodChangeIsReportedAndTheOldPolicyStays() {
        validReport(); assertTrue(GuardEngine.sendScoresModePolicy(ctx, true, ScoresMethod.UPLOAD)); validReport()
        val id = GuardEngine.scoresModePolicy(ctx)!!.policyId
        ScoresEngineBridge.sendPolicy = { _, _, _ -> false }
        val c = launch()
        try {
            UiSupport.all(c.get().window.decorView).single { it.tag == "scores_method_change" }.performClick()
            (tagged("scores_capture_ack").single() as CheckBox).isChecked = true   // the sheet offers the other method, capture, first
            tagged("scores_on_confirm").single().performClick(); answer(c.get(), true)
            assertEquals(ScoresUi.METHOD_FAILED, org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
            assertEquals(id, GuardEngine.scoresModePolicy(ctx)!!.policyId)
        } finally { c.pause().stop().destroy() }
    }
}
