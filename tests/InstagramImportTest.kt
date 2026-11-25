package app.family.igguard

import android.app.Application
import android.app.Notification
import android.app.job.JobScheduler
import android.app.Person
import android.content.Context
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.*
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The Instagram import into the child's store, and the order of messages in the prompt. Invented chats only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class InstagramImportTest {
    private val ctx: Application get() = RuntimeEnvironment.getApplication()
    private val prefs get() = LocalVault.preferences(ctx)
    private val scope get() = GuardEngine.getChannelTopic(ctx)
    private val token get() = GuardEngine.getOrCreateFamilyToken(ctx)
    private var original: ProxySelector? = null
    private val secret = "PRIVATE-WORDS-"
    private val hour = 3_600_000L
    /** A valid Instagram handle, so the stored child name can be set to it; the export carries it as the display name here. */
    private val teen = "alex_teen"
    private val friend = "Bea Friend"
    private val tinyBytes = "tiny model file for tests".toByteArray()

    @Before fun reset() {
        TestKeyStore.failReads = false
        ctx.deleteDatabase("family_evidence.db"); prefs.edit().clear().commit()
        ctx.getSharedPreferences("ig_guard_scores_consent", Context.MODE_PRIVATE).edit().clear().commit()
        RiskModelStore.directory(ctx).deleteRecursively()
        RiskModelStore.expectation = RiskModelStore.Expectation(RiskModelSpec.FILE_BYTES, RiskModelSpec.FILE_SHA256)
        RiskModelStore.memoryProbe = { 8L shl 30 }; RiskModelStore.abiProbe = { true }
        RiskBatch.instructionLoader = { c -> RiskBatch.checkedInstruction(c.resources.openRawResource(R.raw.risk_instruction_v1).readBytes()) }
        RiskScoringJob.scorerFactory = { error("no native scorer in unit tests") }
        ctx.getSystemService(JobScheduler::class.java).cancelAll()
        GuardEngine.setFamilyToken(ctx, UUID.randomUUID().toString()); GuardEngine.setRole(ctx, "child")
        val authority = FamilySecurity.newSigningIdentity(); val id = UUID.randomUUID().toString()
        prefs.edit().putString("parent_command_public_key", authority.publicKey).putString("capture_policy_id", id).putString("capture_local_ack_id", id).commit()
        original = original ?: ProxySelector.getDefault()
        ProxySelector.setDefault(object : ProxySelector() {
            override fun select(u: URI) = if (u.host == "ntfy.sh") listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 9))) else original?.select(u) ?: listOf(Proxy.NO_PROXY)
            override fun connectFailed(u: URI, a: SocketAddress, e: IOException) {}
        })
        ShadowLog.clear()
    }
    @After fun cleanup() {
        RiskModelStore.abiProbe = { android.os.Build.SUPPORTED_ABIS.contains("arm64-v8a") }
        ProxySelector.setDefault(original); original = null
        RiskModelStore.expectation = RiskModelStore.Expectation(RiskModelSpec.FILE_BYTES, RiskModelSpec.FILE_SHA256)
        RiskModelStore.memoryProbe = { c -> val i = android.app.ActivityManager.MemoryInfo(); c.getSystemService(android.app.ActivityManager::class.java)?.getMemoryInfo(i); i.totalMem }
    }

    // ---------- helpers ----------
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private fun installModel(): Boolean {
        RiskModelStore.expectation = RiskModelStore.Expectation(tinyBytes.size.toLong(), sha(tinyBytes))
        val ok = RiskModelStore.importModel(ctx, ByteArrayInputStream(tinyBytes), {}, tinyBytes.size.toLong(), sha(tinyBytes)) == RiskImportResult.OK
        if (ok) RiskLocal.changed(ctx)
        return ok
    }
    private fun activate(method: String) {
        assertTrue(installModel()); RiskLocal.receivePolicy(ctx, UUID.randomUUID().toString(), true, method)
        assertTrue(RiskLocal.accept(ctx)); assertTrue(RiskLocal.active(ctx)); assertEquals(method, RiskLocal.agreedMethod(ctx))
    }
    private fun count(table: String) = FamilyEvidence(ctx).use { db -> db.readableDatabase.rawQuery("SELECT COUNT(*) FROM $table", null).use { it.moveToFirst(); it.getInt(0) } }
    private fun outbox(): List<JSONObject> {
        val wires = FamilyEvidence(ctx).use { db -> db.readableDatabase.rawQuery("SELECT wire FROM outbox WHERE scope=?", arrayOf(scope)).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } } }
        return wires.mapNotNull { GuardEngine.decryptPayload(token, it) }
    }
    /** Everything queued that is not a scores report. */
    private fun nonReportRecords() = outbox().filter { !(it.optBoolean("relay_control") && it.optJSONObject("envelope")?.optString("type") == "scores_mode_report") }
    private fun stored(): List<String> = FamilyEvidence(ctx).use { db -> db.riskDueThreads(scope).flatMap { db.riskThreadMessages(scope, it.first, 100).map { m -> "${m.speaker}: ${m.text}" } } }
    private fun runBatch(scorer: FakeScorer = FakeScorer()) = RiskBatch.run(ctx, { scorer }, { false })

    private fun time(ms: Long) = SimpleDateFormat("MMM dd, yyyy, h:mm a", Locale.ENGLISH).format(Date(ms))
    private data class M(val sender: String, val text: String, val hoursAgo: Int)
    private val chat = listOf(M(friend, "see you tomorrow", 1), M(teen, "ok, bring the notes", 2), M(friend, "did you finish the maths?", 3), M(teen, "hi Bea", 4))
    /** The time as a Chinese-language account's HTML export writes it. */
    private fun zhTime(ms: Long) = java.util.Calendar.getInstance().apply { timeInMillis = ms }.let { c ->
        val h = c.get(java.util.Calendar.HOUR_OF_DAY)
        "${c.get(java.util.Calendar.MONTH) + 1} 月 ${c.get(java.util.Calendar.DAY_OF_MONTH)}, ${c.get(java.util.Calendar.YEAR)} ${if (h % 12 == 0) 12 else h % 12}:${"%02d".format(c.get(java.util.Calendar.MINUTE))} ${if (h < 12) "上午" else "下午"}"
    }
    private fun html(messages: List<M>, now: Long = System.currentTimeMillis(), format: (Long) -> String = ::time) = "<html><body>" + messages.joinToString("") {
        "<div class=\"pam\"><h2 class=\"_3-95 _2pim _a6-h _a6-i\">${it.sender}</h2><div class=\"_3-95 _a6-p\"><div><div></div><div>${it.text}</div><div></div></div></div><div class=\"_3-94 _a6-o\">${format(now - it.hoursAgo * hour)}</div></div>"
    } + "</body></html>"
    private fun json(messages: List<M>, participants: List<String> = listOf(friend, teen), now: Long = System.currentTimeMillis()) = JSONObject()
        .put("participants", JSONArray(participants.map { JSONObject().put("name", it) }))
        .put("messages", JSONArray(messages.map { JSONObject().put("sender_name", it.sender).put("timestamp_ms", now - it.hoursAgo * hour).put("content", it.text) })).toString()
    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> for ((name, bytes) in entries) { z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() } }
        return out.toByteArray()
    }
    private fun import(bytes: ByteArray, name: String = "export.zip", limits: ExportLimits = ExportLimits()) = InstagramImport.run(ctx, ByteArrayInputStream(bytes), name, limits)

    private fun posted(text: String, sender: String = "outsider"): StatusBarNotification {
        val style = Notification.MessagingStyle(Person.Builder().setName("You").build()).addMessage(text, System.currentTimeMillis(), Person.Builder().setName(sender).build())
        val n = Notification.Builder(ctx, "test").setSmallIcon(android.R.drawable.ic_lock_idle_lock).setStyle(style).build()
        n.extras.putCharSequence(Notification.EXTRA_TITLE, "Alex Chan"); n.extras.putCharSequence(Notification.EXTRA_CONVERSATION_TITLE, sender)
        return StatusBarNotification("com.instagram.android", "com.instagram.android", 1, null, 0, 0, 0, n, android.os.UserHandle.getUserHandleForUid(0), System.currentTimeMillis())
    }
    private fun note(label: String, body: String, direction: String, ago: Long, key: String = "$label:$body:$ago") =
        CapturedText(label, body, direction, if (direction == "incoming") label else "子女", "", "notification", System.currentTimeMillis() - ago, key, false, "direct")
    private fun screen(body: String, direction: String, conversation: String = "Pal") =
        CapturedText(conversation, body, direction, if (direction == "outgoing") "子女" else "", "", "visible_dm", 0L, "", false, "unknown", true)

    // ---------- the import ----------
    @Test fun anErrorInTheImportOrModelCopyThreadEndsInAFailureNotAStuckScreen() {
        // Bug-review B-6: an Error (not an Exception) left `running` true, so the card said "Reading the file..." for ever.
        val importRunner = InstagramImport.runner; val modelRunner = ModelImportJob.runner; val importer = ModelImportJob.importer
        try {
            InstagramImport.runner = { it.run() }; ModelImportJob.runner = { it.run() }
            val export = android.net.Uri.parse("content://igguard.test/export.zip")
            org.robolectric.Shadows.shadowOf(ctx.contentResolver).registerInputStream(export, object : java.io.InputStream() {
                override fun read(): Int = throw StackOverflowError()
                override fun close() = throw StackOverflowError()
            })
            assertTrue(InstagramImport.start(ctx, export))
            assertFalse(InstagramImport.running); assertEquals(ImportResult.IO_ERROR, InstagramImport.last?.result)
            val model = android.net.Uri.parse("content://igguard.test/model.gguf")
            org.robolectric.Shadows.shadowOf(ctx.contentResolver).registerInputStream(model, ByteArrayInputStream(tinyBytes))
            ModelImportJob.importer = { _, _, _ -> throw OutOfMemoryError() }
            assertTrue(ModelImportJob.start(ctx, model))
            assertFalse(ModelImportJob.running); assertEquals(RiskImportResult.IO_ERROR, ModelImportJob.result)
        } finally {
            InstagramImport.runner = importRunner; ModelImportJob.runner = modelRunner; ModelImportJob.importer = importer
            InstagramImport.running = false; InstagramImport.dismiss(); ModelImportJob.running = false; ModelImportJob.dismissResult()
        }
    }
    @Test fun htmlAndJsonImportsStoreTheSameMessagesOldestFirstUnderTheSameConversation() {
        activate(ScoresMethod.UPLOAD); GuardEngine.setChildSelfHandle(ctx, teen)
        val a = import(html(chat).toByteArray(), "message_1.html")
        assertEquals(ImportResult.OK, a.result); assertEquals(4, a.messages); assertEquals(1, a.conversations)
        val fromHtml = stored()
        FamilyEvidence(ctx).use { it.wipeRisk() }
        val b = import(json(chat).toByteArray(), "message_1.json")
        assertEquals(ImportResult.OK, b.result); assertEquals(4, b.messages)
        assertEquals(fromHtml, stored())
        assertEquals(listOf("child: hi Bea", "other: did you finish the maths?", "child: ok, bring the notes", "other: see you tomorrow"), stored())
        val labels = FamilyEvidence(ctx).use { db -> db.riskReportRows(scope, System.currentTimeMillis()) }
        assertTrue(labels.isEmpty())   // nothing scored yet
        val result = runBatch(); assertEquals(1, result.scored)
    }
    @Test fun anImportedThreadIsScoredAndReportedLikeACapturedOne() {
        activate(ScoresMethod.UPLOAD)
        assertEquals(ImportResult.OK, import(json(chat).toByteArray(), "message_1.json").result)
        val scorer = FakeScorer(); val r = runBatch(scorer)
        assertEquals(1, r.scored); assertEquals(1, scorer.prompts.size)
        val report = outbox().filter { it.optBoolean("relay_control") }.map { it.getJSONObject("envelope") }.filter { it.optString("type") == "scores_mode_report" }.maxByOrNull { it.getLong("sent_at_ms") }!!
        assertEquals(friend, report.getJSONArray("conversations").getJSONObject(0).getString("account_label"))
        assertFalse(outbox().any { it.toString().contains("hi Bea") })
    }
    @Test fun importingTheSameExportAgainDoesNotDuplicate() {
        activate(ScoresMethod.UPLOAD)
        val fixed = System.currentTimeMillis()
        val bytes = zip("inbox/bea/message_1.json" to json(chat, now = fixed).toByteArray(), "inbox/cy/message_1.json" to json(listOf(M("Cy Pal", "lunch?", 1), M(teen, "yes", 2)), listOf("Cy Pal", teen), now = fixed).toByteArray())
        val first = import(bytes); assertEquals(ImportResult.OK, first.result); assertEquals(6, first.messages)
        val rows = count("risk_store"); val seq = stored()
        val again = import(bytes); assertEquals(ImportResult.OK, again.result); assertEquals(0, again.messages)
        assertEquals(rows, count("risk_store")); assertEquals(seq, stored())
        // One conversation's file on its own has no folder to name it: it is keyed by the name, apart from the zip's, and a repeat of it stores nothing.
        assertEquals(4, import(json(chat, now = fixed).toByteArray(), "message_1.json").messages)
        assertEquals(0, import(json(chat, now = fixed).toByteArray(), "message_1.json").messages)
        val rowsWithLone = count("risk_store")
        // HTML: the same file twice.
        GuardEngine.setChildSelfHandle(ctx, teen)
        val htmlTwice = html(chat.take(2).map { it.copy(text = "html only ${it.text}") }, fixed).toByteArray()
        val h1 = import(htmlTwice, "message_1.html"); val h2 = import(htmlTwice, "message_1.html")
        assertEquals(2, h1.messages); assertEquals(0, h2.messages); assertEquals(rowsWithLone + 2, count("risk_store"))
    }
    @Test fun theSameMessagesFromJsonHtmlOrAnotherLanguageAreStoredOnce() {
        activate(ScoresMethod.UPLOAD); GuardEngine.setChildSelfHandle(ctx, teen)
        val fixed = System.currentTimeMillis()
        assertEquals(4, import(json(chat, now = fixed).toByteArray(), "message_1.json").messages)
        assertEquals("the HTML of the same chat", 0, import(html(chat, fixed).toByteArray(), "message_1.html").messages)
        assertEquals("a Chinese-language export of it", 0, import(html(chat, fixed, ::zhTime).toByteArray(), "message_1.html").messages)
        assertEquals(4, count("risk_store")); assertEquals(4, stored().size)
    }
    @Test fun twoAccountsWithTheSameNameAreTwoConversations() {
        activate(ScoresMethod.UPLOAD)
        val first = json(listOf(M("Sam", "from the first sam", 1), M(teen, "hi first", 2)), listOf("Sam", teen))
        val second = json(listOf(M("Sam", "from the second sam", 3), M(teen, "hi second", 4)), listOf("Sam", teen))
        val r = import(zip("inbox/sam_111/message_1.json" to first.toByteArray(), "inbox/sam_222/message_1.json" to second.toByteArray()))
        assertEquals(ImportResult.OK, r.result); assertEquals(2, r.conversations); assertEquals(4, r.messages)
        assertEquals(2, FamilyEvidence(ctx).use { it.riskDueThreads(scope).size })
        val scorer = FakeScorer(); assertEquals(2, runBatch(scorer).scored)
        assertTrue("each prompt holds one account's chat", scorer.prompts.none { it.contains("first sam") && it.contains("second sam") })
        val report = outbox().filter { it.optBoolean("relay_control") }.map { it.getJSONObject("envelope") }.filter { it.optString("type") == "scores_mode_report" }.maxByOrNull { it.getLong("sent_at_ms") }!!
        assertEquals("the parent sees the name once", 1, report.getJSONArray("conversations").length())
    }
    @Test fun aConversationIsKeyedByItsThreadNumberSoANewNameIsTheSameThread() {
        activate(ScoresMethod.UPLOAD)
        val fixed = System.currentTimeMillis()
        val first = json(listOf(M("Sam", "hello", 2), M(teen, "hi", 3)), listOf("Sam", teen), fixed)
        assertEquals(2, import(zip("inbox/sam_111/message_1.json" to first.toByteArray())).messages)
        assertEquals(1, runBatch().scored); assertEquals(100.0, rows().single().second!!, 0.01)
        // Sam renamed the account and the next export is overlapping: one thread, the old messages once.
        val renamed = json(listOf(M("Samuel", "new name, same me", 1), M("Samuel", "hello", 2), M(teen, "hi", 3)), listOf("Samuel", teen), fixed)
        val r = import(zip("inbox/samuel_111/message_1.json" to renamed.toByteArray()))
        assertEquals(ImportResult.OK, r.result); assertEquals(1, r.messages); assertEquals(3, count("risk_store"))
        assertEquals(listOf("other: hello", "child: hi", "other: new name, same me").sorted(), stored().sorted())
        assertEquals("until it is scored again the old score stands", listOf("Sam" to 100.0), rows())
        // Scored again with a different result: the new score replaces the old one, it does not join it.
        assertEquals(1, runBatch(FakeScorer({ FakeScorer.LOW })).scored); assertEquals(1, count("risk_results"))
        assertEquals(listOf("Samuel" to 0.0), rows())
        // The same export again changes nothing.
        assertEquals(0, import(zip("inbox/samuel_111/message_1.json" to renamed.toByteArray())).messages)
        assertEquals(0, runBatch().scored); assertEquals(listOf("Samuel" to 0.0), rows())
    }
    @Test fun twoAccountsWithOneNameMergedBy601AreSplitByTheirThreadNumbers() {
        activate(ScoresMethod.UPLOAD)
        val fixed = System.currentTimeMillis()
        val bytes = zip("inbox/sam_111/message_1.json" to json(listOf(M("Sam", "from the first sam", 1), M(teen, "hi first", 2)), listOf("Sam", teen), fixed).toByteArray(),
            "inbox/sam_222/message_1.json" to json(listOf(M("Sam", "from the second sam", 3), M(teen, "hi second", 4)), listOf("Sam", teen), fixed).toByteArray())
        storeAs601(bytes, "export.zip")
        assertEquals("6.0.1 kept them as one thread", 1, runBatch().scored)
        val r = import(bytes)
        assertEquals(ImportResult.OK, r.result); assertEquals(0, r.messages); assertEquals(4, count("risk_store"))
        assertEquals(2, FamilyEvidence(ctx).use { it.riskDueThreads(scope).size })
        assertEquals("the merged thread's score went with the first and stands until it is scored again", listOf("Sam" to 100.0), rows())
        val scorer = FakeScorer(); assertEquals(2, runBatch(scorer).scored)
        assertTrue(scorer.prompts.none { it.contains("first sam") && it.contains("second sam") })
        assertEquals(2, count("risk_results")); assertEquals(2, count("risk_threads"))
    }
    /** The child's report rows: (label, score rounded). */
    private fun rows() = FamilyEvidence(ctx).use { it.riskReportRows(scope, System.currentTimeMillis()) }.map { it.label to it.score?.let { s -> Math.round(s).toDouble() } }
    /** What 6.0.1 stored for an import: the thread keyed by the other person's name, the id by the time exactly as the file wrote it. */
    private fun storeAs601(bytes: ByteArray, name: String) {
        val parsed = InstagramExport.parse(ByteArrayInputStream(bytes), name, teen, ExportLimits())
        val now = System.currentTimeMillis()
        FamilyEvidence(ctx).use { db ->
            for (c in parsed.conversations) {
                val key = c.label.trim().removePrefix("@").lowercase(Locale.ROOT)
                val seen = HashMap<String, Int>()
                val recent = c.messages.filter { it.ts != null && it.ts >= now - RiskLimits.DAYS * 86_400_000L }.takeLast(RiskLimits.MESSAGES)
                for ((i, m) in recent.withIndex()) {
                    val direction = if (m.outgoing) "outgoing" else "incoming"
                    val basis = "$key|${m.timeKey}|$direction|${m.text}"
                    val id = "import:" + sha("$basis|${seen.merge(basis, 1, Int::plus)}".toByteArray()).take(40)
                    assertTrue(db.storeRiskMessage(scope, id, key, c.label, direction, m.text, m.ts!!, m.ts!!, i.toLong(), now))
                }
            }
        }
    }
    @Test fun aPhoneUpgradedFrom601ThatImportsAnOverlappingExportKeepsEachMessageOnceInOneThread() {
        activate(ScoresMethod.UPLOAD); GuardEngine.setChildSelfHandle(ctx, teen)
        val fixed = System.currentTimeMillis()
        val newer = listOf(M(friend, "one more thing", 0)) + chat
        val expected = listOf("child: hi Bea", "other: did you finish the maths?", "child: ok, bring the notes", "other: see you tomorrow", "other: one more thing")
        // A zip after a 6.0.1 import of the same chat (plus an older message the new export does not hold) that was already scored.
        val older = M(friend, "an older note", 30)
        storeAs601(json(chat + older, now = fixed).toByteArray(), "message_1.json")
        assertEquals(1, runBatch().scored); assertEquals(listOf(friend to 100.0), rows())
        val export = zip("inbox/beafriend_1/message_1.json" to json(newer, now = fixed).toByteArray())
        // A failure before the import commits leaves the old thread, its messages and its score as they were.
        InstagramImport.beforeCommit = { throw IllegalStateException("invented failure") }
        try { assertEquals(ImportResult.IO_ERROR, import(export).result) } finally { InstagramImport.beforeCommit = {} }
        assertEquals(5, count("risk_store")); assertEquals(1, count("risk_results")); assertEquals(1, count("risk_threads"))
        assertEquals(listOf(friend to 100.0), rows()); assertEquals(0, runBatch().scored)
        val r = import(export)
        assertEquals(ImportResult.OK, r.result); assertEquals("only the newer message is new", 1, r.messages)
        assertEquals("one thread: the older note moved with it", 6, count("risk_store")); assertEquals(listOf("other: an older note") + expected, stored())
        assertEquals("its score moved with it and stands", listOf(friend to 100.0), rows()); assertEquals(1, count("risk_threads"))
        val scorer = FakeScorer({ FakeScorer.LOW }); assertEquals("one thread is scored, once", 1, runBatch(scorer).scored)
        assertEquals(expected.size + 1, (expected + "other: an older note").count { scorer.prompts.single().contains(it) })
        assertEquals("the new score replaced the old one", listOf(friend to 0.0), rows()); assertEquals(1, count("risk_results"))
        // The same export again changes nothing.
        assertEquals(0, import(export).messages); assertEquals(6, count("risk_store")); assertEquals(1, count("risk_results"))
        assertEquals("nothing else is due", 0, runBatch().scored); assertEquals(listOf(friend to 0.0), rows())
        // A lone HTML file after a 6.0.1 import of the same file, not yet scored.
        FamilyEvidence(ctx).use { it.wipeRisk() }
        storeAs601(html(chat, fixed).toByteArray(), "message_1.html")
        val h = import(html(newer, fixed).toByteArray(), "message_1.html")
        assertEquals(ImportResult.OK, h.result); assertEquals(1, h.messages)
        assertEquals(5, count("risk_store")); assertEquals(expected, stored())
        assertEquals(1, runBatch().scored)
    }
    @Test fun oldMessageFilesAreDroppedWhileReadingAndDoNotCountAgainstTheTotal() {
        activate(ScoresMethod.UPLOAD)
        val recent = json(chat).toByteArray()
        val olderBea = json((1..40).map { M(if (it % 2 == 0) teen else friend, "older words number $it", 24 * 20 + it) }).toByteArray()
        val oldCy = json((1..40).map { M(if (it % 2 == 0) teen else "Cy Pal", "old words number $it", 24 * 30 + it) }, listOf("Cy Pal", teen)).toByteArray()
        val limits = ExportLimits(maxMessageBytes = recent.size + 100L)
        val r = import(zip("inbox/bea/message_1.json" to recent, "inbox/bea/message_2.json" to olderBea, "inbox/cy/message_1.json" to oldCy), limits = limits)
        assertEquals(ImportResult.OK, r.result); assertEquals(1, r.conversations); assertEquals(4, r.messages); assertEquals(0, r.groups)
        assertTrue(stored().none { it.contains("old") })
        // Recent files still count in full: the cap is still a cap.
        val recentCy = json(listOf(M("Cy Pal", "lunch?", 1), M(teen, "yes", 2)), listOf("Cy Pal", teen)).toByteArray()
        assertEquals(ImportResult.TOO_LARGE, import(zip("inbox/bea/message_1.json" to recent, "inbox/cy/message_1.json" to recentCy), limits = ExportLimits(maxMessageBytes = recent.size + 10L)).result)
    }
    @Test fun anExportWhoseChatsAreAllOlderThanTheLimitSaysSoAndStoresNothing() {
        activate(ScoresMethod.UPLOAD)
        val old = listOf(M(friend, "ancient", 24 * 15), M(teen, "also ancient", 24 * 16))
        val r = import(json(old).toByteArray(), "message_1.json")
        assertEquals(ImportResult.TOO_OLD, r.result); assertEquals(0, r.messages); assertEquals(0, count("risk_store"))
        val (title, body) = ScoresChildCopy.exportResult(r)
        assertTrue(title, title.contains("14"))
        assertFalse("not blamed on the file's content", body.contains("沒有一對一對話") || body.contains("no one-to-one chats"))
        // A file that really has only groups keeps its own wording.
        val club = listOf(M("Cy Pal", "hello all", 1), M(friend, "hi", 2), M(teen, "hey", 3))
        val groups = import(json(club, listOf("Cy Pal", friend, teen)).toByteArray(), "message_1.json")
        assertEquals(ImportResult.OK, groups.result); assertEquals(0, groups.conversations)
    }
    @Test fun groupThreadsAreSkippedAndCountedAsWithCapture() {
        activate(ScoresMethod.UPLOAD)
        val club = listOf(M("Cy Pal", "hello all", 1), M(friend, "hi", 2), M(teen, "hey", 3))
        val r = import(zip("inbox/bea/message_1.json" to json(chat).toByteArray(), "inbox/club/message_1.json" to json(club, listOf("Cy Pal", friend, teen)).toByteArray()))
        assertEquals(ImportResult.OK, r.result); assertEquals(1, r.groups); assertEquals(1, r.conversations)
        assertEquals(4, count("risk_store")); assertEquals(1, RiskLocal.status(ctx).unknownThreads)
        assertTrue(stored().none { it.contains("hello all") })
        import(zip("inbox/bea/message_1.json" to json(chat).toByteArray(), "inbox/club/message_1.json" to json(club, listOf("Cy Pal", friend, teen)).toByteArray()))
        assertEquals("a repeat does not count the group twice", 1, RiskLocal.status(ctx).unknownThreads)
    }
    @Test fun theLimitsOfTheStoreApplyToImportedMessages() {
        activate(ScoresMethod.UPLOAD)
        val many = (1..100).map { M(if (it % 2 == 0) teen else friend, "message number $it", it) }   // number 1 is the newest
        val old = listOf(M(friend, "ancient", 24 * 15), M(teen, "also ancient", 24 * 16))
        assertEquals(ImportResult.OK, import(json(many + old).toByteArray(), "message_1.json").result)
        val texts = stored()
        assertEquals(RiskLimits.MESSAGES, count("risk_store")); assertEquals(RiskLimits.MESSAGES, texts.size); assertTrue(texts.none { it.contains("ancient") })
        assertTrue("the newest 60 are kept, oldest first", texts.first().endsWith("message number 60") && texts.last().endsWith("message number 1"))
        val scorer = FakeScorer(); runBatch(scorer)
        val prompt = scorer.prompts.single()
        assertTrue("the prompt carries the newest 30", prompt.contains("message number 30") && !prompt.contains("message number 31") && prompt.indexOf("message number 30") < prompt.lastIndexOf("message number 1"))
    }
    @Test fun badFilesStoreNothing() {
        activate(ScoresMethod.UPLOAD)
        val ok = json(chat).toByteArray()
        val cases = mapOf(
            "oversized" to (zip("a/message_1.json" to ByteArray(3_000) { ' '.code.toByte() }) to ExportLimits(maxEntryBytes = 2_000)),
            "too many entries" to (zip("a/message_1.json" to ok, "b.txt" to ByteArray(1), "c.txt" to ByteArray(1)) to ExportLimits(maxEntries = 2)),
            "total too large" to (zip("a/message_1.json" to ok, "b/message_1.json" to ok) to ExportLimits(maxMessageBytes = ok.size + 5L)),
            "traversal" to (zip("a/message_1.json" to ok, "../x/message_1.json" to ok) to ExportLimits()),
            "absolute" to (zip("a/message_1.json" to ok, "/x/message_1.json" to ok) to ExportLimits()),
            "drive letter" to (zip("a/message_1.json" to ok, "C:/x/message_1.json" to ok) to ExportLimits()),
            "malformed" to (zip("a/message_1.json" to ok, "b/message_1.json" to "{ broken".toByteArray()) to ExportLimits()),
            "not an export" to ("just some words".toByteArray() to ExportLimits()))
        val expected = mapOf("oversized" to ImportResult.TOO_LARGE, "too many entries" to ImportResult.TOO_MANY_ENTRIES, "total too large" to ImportResult.TOO_LARGE, "traversal" to ImportResult.UNSAFE_ENTRY,
            "absolute" to ImportResult.UNSAFE_ENTRY, "drive letter" to ImportResult.UNSAFE_ENTRY, "malformed" to ImportResult.MALFORMED, "not an export" to ImportResult.MALFORMED)
        for ((name, pair) in cases) {
            val r = import(pair.first, "x.zip", pair.second)
            assertEquals(name, expected[name], r.result); assertEquals(name, 0, r.messages)
            assertEquals(name, 0, count("risk_store")); assertEquals(name, 0, count("risk_unknown"))
        }
        // A file that parses in part is not kept in part: the second conversation names nobody the teen can be told from.
        assertEquals(ImportResult.TEEN_UNKNOWN, import(html(chat).toByteArray(), "message_1.html").result); assertEquals(0, count("risk_store"))
    }
    @Test fun anImportIsRefusedUnlessTheChildAgreedToUploadAndNothingIsPaused() {
        val bytes = json(chat).toByteArray()
        assertEquals(ImportResult.NOT_ALLOWED, import(bytes).result)                          // nothing agreed
        activate(ScoresMethod.CAPTURE); assertEquals(ImportResult.NOT_ALLOWED, import(bytes).result); assertFalse(InstagramImport.allowed(ctx))
        RiskLocal.receivePolicy(ctx, UUID.randomUUID().toString(), true, ScoresMethod.UPLOAD)
        assertEquals("a requested method is not an agreed one", ImportResult.NOT_ALLOWED, import(bytes).result)
        assertTrue(RiskLocal.accept(ctx)); assertTrue(InstagramImport.allowed(ctx))
        assertTrue(RiskLocal.pause(ctx)); assertEquals(ImportResult.NOT_ALLOWED, import(bytes).result)
        assertTrue(RiskLocal.resume(ctx)); assertEquals(ImportResult.OK, import(bytes, "message_1.json").result)
        assertTrue(RiskLocal.withdraw(ctx)); assertEquals(ImportResult.NOT_ALLOWED, import(bytes).result); assertEquals(0, count("risk_store"))
        assertEquals(0, count("risk_store"))
    }
    private fun block(sender: String, text: String, time: String) =
        "<div class=\"pam\"><h2 class=\"_3-95 _2pim _a6-h _a6-i\">$sender</h2><div class=\"_3-95 _a6-p\"><div><div></div><div>$text</div><div></div></div></div><div class=\"_3-94 _a6-o\">$time</div></div>"
    @Test fun messagesWithoutAReadableTimeAreNeverStoredAndAnAllUndatedFileIsNotReportedAsOk() {
        activate(ScoresMethod.UPLOAD); GuardEngine.setChildSelfHandle(ctx, teen)
        val undated = "<html><body>" + chat.joinToString("") { block(it.sender, it.text, "yesterday-ish") } + "</body></html>"
        assertEquals(ImportResult.TIME_UNREADABLE, import(undated.toByteArray(), "message_1.html").result); assertEquals(0, count("risk_store"))
        // Mixed: only the message with a time that reads is kept (and it is dated by that time, not by the import).
        val mixed = "<html><body>" + block(friend, "see you tomorrow", time(System.currentTimeMillis() - hour)) + block(teen, "ancient undated words", "someday") + "</body></html>"
        val r = import(mixed.toByteArray(), "message_1.html")
        assertEquals(ImportResult.OK, r.result); assertEquals(1, r.messages); assertEquals(1, count("risk_store"))
        assertTrue(stored().single().endsWith("see you tomorrow"))
    }
    @Test fun anImportThatLosesPermissionWhileParsingStoresNothing() {
        val bytes = json(chat).toByteArray()
        val changes = listOf<Pair<String, () -> Unit>>("withdrawn" to { assertTrue(RiskLocal.withdraw(ctx)) }, "paused" to { assertTrue(RiskLocal.pause(ctx)) },
            "method changed" to { RiskLocal.receivePolicy(ctx, UUID.randomUUID().toString(), true, ScoresMethod.CAPTURE); assertTrue(RiskLocal.accept(ctx)) },
            "switched off" to { RiskLocal.receivePolicy(ctx, UUID.randomUUID().toString(), false) })
        for ((name, change) in changes) {
            reset(); activate(ScoresMethod.UPLOAD)
            // The change lands after allowed() was checked, in the middle of reading the file.
            val input = object : java.io.FilterInputStream(ByteArrayInputStream(bytes)) {
                var done = false
                override fun read(b: ByteArray, off: Int, len: Int): Int { if (!done) { done = true; change() }; return super.read(b, off, len) }
            }
            assertEquals(name, ImportResult.NOT_ALLOWED, InstagramImport.run(ctx, input, "message_1.json").result)
            assertEquals(name, 0, count("risk_store"))
        }
    }
    @Test fun importedTextIsNeverLoggedAndNeverQueuedForTheRelay() {
        activate(ScoresMethod.UPLOAD)
        val messages = listOf(M(friend, "$secret one", 1), M(teen, "$secret two", 2))
        assertEquals(ImportResult.OK, import(json(messages).toByteArray(), "message_1.json").result)
        assertEquals(ImportResult.MALFORMED, import("{ $secret".toByteArray(), "message_1.json").result)
        assertTrue(ShadowLog.getLogs().none { (it.msg ?: "").contains(secret) || (it.msg ?: "").contains(friend) })
        assertTrue(outbox().none { it.toString().contains(secret) })
        runBatch(); assertTrue(outbox().none { it.toString().contains(secret) || it.toString().contains("PRIVATE") })
    }

    // ---------- order of messages in the prompt ----------
    private fun promptOrder(prompt: String, vararg texts: String): List<Int> = texts.map { prompt.indexOf(it).also { i -> assertTrue("missing $it", i >= 0) } }
    @Test fun thePromptListsAnImportedConversationOldestFirst() {
        activate(ScoresMethod.UPLOAD)
        val shuffled = listOf(chat[2], chat[0], chat[3], chat[1])   // the file order does not matter, the timestamps do
        assertEquals(ImportResult.OK, import(json(shuffled).toByteArray(), "message_1.json").result)
        val scorer = FakeScorer(); assertEquals(1, runBatch(scorer).scored)
        val p = scorer.prompts.single()
        val at = promptOrder(p, "child: hi Bea", "other: did you finish the maths?", "child: ok, bring the notes", "other: see you tomorrow")
        assertEquals(at.sorted(), at)
    }
    @Test fun thePromptListsACapturedConversationOldestFirst() {
        activate(ScoresMethod.CAPTURE)
        // The newest message is read first and the oldest last, as when several notifications arrive together.
        GuardEngine.captureMessages(ctx, listOf(note("Pal", "fourth message", "incoming", 1000), note("Pal", "third message", "outgoing", 2000)))
        GuardEngine.captureMessages(ctx, listOf(note("Pal", "second message", "incoming", 3000), note("Pal", "first message", "outgoing", 4000)))
        val scorer = FakeScorer(); assertEquals(1, runBatch(scorer).scored)
        val at = promptOrder(scorer.prompts.single(), "first message", "second message", "third message", "fourth message")
        assertEquals(at.sorted(), at)
    }
}
