package app.family.igguard

import android.util.Base64
import android.widget.Button
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast

/** The parent's "Ask for a fresh Instagram export" button. Invented labels only; no message text anywhere. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ImportRequestParentTest {
    private val ctx get() = ShotSupport.ctx
    private val hour = 3_600_000L
    private val day = 24 * hour
    private val now = System.currentTimeMillis()

    @Before fun reset() { ShotSupport.parentReset(); ScoresEngineBridge.reset() }
    @After fun after() { ScoresEngineBridge.reset() }

    // ---------- when the button is offered ----------
    private fun policy(method: String = ScoresMethod.UPLOAD, enabled: Boolean = true) = ScoresModePolicy("p1", enabled, now - day, method)
    private fun state(consent: String = "accepted", paused: Boolean = false, policyId: String = "p1", model: String = "valid") =
        ScoresModeState(policyId, model, consent, paused, if (paused) now - hour else null, now - hour, 1, 0, now, now, false, true)
    private fun rows(at: Long) = listOf(RiskScoreRow("test_account_a", 40.0, null, at))
    private fun offered(p: ScoresModePolicy?, s: ScoresModeState?, r: List<RiskScoreRow> = rows(now - day), active: Boolean? = true) =
        ScoresUi.importRequestOffered(ScoresUi.classify(p, s, r, now, active))

    @Test fun offeredOnlyUnderUploadWithTheChildAgreedAndNotPaused() {
        assertTrue(offered(policy(), state()))
        assertTrue("no scores yet", offered(policy(), state(), emptyList()))
        assertTrue("agreed but not running is still agreed", offered(policy(), state(), active = false))
        assertFalse("capture", offered(policy(ScoresMethod.CAPTURE), state()))
        assertFalse("mode off", offered(policy(enabled = false), state()))
        assertFalse("no policy", offered(null, state()))
        assertFalse("no report", offered(policy(), null))
        assertFalse("paused", offered(policy(), state(paused = true)))
        assertFalse("withdrawn", offered(policy(), state(consent = "withdrawn")))
        assertFalse("not agreed yet", offered(policy(), state(consent = "none")))
        assertFalse("asked to agree again", offered(policy(), state(consent = "reconsent")))
        assertFalse("the agreement is to an older policy (method change pending)", offered(policy(), state(policyId = "p0")))
    }
    @Test fun theParentWindowIsTwentyFourHours() {
        assertFalse(ScoresUi.importRequestLocked(0L, now))
        assertTrue(ScoresUi.importRequestLocked(now - 23 * hour, now))
        assertFalse(ScoresUi.importRequestLocked(now - 24 * hour, now))
        assertTrue("a time ahead of the clock counts as inside", ScoresUi.importRequestLocked(now + hour, now))
    }
    @Test fun theCalmLineNeedsThreeDaysAndNoNewerScore() {
        val m = { at: Long -> ScoresUi.classify(policy(), state(), rows(at), now, true) }
        assertFalse(ScoresUi.noScoreSinceRequest(m(now - 5 * day), 0L, now))
        assertFalse("not yet 3 days", ScoresUi.noScoreSinceRequest(m(now - 5 * day), now - 2 * day, now))
        assertTrue(ScoresUi.noScoreSinceRequest(m(now - 5 * day), now - 4 * day, now))
        assertFalse("a newer score", ScoresUi.noScoreSinceRequest(m(now - day), now - 4 * day, now))
        assertTrue("no score at all", ScoresUi.noScoreSinceRequest(ScoresUi.classify(policy(), state(), emptyList(), now, true), now - 4 * day, now))
        for (s in listOf(ScoresUi.NO_SCORE_SINCE_REQUEST, ScoresUi.IMPORT_REQUEST_QUEUED, ScoresUi.importRequestedAt(now)))
            for (claim in listOf("已收到", "已閱讀", "delivered", "has read", "has seen")) assertFalse("$claim in $s", s.contains(claim))
    }

    // ---------- the engine ----------
    private fun parentOutbox(): List<JSONObject> {
        val token = GuardEngine.getOrCreateFamilyToken(ctx)
        val wires = FamilyEvidence(ctx).use { db -> db.readableDatabase.rawQuery("SELECT wire FROM outbox", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } } }
        val whole = mutableListOf<JSONObject>(); val parts = mutableMapOf<String, MutableList<JSONObject>>()
        for (w in wires) {
            val j = GuardEngine.decryptPayload(token, w) ?: continue
            if (j.optString("type") == "alert_fragment") parts.getOrPut(j.getString("event_id")) { mutableListOf() }.add(j) else whole.add(j)
        }
        for ((_, p) in parts) {
            val bytes = java.io.ByteArrayOutputStream(); p.sortedBy { it.getInt("part") }.forEach { bytes.write(Base64.decode(it.getString("data"), Base64.NO_WRAP)) }
            whole.add(JSONObject(bytes.toString("UTF-8")))
        }
        return whole
    }
    private fun requests() = parentOutbox().filter { it.optBoolean("relay_control") }.map { it.getJSONObject("envelope") }
        .filter { it.has("payload") && JSONObject(it.getString("payload")).optString("type") == "import_request" }
    private fun upload() { ShotSupport.report(); check(GuardEngine.sendScoresModePolicy(ctx, true, ScoresMethod.UPLOAD)); ShotSupport.report() }

    @Test fun theRequestIsSignedContentFreeAndAtMostOncePerDay() {
        upload()
        val before = System.currentTimeMillis()
        assertTrue(GuardEngine.sendImportRequest(ctx))
        val sent = requests().single()
        val payload = JSONObject(sent.getString("payload"))
        assertEquals(setOf("type", "scope", "command_id", "ts_ms"), payload.keys().asSequence().toSet())
        assertEquals(GuardEngine.getChannelTopic(ctx), payload.getString("scope"))
        val pub = LocalVault.preferences(ctx).getString("parent_signing_public", null)!!
        assertNotNull("the child's check accepts it", FamilySecurity.readCommand(sent, pub, GuardEngine.getChannelTopic(ctx), System.currentTimeMillis()))
        val at = RiskLocal.parentImportRequestAt(ctx)
        assertTrue(at >= before); assertEquals(at, payload.getLong("ts_ms"))
        assertFalse("inside 24 hours", GuardEngine.sendImportRequest(ctx)); assertEquals(1, requests().size)
        RiskLocal.saveParentImportRequest(ctx, System.currentTimeMillis() - 25 * hour)
        assertTrue(GuardEngine.sendImportRequest(ctx)); assertEquals(2, requests().size)
    }
    @Test fun refusedUnderCaptureOrWithTheModeOff() {
        ShotSupport.enable()
        assertFalse(GuardEngine.sendImportRequest(ctx)); assertTrue(requests().isEmpty()); assertEquals(0L, RiskLocal.parentImportRequestAt(ctx))
        check(GuardEngine.sendScoresModePolicy(ctx, false))
        assertFalse(GuardEngine.sendImportRequest(ctx)); assertTrue(requests().isEmpty())
    }
    @Test fun aNewFamilyForgetsTheParentsRequestTime() {
        upload(); assertTrue(GuardEngine.sendImportRequest(ctx)); assertTrue(RiskLocal.parentImportRequestAt(ctx) > 0)
        RiskLocal.resetFamily(ctx); assertEquals(0L, RiskLocal.parentImportRequestAt(ctx))
    }

    // ---------- the button on the scores page ----------
    private fun page(): List<android.view.View> { val c = ShotSupport.open("scores"); try { return UiSupport.views(c.get(), "scores_loading") } finally { c.pause().stop().destroy() } }
    private fun button(views: List<android.view.View>) = views.filter { it.tag == "scores_import_request" }.map { it as Button }.singleOrNull()

    @Test fun hiddenUnderCaptureWhilePausedAndAfterWithdrawal() {
        ShotSupport.enable(); ShotSupport.report(); assertNull("capture", button(page()))
        ShotSupport.parentReset(); upload(); ShotSupport.report(paused = true); assertNull("paused", button(page()))
        ShotSupport.parentReset(); upload(); ShotSupport.report(consent = "withdrawn"); assertNull("withdrawn", button(page()))
    }
    @Test fun enabledThenDisabledWithTheTimeAfterATap() {
        upload()
        val c = ShotSupport.open("scores")
        try {
            val b = button(UiSupport.views(c.get(), "scores_loading"))!!
            assertTrue(b.isEnabled); assertEquals(ScoresUi.IMPORT_REQUEST, b.text.toString())
            b.performClick(); org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(ScoresUi.IMPORT_REQUEST_QUEUED, ShadowToast.getTextOfLatestToast())
        } finally { c.pause().stop().destroy() }
        val at = RiskLocal.parentImportRequestAt(ctx); assertTrue(at > 0)
        val after = button(page())!!
        assertFalse(after.isEnabled); assertEquals(ScoresUi.importRequestedAt(at), after.text.toString())
        assertTrue(after.text.toString().startsWith("已於 ") && after.text.toString().contains(" / Requested "))
    }
    @Test fun aRefusedSendSaysSoAndKeepsTheButton() {
        upload(); ScoresEngineBridge.requestImport = { false }
        val c = ShotSupport.open("scores")
        try {
            button(UiSupport.views(c.get(), "scores_loading"))!!.performClick(); org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(ScoresUi.IMPORT_REQUEST_FAILED, ShadowToast.getTextOfLatestToast())
        } finally { c.pause().stop().destroy() }
        assertTrue(button(page())!!.isEnabled)
    }
    @Test fun theCalmLineShowsAfterThreeDaysWithoutANewerScore() {
        upload(); ShotSupport.report(rows = listOf(ScoresReportRow("test_account_a", 40.0, null, now - 5 * day)))
        RiskLocal.saveParentImportRequest(ctx, now - 2 * day)
        assertTrue(page().none { it.tag == "scores_no_score_since_request" })
        RiskLocal.saveParentImportRequest(ctx, now - 4 * day)
        val views = page()
        assertTrue(button(views)!!.isEnabled)
        assertEquals(ScoresUi.NO_SCORE_SINCE_REQUEST, (views.single { it.tag == "scores_no_score_since_request" } as android.widget.TextView).text.toString())
        ShotSupport.report(rows = listOf(ScoresReportRow("test_account_a", 40.0, null, now - hour)))
        assertTrue("a newer score", page().none { it.tag == "scores_no_score_since_request" })
    }
}

/** Renders of the parent's button, light and dark: enabled, requested, and the 3-day line. Written under build/test-screenshots/. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImportRequestParentScreenshotTest {
    private val ctx get() = ShotSupport.ctx
    @Before fun reset() { RuntimeEnvironment.setFontScale(1f); ShotSupport.parentReset() }

    @Test fun parentButtonLightAndDark() {
        val now = System.currentTimeMillis()
        for (bright in listOf(true, false)) for (state in listOf("enabled", "requested", "no-score-since")) {
            ShotSupport.parentReset(); GuardEngine.setBrightTheme(ctx, bright)
            ShotSupport.report(); check(GuardEngine.sendScoresModePolicy(ctx, true, ScoresMethod.UPLOAD))
            ShotSupport.report(rows = ShotSupport.rows(now - 4 * 86_400_000L))
            when (state) {
                "requested" -> RiskLocal.saveParentImportRequest(ctx, now - 2 * 3_600_000L)
                "no-score-since" -> RiskLocal.saveParentImportRequest(ctx, now - 3 * 86_400_000L - 3_600_000L)
            }
            val c = ShotSupport.open("scores")
            try { UiSupport.views(c.get(), "scores_loading"); ShotSupport.shoot(c.get(), "import-request-parent-$state-" + if (bright) "light" else "dark", 360, 1400) } finally { c.pause().stop().destroy() }
        }
        GuardEngine.setBrightTheme(ctx, true)
    }
}
