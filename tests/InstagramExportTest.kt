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

/** The Instagram export parser. Invented chats only; the parser has no Android UI and no storage. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class InstagramExportTest {
    private val now = System.currentTimeMillis()
    private val hour = 3_600_000L
    private val teen = "Alex Teen"
    private val friend = "Bea Friend"

    /** (sender, text, hours ago), newest first as Instagram writes them. */
    private data class M(val sender: String, val text: String, val hoursAgo: Int)
    private val chat = listOf(M(friend, "see you tomorrow", 1), M(teen, "ok, bring the notes", 2), M(friend, "did you finish the maths?", 3), M(teen, "hi Bea", 4))

    private fun time(hoursAgo: Int) = SimpleDateFormat("MMM dd, yyyy, h:mm a", Locale.ENGLISH).format(Date(now - hoursAgo * hour))
    private fun block(sender: String, bodyHtml: String, time: String, reactions: String = "") =
        "<div class=\"pam _3-95 _2pi0 _2lej uiBoxWhite noborder\"><h2 class=\"_3-95 _2pim _a6-h _a6-i\">$sender</h2>" +
            "<div class=\"_3-95 _a6-p\"><div><div></div><div>$bodyHtml</div><div></div></div>$reactions</div><div class=\"_3-94 _a6-o\">$time</div></div>"
    private fun html(blocks: List<String>) = "<html><head><title>x</title></head><body><div class=\"_a705\">${blocks.joinToString("")}</div></body></html>"
    private fun htmlOf(messages: List<M>) = html(messages.map { block(it.sender, it.text, time(it.hoursAgo)) })
    private fun latin1(s: String) = String(s.toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)
    private fun jsonOf(messages: List<M>, participants: List<String> = listOf(friend, teen), extra: (M, JSONObject) -> Unit = { _, _ -> }): String {
        val array = JSONArray()
        for (m in messages) array.put(JSONObject().put("sender_name", latin1(m.sender)).put("timestamp_ms", now - m.hoursAgo * hour).put("content", latin1(m.text)).also { extra(m, it) })
        return JSONObject().put("participants", JSONArray(participants.map { JSONObject().put("name", latin1(it)) })).put("messages", array).put("title", "x").toString()
    }
    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> for ((name, bytes) in entries) { z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() } }
        return out.toByteArray()
    }
    private fun parse(bytes: ByteArray, name: String? = null, child: String? = null, limits: ExportLimits = ExportLimits()) = InstagramExport.parse(ByteArrayInputStream(bytes), name, child, limits)
    private fun parse(text: String, name: String? = null, child: String? = null) = parse(text.toByteArray(Charsets.UTF_8), name, child)
    private fun pairs(c: ExportConversation) = c.messages.map { it.outgoing to it.text }

    @Test fun htmlAndJsonSamplesGiveTheSameMessagesOldestFirst() {
        val fromHtml = parse(htmlOf(chat), "message_1.html", child = teen)
        val fromJson = parse(jsonOf(chat), "message_1.json")   // no child name: the first-listed participant is the other person
        for (p in listOf(fromHtml, fromJson)) {
            assertEquals(ImportResult.OK, p.result); assertEquals(0, p.groups)
            assertEquals(friend, p.conversations.single().label)
        }
        val expected = listOf(true to "hi Bea", false to "did you finish the maths?", true to "ok, bring the notes", false to "see you tomorrow")
        assertEquals(expected, pairs(fromHtml.conversations.single())); assertEquals(expected, pairs(fromJson.conversations.single()))
        assertTrue("HTML times parse", fromHtml.conversations.single().messages.all { it.ts != null })
        assertTrue(fromHtml.conversations.single().messages.zipWithNext().all { (a, b) -> a.ts!! < b.ts!! })
    }

    @Test fun aFloodOfUnclosedTagsAfterATimeDoesNotHangTheImport() {
        // Security-audit L6: about 200 KB of "<" with no ">" and no "</div>" after the time element took quadratic time.
        val flood = "<html><body>" + block(teen, "hi Bea", time(2)) +
            "<div class=\"pam\"><h2 class=\"_3-95 _2pim _a6-h _a6-i\">$friend</h2><div class=\"_3-95 _a6-p\"><div>hello</div></div><div class=\"_3-94 _a6-o\">" + "<".repeat(200_000)
        val started = System.nanoTime()
        val p = parse(flood, "message_1.html", child = teen)
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue("parse took $ms ms", ms < 3_000)
        assertEquals(ImportResult.OK, p.result)
        assertEquals(listOf(false to "hello", true to "hi Bea"), pairs(p.conversations.single()))
    }
    @Test fun htmlReactionsAreIgnoredAndMediaAndLinksBecomePlaceholders() {
        val secretCaption = "SOMEONE-ELSES-CAPTION"
        val blocks = listOf(
            block(friend, "<a href=\"https://example.invalid/p/1\">https://example.invalid/p/1</a><br/>$secretCaption", time(1)),
            block(teen, "<img src=\"photos/1.jpg\"/>", time(2)),
            block(friend, "<video src=\"videos/1.mp4\"></video>", time(3)),
            block(teen, "<audio src=\"audio/1.mp4\"></audio>", time(4)),
            block(friend, "real words &amp; more", time(5), "<ul class=\"_a6-q\"><li>REACTION-TEXT ${'\u2764'} Alex</li></ul>"),
            block(teen, "line one<br/>line two", time(6))
        )
        val c = parse(html(blocks), "message_1.html", child = teen).conversations.single()
        assertEquals(listOf(true to "line one\nline two", false to "real words & more", true to "[voice note]", false to "[video]", true to "[photo]", false to "[link]"), pairs(c))
        val all = c.messages.joinToString("|") { it.text }
        assertFalse(all.contains(secretCaption)); assertFalse(all.contains("REACTION-TEXT")); assertFalse(all.contains("example.invalid"))
    }

    @Test fun jsonPlaceholdersAndMojibake() {
        val messages = listOf(M(friend, "caf\u00e9 \u4f60\u597d \uD83D\uDE00", 1), M(teen, "x", 2), M(friend, "SHARED-CAPTION", 3), M(teen, "x", 4), M(friend, "x", 5), M(teen, "x", 6))
        val json = jsonOf(messages) { m, o ->
            when (m.hoursAgo) {
                2 -> o.remove("content").also { o.put("photos", JSONArray().put(JSONObject().put("uri", "a.jpg"))) }
                3 -> o.put("share", JSONObject().put("link", "https://example.invalid/x").put("share_text", "SHARED-CAPTION"))
                4 -> o.remove("content").also { o.put("videos", JSONArray().put(JSONObject().put("uri", "a.mp4"))) }
                5 -> o.remove("content").also { o.put("audio_files", JSONArray().put(JSONObject().put("uri", "a.mp4"))) }
                6 -> o.put("content", latin1("reply")).put("reactions", JSONArray().put(JSONObject().put("reaction", "REACTION-TEXT").put("actor", "Bea")))
            }
        }
        val c = parse(json, "message_1.json").conversations.single()
        assertEquals(listOf(true to "reply", false to "[voice note]", true to "[video]", false to "[link]", true to "[photo]", false to "caf\u00e9 \u4f60\u597d \uD83D\uDE00"), pairs(c))
        assertFalse(c.messages.joinToString("|") { it.text }.let { it.contains("SHARED-CAPTION") || it.contains("REACTION-TEXT") })
        assertEquals(friend, c.label)
        // Text that is not mojibake stays as it is (it is not valid UTF-8 once re-read as bytes).
        assertEquals("caf\u00e9", InstagramExport.fixMojibake("caf\u00e9")); assertEquals("\u4f60\u597d", InstagramExport.fixMojibake("\u4f60\u597d")); assertEquals("plain", InstagramExport.fixMojibake("plain"))
        assertEquals("\u00c3(", InstagramExport.fixMojibake("\u00c3("))
    }

    @Test fun deeplyNestedJsonIsMalformedNotACrash() {
        val deep = "{\"messages\":" + "[".repeat(100_000) + "]".repeat(100_000) + "}"
        assertEquals(ImportResult.MALFORMED, parse(deep, "message_1.json").result)
        assertEquals(ImportResult.MALFORMED, parse("{\"a\":" + "{\"a\":".repeat(100_000) + "1" + "}".repeat(100_001), "message_1.json").result)
    }

    @Test fun jsonOrdersByItsOwnTimestampsWhateverTheFileOrder() {
        val shuffled = listOf(chat[2], chat[0], chat[3], chat[1])
        val c = parse(jsonOf(shuffled), "message_1.json").conversations.single()
        assertEquals(listOf("hi Bea", "did you finish the maths?", "ok, bring the notes", "see you tomorrow"), c.messages.map { it.text })
    }

    @Test fun htmlWithUnreadableTimesKeepsFileOrderReversed() {
        val blocks = chat.map { block(it.sender, it.text, "yesterday-ish") }
        val c = parse(html(blocks), "message_1.html", child = teen).conversations.single()
        assertEquals(listOf("hi Bea", "did you finish the maths?", "ok, bring the notes", "see you tomorrow"), c.messages.map { it.text })
        assertTrue(c.messages.all { it.ts == null })
        assertNotNull(InstagramExport.parseTime("Mar 04, 2026, 9:15 pm")); assertNotNull(InstagramExport.parseTime("Mar 4, 2026, 9:15\u202FPM"))
        assertNull(InstagramExport.parseTime("Mar 04, 2026, 9:15 pm and more")); assertNull(InstagramExport.parseTime("whenever"))
    }

    @Test fun aZipWithSeveralConversationsFindsTheTeenAsTheCommonParticipantAndSkipsGroups() {
        val other = listOf(M("Cy Pal", "lunch?", 1), M(teen, "yes", 2))
        val group = listOf(M("Cy Pal", "hello all", 1), M(friend, "hi", 2), M(teen, "hey", 3))
        val bytes = zip(
            "your_instagram_activity/messages/inbox/bea_1/message_1.json" to jsonOf(chat).toByteArray(),
            "your_instagram_activity/messages/inbox/cy_2/message_1.html" to htmlOf(other).toByteArray(),
            "your_instagram_activity/messages/inbox/club_3/message_1.json" to jsonOf(group, listOf("Cy Pal", friend, teen)).toByteArray(),
            "your_instagram_activity/messages/inbox/club_3/message_2.json" to jsonOf(group.take(1), listOf("Cy Pal", friend, teen)).toByteArray(),
            "media/photos/1.jpg" to ByteArray(500) { 7 }, "readme.txt" to "hi".toByteArray())
        val p = parse(bytes, "export.zip")
        assertEquals(ImportResult.OK, p.result); assertEquals(1, p.groups)
        assertEquals(setOf(friend, "Cy Pal"), p.conversations.map { it.label }.toSet())
        assertEquals(listOf(true to "yes", false to "lunch?"), pairs(p.conversations.single { it.label == "Cy Pal" }))
        assertEquals(true, p.conversations.single { it.label == friend }.messages.first().outgoing)
    }

    @Test fun messageFilesOfOneConversationAreMergedNewestFileFirst() {
        val newer = listOf(M(friend, "newest", 1), M(teen, "newer", 2))
        val older = listOf(M(friend, "older", 30), M(teen, "oldest", 31))
        val other = listOf(M("Cy Pal", "lunch?", 3), M(teen, "yes", 4))
        // The old file is listed first in the zip, as a real archive may order it.
        val p = parse(zip("inbox/bea/message_2.html" to htmlOf(older).toByteArray(), "inbox/bea/message_1.html" to htmlOf(newer).toByteArray(), "inbox/cy/message_1.html" to htmlOf(other).toByteArray()), "e.zip")
        assertEquals(listOf("oldest", "older", "newer", "newest"), p.conversations.single { it.label == friend }.messages.map { it.text })
    }

    @Test fun twoPeopleAreRequiredOtherwiseTheConversationIsAGroup() {
        val alone = listOf(M(teen, "anyone there", 1))
        val p = parse(zip("inbox/a/message_1.html" to htmlOf(alone).toByteArray(), "inbox/b/message_1.html" to htmlOf(chat).toByteArray()), "e.zip")
        assertEquals(1, p.groups); assertEquals(1, p.conversations.size)
        assertEquals(ImportResult.OK, p.result)
        assertEquals(ImportResult.NOTHING_FOUND, parse(zip("notes.txt" to "x".toByteArray()), "e.zip").result)
        assertEquals(ImportResult.NOTHING_FOUND, parse(zip(), "e.zip").result)
    }

    @Test fun theTeenRuleForASingleFile() {
        // HTML has no participant list: without a stored child name the teen cannot be told, so nothing is imported.
        assertEquals(ImportResult.TEEN_UNKNOWN, parse(htmlOf(chat), "message_1.html").result)
        // A stored name that matches decides (case and a leading @ do not matter).
        assertEquals(true, parse(htmlOf(chat), "message_1.html", child = "@ALEX TEEN").conversations.single().messages.first().outgoing)
        assertEquals(friend, parse(htmlOf(chat), "message_1.html", child = " alex teen ").conversations.single().label)
        assertEquals("the stored name decides who is who", teen, parse(htmlOf(chat), "message_1.html", child = friend).conversations.single().label)
        // A stored name that names nobody in the file (a handle is not the display name) is no guess: HTML has nothing else to go on, JSON falls back to its participant list.
        assertEquals(ImportResult.TEEN_UNKNOWN, parse(htmlOf(chat), "message_1.html", child = "someone.else").result)
        assertEquals(friend, parse(jsonOf(chat), "message_1.json", child = "someone.else").conversations.single().label)
        // JSON with no stored name: the first-listed participant is the other person.
        assertEquals(friend, parse(jsonOf(chat, listOf(friend, teen)), "message_1.json").conversations.single().label)
        assertEquals(teen, parse(jsonOf(chat, listOf(teen, friend)), "message_1.json").conversations.single().label)
    }

    @Test fun unsafeEntryNamesAreRefusedWhetherOrNotTheyAreRead() {
        val ok = jsonOf(chat).toByteArray()
        for (name in listOf("../evil/message_1.json", "inbox/../../message_1.json", "/abs/message_1.json", "\\abs\\message_1.json", "C:/x/message_1.json", "inbox/D:\\x\\message_1.json", "media/..hidden.jpg", "a/b/../c.txt")) {
            val bytes = zip("inbox/bea/message_1.json" to ok, name to "x".toByteArray())
            assertEquals(name, ImportResult.UNSAFE_ENTRY, parse(bytes, "e.zip").result)
        }
    }

    @Test fun limitsAreEnforcedWhileReading() {
        val ok = jsonOf(chat).toByteArray()
        val small = ExportLimits(maxEntries = 3, maxEntryBytes = 2_000, maxMessageBytes = 3_000, maxInflatedBytes = 50_000)
        assertEquals(ImportResult.TOO_MANY_ENTRIES, parse(zip("a/message_1.json" to ok, "b.txt" to ByteArray(1), "c.txt" to ByteArray(1), "d.txt" to ByteArray(1)), "e.zip", limits = small).result)
        assertEquals(ImportResult.TOO_LARGE, parse(zip("a/message_1.json" to ByteArray(2_001) { ' '.code.toByte() }), "e.zip", limits = small).result)
        assertEquals("a bomb: tiny when compressed, large when read", ImportResult.TOO_LARGE, parse(zip("media/big.bin" to ByteArray(60_000)), "e.zip", limits = small).result)
        val chatBytes = jsonOf(chat).toByteArray().size
        assertEquals(ImportResult.TOO_LARGE, parse(zip("a/message_1.json" to ok, "b/message_1.json" to ok), "e.zip", limits = ExportLimits(maxMessageBytes = chatBytes.toLong() + 10L)).result)
        // A single file is bounded too.
        assertEquals(ImportResult.TOO_LARGE, parse(ByteArray(2_001) { ' '.code.toByte() } + "{}".toByteArray(), "message_1.json", limits = small).result)
        assertEquals(ImportResult.OK, parse(zip("a/message_1.json" to ok, "b.txt" to ByteArray(1)), "e.zip", child = teen, limits = small.copy(maxEntries = 2, maxEntryBytes = 100_000, maxMessageBytes = 100_000)).result)
    }

    @Test fun messagesOlderThanTheWindowAreDroppedWhileReadingAndOnlyWhatIsKeptCountsAgainstTheTotal() {
        val since = now - 14 * 24 * hour
        val old = jsonOf(chat.map { it.copy(hoursAgo = it.hoursAgo + 24 * 20) }).toByteArray()
        val recent = jsonOf(listOf(M("Cy Pal", "lunch?", 1), M(teen, "yes", 2)), listOf("Cy Pal", teen)).toByteArray()
        val bytes = zip("a/message_1.json" to old, "b/message_1.json" to recent)
        val limits = ExportLimits(maxMessageBytes = recent.size + 100L)
        assertEquals("without a window every file counts, as before", ImportResult.TOO_LARGE, parse(bytes, "e.zip", limits = limits).result)
        val p = InstagramExport.parse(ByteArrayInputStream(bytes), "e.zip", null, limits, since)
        assertEquals(ImportResult.OK, p.result)
        val bea = p.conversations.single { it.label == friend }
        assertTrue(bea.old); assertTrue(bea.messages.isEmpty())
        assertEquals(listOf(true to "yes", false to "lunch?"), pairs(p.conversations.single { it.label == "Cy Pal" }))
        // An old file still counts the names it keeps.
        val crowd = jsonOf((1..200).map { M("sender with a long name number $it", "hi", 24 * 20 + it) }, emptyList()).toByteArray()
        assertEquals(ImportResult.TOO_LARGE, InstagramExport.parse(ByteArrayInputStream(zip("c/message_1.json" to crowd)), "e.zip", null, ExportLimits(maxMessageBytes = 3_000), since).result)
    }

    @Test fun aConversationIsKeyedByTheThreadNumberAtTheEndOfItsFolder() {
        fun keyOf(folder: String) = parse(zip("$folder/message_1.json" to jsonOf(chat).toByteArray()), "e.zip").conversations.single().key
        assertEquals("thread:1234567890", keyOf("your_instagram_activity/messages/inbox/beafriend_1234567890"))
        assertEquals("another name and layout, same number", "thread:1234567890", keyOf("messages/inbox/someone_else_1234567890"))
        assertEquals("a non-Latin name before the number", "thread:456", keyOf("inbox/陳小明_456"))
        assertEquals("nothing before the number", "thread:789", keyOf("inbox/_789"))
        assertEquals("no number: the folder's name", "folder:beafriend", keyOf("inbox/BeaFriend"))
        assertEquals("folder:bea_friend_x1", keyOf("inbox/bea_friend_x1"))
        assertEquals("a lone file: the other person's name", "bea friend", parse(jsonOf(chat), "message_1.json").conversations.single().key)
    }

    @Test fun malformedFilesFailPlainly() {
        assertEquals(ImportResult.MALFORMED, parse("{ not json", "message_1.json").result)
        assertEquals(ImportResult.MALFORMED, parse("{\"participants\": []}", "message_1.json").result)
        assertEquals(ImportResult.MALFORMED, parse("just words", "message_1.txt").result)
        assertEquals(ImportResult.MALFORMED, parse(byteArrayOf(0x7b, 0xC3.toByte(), 0x28, 0x7d), "message_1.json").result)
        assertEquals(ImportResult.MALFORMED, parse(zip("a/message_1.json" to jsonOf(chat).toByteArray(), "b/message_1.json" to "{ broken".toByteArray()), "e.zip").result)
        val truncated = zip("a/message_1.json" to jsonOf(chat).toByteArray()).copyOf(60)
        assertNotEquals(ImportResult.OK, parse(truncated, "e.zip").result)
        assertEquals(ImportResult.MALFORMED, parse("", "message_1.json").result)
    }

    @Test fun zipWithNonUtf8EntryNameIsMalformed() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out, java.nio.charset.Charset.forName("GBK")).use { z -> z.putNextEntry(ZipEntry("聊天/message_1.json")); z.write(jsonOf(chat).toByteArray()); z.closeEntry() }
        val bytes = out.toByteArray()
        assertEquals("general-purpose flag bit 11 (UTF-8 names) is clear", 0, bytes[7].toInt() and 0x08)
        assertEquals(ImportResult.MALFORMED, parse(bytes, "e.zip").result)
    }
}
