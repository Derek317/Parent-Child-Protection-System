package app.family.igguard

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
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
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowDialog
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.*
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

internal object UiSupport {
    fun all(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
    fun texts(v: View): List<String> = all(v).filterIsInstance<TextView>().map { it.text.toString() }
    /** Every string a screen reader or a sighted user could meet in this tree. */
    fun everything(v: View): List<String> = all(v).flatMap { listOfNotNull((it as? TextView)?.text?.toString(), it.contentDescription?.toString(), (it as? TextView)?.hint?.toString()) }
    fun views(a: MainActivity, loadingTag: String): List<View> {
        for (i in 0..100) { shadowOf(Looper.getMainLooper()).idleFor(50, TimeUnit.MILLISECONDS); if (all(a.window.decorView).none { it.tag == loadingTag }) break; Thread.sleep(10) }
        return all(a.window.decorView)
    }
    fun nav(a: MainActivity, to: String) { MainActivity::class.java.getDeclaredMethod("navigateTo", String::class.java).apply { isAccessible = true }.invoke(a, to) }
    /** The screen on show: while a screen change animates, the previous one stays in the tree. */
    fun current(a: MainActivity): View = (MainActivity::class.java.getDeclaredField("contentFrame").apply { isAccessible = true }.get(a) as ViewGroup).let { it.getChildAt(it.childCount - 1) }
    fun theme(a: MainActivity): Any = MainActivity::class.java.getDeclaredMethod("currentTheme").apply { isAccessible = true }.invoke(a)!!
    fun token(theme: Any, name: String): String = theme.javaClass.getDeclaredMethod("get" + name.replaceFirstChar { it.uppercase() }).apply { isAccessible = true }.invoke(theme) as String
    fun luminance(hex: String): Double {
        val v = listOf(1, 3, 5).map { hex.substring(it, it + 2).toInt(16) / 255.0 }.map { if (it <= .04045) it / 12.92 else Math.pow((it + .055) / 1.055, 2.4) }
        return .2126 * v[0] + .7152 * v[1] + .0722 * v[2]
    }
    fun contrast(a: String, b: String): Double { val (lo, hi) = listOf(luminance(a), luminance(b)).sorted(); return (hi + .05) / (lo + .05) }
    fun measure(a: MainActivity, w: Int, h: Int): View {
        val shell = MainActivity::class.java.getDeclaredField("screenShell").apply { isAccessible = true }.get(a) as View
        shell.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)); shell.layout(0, 0, w, h)
        return shell
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ScoresParentUiTest {
    private val ctx: Application get() = RuntimeEnvironment.getApplication()
    private val scope get() = GuardEngine.getChannelTopic(ctx)
    private val marker = "SECRET-MESSAGE-MARKER"
    private val day = 86_400_000L
    private var original: ProxySelector? = null

    @Before fun reset() {
        RuntimeEnvironment.setFontScale(1f)
        ctx.deleteDatabase("family_evidence.db"); LocalVault.preferences(ctx).edit().clear().commit()
        GuardEngine.setFamilyToken(ctx, UUID.randomUUID().toString()); GuardEngine.setRole(ctx, "parent")
        original = original ?: ProxySelector.getDefault()
        ProxySelector.setDefault(object : ProxySelector() {
            override fun select(u: URI) = if (u.host == "ntfy.sh") listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 9))) else original?.select(u) ?: listOf(Proxy.NO_PROXY)
            override fun connectFailed(u: URI, a: SocketAddress, e: IOException) {}
        })
    }
    @After fun cleanup() { ProxySelector.setDefault(original); original = null }

    private fun row(label: String, score: Double?, at: Long) = ScoresReportRow(label, score, if (score == null) "insufficient_context" else null, at)
    /** Seeds the child's last report through the parent store, the same path a real report takes. */
    private fun report(model: String = "valid", consent: String = "accepted", paused: Boolean = false, rows: List<ScoresReportRow> = emptyList(),
                       policyId: String? = GuardEngine.scoresModePolicy(ctx)?.policyId, receivedAt: Long = System.currentTimeMillis(), unknown: Int = 3) {
        val sent = System.currentTimeMillis() - 1000
        val state = ChildScoresState(policyId, model, consent, paused, if (paused) sent - 5000 else null, sent - 100, rows.size, unknown)
        FamilyEvidence(ctx).use { it.storeScoresReport(scope, ParsedScoresReport(UUID.randomUUID().toString(), sent, state, rows), receivedAt) }
    }
    private fun enable() { report(policyId = null); assertTrue(GuardEngine.sendScoresModePolicy(ctx, true)) }
    private fun launch() = Robolectric.buildActivity(MainActivity::class.java).setup().also { UiSupport.nav(it.get(), "overview") }
    private fun page(c: org.robolectric.android.controller.ActivityController<MainActivity>): List<View> { UiSupport.nav(c.get(), "scores"); return UiSupport.views(c.get(), "scores_loading") }
    private fun tagged(v: List<View>, tag: String) = v.filter { it.tag == tag }

    @Test fun everyStateShowsItsOwnTitleAndOneNextStep() {
        val now = System.currentTimeMillis()
        val cases = listOf<Pair<Pair<String, String>, () -> Unit>>(
            ("僅分數模式未開啟" to "下一步：到「家庭」頁") to {},
            ("等待子女同意" to "下一步：請向孩子說明") to { enable() },
            ("等待子女同意" to "下一步：請向孩子說明") to { enable(); report(consent = "none") },
            ("子女手機尚未安裝模型" to "下一步：在子女手機的「權限」頁") to { enable(); report(model = "missing") },
            ("子女手機的模型檔不正確" to "下一步：在子女手機移除") to { enable(); report(model = "mismatch") },
            ("子女手機不支援這個功能" to "下一步：如不打算使用") to { enable(); report(model = "unsupported_device") },
            ("子女已暫停分析" to "下一步：如果想知道原因") to { enable(); report(paused = true, rows = listOf(row("amy", 60.0, now - 1000))) },
            ("子女已撤回同意" to "下一步：如要再次使用") to { enable(); report(consent = "withdrawn", rows = listOf(row("amy", 60.0, now - 1000))) },
            ("分析進行中，暫時還沒有分數" to "下一步：不需要操作") to { enable(); report() },
        )
        val titles = mutableSetOf<String>()
        for ((expected, seed) in cases) {
            reset(); seed()
            val c = launch()
            try {
                val v = page(c)
                val title = tagged(v, "scores_state_title").single() as TextView
                assertEquals(expected.first, title.text.toString())
                assertTrue(tagged(v, "scores_next_step").single().let { (it as TextView).text.toString().startsWith(expected.second) })
                assertEquals(1, tagged(v, "scores_next_step").size)
                titles.add(title.text.toString())
            } finally { c.pause().stop().destroy() }
        }
        assertEquals(8, titles.size)
    }
    @Test fun pausedShowsTheTimeAndWithdrawnMarksOldScores() {
        val now = System.currentTimeMillis(); enable(); report(paused = true, rows = listOf(row("amy", 60.0, now - 1000)))
        var c = launch()
        try {
            val v = page(c); val body = UiSupport.texts(c.get().window.decorView.let { it }).joinToString("\n")
            assertTrue(body.contains(ScoresUi.time(FamilyEvidence(ctx).use { it.scoresModeState(scope) }!!.pausedAt!!)))
            assertEquals(1, tagged(v, "score_row").size)
        } finally { c.pause().stop().destroy() }
        report(consent = "withdrawn", rows = listOf(row("amy", 60.0, now - 1000)))
        c = launch()
        try {
            val v = page(c)
            assertTrue((tagged(v, "scores_list_title").single() as TextView).text.contains("不再更新"))
            assertEquals(1, tagged(v, "score_row").size)
        } finally { c.pause().stop().destroy() }
    }
    @Test fun staleReportShowsABannerWithTheLastReportTimeAboveTheList() {
        val received = System.currentTimeMillis() - 80L * 3_600_000L
        enable(); report(rows = listOf(row("amy", 70.0, received - 1000)), receivedAt = received)
        val c = launch()
        try {
            val v = page(c)
            val banner = tagged(v, "scores_stale_banner").single()
            assertTrue(UiSupport.texts(banner).joinToString().contains(ScoresUi.time(received)))
            assertTrue(v.indexOf(banner) < v.indexOf(tagged(v, "score_row").single()))
        } finally { c.pause().stop().destroy() }
    }
    @Test fun errorStateOffersRetry() {
        val c = launch()
        try {
            UiSupport.nav(c.get(), "scores"); UiSupport.views(c.get(), "scores_loading")
            val out = android.widget.LinearLayout(c.get())
            MainActivity::class.java.getDeclaredMethod("showScoresModel", android.widget.LinearLayout::class.java, ScoresPageModel::class.java, UiSupport.theme(c.get()).javaClass)
                .apply { isAccessible = true }.invoke(c.get(), out, ScoresPageModel(ScoresPage.ERROR, emptyList(), 0, 0, null), UiSupport.theme(c.get()))
            assertTrue(UiSupport.texts(out).contains("未能讀取分數")); assertTrue(UiSupport.all(out).any { it.tag == "scores_retry" })
        } finally { c.pause().stop().destroy() }
    }
    @Test fun rowsShowBandWholeScoreLabelAndDateAndAreSorted() {
        val now = System.currentTimeMillis()
        enable()
        report(rows = listOf(row("low", 10.4, now - 3000), row("pot", 30.0, now - 3000), row("mod_new", 62.9, now - 1000), row("mod_old", 62.2, now - 2 * day),
            row("high", 91.0, now - 5000), row("review", null, now - 100), row("edge", 49.9, now - 4000)))
        val c = launch()
        try {
            val v = page(c); val rows = tagged(v, "score_row")
            assertEquals(listOf("high", "mod_new", "mod_old", "edge", "pot", "low", "review"), rows.map { r -> UiSupport.texts(r).first { it.contains(" · ") }.substringBefore(" · ") })
            val high = rows.first(); val desc = high.contentDescription.toString()
            assertTrue(UiSupport.texts(high).containsAll(listOf("高風險", "91 / 100", "high · ${ScoresUi.day(now - 5000)}")))
            assertTrue(desc.contains("高風險") && desc.contains("分數 91") && desc.contains("high") && desc.contains(ScoresUi.spokenDay(now - 5000)))
            assertTrue(UiSupport.texts(rows[3]).contains("潛在風險")); assertTrue(UiSupport.texts(rows[3]).contains("49 / 100"))
            val review = rows.last(); assertTrue(UiSupport.texts(review).contains(ScoresUi.REVIEW_TITLE))
            assertTrue(review.contentDescription.toString().contains("需要檢視") && review.contentDescription.toString().contains("review"))
            val lead = (tagged(v, "scores_lead").single() as TextView).text.toString()
            assertTrue(lead.contains("3 個對話的分數在 50 或以上") && lead.contains("另有 1 個對話需要檢視"))
            assertTrue(rows.none { it.isClickable })
        } finally { c.pause().stop().destroy() }
    }
    @Test fun noticeAndNextStepsAreAlwaysThereAndNoMessageTextAppearsAnywhere() {
        val now = System.currentTimeMillis()
        enable(); report(rows = listOf(row("amy", 80.0, now - 1000)))
        // An archived message and a child-store message both carry the marker.
        val r = ConversationCapture.record(CapturedText("amy", "$marker archive", "incoming", "amy", "child", messageTime = now - 5000), now - 4000, true, false).put("event_id", "arch-1")
        GuardEngine.recordParentAlert(ctx, r, "arch-1")
        FamilyEvidence(ctx).use { it.storeRiskMessage(scope, "store-1", "key-1", "amy", "incoming", "$marker store", now - 3000, now - 3000, 1L, now) }
        for (setup in listOf<() -> Unit>({}, { reset(); enable(); report(model = "missing") }, { reset() })) {
            setup()
            val c = launch()
            try {
                val v = page(c)
                val seen = UiSupport.everything(c.get().window.decorView).joinToString("\n")
                for (needle in listOf("只用虛構對話測試過", "可能高估，也可能漏掉真正的風險", "先和孩子好好談談", "999", "你不會收到訊息內容")) assertTrue(needle, seen.contains(needle))
                assertFalse(seen.contains(marker))
                tagged(v, "scores_safety_advice").single().performClick()
                val sheet = UiSupport.everything(ShadowDialog.getLatestDialog().window!!.decorView).joinToString("\n")
                assertTrue(sheet.contains("999")); assertFalse(sheet.contains(marker))
            } finally { c.pause().stop().destroy() }
        }
    }
    @Test fun lockedParentSeesTheLockInsteadOfTheScores() {
        enable(); report(rows = listOf(row("amy", 80.0, System.currentTimeMillis() - 1000)))
        ParentUiAuthFixture.autoApprove = false
        try {
            val c = Robolectric.buildActivity(MainActivity::class.java).setup()
            try {
                MainActivity::class.java.getDeclaredField("section").apply { isAccessible = true }.set(c.get(), "scores")
                val tree = UiSupport.all(c.get().window.decorView)
                assertTrue(tree.any { it.tag == "parent_privacy_lock" })
                assertTrue(tree.none { it.tag == "score_row" || it.tag == "scores_notice" })
            } finally { c.pause().stop().destroy() }
        } finally { ParentUiAuthFixture.autoApprove = true }
    }
    @Test fun unknownThreadCountIsShownWhenAReportExists() {
        enable(); report(unknown = 7)
        val c = launch()
        try { page(c); assertTrue(UiSupport.texts(c.get().window.decorView).any { it.contains("7 個") && it.contains("類型未能確認") }) } finally { c.pause().stop().destroy() }
    }
    @Test fun overviewRowOpensThePage() {
        enable(); report(rows = listOf(row("amy", 80.0, System.currentTimeMillis() - 1000)))
        val c = launch()
        try {
            val v = UiSupport.all(c.get().window.decorView).single { it.tag == "scores_overview_row" }
            assertTrue(v.contentDescription.toString().contains("1 個對話需要留意"))
            v.performClick(); assertTrue(UiSupport.views(c.get(), "scores_loading").any { it.tag == "score_row" })
        } finally { c.pause().stop().destroy() }
    }

    // ---------------- the switch ----------------
    private fun family(c: org.robolectric.android.controller.ActivityController<MainActivity>): List<View> { UiSupport.nav(c.get(), "family"); return UiSupport.all(c.get().window.decorView) }
    @Test fun switchIsDisabledWithoutAValidModelAndExplainsWhy() {
        for (seed in listOf<() -> Unit>({}, { report(model = "missing", policyId = null) }, { report(model = "mismatch", policyId = null) }, { report(model = "unsupported_device", policyId = null) })) {
            reset(); seed()
            val c = launch()
            try {
                val sw = family(c).single { it.tag == "scores_mode_switch" }
                assertFalse(sw.isEnabled); assertFalse(sw.isClickable)
                assertFalse(sw.createAccessibilityNodeInfo().isEnabled)
                val why = UiSupport.texts(sw).joinToString("\n")
                assertTrue(why.contains("暫時不能開啟"))
                assertTrue(why.contains("匯入模型檔 ${RiskModelSpec.FILE_NAME}") || why.contains("記憶體不足") || why.contains("還沒有收到"))
                sw.performClick()
                assertNull(GuardEngine.scoresModePolicy(ctx))
            } finally { c.pause().stop().destroy() }
        }
    }
    @Test fun enablingGoesThroughTheChangeOfModePageThenSendsThePolicy() {
        report(policyId = null)
        val c = launch()
        try {
            val sw = family(c).single { it.tag == "scores_mode_switch" }
            assertTrue(sw.isEnabled); assertTrue(sw.createAccessibilityNodeInfo().isCheckable)
            sw.performClick()
            assertNull("nothing is sent before confirming", GuardEngine.scoresModePolicy(ctx))
            val dialog = ShadowDialog.getLatestDialog(); val text = UiSupport.texts(dialog.window!!.decorView).joinToString("\n")
            for (needle in listOf("新訊息的文字不會再傳到你的手機", "現有的封存會保留，直到你自己刪除", "即使遇到緊急情況，你也不能閱讀訊息內容", "子女要在自己的手機上同意")) assertTrue(needle, text.contains(needle))
            UiSupport.all(dialog.window!!.decorView).first { it.tag == "scores_on_cancel" }.performClick()
            assertNull(GuardEngine.scoresModePolicy(ctx))
            family(c).single { it.tag == "scores_mode_switch" }.performClick()
            UiSupport.all(ShadowDialog.getLatestDialog().window!!.decorView).first { it.tag == "scores_on_confirm" }.performClick()
            assertEquals(true, GuardEngine.scoresModePolicy(ctx)?.enabled)
            assertTrue(UiSupport.texts(c.get().window.decorView).any { it.contains("等待子女同意") })
        } finally { c.pause().stop().destroy() }
    }
    @Test fun turningOffAsksFirstAndThenSendsDisable() {
        enable()
        val c = launch()
        try {
            val sw = family(c).single { it.tag == "scores_mode_switch" }
            assertTrue(sw.createAccessibilityNodeInfo().isChecked)
            sw.performClick()
            assertEquals(true, GuardEngine.scoresModePolicy(ctx)?.enabled)
            ShadowAlertDialog.getLatestAlertDialog().getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick(); shadowOf(Looper.getMainLooper()).idle()
            assertEquals(false, GuardEngine.scoresModePolicy(ctx)?.enabled)
        } finally { c.pause().stop().destroy() }
    }
    @Test fun aRefusedPolicyIsReportedNotSilent() {
        report(policyId = null)
        val c = launch()
        try {
            family(c).single { it.tag == "scores_mode_switch" }.performClick()
            // The child's report disappears between opening the page and confirming.
            ctx.deleteDatabase("family_evidence.db")
            UiSupport.all(ShadowDialog.getLatestDialog().window!!.decorView).first { it.tag == "scores_on_confirm" }.performClick()
            assertNull(GuardEngine.scoresModePolicy(ctx))
            assertEquals(ScoresUi.SEND_FAILED, org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
        } finally { c.pause().stop().destroy() }
    }

    // ---------------- notification link and content-free alerts ----------------
    @Test fun scoresNotificationLinkOpensTheScorePage() {
        enable(); report(rows = listOf(row("amy", 80.0, System.currentTimeMillis() - 1000)))
        val c = launch()
        try {
            val a = c.get()
            assertFalse(UiSupport.all(a.window.decorView).any { it.tag == "score_row" })
            MainActivity::class.java.getDeclaredMethod("onNewIntent", Intent::class.java).apply { isAccessible = true }.invoke(a, ScoresLink.intent(ctx, scope))
            assertTrue(UiSupport.views(a, "scores_loading").any { it.tag == "score_row" })
            // A link for another family is ignored.
            UiSupport.nav(a, "overview")
            MainActivity::class.java.getDeclaredMethod("onNewIntent", Intent::class.java).apply { isAccessible = true }.invoke(a, ScoresLink.intent(ctx, "other"))
            assertFalse(UiSupport.all(UiSupport.current(a)).any { it.tag == "score_row" })
        } finally { c.pause().stop().destroy() }
    }
    @Test fun theServiceBuildsTheScoresNotificationWithTheScoresLink() {
        val service = Robolectric.buildService(ParentAlertService::class.java).get()
        val alert = JSONObject().put("type", "alert").put("reason", "scores_threshold").put("event_id", "scores:" + UUID.randomUUID()).put("sender", "").put("text", "").put("ts_ms", System.currentTimeMillis())
        ParentAlertService::class.java.getDeclaredMethod("fireNativeParentNotification", JSONObject::class.java).apply { isAccessible = true }.invoke(service, alert)
        val posted = shadowOf(ctx.getSystemService(android.app.NotificationManager::class.java)).allNotifications.single()
        val intent = shadowOf(posted.contentIntent).savedIntent
        assertTrue(ScoresLink.isScoresLink(intent, scope))
        assertFalse(posted.extras.getString("android.text")!!.contains("amy"))
    }
    @Test fun contentFreeAlertsRenderCategoryAccountAndTimeWithANoTextLine() {
        val now = System.currentTimeMillis()
        val alert = JSONObject().put("type", "alert").put("reason", "stranger_message").put("event_id", "cf-1").put("sender", "stranger_amy").put("text", "")
            .put("ts_ms", now).put("time", "10:00:00")
        assertTrue(GuardEngine.recordParentAlert(ctx, alert, "cf-1"))
        val c = launch()
        try {
            val a = c.get(); UiSupport.nav(a, "alerts")
            UiSupport.views(a, "evidence_loading").filterIsInstance<Button>().single { it.text.toString() == "需要跟進" }.performClick()
            UiSupport.views(a, "evidence_loading")
            val rows = UiSupport.texts(a.window.decorView)
            assertTrue(rows.any { it.contains("此提醒不含訊息文字") })
            val stored = FamilyEvidence(ctx).use { it.find(scope, "cf-1") }!!
            val t = UiSupport.theme(a)
            MainActivity::class.java.getDeclaredMethod("showAlertDetails", JSONObject::class.java, t.javaClass).apply { isAccessible = true }.invoke(a, stored, t)
            val sheet = UiSupport.texts(ShadowDialog.getLatestDialog().window!!.decorView)
            assertTrue(sheet.contains("未在信任名單中的訊息 / 回覆")); assertTrue(sheet.any { it.contains("stranger_amy") })
            assertTrue(sheet.any { it.contains("此提醒不含訊息文字") })
        } finally { c.pause().stop().destroy() }
    }

    // ---------------- colour ----------------
    @Test fun bandColoursMeetContrastInBothPalettes() {
        for (bright in listOf(true, false)) {
            GuardEngine.setBrightTheme(ctx, bright)
            val c = launch()
            try {
                val t = UiSupport.theme(c.get())
                for (band in RiskBand.entries.map<RiskBand, RiskBand?> { it } + null) {
                    val tint = UiSupport.token(t, ScoresUi.bandToken(band))
                    for (bg in listOf("bgCard", "bgHero", "bgCanvas")) assertTrue("$band $bg bright=$bright", UiSupport.contrast(tint, UiSupport.token(t, bg)) >= 4.5)
                }
                for (bg in listOf("bgCard", "bgHero")) assertTrue(UiSupport.contrast(UiSupport.token(t, "textPrimary"), UiSupport.token(t, bg)) >= 4.5)
            } finally { c.pause().stop().destroy(); GuardEngine.setBrightTheme(ctx, true) }
        }
    }
    @Test fun everyBandHasADistinctIconOrTitleAndTitlesAreKept() {
        assertEquals(listOf("低風險", "潛在風險", "中度風險", "高風險"), RiskBand.entries.map { ScoresUi.bandTitle(it) })
        assertEquals(5, (RiskBand.entries.map<RiskBand, RiskBand?> { it } + null).map { ScoresUi.bandIcon(it) }.toSet().size)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w320dp-h700dp-mdpi")
class ScoresNarrowLargeTextTest {
    private val ctx: Application get() = RuntimeEnvironment.getApplication()
    @Before fun reset() {
        RuntimeEnvironment.setFontScale(1.5f)
        ctx.deleteDatabase("family_evidence.db"); LocalVault.preferences(ctx).edit().clear().commit()
        GuardEngine.setFamilyToken(ctx, UUID.randomUUID().toString()); GuardEngine.setRole(ctx, "parent")
    }
    @After fun restore() { RuntimeEnvironment.setFontScale(1f) }
    private fun checkLayout(a: MainActivity) {
        val shell = UiSupport.measure(a, 320, 700)
        val targets = UiSupport.all(UiSupport.current(a)).filter { it.visibility == View.VISIBLE && it.tag != "scores_mode_switch_view" && (it is Button || (it.isClickable && it.tag == "scores_mode_switch") || (it.isClickable && it is android.widget.LinearLayout)) }
        assertTrue(targets.isNotEmpty())
        for (v in targets) assertTrue("${v.tag} ${(v as? TextView)?.text} is ${v.height}px high", v.height >= 48)
        for (v in UiSupport.all(UiSupport.current(a)).filterIsInstance<TextView>()) assertTrue("text wider than the screen: ${v.text}", v.measuredWidth <= 320)
    }
    @Test fun parentScoresPageAndSwitchFitAt320dpAndFontScale15() {
        val now = System.currentTimeMillis()
        FamilyEvidence(ctx).use {
            val scope = GuardEngine.getChannelTopic(ctx)
            it.storeScoresReport(scope, ParsedScoresReport(UUID.randomUUID().toString(), now - 1000, ChildScoresState(null, "valid", "accepted", false, null, now - 2000, 2, 1), listOf(ScoresReportRow("a_long_account_label.with.dots", 88.0, null, now - 3000), ScoresReportRow("bob", null, "context_too_long", now - 4000))), now)
        }
        GuardEngine.sendScoresModePolicy(ctx, true)
        val c = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            UiSupport.nav(c.get(), "family"); checkLayout(c.get())
            UiSupport.nav(c.get(), "scores"); UiSupport.views(c.get(), "scores_loading"); checkLayout(c.get())
            UiSupport.all(c.get().window.decorView).first { it.tag == "scores_safety_advice" }.performClick()
            val sheetRoot = ShadowDialog.getLatestDialog().window!!.decorView
            sheetRoot.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(700, View.MeasureSpec.AT_MOST)); sheetRoot.layout(0, 0, 320, sheetRoot.measuredHeight)
            for (b in UiSupport.all(sheetRoot).filterIsInstance<Button>()) assertTrue(b.height >= 48)
        } finally { c.pause().stop().destroy() }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w320dp-h700dp-mdpi")
class ScoresChildUiTest {
    private val ctx: Application get() = RuntimeEnvironment.getApplication()
    private val prefs get() = LocalVault.preferences(ctx)
    private val scope get() = GuardEngine.getChannelTopic(ctx)
    private var original: ProxySelector? = null
    private val tiny = "tiny model file for tests".toByteArray()

    @Before fun reset() {
        RuntimeEnvironment.setFontScale(1f)
        TestKeyStore.failReads = false
        ctx.deleteDatabase("family_evidence.db"); prefs.edit().clear().commit()
        ctx.getSharedPreferences("ig_guard_scores_consent", Context.MODE_PRIVATE).edit().clear().commit()
        RiskModelStore.directory(ctx).deleteRecursively()
        RiskModelStore.expectation = RiskModelStore.Expectation(RiskModelSpec.FILE_BYTES, RiskModelSpec.FILE_SHA256)
        RiskModelStore.memoryProbe = { 8L shl 30 }; RiskModelStore.abiProbe = { true }
        RiskScoringJob.scorerFactory = { error("no native scorer in unit tests") }
        ModelImportJob.importer = { c, i, p -> RiskLocal.importModel(c, i, p) }; ModelImportJob.runner = { it.run() }; ModelImportJob.dismissResult(); ModelImportJob.running = false
        GuardEngine.setFamilyToken(ctx, UUID.randomUUID().toString()); GuardEngine.setRole(ctx, "child")
        val authority = FamilySecurity.newSigningIdentity(); val id = UUID.randomUUID().toString()
        prefs.edit().putString("parent_command_public_key", authority.publicKey).putString("capture_policy_id", id).putString("capture_local_ack_id", id).commit()
        original = original ?: ProxySelector.getDefault()
        ProxySelector.setDefault(object : ProxySelector() {
            override fun select(u: URI) = if (u.host == "ntfy.sh") listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 9))) else original?.select(u) ?: listOf(Proxy.NO_PROXY)
            override fun connectFailed(u: URI, a: SocketAddress, e: IOException) {}
        })
    }
    @After fun cleanup() {
        ProxySelector.setDefault(original); original = null; RuntimeEnvironment.setFontScale(1f)
        ModelImportJob.importer = { c, i, p -> RiskLocal.importModel(c, i, p) }; ModelImportJob.runner = { Thread(it, "Model import").start() }; ModelImportJob.dismissResult()
        RiskModelStore.expectation = RiskModelStore.Expectation(RiskModelSpec.FILE_BYTES, RiskModelSpec.FILE_SHA256)
        RiskModelStore.abiProbe = { android.os.Build.SUPPORTED_ABIS.contains("arm64-v8a") }
        RiskModelStore.memoryProbe = { c -> val i = android.app.ActivityManager.MemoryInfo(); c.getSystemService(android.app.ActivityManager::class.java)?.getMemoryInfo(i); i.totalMem }
    }
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private fun installModel() {
        RiskModelStore.expectation = RiskModelStore.Expectation(tiny.size.toLong(), sha(tiny))
        assertEquals(RiskImportResult.OK, RiskModelStore.importModel(ctx, ByteArrayInputStream(tiny), {}, tiny.size.toLong(), sha(tiny))); RiskLocal.changed(ctx)
    }
    private fun policy(enabled: Boolean = true) = RiskLocal.receivePolicy(ctx, UUID.randomUUID().toString(), enabled)
    private fun activate() { installModel(); policy(); assertTrue(RiskLocal.accept(ctx)); assertTrue(RiskLocal.active(ctx)) }
    private fun launch() = Robolectric.buildActivity(MainActivity::class.java).setup().also { UiSupport.nav(it.get(), "overview") }
    private fun v(c: org.robolectric.android.controller.ActivityController<MainActivity>) = UiSupport.all(c.get().window.decorView)
    private fun tag(c: org.robolectric.android.controller.ActivityController<MainActivity>, t: String) = v(c).filter { it.tag == t }
    private fun count(table: String) = FamilyEvidence(ctx).use { db -> db.readableDatabase.rawQuery("SELECT COUNT(*) FROM $table", null).use { it.moveToFirst(); it.getInt(0) } }
    private fun stateLabel(c: org.robolectric.android.controller.ActivityController<MainActivity>) = (tag(c, "scores_state_label").single() as TextView).text.toString()

    // ---------------- consent ----------------
    @Test fun consentCardAppearsOnlyWhenAPolicyIsPending() {
        var c = launch()
        try { assertTrue(tag(c, "scores_consent_card").isEmpty()); assertTrue(tag(c, "scores_status_block").isEmpty()) } finally { c.pause().stop().destroy() }
        policy(false)
        c = launch()
        try { assertTrue(tag(c, "scores_consent_card").isEmpty()) } finally { c.pause().stop().destroy() }
        policy(true)
        c = launch()
        try {
            assertEquals(1, tag(c, "scores_consent_card").size)
            assertTrue(stateLabel(c).contains("WAITING"))
            assertTrue(ShadowDialog.getLatestDialog()?.isShowing != true)
        } finally { c.pause().stop().destroy() }
    }
    @Test fun consentPageExplainsEverythingAndNotNowStoresNothing() {
        policy()
        val c = launch()
        try {
            tag(c, "scores_consent_open").single().performClick()
            val text = UiSupport.texts(c.get().window.decorView).joinToString("\n")
            for (needle in listOf("一對一對話", "最新 60 則", "14 天", "充電", "帳戶名稱", "風險分數", "不含訊息文字", "家長不會收到你的訊息", "只用虛構的對話測試過", "兩個方向都可能出錯", "隨時暫停", "撤回", "刪除", "未獲 Meta 批准",
                "one-to-one", "latest 60 messages", "invented chats", "wrong in both directions", "Not approved by Meta")) assertTrue(needle, text.contains(needle))
            assertFalse("no pre-ticked box", v(c).any { it is android.widget.CheckBox })
            assertEquals(0, tag(c, "scores_agree").count { !it.isEnabled })
            tag(c, "scores_not_now").single().performClick()
            assertEquals("none", RiskLocal.consentState(ctx)); assertEquals(0, count("risk_store"))
            assertTrue(RiskLocal.pendingPolicy(ctx)); assertEquals(1, tag(c, "scores_consent_card").size)
        } finally { c.pause().stop().destroy() }
    }
    @Test fun agreeCallsAcceptThenShowsTheOneTimeChangePage() {
        installModel(); policy()
        val c = launch()
        try {
            tag(c, "scores_consent_open").single().performClick()
            tag(c, "scores_agree").single().performClick()
            assertEquals("accepted", RiskLocal.consentState(ctx))
            assertTrue(UiSupport.texts(c.get().window.decorView).any { it.contains("新訊息不再傳給家長") })
            tag(c, "scores_change_done").single().performClick()
            assertTrue(tag(c, "scores_change_card").isEmpty()); assertNull(ScoresNotices.pending(ctx))
            assertTrue(stateLabel(c).contains("ON"))
        } finally { c.pause().stop().destroy() }
    }
    @Test fun parentTurningTheModeOffShowsTheOffPageOnce() {
        activate(); ScoresNotices.markSeen(ctx, "on")
        policy(false)
        val c = launch()
        try {
            UiSupport.all(tag(c, "scores_change_card").single()).filterIsInstance<Button>().single().performClick()
            assertTrue(UiSupport.texts(c.get().window.decorView).any { it.contains("原有的運作方式再次適用") })
            tag(c, "scores_change_done").single().performClick()
            assertTrue(tag(c, "scores_change_card").isEmpty())
        } finally { c.pause().stop().destroy() }
    }
    @Test fun whatIsAnalysedIsReadOnly() {
        activate(); ScoresNotices.markSeen(ctx, "on")
        val c = launch()
        try {
            tag(c, "scores_what").single().performClick()
            assertTrue(UiSupport.texts(c.get().window.decorView).any { it.contains("一對一對話") })
            assertTrue(tag(c, "scores_agree").isEmpty()); assertEquals("accepted", RiskLocal.consentState(ctx))
        } finally { c.pause().stop().destroy() }
    }

    // ---------------- status block ----------------
    @Test fun statusBlockShowsEachState() {
        data class Case(val expect: String, val seed: () -> Unit)
        val cases = listOf(
            Case("WAITING") { installModel(); policy() },
            Case("ON") { activate(); ScoresNotices.markSeen(ctx, "on") },
            Case("PAUSED") { activate(); assertTrue(RiskLocal.pause(ctx)) },
            Case("WITHDRAWN") { activate(); assertTrue(RiskLocal.withdraw(ctx)) },
            Case("MODEL MISSING") { installModel(); policy(); assertTrue(RiskLocal.accept(ctx)); RiskLocal.removeModel(ctx) },
            Case("NOT AVAILABLE") { RiskModelStore.memoryProbe = { 2L shl 30 }; policy() },
        )
        val labels = mutableSetOf<String>()
        for (case in cases) {
            reset(); case.seed()
            val c = launch()
            try {
                val block = tag(c, "scores_status_block").single(); val text = UiSupport.texts(block).joinToString("\n")
                assertTrue(case.expect + " in " + stateLabel(c), stateLabel(c).contains(case.expect))
                for (needle in listOf("AI 風險分析", "充電時運行", "Runs on this phone while charging", "看不到你的訊息", "They do not see your messages", "類型未明")) assertTrue(needle, text.contains(needle))
                assertTrue(text.contains("Last run") || text.contains("Not run yet"))
                labels.add(stateLabel(c))
            } finally { c.pause().stop().destroy() }
        }
        assertEquals(6, labels.size)
    }
    @Test fun statusShowsLastRunAndUnknownCount() {
        activate(); ScoresNotices.markSeen(ctx, "on")
        val now = System.currentTimeMillis()
        RiskLocal.recordRun(ctx, 14, now)
        FamilyEvidence(ctx).use { it.noteRiskUnknown(scope, "thread-x", now) }
        val c = launch()
        try {
            val text = UiSupport.texts(tag(c, "scores_status_block").single()).joinToString("\n")
            assertTrue(text.contains(ScoresUi.time(now)) && text.contains("14 個對話")); assertTrue(text.contains("類型未明）：1 個"))
        } finally { c.pause().stop().destroy() }
    }
    @Test fun pauseResumeAndWithdrawCallTheEngineAndUpdateTheScreen() {
        activate(); ScoresNotices.markSeen(ctx, "on")
        val c = launch()
        try {
            tag(c, "scores_pause").single().performClick()
            assertTrue(RiskLocal.paused(ctx)); assertTrue(stateLabel(c).contains("PAUSED")); assertTrue(tag(c, "scores_pause").isEmpty())
            tag(c, "scores_resume").single().performClick()
            assertFalse(RiskLocal.paused(ctx)); assertTrue(stateLabel(c).contains("ON"))
            FamilyEvidence(ctx).use { it.storeRiskMessage(scope, "m1", "k1", "amy", "incoming", "hello", System.currentTimeMillis(), System.currentTimeMillis(), 1L, System.currentTimeMillis()) }
            assertEquals(1, count("risk_store"))
            tag(c, "scores_withdraw").single().performClick()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            val said = (dialog.findViewById<TextView>(android.R.id.message)).text.toString()
            assertTrue(said.contains("刪除") && said.contains("家長會被告知"))
            assertEquals("accepted", RiskLocal.consentState(ctx))
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick(); shadowOf(Looper.getMainLooper()).idle()
            assertEquals("withdrawn", RiskLocal.consentState(ctx)); assertEquals(0, count("risk_store"))
            assertTrue(stateLabel(c).contains("WITHDRAWN")); assertTrue(tag(c, "scores_withdraw").isEmpty())
        } finally { c.pause().stop().destroy() }
    }

    // ---------------- model import ----------------
    private fun permissions(c: org.robolectric.android.controller.ActivityController<MainActivity>) { UiSupport.nav(c.get(), "permissions") }
    private fun pick(c: org.robolectric.android.controller.ActivityController<MainActivity>, bytes: ByteArray = tiny) {
        val uri = Uri.parse("content://test.picker/model")
        shadowOf(ctx.contentResolver).registerInputStream(uri, ByteArrayInputStream(bytes))
        MainActivity::class.java.getDeclaredMethod("onActivityResult", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Intent::class.java).apply { isAccessible = true }
            .invoke(c.get(), 9032, android.app.Activity.RESULT_OK, Intent().setData(uri))
        shadowOf(Looper.getMainLooper()).idle()
    }
    @Test fun importRowOpensTheSystemPickerForAnyFile() {
        val c = launch()
        try {
            permissions(c); tag(c, "model_import_row").single().performClick()
            val started = shadowOf(c.get()).nextStartedActivityForResult
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, started.intent.action); assertEquals("*/*", started.intent.type); assertEquals(9032, started.requestCode)
        } finally { c.pause().stop().destroy() }
    }
    private fun resultText(c: org.robolectric.android.controller.ActivityController<MainActivity>) = UiSupport.texts(tag(c, "scores_import_result").single()).joinToString("\n")
    @Test fun aWrongFileIsRefusedWithTheExpectedNameAndSize() {
        val c = launch()
        try {
            permissions(c); pick(c)
            val text = resultText(c)
            assertTrue(text.contains("這不是指定的模型檔") && text.contains("只接受完全相同的檔案") && text.contains(RiskModelSpec.FILE_NAME) && text.contains("1,107,408,736"))
            assertEquals(RiskModelState.MISSING, RiskModelStore.state(ctx)); assertFalse(RiskModelStore.file(ctx).exists())
            tag(c, "scores_import_result").single().let { UiSupport.all(it).filterIsInstance<Button>().single().performClick() }
            assertTrue(tag(c, "scores_import_result").isEmpty())
        } finally { c.pause().stop().destroy() }
    }
    @Test fun eachImportOutcomeHasItsOwnMessage() {
        val expected = mapOf(RiskImportResult.OK to "模型已安裝", RiskImportResult.WRONG_FILE to "這不是指定的模型檔", RiskImportResult.NOT_ENOUGH_SPACE to "手機儲存空間不足",
            RiskImportResult.IO_ERROR to "讀取檔案時出錯", RiskImportResult.UNSUPPORTED_DEVICE to "這部手機不能使用")
        val seen = mutableSetOf<String>()
        for ((result, title) in expected) {
            ModelImportJob.importer = { _, _, p -> p(1_000_000L); result }
            val c = launch()
            try { permissions(c); pick(c); assertTrue(title, resultText(c).contains(title)); seen.add(resultText(c).lines().first()) } finally { c.pause().stop().destroy() }
            ModelImportJob.dismissResult()
        }
        assertEquals(5, seen.size)
    }
    @Test fun cancellingLeavesNothingAndSaysSo() {
        ModelImportJob.importer = { _, input, _ -> ModelImportJob.requestCancel(); try { input.read(ByteArray(8)); RiskImportResult.OK } catch (_: IOException) { RiskImportResult.IO_ERROR } }
        val c = launch()
        try { permissions(c); pick(c); assertTrue(resultText(c).contains("已取消匯入")); assertFalse(RiskModelStore.file(ctx).exists()) } finally { c.pause().stop().destroy() }
    }
    @Test fun progressShowsBytesCopiedOfTheTotalWithACancelButton() {
        val c = launch()
        try {
            ModelImportJob.running = true; ModelImportJob.copied = 553_704_368L
            permissions(c)
            val text = (tag(c, "scores_import_text").single() as TextView).text.toString()
            assertTrue(text, text.contains("0.52 GB / 1.03 GB"))
            assertEquals(500, (tag(c, "scores_import_bar").single() as android.widget.ProgressBar).progress)
            assertEquals(1, tag(c, "scores_import_cancel").size); assertTrue(tag(c, "model_import_row").isEmpty())
        } finally { ModelImportJob.running = false; c.pause().stop().destroy() }
    }
    @Test fun notAvailableOnThisPhoneReplacesTheImportRow() {
        RiskModelStore.memoryProbe = { 2L shl 30 }
        val c = launch()
        try {
            permissions(c)
            assertEquals(1, tag(c, "scores_model_unsupported").size); assertTrue(tag(c, "model_import_row").isEmpty())
            assertTrue(UiSupport.texts(c.get().window.decorView).any { it.contains("不能使用 AI 風險分析") })
        } finally { c.pause().stop().destroy() }
    }
    @Test fun installedModelCanBeRemovedAfterConfirmation() {
        installModel()
        val c = launch()
        try {
            permissions(c)
            assertTrue(UiSupport.texts(c.get().window.decorView).any { it.contains("模型已安裝") }); assertTrue(tag(c, "model_import_row").isEmpty())
            tag(c, "scores_model_remove").single().performClick()
            assertTrue(RiskModelStore.file(ctx).exists())
            ShadowAlertDialog.getLatestAlertDialog().getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick(); shadowOf(Looper.getMainLooper()).idle()
            assertFalse(RiskModelStore.file(ctx).exists()); assertEquals(1, tag(c, "model_import_row").size)
        } finally { c.pause().stop().destroy() }
    }

    // ---------------- layout ----------------
    @Test fun childScreensKeepTargetsAtLeast48dpAt320dpAndFontScale15() {
        RuntimeEnvironment.setFontScale(1.5f)
        activate(); ScoresNotices.markSeen(ctx, "on")
        val c = launch()
        try {
            for (screen in listOf("overview", "permissions", "scores_consent")) {
                UiSupport.nav(c.get(), screen)
                if (screen == "scores_consent") { MainActivity::class.java.getDeclaredField("scoresReadOnly").apply { isAccessible = true }.setBoolean(c.get(), true) ; UiSupport.nav(c.get(), "overview"); UiSupport.nav(c.get(), screen) }
                val shell = UiSupport.measure(c.get(), 320, 700)
                val buttons = UiSupport.all(UiSupport.current(c.get())).filterIsInstance<Button>().filter { it.visibility == View.VISIBLE }
                assertTrue(screen, buttons.isNotEmpty())
                for (b in buttons) assertTrue("$screen ${b.text} ${b.height}px", b.height >= 48)
                val sorted = buttons.map { b -> IntArray(2).also { b.getLocationOnScreen(it) }[1] to b.height }.sortedBy { it.first }
                for ((a, b) in sorted.zipWithNext()) assertTrue("buttons need 8dp between them", b.first - (a.first + a.second) >= 8 || b.first == a.first)
                for (tv in UiSupport.all(UiSupport.current(c.get())).filterIsInstance<TextView>()) assertTrue(tv.measuredWidth <= 320)
            }
        } finally { c.pause().stop().destroy() }
    }
}
