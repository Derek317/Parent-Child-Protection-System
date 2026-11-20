package app.family.igguard

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Telling the teen from the other person in an export: one odd thread must not sink the file, and a handle that is not the display name must not stop the JSON list from deciding. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ExportTeenRuleTest {
    private val now = System.currentTimeMillis()
    private val hour = 3_600_000L
    private val teen = "Alex Teen"
    private fun time(hoursAgo: Int) = SimpleDateFormat("MMM dd, yyyy, h:mm a", Locale.ENGLISH).format(Date(now - hoursAgo * hour))
    private fun html(vararg messages: Triple<String, String, Int>) = "<html><body>" + messages.joinToString("") { (sender, text, ago) ->
        "<div class=\"pam\"><h2 class=\"_3-95 _2pim _a6-h _a6-i\">$sender</h2><div class=\"_3-95 _a6-p\"><div><div></div><div>$text</div><div></div></div></div><div class=\"_3-94 _a6-o\">${time(ago)}</div></div>"
    } + "</body></html>"
    private fun json(participants: List<String>, vararg messages: Triple<String, String, Int>) = JSONObject()
        .put("participants", JSONArray(participants.map { JSONObject().put("name", it) }))
        .put("messages", JSONArray(messages.map { (s, t, ago) -> JSONObject().put("sender_name", s).put("timestamp_ms", now - ago * hour).put("content", t) })).toString()
    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> for ((name, text) in entries) { z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() } }
        return out.toByteArray()
    }
    private fun parse(bytes: ByteArray, name: String, child: String? = null) = InstagramExport.parse(ByteArrayInputStream(bytes), name, child, ExportLimits())

    @Test fun aThreadTheTeenNeverAnsweredDoesNotEmptyTheCommonParticipant() {
        val export = zip(
            "inbox/bea_1/message_1.html" to html(Triple("Bea Friend", "hello", 1), Triple(teen, "hi Bea", 2)),
            "inbox/cal_2/message_1.html" to html(Triple("Cal Pal", "ready?", 3), Triple(teen, "yes", 4)),
            "inbox/stranger_3/message_1.html" to html(Triple("Some Stranger", "hey you", 5)))   // the teen never answered: one person only
        val p = parse(export, "export.zip")
        assertEquals(ImportResult.OK, p.result)
        assertEquals(setOf("Bea Friend", "Cal Pal"), p.conversations.map { it.label }.toSet())
        assertTrue(p.conversations.all { c -> c.messages.any { it.outgoing } }); assertEquals(1, p.groups)
    }
    @Test fun withTwoConversationsOnlyOneOfWhichHasTwoPeopleTheTeenIsStillUnknownInHtml() {
        val export = zip("inbox/bea_1/message_1.html" to html(Triple("Bea Friend", "hello", 1), Triple(teen, "hi", 2)),
            "inbox/stranger_3/message_1.html" to html(Triple("Some Stranger", "hey you", 5)))
        assertEquals(ImportResult.TEEN_UNKNOWN, parse(export, "export.zip").result)   // nothing in common to learn from: no guess
    }
    @Test fun aStoredHandleThatIsNotInTheFileFallsBackToTheJsonParticipantList() {
        val text = json(listOf("Bea Friend", teen), Triple("Bea Friend", "hello", 1), Triple(teen, "hi", 2))
        val p = parse(text.toByteArray(), "message_1.json", child = "alex.handle_99")
        assertEquals(ImportResult.OK, p.result); assertEquals("Bea Friend", p.conversations.single().label)
        assertEquals(true, p.conversations.single().messages.first().outgoing)
        // HTML has no participant list to fall back to, and still guesses nothing.
        assertEquals(ImportResult.TEEN_UNKNOWN, parse(html(Triple("Bea Friend", "hello", 1), Triple(teen, "hi", 2)).toByteArray(), "message_1.html", child = "alex.handle_99").result)
        // A handle that is in the file still decides over the list.
        assertEquals("Bea Friend", parse(text.toByteArray(), "message_1.json", child = teen).conversations.single().label)
        assertEquals(teen, parse(text.toByteArray(), "message_1.json", child = "Bea Friend").conversations.single().label)
    }
}
