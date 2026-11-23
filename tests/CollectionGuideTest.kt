package app.family.igguard

import android.content.Intent
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/** The child's upload card: the guide to Instagram's own export tool. Invented data only; no message text anywhere. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CollectionGuideTest : UploadTestBase() {
    @Before fun clearImport() { InstagramImport.dismiss(); InstagramImport.running = false }
    @After fun clearAfter() { InstagramImport.dismiss(); shadowOf(ctx).checkActivities(false) }

    private fun launch(): ActivityController<MainActivity> = Robolectric.buildActivity(MainActivity::class.java).setup().also { UiSupport.nav(it.get(), "overview") }
    private fun tagged(c: ActivityController<MainActivity>, tag: String) = UiSupport.all(c.get().window.decorView).single { it.tag == tag }
    private fun idle() = shadowOf(android.os.Looper.getMainLooper()).idle()
    /** One tap on the card's link: it must open the guide sheet and nothing else. */
    private fun openGuide(c: ActivityController<MainActivity>): android.app.Dialog {
        tagged(c, "scores_export_guide_link").performClick(); idle()
        assertNull("the link only opens the sheet", shadowOf(c.get()).nextStartedActivity)
        val sheet = org.robolectric.shadows.ShadowDialog.getLatestDialog()
        assertTrue(sheet.isShowing); assertFalse("the sheet is not the cloud notice", sheet is android.app.AlertDialog)
        return sheet
    }
    private fun inSheet(sheet: android.app.Dialog, tag: String) = UiSupport.all(sheet.window!!.decorView).single { it.tag == tag }

    @Test fun theCardKeepsOnlyTheLinkTheExportButtonAndTheImportRowInThatOrder() {
        activate(ScoresMethod.UPLOAD)
        val c = launch()
        try {
            val views = UiSupport.all(c.get().window.decorView)
            val order = listOf("scores_export_guide_link", "scores_export_open", "scores_export_row").map { tag -> views.indexOfFirst { it.tag == tag } }
            assertTrue("$order", order.all { it >= 0 } && order == order.sorted())
            assertEquals(ScoresChildCopy.EXPORT_GUIDE_TITLE, (tagged(c, "scores_export_guide_link") as android.widget.TextView).text.toString())
            val text = UiSupport.texts(c.get().window.decorView).joinToString("\n")
            for (needle in listOf("Messages only", "four days", "Meta's", ScoresChildCopy.CLOUD_TITLE)) assertFalse("$needle is in the sheet, not on the card", text.contains(needle))
            for (tag in listOf("scores_export_guide", "scores_cloud_title", "scores_cloud_drive", "scores_cloud_dropbox")) assertTrue(tag, views.none { it.tag == tag })
        } finally { c.pause().stop().destroy() }
    }
    @Test fun theGuideSheetHoldsTheStepsAndTheCloudSectionAndClosesWithItsButton() {
        activate(ScoresMethod.UPLOAD)
        val c = launch()
        try {
            val sheet = openGuide(c)
            val text = UiSupport.texts(sheet.window!!.decorView).joinToString("\n")
            for (needle in listOf(ScoresChildCopy.EXPORT_GUIDE_TITLE, "Messages only", "JSON", "four days", ScoresChildCopy.EXPORT_GUIDE_NOTE, ScoresChildCopy.CLOUD_TITLE, ScoresChildCopy.CLOUD_NOTICE)) assertTrue(needle, text.contains(needle))
            for (tag in listOf("scores_export_guide", "scores_cloud_title", "scores_cloud_drive", "scores_cloud_dropbox")) inSheet(sheet, tag)
            UiSupport.all(sheet.window!!.decorView).filterIsInstance<android.widget.Button>().single { it.text.toString() == "關閉" }.performClick(); idle()
            assertFalse(sheet.isShowing)
        } finally { c.pause().stop().destroy() }
    }
    @Test fun theGuideOpensInstagramsOwnExportToolWithAFixedHttpsLinkAndNothingElse() {
        activate(ScoresMethod.UPLOAD)
        val c = launch()
        try {
            tagged(c, "scores_export_open").performClick()
            val sent = shadowOf(c.get()).nextStartedActivity
            assertEquals(Intent.ACTION_VIEW, sent.action)
            assertEquals("https://accountscenter.instagram.com/info_and_permissions/dyi", sent.dataString)
            assertNull("nothing about the family travels with the link", sent.extras)
        } finally { c.pause().stop().destroy() }
    }
    @Test fun withNoAppForTheLinkTheChildIsToldAndNothingCrashes() {
        activate(ScoresMethod.UPLOAD)
        val c = launch()
        try {
            shadowOf(ctx).checkActivities(true)
            tagged(c, "scores_export_open").performClick()
            assertEquals(ScoresChildCopy.EXPORT_LINK_MISSING, ShadowToast.getTextOfLatestToast())
        } finally { c.pause().stop().destroy() }
    }
    @Test fun thePickerOpensAtDownloadsOffersZipJsonAndHtmlAndAWrongFileIsStillRefused() {
        activate(ScoresMethod.UPLOAD)
        val c = launch()
        try {
            tagged(c, "scores_export_row").performClick()
            val sent = shadowOf(c.get()).nextStartedActivityForResult.intent
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, sent.action)
            assertTrue(sent.hasCategory(Intent.CATEGORY_OPENABLE))
            val types = sent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)!!.toSet()
            assertTrue(types.containsAll(setOf("application/zip", "application/json", "text/html")))
            assertEquals(ScoresChildCopy.DOWNLOADS_URI, sent.getParcelableExtra<android.net.Uri>(android.provider.DocumentsContract.EXTRA_INITIAL_URI))
        } finally { c.pause().stop().destroy() }
        // The type filter is a convenience: a file that is not an Instagram export is refused by its content, whatever its name.
        assertNotEquals(ImportResult.OK, InstagramImport.run(ctx, java.io.ByteArrayInputStream("not an export".toByteArray()), "message_1.json").result)
        assertEquals(0, count("risk_store"))
    }
    @Test fun theOptionalCloudExportOpensOnlyAfterTheNoticeIsConfirmed() {
        activate(ScoresMethod.UPLOAD)
        val c = launch()
        try {
            val sheet = openGuide(c)
            val text = UiSupport.texts(sheet.window!!.decorView).joinToString("\n")
            for (needle in listOf("Meta's", "never sees it", "under that provider's terms")) assertTrue(needle, text.contains(needle))
            inSheet(sheet, "scores_cloud_drive").performClick(); shadowOf(android.os.Looper.getMainLooper()).idle()
            assertNull("one tap only shows the notice", shadowOf(c.get()).nextStartedActivity)
            val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog() as android.app.AlertDialog
            assertTrue(UiSupport.texts(dialog.window!!.decorView).any { it == ScoresChildCopy.CLOUD_NOTICE })
            dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick(); shadowOf(android.os.Looper.getMainLooper()).idle()
            assertNull("cancel opens nothing", shadowOf(c.get()).nextStartedActivity)
            for ((tag, service) in listOf("scores_cloud_drive" to "GoogleDrive", "scores_cloud_dropbox" to "Dropbox")) {
                inSheet(sheet, tag).performClick(); shadowOf(android.os.Looper.getMainLooper()).idle()
                (org.robolectric.shadows.ShadowDialog.getLatestDialog() as android.app.AlertDialog).getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
                shadowOf(android.os.Looper.getMainLooper()).idle()
                val sent = shadowOf(c.get()).nextStartedActivity
                assertEquals(Intent.ACTION_VIEW, sent.action); assertNull(sent.extras)
                assertEquals("https://accountscenter.instagram.com/info_and_permissions/dyi?source=external&account_type=1&import_service=$service&sections[0]=IG_MESSAGES&format=JSON&date_range=LAST_WEEK&schedule_frequency=ONCE_A_WEEK_FOR_ONE_YEAR&locale_override=en_US", sent.dataString)
            }
        } finally { c.pause().stop().destroy() }
    }
    // ---------- C3: prompt analysis after an import ----------
    private val scheduler get() = ctx.getSystemService(android.app.job.JobScheduler::class.java)
    private fun afterImportJob() = scheduler.allPendingJobs.singleOrNull { it.id == RiskScoringSchedule.AFTER_IMPORT_ID }
    /** One invented one-to-one chat, [hoursAgo] old, as Instagram's JSON. */
    private fun export(hoursAgo: Long = 2): ByteArray {
        val at = System.currentTimeMillis() - hoursAgo * 3_600_000L
        return org.json.JSONObject().put("participants", org.json.JSONArray().put(org.json.JSONObject().put("name", "Bea Friend")).put(org.json.JSONObject().put("name", "alex_teen")))
            .put("messages", org.json.JSONArray().put(org.json.JSONObject().put("sender_name", "Bea Friend").put("timestamp_ms", at).put("content", "invented test words")))
            .toString().toByteArray()
    }
    private fun import(bytes: ByteArray) = InstagramImport.run(ctx, java.io.ByteArrayInputStream(bytes), "message_1.json")

    @Test fun aSuccessfulImportSchedulesAOneShotRunThatNeedsChargingButNotIdle() {
        activate(ScoresMethod.UPLOAD)
        assertNull(afterImportJob())
        assertEquals(ImportResult.OK, import(export()).result)
        val job = afterImportJob()!!
        assertTrue(job.isRequireCharging); assertFalse(job.isRequireDeviceIdle); assertFalse(job.isPeriodic); assertTrue(job.isPersisted)
        assertEquals("app.family.igguard.RiskScoringJob", job.service.className)
        // The periodic idle job stays as it was: the Terms promise "only while charging" holds for all three.
        assertTrue(scheduler.allPendingJobs.filter { it.service.className.endsWith("RiskScoringJob") }.all { it.isRequireCharging })
    }
    @Test fun aFailedOrTooOldImportSchedulesNothing() {
        activate(ScoresMethod.UPLOAD)
        assertNotEquals(ImportResult.OK, import("not an export".toByteArray()).result)
        assertEquals(ImportResult.TOO_OLD, import(export(hoursAgo = 20 * 24)).result)
        assertNull(afterImportJob())
    }
    @Test fun pauseWithdrawalAndSwitchOffCancelItWhereTheOtherJobsAreCancelled() {
        activate(ScoresMethod.UPLOAD); import(export()); assertNotNull(afterImportJob())
        assertTrue(RiskLocal.pause(ctx)); assertNull("paused", afterImportJob())
        assertTrue(RiskLocal.resume(ctx)); import(export(hoursAgo = 3)); assertNotNull(afterImportJob())
        assertTrue(RiskLocal.withdraw(ctx)); assertNull("withdrawn", afterImportJob())
        assertTrue(scheduler.allPendingJobs.none { it.service.className.endsWith("RiskScoringJob") })
    }
    @Test fun aRunOfThatJobRespectsTheSamePauseCheck() {
        activate(ScoresMethod.UPLOAD); import(export())
        assertTrue(RiskLocal.pause(ctx))
        RiskScoringSchedule.afterImport(ctx); assertNull("not scheduled while paused", afterImportJob())
        assertEquals(RiskBatch.Outcome.INACTIVE, RiskBatch.run(ctx, { error("must not open the scorer") }, { false }).outcome)
    }
    @Test fun theCardTellsTheChildAnalysisStartsWhenCharging() {
        activate(ScoresMethod.UPLOAD)
        InstagramImport.last = import(export())
        val c = launch()
        try { assertTrue(UiSupport.texts(c.get().window.decorView).contains(ScoresChildCopy.EXPORT_CHARGING)) } finally { c.pause().stop().destroy() }
    }
    // ---------- C4: weekly reminder ----------
    private val day = 86_400_000L
    private fun reminder() = shadowOf(ctx.getSystemService(android.app.NotificationManager::class.java)).allNotifications
        .firstOrNull { it.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() == ScoresChildCopy.REMINDER_TITLE }

    @Test fun onlyTheTimeOfASuccessfulImportIsKeptAndTheReminderIsDueAfterSevenDays() {
        activate(ScoresMethod.UPLOAD)
        val before = System.currentTimeMillis()
        assertEquals(ImportResult.OK, import(export()).result)
        val at = RiskLocal.lastImport(ctx)
        assertTrue(at >= before)
        assertFalse(RiskLocal.importReminderDue(ctx, at + 6 * day))
        assertTrue(RiskLocal.importReminderDue(ctx, at + 8 * day))
        import("not an export".toByteArray()); assertEquals("a failed import does not count", at, RiskLocal.lastImport(ctx))
    }
    @Test fun theReminderIsContentFreeOpensTheUploadCardAndComesFromTheDailyJob() {
        activate(ScoresMethod.UPLOAD); import(export())
        RiskLocal.recordImport(ctx, System.currentTimeMillis() - 8 * day)
        val controller = Robolectric.buildService(ArchiveMaintenanceJob::class.java).create()
        try {
            assertTrue(controller.get().onStartJob(org.robolectric.shadow.api.Shadow.newInstanceOf(android.app.job.JobParameters::class.java)))
            val deadline = System.nanoTime() + 10_000_000_000L
            while (reminder() == null && System.nanoTime() < deadline) Thread.sleep(25)
        } finally { controller.destroy() }
        val n = reminder()!!
        val words = listOfNotNull(n.extras.getCharSequence(android.app.Notification.EXTRA_TEXT), n.extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT)).joinToString()
        assertFalse("no message text in the reminder", words.contains("invented test words") || words.contains("Bea"))
        val open = shadowOf(n.contentIntent).savedIntent
        assertEquals(RiskLocal.OPEN_UPLOAD_ACTION, open.action); assertNull(open.data); assertNull(open.extras)
        // Opening it lands on the overview, where the upload card is.
        val c = Robolectric.buildActivity(MainActivity::class.java, open).setup()
        try { assertTrue(UiSupport.all(c.get().window.decorView).any { it.tag == "scores_export_open" }) } finally { c.pause().stop().destroy() }
    }
    @Test fun noReminderWhilePausedAfterWithdrawalOrUnderCaptureAndTheTimeIsWipedWithTheStore() {
        activate(ScoresMethod.UPLOAD); import(export())
        val late = System.currentTimeMillis() + 8 * day
        assertTrue(RiskLocal.pause(ctx)); assertFalse("paused", RiskLocal.importReminderDue(ctx, late))
        RiskLocal.remindImportIfDue(ctx, late); assertNull(reminder())
        assertTrue(RiskLocal.resume(ctx)); assertTrue(RiskLocal.importReminderDue(ctx, late))
        assertTrue(RiskLocal.withdraw(ctx)); assertFalse("withdrawn", RiskLocal.importReminderDue(ctx, late))
        assertEquals("withdrawal wipes the time with the store", 0L, RiskLocal.lastImport(ctx))
        RiskLocal.remindImportIfDue(ctx, late); assertNull(reminder())
        switchOff(); assertEquals(0L, RiskLocal.lastImport(ctx)); assertFalse(RiskLocal.importReminderDue(ctx, late))
    }
    @Test fun onlyHttpsLinksCanBeOpened() {
        try { InstagramExportLink.intent("http://accountscenter.instagram.com/"); fail("plain http must be refused") } catch (_: IllegalArgumentException) {}
    }
}

