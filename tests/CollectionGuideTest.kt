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
    @Test fun onlyHttpsLinksCanBeOpened() {
        try { InstagramExportLink.intent("http://accountscenter.instagram.com/"); fail("plain http must be refused") } catch (_: IllegalArgumentException) {}
    }
}
