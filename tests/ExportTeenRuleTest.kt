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

    @Test fun aThreadTheTeenNeverAnsweredIsAOneToOneChatOfIncomingMessages() {
        val export = zip(
            "inbox/bea_1/message_1.html" to html(Triple("Bea Friend", "hello", 1), Triple(teen, "hi Bea", 2)),
            "inbox/cal_2/message_1.html" to html(Triple("Cal Pal", "ready?", 3), Triple(teen, "yes", 4)),
            "inbox/stranger_3/message_1.html" to html(Triple("Some Stranger", "hey you", 5), Triple("Some Stranger", "answer me", 4)))   // the teen never answered: one person only
        val p = parse(export, "export.zip")
        assertEquals(ImportResult.OK, p.result); assertEquals(0, p.groups)
        assertEquals(setOf("Bea Friend", "Cal Pal", "Some Stranger"), p.conversations.map { it.label }.toSet())
        assertEquals(listOf(false to "hey you", false to "answer me"), p.conversations.single { it.label == "Some Stranger" }.messages.map { it.outgoing to it.text })
        assertTrue(p.conversations.filter { it.label != "Some Stranger" }.all { c -> c.messages.any { it.outgoing } })
        // Without the teen known from the file, a lone sender may be the teen: it stays aside, as before.
        assertEquals(1, parse(html(Triple("Some Stranger", "hey you", 5)).toByteArray(), "message_1.html", child = "alex.handle_99").groups)
    }
    @Test fun aGroupTheTeenNeverWroteInIsSkippedAndDoesNotHideTheTeen() {
        val bea = "inbox/bea_1/message_1.html" to html(Triple("Bea Friend", "hello", 1), Triple(teen, "hi Bea", 2))
        val cal = "inbox/cal_2/message_1.html" to html(Triple("Cal Pal", "ready?", 3), Triple(teen, "yes", 4))
        val dee = "inbox/dee_3/message_1.html" to html(Triple("Dee Mate", "lunch?", 5), Triple(teen, "sure", 6))
        val club = "inbox/club_4/message_1.html" to html(Triple("Bea Friend", "party at 8", 7), Triple("Cal Pal", "count me in", 8))   // the teen never wrote here
        val p = parse(zip(bea, cal, dee, club), "export.zip")
        assertEquals(ImportResult.OK, p.result); assertEquals(1, p.groups)
        assertEquals(setOf("Bea Friend", "Cal Pal", "Dee Mate"), p.conversations.map { it.label }.toSet())
        assertTrue(p.conversations.all { c -> c.messages.any { it.outgoing } && c.messages.none { it.text == "party at 8" } })
        val big = "inbox/club_5/message_1.html" to html(Triple("Bea Friend", "party at 8", 7), Triple("Cal Pal", "count me in", 8), Triple("Dee Mate", "me too", 9))
        assertEquals(1, parse(zip(bea, cal, dee, big), "export.zip").groups)
        // A stored name found in the file decides: one chat and a silent two-sender group are enough, and the group is not taken for a chat.
        val q = parse(zip(bea, club), "export.zip", child = teen)
        assertEquals(ImportResult.OK, q.result); assertEquals(1, q.groups)
        assertEquals("Bea Friend", q.conversations.single().label); assertEquals(true, q.conversations.single().messages.first().outgoing)
        // As many chats with each friend as with the teen: nothing tells who the teen is, so nothing is guessed.
        assertEquals(ImportResult.TEEN_UNKNOWN, parse(zip(bea, cal, club), "export.zip").result)
    }
    @Test fun theFolderNamedAfterTheOtherPersonTellsTheTeen() {
        // One chat where both wrote, and a group the teen never wrote in where the friend is busier than the teen.
        val chat = "inbox/annlee_1234567890/message_1.html" to html(Triple("Ann Lee", "you there?", 1), Triple(teen, "yes", 2), Triple("Ann Lee", "call me", 3))
        val club = "inbox/weekendcrew_222/message_1.html" to html(Triple("Ann Lee", "who is in", 4), Triple("Ben Ho", "me", 5), Triple("Cy Pal", "me too", 6))
        val p = parse(zip(chat, club), "export.zip")
        assertEquals(ImportResult.OK, p.result); assertEquals(1, p.groups)
        val c = p.conversations.single()
        assertEquals("Ann Lee", c.label)
        assertEquals(listOf(false to "call me", true to "yes", false to "you there?"), c.messages.map { it.outgoing to it.text })
    }
    @Test fun aNameTheFolderCannotShowTakesTheTeenFromAnotherThread() {
        val bea = "inbox/beafriend_1/message_1.html" to html(Triple("Bea Friend", "hello", 1), Triple(teen, "hi Bea", 2))
        val chen = "inbox/chenxiaoming_2/message_1.html" to html(Triple("陳小明", "你好", 3), Triple(teen, "hi", 4))
        val club = "inbox/club_3/message_1.html" to html(Triple("Bea Friend", "party at 8", 5), Triple("陳小明", "好", 6))
        val p = parse(zip(chen, club, bea), "export.zip")
        assertEquals(ImportResult.OK, p.result); assertEquals(1, p.groups)
        assertEquals(setOf("Bea Friend", "陳小明"), p.conversations.map { it.label }.toSet())
        assertEquals(listOf(true to "hi", false to "你好"), p.conversations.single { it.label == "陳小明" }.messages.map { it.outgoing to it.text })
    }
    @Test fun withNoFolderMatchingTheOldRulesStillDecide() {
        // Every other person has a name the folder cannot show: the teen is the one in the most threads.
        val a = "inbox/chenxiaoming_1/message_1.html" to html(Triple("陳小明", "你好", 1), Triple(teen, "hi", 2))
        val b = "inbox/lihua_2/message_1.html" to html(Triple("李華", "早晨", 3), Triple(teen, "morning", 4))
        val p = parse(zip(a, b), "export.zip")
        assertEquals(ImportResult.OK, p.result); assertEquals(setOf("陳小明", "李華"), p.conversations.map { it.label }.toSet())
        assertTrue(p.conversations.all { c -> c.messages.single { it.outgoing }.text in setOf("hi", "morning") })
        // Folders that disagree are no answer either.
        val ann = "inbox/annlee_1/message_1.html" to html(Triple("Ann Lee", "hello", 1), Triple(teen, "hi", 2))
        val odd = "inbox/alexteen_2/message_1.html" to html(Triple("Ann Lee", "hey", 3), Triple(teen, "yo", 4))
        assertEquals(ImportResult.TEEN_UNKNOWN, parse(zip(ann, odd), "export.zip").result)
        // A lone file has no folder: unchanged.
        val lone = html(Triple("Ann Lee", "hello", 1), Triple(teen, "hi", 2)).toByteArray()
        assertEquals(ImportResult.TEEN_UNKNOWN, parse(lone, "message_1.html").result)
        assertEquals("Ann Lee", parse(lone, "message_1.html", child = teen).conversations.single().label)
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
