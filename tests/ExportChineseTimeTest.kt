package app.family.igguard

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Calendar
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The time format of a current Chinese-language HTML export ("11 月 13, 2026 9:05 下午") and the folder layout around it. Invented chats only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ExportChineseTimeTest {
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long = Calendar.getInstance().apply { clear(); set(y, mo - 1, d, h, mi, 0) }.timeInMillis

    @Test fun theMonthMarkedFormatIsReadInTheDeviceTimeZone() {
        assertEquals("no-break spaces are tolerated like the other formats", at(2026, 11, 13, 21, 5), InstagramExport.parseTime("11 月 13, 2026 9:05 下午"))
        assertEquals(at(2026, 3, 5, 11, 20), InstagramExport.parseTime("3 月 05, 2026 11:20 上午"))
        assertEquals("12 am is midnight", at(2026, 3, 5, 0, 7), InstagramExport.parseTime("3 月 05, 2026 12:07 上午"))
        assertEquals("12 pm is noon", at(2026, 3, 5, 12, 7), InstagramExport.parseTime("3 月 05, 2026 12:07 下午"))
        for (bad in listOf("13 月 01, 2026 9:05 下午", "0 月 01, 2026 9:05 下午", "2 月 30, 2026 9:05 下午", "3 月 05, 2026 13:05 上午", "3 月 05, 2026 0:05 上午", "3 月 05, 2026 9:60 上午", "3 月 05, 2026 9:05", "3月 05, 2026 9:05 上午"))
            assertNull(bad, InstagramExport.parseTime(bad))
    }

    private fun block(sender: String, text: String, time: String) =
        "<div class=\"pam _3-95 _2pi0 _2lej uiBoxWhite noborder\"><h2 class=\"_3-95 _2pim _a6-h _a6-i\">$sender</h2><div class=\"_3-95 _a6-p\"><div><div></div><div>$text</div><div></div></div></div><div class=\"_3-94 _a6-o\">$time</div></div>"
    private fun page(vararg blocks: String) = "<html><body><div class=\"_a705\">${blocks.joinToString("")}</div></body></html>"
    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> for ((name, text) in entries) { z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() } }
        return out.toByteArray()
    }

    @Test fun aRealLayoutImportsAndTheSiblingFilesAreIgnored() {
        val root = "your_instagram_activity/messages/"
        val export = zip(
            root + "inbox/bea_1/message_1.html" to page(block("Bea Friend", "see you tomorrow", "11 月 13, 2026 9:05 下午"), block("alex_teen", "ok, bring the notes", "11 月 13, 2026 9:01 下午")),
            root + "inbox/cal_2/message_1.html" to page(block("Cal Pal", "ready?", "3 月 05, 2026 11:20 上午"), block("alex_teen", "yes", "3 月 05, 2026 11:19 上午")),
            root + "inbox/bea_1/photos/123.jpg" to "not a message",
            root + "inbox/bea_1/audio/456.mp4" to "not a message",
            root + "inbox/bea_1/videos/789.mp4" to "not a message",
            root + "chats.html" to "<html><body>index of chats</body></html>",
            root + "threads_app_chats.html" to "<html><body>other app</body></html>",
            root + "ai_conversations.html" to "<html><body>ai</body></html>",
            root + "ai_conversations/threadformailbox1_1.html" to "<html><body>ai thread</body></html>")
        val p = InstagramExport.parse(ByteArrayInputStream(export), "export.zip", null, ExportLimits())
        assertEquals(ImportResult.OK, p.result); assertEquals(0, p.groups)
        assertEquals(setOf("Bea Friend", "Cal Pal"), p.conversations.map { it.label }.toSet())
        val bea = p.conversations.single { it.label == "Bea Friend" }
        assertEquals(listOf(true to "ok, bring the notes", false to "see you tomorrow"), bea.messages.map { it.outgoing to it.text })
        assertEquals(at(2026, 11, 13, 21, 1), bea.messages.first().ts)
    }
}
