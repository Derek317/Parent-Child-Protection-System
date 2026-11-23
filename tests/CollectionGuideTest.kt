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

    @Test fun theGuideOpensInstagramsOwnExportToolWithAFixedHttpsLinkAndNothingElse() {
        activate(ScoresMethod.UPLOAD)
        val c = launch()
        try {
            val text = UiSupport.texts(c.get().window.decorView).joinToString("\n")
            for (needle in listOf(ScoresChildCopy.EXPORT_GUIDE_TITLE, "Messages only", "JSON", "four days")) assertTrue(needle, text.contains(needle))
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
            val text = UiSupport.texts(c.get().window.decorView).joinToString("\n")
            for (needle in listOf("Meta's", "never sees it", "under that provider's terms")) assertTrue(needle, text.contains(needle))
            tagged(c, "scores_cloud_drive").performClick(); shadowOf(android.os.Looper.getMainLooper()).idle()
            assertNull("one tap only shows the notice", shadowOf(c.get()).nextStartedActivity)
            val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog() as android.app.AlertDialog
            assertTrue(UiSupport.texts(dialog.window!!.decorView).any { it == ScoresChildCopy.CLOUD_NOTICE })
            dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick(); shadowOf(android.os.Looper.getMainLooper()).idle()
            assertNull("cancel opens nothing", shadowOf(c.get()).nextStartedActivity)
            for ((tag, service) in listOf("scores_cloud_drive" to "GoogleDrive", "scores_cloud_dropbox" to "Dropbox")) {
                tagged(c, tag).performClick(); shadowOf(android.os.Looper.getMainLooper()).idle()
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
    @Test fun onlyHttpsLinksCanBeOpened() {
        try { InstagramExportLink.intent("http://accountscenter.instagram.com/"); fail("plain http must be refused") } catch (_: IllegalArgumentException) {}
    }
}
