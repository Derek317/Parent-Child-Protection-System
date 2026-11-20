package app.family.igguard

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A file whose times cannot be read is not a damaged file: it says so, and tells the teen to ask Instagram for JSON. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ExportTimeUnreadableTest : UploadTestBase() {
    private val hour = 3_600_000L
    private fun block(sender: String, text: String, time: String) =
        "<div class=\"pam\"><h2 class=\"_3-95 _2pim _a6-h _a6-i\">$sender</h2><div class=\"_3-95 _a6-p\"><div><div></div><div>$text</div><div></div></div></div><div class=\"_3-94 _a6-o\">$time</div></div>"
    private fun page(vararg blocks: String) = "<html><body>" + blocks.joinToString("") + "</body></html>"

    @Test fun anAllUndatedFileIsTimeUnreadableNotMalformedAndStoresNothing() {
        activate(ScoresMethod.UPLOAD); GuardEngine.setChildSelfHandle(ctx, "alex_teen")
        val undated = page(block("Bea Friend", "see you", "yesterday-ish"), block("alex_teen", "ok", "someday"))
        val r = InstagramImport.run(ctx, ByteArrayInputStream(undated.toByteArray()), "message_1.html")
        assertEquals("TIME_UNREADABLE", r.result.name); assertEquals(0, count("risk_store"))
        val (title, body) = ScoresChildCopy.exportResult(r)
        assertTrue(body, body.contains("JSON")); assertTrue(body, body.contains("Nothing was stored")); assertNotEquals(ScoresChildCopy.exportResult(ImportSummary(ImportResult.MALFORMED)).first, title)
    }
    @Test fun theFormatWithoutTheSecondCommaIsRead() {
        val t = System.currentTimeMillis() - hour
        for (pattern in listOf("MMM dd, yyyy h:mm a", "MMM d, yyyy h:mm a")) {
            val raw = SimpleDateFormat(pattern, Locale.ENGLISH).format(Date(t))
            val parsed = InstagramExport.parseTime(raw)
            assertNotNull(raw, parsed); assertTrue(raw, Math.abs(parsed!! - t) < 60_000L)
        }
        assertNull("other locales are not guessed", InstagramExport.parseTime("12 nov 2026 3:04 pm"))
    }
}
