package app.family.igguard

import android.app.Application
import android.app.KeyguardManager
import android.content.Context
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

/** While capture is held after a switch-off that followed manual upload, neither phone may say or imply that capture is running. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CaptureHeldChildTest : UploadTestBase() {
    private fun reasons() = heartbeats().map { it.optString("reason") }
    @Test fun aHeldPhoneWithNotificationAccessOffIsATamperAlertAndSaysCaptureIsHeld() {
        activate(ScoresMethod.UPLOAD); switchOff(); assertTrue(RiskLocal.captureHeld(ctx))
        GuardEngine.checkAndSendChildHeartbeatIfDue(ctx, force = true)
        assertEquals(listOf("tamper_attempt"), reasons())
        assertTrue(heartbeats().single().getString("text").endsWith(GuardEngine.CAPTURE_HELD_MARK))
    }
    @Test fun uploadThatIsOnStillExemptsTheHeartbeatAndCarriesNoMarker() {
        activate(ScoresMethod.UPLOAD)
        GuardEngine.checkAndSendChildHeartbeatIfDue(ctx, force = true)
        assertEquals(listOf("heartbeat_ok"), reasons()); assertFalse(heartbeats().single().getString("text").contains(GuardEngine.CAPTURE_HELD_MARK))
    }
    @Test fun releasingTheHoldRemovesTheMarker() {
        activate(ScoresMethod.UPLOAD); switchOff(); RiskLocal.releaseCaptureHold(ctx)
        GuardEngine.checkAndSendChildHeartbeatIfDue(ctx, force = true)
        assertFalse(heartbeats().single().getString("text").contains(GuardEngine.CAPTURE_HELD_MARK))
    }
    @Test fun theParentReadsTheMarkerOnlyAtTheEndOfAReportAndNeverShowsItRaw() {
        val now = System.currentTimeMillis()
        GuardEngine.recordChildHeartbeatOnParent(ctx, "summary" + GuardEngine.CAPTURE_HELD_MARK, now - 1000)
        assertTrue(GuardEngine.isChildCaptureHeld(ctx)); assertFalse(GuardEngine.getLastChildHeartbeatInfo(ctx).contains(GuardEngine.CAPTURE_HELD_MARK))
        GuardEngine.recordChildHeartbeatOnParent(ctx, "summary", now - 500)   // a later report without it: not held any more
        assertFalse(GuardEngine.isChildCaptureHeld(ctx))
        GuardEngine.recordChildHeartbeatOnParent(ctx, GuardEngine.CAPTURE_HELD_MARK + " summary", now - 100)   // not at the end: not a marker
        assertFalse(GuardEngine.isChildCaptureHeld(ctx))
    }
    @Test fun theParentCopyIsPlainAndTheSwitchOffWordingForUploadSaysCaptureStaysOff() {
        assertTrue(ScoresUi.PARENT_CAPTURE_HELD.contains("Capture is paused until your child allows it again"))
        val upload = ScoresUi.parentOffBody(ScoresMethod.UPLOAD)
        assertFalse(upload, upload.contains("earlier mode applies")); assertFalse(upload, upload.contains("reach your phone"))
        assertTrue(upload, upload.contains("stay off until your child allows")); assertTrue(upload, upload.contains("手動上載"))
        assertEquals(ScoresUi.PARENT_OFF_BODY, ScoresUi.parentOffBody(ScoresMethod.CAPTURE))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CaptureHeldParentUiTest {
    private val ctx: Application get() = RuntimeEnvironment.getApplication()
    private val scope get() = GuardEngine.getChannelTopic(ctx)
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
    private fun validReport() {
        val sent = System.currentTimeMillis() - 1000
        val state = ChildScoresState(GuardEngine.scoresModePolicy(ctx)?.policyId, "valid", "accepted", false, null, sent - 100, 0, 0, true)
        FamilyEvidence(ctx).use { it.storeScoresReport(scope, ParsedScoresReport(UUID.randomUUID().toString(), sent, state, emptyList()), System.currentTimeMillis()) }
    }
    private fun launch(section: String): ActivityController<MainActivity> = Robolectric.buildActivity(MainActivity::class.java).setup().also { shadowOf(ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).setIsDeviceSecure(true); UiSupport.nav(it.get(), section) }

    @Test fun theSwitchOffConfirmationForAnUploadPolicyDoesNotPromiseTheEarlierMode() {
        validReport(); assertTrue(GuardEngine.sendScoresModePolicy(ctx, true, ScoresMethod.UPLOAD)); validReport()
        val c = launch("family")
        try {
            UiSupport.all(c.get().window.decorView).single { it.tag == "scores_mode_switch" }.performClick()
            val text = UiSupport.texts(ShadowDialog.getLatestDialog().window!!.decorView).joinToString("\n")
            assertTrue(text, text.contains("stay off until your child allows")); assertFalse(text, text.contains("reach your phone"))
        } finally { c.pause().stop().destroy() }
    }
    @Test fun theOverviewSaysCaptureIsPausedOnlyWhenTheChildReportedIt() {
        GuardEngine.recordChildHeartbeatOnParent(ctx, "summary" + GuardEngine.CAPTURE_HELD_MARK, System.currentTimeMillis() - 1000)
        val c = launch("overview")
        try {
            val text = UiSupport.texts(c.get().window.decorView).joinToString("\n")
            assertTrue(text, text.contains(ScoresUi.PARENT_CAPTURE_HELD)); assertFalse(text, text.contains(GuardEngine.CAPTURE_HELD_MARK))
        } finally { c.pause().stop().destroy() }
        GuardEngine.recordChildHeartbeatOnParent(ctx, "summary", System.currentTimeMillis() - 500)
        val d = launch("overview")
        try { assertFalse(UiSupport.texts(d.get().window.decorView).joinToString("\n").contains(ScoresUi.PARENT_CAPTURE_HELD)) } finally { d.pause().stop().destroy() }
    }
}
