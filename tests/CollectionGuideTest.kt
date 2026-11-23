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
    @Test fun onlyHttpsLinksCanBeOpened() {
        try { InstagramExportLink.intent("http://accountscenter.instagram.com/"); fail("plain http must be refused") } catch (_: IllegalArgumentException) {}
    }
}