/** C5: the parent sees how fresh the analysis is, derived only from score times already received. Invented labels only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CollectionFreshnessTest {
    private val ctx get() = ShotSupport.ctx
    private val day = 86_400_000L
    private val now = System.currentTimeMillis()
    private fun policy(method: String) = ScoresModePolicy("p1", true, now - day, method)
    private fun state() = ScoresModeState("p1", "valid", "accepted", false, null, now - 3_600_000L, 1, 0, now, now, false, true)
    private fun rows(newest: Long) = listOf(RiskScoreRow("test_account_a", 40.0, null, newest), RiskScoreRow("test_account_b", 10.0, null, newest - 2 * day))

    @Test fun theLatestAnalysisIsTheNewestScoreTimeAndTheHintNeedsUploadAndNineDays() {
        val fresh = ScoresUi.classify(policy(ScoresMethod.UPLOAD), state(), rows(now - 8 * day), now, active = true)
        assertEquals(now - 8 * day, ScoresUi.latestAnalysis(fresh)); assertNull(ScoresUi.oldAnalysisHint(fresh, now))
        val old = ScoresUi.classify(policy(ScoresMethod.UPLOAD), state(), rows(now - 10 * day), now, active = true)
        assertEquals(ScoresUi.OLD_ANALYSIS_HINT, ScoresUi.oldAnalysisHint(old, now))
        val capture = ScoresUi.classify(policy(ScoresMethod.CAPTURE), state(), rows(now - 10 * day), now, active = true)
        assertNull("under capture a quiet week is normal", ScoresUi.oldAnalysisHint(capture, now))
        assertNull(ScoresUi.latestAnalysis(ScoresUi.classify(policy(ScoresMethod.UPLOAD), state(), emptyList(), now, active = true)))
    }
    @Test fun theParentScoresPageShowsTheDateAndTheCalmHint() {
        ShotSupport.parentReset(); ShotSupport.report(); check(GuardEngine.sendScoresModePolicy(ctx, true, ScoresMethod.UPLOAD))
        ShotSupport.report(rows = listOf(ScoresReportRow("test_account_a", 62.0, null, now - 10 * day)))
        val c = ShotSupport.open("scores")
        try {
            val texts = UiSupport.texts(c.get().window.decorView)
            assertTrue(texts.contains(ScoresUi.latestAnalysisLine(now - 10 * day)))
            assertTrue(texts.contains(ScoresUi.OLD_ANALYSIS_HINT))
        } finally { c.pause().stop().destroy() }
    }
}
