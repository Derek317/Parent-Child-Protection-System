package app.family.igguard

import android.app.Application
import android.app.job.JobScheduler
import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.*
import java.security.MessageDigest
import java.util.UUID

/** Scores-only family mode, child side. The native library is never loaded: a fake scorer returns scripted logits. */
class FakeScorer(private val logits: (String) -> FloatArray = { HIGH }, private val onScore: (String) -> Unit = {}) : RiskScorer {
    val prompts = mutableListOf<String>()
    var closed = false
    override fun score(prompt: String): FloatArray { prompts.add(prompt); onScore(prompt); return logits(prompt) }
    override fun close() { closed = true }
    companion object {
        val HIGH = floatArrayOf(-9f, -9f, -9f, 9f, -9f)   // answer 3: score 100
        val LOW = floatArrayOf(9f, -9f, -9f, -9f, -9f)    // answer 0: score 0
        val UNSURE = floatArrayOf(-9f, -9f, -9f, -9f, 9f) // answer U: needs review
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ScoresModeChildTest {
    private val ctx: Application get() = RuntimeEnvironment.getApplication()
    private val prefs get() = LocalVault.preferences(ctx)
    private val scope get() = GuardEngine.getChannelTopic(ctx)
    private val token get() = GuardEngine.getOrCreateFamilyToken(ctx)
    private var original: ProxySelector? = null
    private lateinit var authority: FamilySecurity.SigningIdentity
    private var clock = System.currentTimeMillis() - 600_000L
    private val day = 86_400_000L
    private val secret = "PRIVATE-WORDS-"

    @Before fun reset() {
        TestKeyStore.failReads = false
        ctx.deleteDatabase("family_evidence.db"); prefs.edit().clear().commit()
        ctx.getSharedPreferences("ig_guard_scores_consent", Context.MODE_PRIVATE).edit().clear().commit()
        RiskModelStore.directory(ctx).deleteRecursively()
        RiskModelStore.expectation = RiskModelStore.Expectation(RiskModelSpec.FILE_BYTES, RiskModelSpec.FILE_SHA256)
        RiskModelStore.memoryProbe = { 8L shl 30 }
        RiskScoringJob.scorerFactory = { error("no native scorer in unit tests") }
        ctx.getSystemService(JobScheduler::class.java).cancelAll()
        GuardEngine.setFamilyToken(ctx, UUID.randomUUID().toString()); GuardEngine.setRole(ctx, "child")
        authority = FamilySecurity.newSigningIdentity(); val id = UUID.randomUUID().toString()
        prefs.edit().putString("parent_command_public_key", authority.publicKey).putString("capture_policy_id", id).putString("capture_local_ack_id", id).commit()
        original = original ?: ProxySelector.getDefault()
        ProxySelector.setDefault(object : ProxySelector() {
            override fun select(u: URI) = if (u.host == "ntfy.sh") listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 9))) else original?.select(u) ?: listOf(Proxy.NO_PROXY)
            override fun connectFailed(u: URI, a: SocketAddress, e: IOException) {}
        })
    }
    @After fun cleanup() {
        ProxySelector.setDefault(original); original = null
        RiskModelStore.expectation = RiskModelStore.Expectation(RiskModelSpec.FILE_BYTES, RiskModelSpec.FILE_SHA256)
        RiskModelStore.memoryProbe = { c -> val i = android.app.ActivityManager.MemoryInfo(); c.getSystemService(android.app.ActivityManager::class.java)?.getMemoryInfo(i); i.totalMem }
    }

    // ---------- helpers ----------
    private val tinyBytes = "tiny model file for tests".toByteArray()
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private fun installModel(): Boolean {
        RiskModelStore.expectation = RiskModelStore.Expectation(tinyBytes.size.toLong(), sha(tinyBytes))
        val ok = RiskModelStore.importModel(ctx, ByteArrayInputStream(tinyBytes), {}, tinyBytes.size.toLong(), sha(tinyBytes)) == RiskImportResult.OK
        if (ok) RiskLocal.changed(ctx)
        return ok
    }
    private fun enablePolicy(enabled: Boolean = true) = RiskLocal.receivePolicy(ctx, UUID.randomUUID().toString(), enabled)
    private fun activate() { assertTrue(installModel()); enablePolicy(); assertTrue(RiskLocal.accept(ctx)); assertTrue(RiskLocal.active(ctx)) }
    private fun text(n: Int) = "$secret$n"
    private fun obs(body: String, direction: String = "incoming", conversation: String = "friend", kind: String = "direct", key: String? = null): CapturedText {
        val t = clock++
        return CapturedText(conversation, body, direction, if (direction == "incoming") conversation else "子女", "", "notification", t, key ?: "$conversation:$body:$t", false, kind)
    }
    private fun capture(vararg o: CapturedText) = GuardEngine.captureMessages(ctx, o.toList())
    private fun count(table: String) = FamilyEvidence(ctx).use { db -> db.readableDatabase.rawQuery("SELECT COUNT(*) FROM $table", null).use { it.moveToFirst(); it.getInt(0) } }

    /** Every decrypted record in the outbox, with alert fragments reassembled. */
    private fun outbox(): List<JSONObject> {
        val wires = FamilyEvidence(ctx).use { db -> db.readableDatabase.rawQuery("SELECT wire FROM outbox WHERE scope=?", arrayOf(scope)).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } } }
        val whole = mutableListOf<JSONObject>(); val parts = mutableMapOf<String, MutableList<JSONObject>>()
        for (w in wires) {
            val j = GuardEngine.decryptPayload(token, w) ?: continue
            if (j.optString("type") == "alert_fragment") parts.getOrPut(j.getString("event_id")) { mutableListOf() }.add(j) else whole.add(j)
        }
        for ((_, p) in parts) {
            val bytes = java.io.ByteArrayOutputStream(); p.sortedBy { it.getInt("part") }.forEach { bytes.write(Base64.decode(it.getString("data"), Base64.NO_WRAP)) }
            whole.add(JSONObject(bytes.toString("UTF-8")))
        }
        return whole
    }
    private fun reports(): List<JSONObject> = outbox().filter { it.optBoolean("relay_control") }.map { it.getJSONObject("envelope") }
        .filter { it.optString("type") == "scores_mode_report" }.sortedBy { it.getLong("sent_at_ms") }
    private fun clearOutbox() { FamilyEvidence(ctx).use { it.writableDatabase.execSQL("DELETE FROM outbox") } }
    private fun rawOutbox() = FamilyEvidence(ctx).use { db -> db.readableDatabase.rawQuery("SELECT wire FROM outbox", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } } }
    private fun assertNoPrivateText() { for (j in outbox()) assertFalse("record leaked message text: $j", j.toString().contains(secret)) }
    private fun runBatch(scorer: FakeScorer = FakeScorer(), stop: () -> Boolean = { false }) = RiskBatch.run(ctx, { scorer }, stop)
    private fun receiveSigned(inner: JSONObject, signed: Boolean = true): RelayConsumption {
        val payload = if (signed) FamilySecurity.signCommand(inner, authority.privateKey) else inner
        val line = JSONObject().put("event", "message").put("id", UUID.randomUUID().toString()).put("message", GuardEngine.encryptPayload(token, payload.toString()))
        return GuardEngine.processIncomingRelayLine(ctx, line.toString(), "child", null)
    }
    private fun policyCommand(id: String, enabled: Boolean) = JSONObject().put("type", "scores_mode_policy").put("policy_id", id).put("enabled", enabled)
        .put("scope", scope).put("command_id", UUID.randomUUID().toString()).put("ts_ms", System.currentTimeMillis())

    // ---------- model file ----------
    @Test fun importKeepsOnlyAnExactMatch() {
        RiskModelStore.expectation = RiskModelStore.Expectation(tinyBytes.size.toLong(), sha(tinyBytes))
        assertEquals(RiskModelState.MISSING, RiskModelStore.state(ctx))
        var progress = 0L
        assertEquals(RiskImportResult.OK, RiskModelStore.importModel(ctx, ByteArrayInputStream(tinyBytes), { progress = it }, tinyBytes.size.toLong(), sha(tinyBytes)))
        assertEquals(tinyBytes.size.toLong(), progress)
        assertEquals(RiskModelState.INSTALLED, RiskModelStore.state(ctx))
        assertTrue(RiskModelStore.verify(ctx)); assertFalse(File(RiskModelStore.directory(ctx), RiskModelSpec.FILE_NAME + ".part").exists())
    }
    @Test fun wrongHashWrongSizeAndReadFailureLeaveNoFile() {
        val size = tinyBytes.size.toLong()
        val wrong = tinyBytes.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertEquals(RiskImportResult.WRONG_FILE, RiskModelStore.importModel(ctx, ByteArrayInputStream(wrong), {}, size, sha(tinyBytes)))
        assertEquals(RiskImportResult.WRONG_FILE, RiskModelStore.importModel(ctx, ByteArrayInputStream(tinyBytes + 1), {}, size, sha(tinyBytes)))
        assertEquals(RiskImportResult.WRONG_FILE, RiskModelStore.importModel(ctx, ByteArrayInputStream(tinyBytes.copyOf(5)), {}, size, sha(tinyBytes)))
        val broken = object : InputStream() { var n = 0; override fun read(): Int = if (n++ < 3) 1 else throw IOException("disk gone") }
        assertEquals(RiskImportResult.IO_ERROR, RiskModelStore.importModel(ctx, broken, {}, size, sha(tinyBytes)))
        assertEquals(RiskImportResult.NOT_ENOUGH_SPACE, RiskModelStore.importModel(ctx, ByteArrayInputStream(tinyBytes), {}, Long.MAX_VALUE / 4, sha(tinyBytes)))
        assertEquals(emptyList<String>(), RiskModelStore.directory(ctx).list()?.toList().orEmpty())
        // The shipped expectation also refuses anything that is not the real model (or has no room for it).
        assertNotEquals(RiskImportResult.OK, RiskLocal.importModel(ctx, ByteArrayInputStream(tinyBytes)))
        assertEquals(RiskModelState.MISSING, RiskModelStore.state(ctx))
    }
    @Test fun sizeChangeOrFailedVerificationIsAMismatchAndSmallPhonesAreUnsupported() {
        assertTrue(installModel())
        File(RiskModelStore.file(ctx).path).appendBytes(byteArrayOf(1))
        assertEquals(RiskModelState.MISMATCH, RiskModelStore.state(ctx))
        assertTrue(installModel()); assertEquals(RiskModelState.INSTALLED, RiskModelStore.state(ctx))
        RiskModelStore.file(ctx).writeBytes(tinyBytes.copyOf().also { it[0] = 0 })   // same size, wrong content
        assertEquals(RiskModelState.INSTALLED, RiskModelStore.state(ctx)); assertFalse(RiskModelStore.verify(ctx))
        assertEquals(RiskModelState.MISMATCH, RiskModelStore.state(ctx))
        RiskModelStore.memoryProbe = { 6L shl 30 }
        assertEquals(RiskModelState.UNSUPPORTED_DEVICE, RiskModelStore.state(ctx))
        assertEquals(RiskImportResult.UNSUPPORTED_DEVICE, RiskModelStore.importModel(ctx, ByteArrayInputStream(tinyBytes), {}, tinyBytes.size.toLong(), sha(tinyBytes)))
    }
    @Test fun removingTheModelDeletesTheFileAndWipesTheStore() {
        activate(); capture(obs(text(1)))
        assertEquals(1, count("risk_store"))
        RiskLocal.removeModel(ctx)
        assertFalse(RiskModelStore.file(ctx).exists()); assertEquals(0, count("risk_store"))
        assertEquals("missing", reports().last().getJSONObject("state").getString("model"))
    }

    // ---------- gating ----------
    @Test fun activeNeedsEveryCondition() {
        activate()
        fun check(name: String, breakIt: () -> Unit, fix: () -> Unit) { breakIt(); assertFalse(name, RiskLocal.active(ctx)); fix(); assertTrue("$name restored", RiskLocal.active(ctx)) }
        val acceptance = ctx.getSharedPreferences("ig_guard_consent", Context.MODE_PRIVATE)
        val saved = acceptance.all.toMap()
        check("terms", { acceptance.edit().clear().commit() }, { acceptance.edit().apply { saved.forEach { (k, v) -> putString(k, v as String) } }.commit() })
        check("child role", { prefs.edit().putString("role", "parent").commit() }, { prefs.edit().putString("role", "child").commit() })
        check("collection", { prefs.edit().putBoolean("collection_withdrawn", true).commit() }, { prefs.edit().putBoolean("collection_withdrawn", false).commit() })
        val ack = prefs.getString("capture_local_ack_id", "")!!
        check("full capture", { prefs.edit().remove("capture_local_ack_id").commit() }, { prefs.edit().putString("capture_local_ack_id", ack).commit() })
        check("model", { RiskModelStore.file(ctx).delete() }, { RiskModelStore.file(ctx).writeBytes(tinyBytes) })
        check("device memory", { RiskModelStore.memoryProbe = { 4L shl 30 } }, { RiskModelStore.memoryProbe = { 8L shl 30 } })
        check("pause", { assertTrue(RiskLocal.pause(ctx)) }, { assertTrue(RiskLocal.resume(ctx)) })
        val policy = RiskLocal.policyId(ctx)
        check("policy", { prefs.edit().putBoolean("scores_policy_enabled", false).commit() }, { prefs.edit().putBoolean("scores_policy_enabled", true).commit() })
        check("child consent", { ctx.getSharedPreferences("ig_guard_scores_consent", Context.MODE_PRIVATE).edit().putString("receipt", "x").commit() },
            { assertEquals(policy, RiskLocal.policyId(ctx)); ctx.getSharedPreferences("ig_guard_scores_consent", Context.MODE_PRIVATE).edit().remove("receipt").commit(); assertTrue(RiskLocal.accept(ctx)) })
    }

    @Test fun nothingIsStoredOrScoredBeforeTheChildAccepts() {
        assertTrue(installModel()); enablePolicy()
        assertTrue(RiskLocal.pendingPolicy(ctx)); assertFalse(RiskLocal.active(ctx)); assertEquals(RiskCapture.NORMAL, RiskLocal.captureMode(ctx))
        capture(obs(text(1)))
        assertEquals(0, count("risk_store"))
        // The earlier behaviour continues unchanged: the text is still queued for the parent.
        assertTrue(outbox().any { it.toString().contains(secret) })
        assertEquals(RiskBatch.Outcome.INACTIVE, runBatch().outcome)
        assertTrue(RiskLocal.accept(ctx)); assertFalse(RiskLocal.pendingPolicy(ctx))
        capture(obs(text(2))); assertEquals(1, count("risk_store"))
    }
    @Test fun acceptingDiscardsQueuedRecordsThatCarryText() {
        assertTrue(installModel()); enablePolicy(); capture(obs(text(1)))
        assertTrue(outbox().any { it.toString().contains(secret) })
        assertTrue(RiskLocal.accept(ctx)); assertNoPrivateText()
    }
    @Test fun offModeLeavesCaptureAndTheOutboxExactlyAsBefore() {
        assertEquals(RiskCapture.NORMAL, RiskLocal.captureMode(ctx))
        val accepted = capture(obs(text(1)))
        assertEquals(1, accepted.size); assertEquals(0, count("risk_store")); assertEquals(0, count("risk_unknown"))
        assertTrue(outbox().any { it.optString("type") == "captured_message" && it.getString("text") == text(1) })
        assertTrue(ctx.getSystemService(JobScheduler::class.java).allPendingJobs.none { it.service.className.endsWith("RiskScoringJob") })
    }
    @Test fun aSignedPolicyIsRequired() {
        val id = UUID.randomUUID().toString()
        receiveSigned(policyCommand(id, true), signed = false); assertFalse(RiskLocal.policyEnabled(ctx))
        receiveSigned(policyCommand(id, true).put("scope", "other")); assertFalse(RiskLocal.policyEnabled(ctx))
        receiveSigned(policyCommand(id, true).put("enabled", "yes")); assertFalse(RiskLocal.policyEnabled(ctx))
        receiveSigned(policyCommand("not-a-uuid", true)); assertFalse(RiskLocal.policyEnabled(ctx))
        assertEquals(RelayConsumption.CONSUMED, receiveSigned(policyCommand(id, true)))
        assertTrue(RiskLocal.policyEnabled(ctx)); assertEquals(id, RiskLocal.policyId(ctx))
        assertTrue(RiskLocal.pendingPolicy(ctx))
    }

    // ---------- store ----------
    @Test fun onlyDirectThreadsAreStoredUnknownOnesAreCountedAndGroupsIgnored() {
        activate()
        capture(obs("hello ${secret}a"), obs("mine ${secret}b", "outgoing"), obs("dropped", "unknown", kind = "direct"))
        capture(obs("group ${secret}c", conversation = "club", kind = "group"))
        capture(obs("screen ${secret}d", conversation = "screen friend", kind = "unknown"), obs("screen ${secret}e", conversation = "screen friend", kind = "unknown"),
            obs("other ${secret}f", conversation = "other screen friend", kind = "unknown"))
        assertEquals(2, count("risk_store")); assertEquals(2, count("risk_unknown"))
        assertEquals(2, RiskLocal.status(ctx).unknownThreads)
        FamilyEvidence(ctx).use { db ->
            assertEquals(listOf(RiskMessage("other", "hello ${secret}a"), RiskMessage("child", "mine ${secret}b")), db.riskThreadMessages(scope, db.riskDueThreads(scope).single().first))
        }
    }
    @Test fun storedTextIsEncryptedAndRepeatedCaptureIsIdempotent() {
        activate()
        val same = obs(text(1), key = "fixed")
        capture(same); capture(same); capture(same.copy())
        assertEquals(1, count("risk_store"))
        FamilyEvidence(ctx).use { db ->
            db.readableDatabase.rawQuery("SELECT body,label,thread_index FROM risk_store", null).use { c ->
                assertTrue(c.moveToFirst())
                for (i in 0..2) { assertFalse(c.getString(i).contains(secret)); assertFalse(c.getString(i).contains("friend")) }
            }
        }
        val raw = ctx.getDatabasePath("family_evidence.db").readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(raw.contains(secret))
    }
    @Test fun sixtyMessagesPerThreadAndFourteenDaysAreEnforced() {
        activate()
        capture(*(1..70).map { obs(text(it)) }.toTypedArray())
        assertEquals(60, count("risk_store"))
        FamilyEvidence(ctx).use { db ->
            val kept = db.riskThreadMessages(scope, db.riskDueThreads(scope).single().first, 100).map { it.text }
            assertEquals((11..70).map { text(it) }, kept)
        }
        val now = System.currentTimeMillis()
        FamilyEvidence(ctx).use { db ->
            assertTrue(db.storeRiskMessage(scope, "old", "k", "old friend", "incoming", "old ${secret}", now - 13 * day, now - 13 * day, 0, now))
            db.pruneExpired(scope, now + day / 2); assertEquals(61, count("risk_store"))
            db.pruneExpired(scope, now + 2 * day)   // the 13-day-old row is now 15 days old
            assertEquals(60, count("risk_store"))
            assertTrue(db.storeRiskMessage(scope, "ancient", "k", "old friend", "incoming", "x", now - 15 * day, now - 15 * day, 0, now))
            assertEquals(60, count("risk_store"))
        }
    }
    @Test fun unknownThreadCountsExpireWithoutHavingKeptText() {
        activate(); capture(obs("x ${secret}", conversation = "screen friend", kind = "unknown"))
        FamilyEvidence(ctx).use { db ->
            val now = System.currentTimeMillis()
            assertEquals(1, db.riskUnknownCount(scope, now)); assertEquals(0, db.riskUnknownCount(scope, now + 15 * day))
            db.pruneExpired(scope, now + 15 * day)
        }
        assertEquals(0, count("risk_unknown")); assertEquals(0, count("risk_store"))
    }

    // ---------- every child-to-parent path ----------
    @Test fun noPathSendsMessageTextWhileTheModeIsOn() {
        activate()
        val naughty = "send nudes ${secret}"
        // captureMessages: keyword alert, unapproved contact, unknown kind, normal and outgoing messages.
        val sent = capture(obs(naughty, conversation = "badguy"), obs("hi ${secret}", conversation = "stranger"), obs("x ${secret}", conversation = "screen", kind = "unknown"),
            obs("reply ${secret}", "outgoing"))
        assertTrue(sent.any { it.getString("reason") == "sexual_or_grooming_content" })
        // The notification/screen path.
        GuardEngine.sendAlertFromChild(ctx, "sexual_or_grooming_content", "badguy", naughty, bypassDedupe = true)
        GuardEngine.sendAlertFromChild(ctx, "stranger_message", "stranger", "hello ${secret}", bypassDedupe = true)
        GuardEngine.sendAlertFromChild(ctx, "flagged_contact_follow_up", "badguy", "again ${secret}", bypassDedupe = true)
        assertNoPrivateText()
        val alerts = outbox().filter { it.optString("type") == "alert" && !it.optBoolean("relay_control") }
        assertTrue(alerts.size >= 4)
        for (a in alerts) {
            assertEquals("", a.getString("text")); assertFalse(a.has("matches")); assertFalse(a.has("signals"))
            assertTrue(a.getString("sender").isNotEmpty())
        }
        assertTrue(alerts.any { it.getString("reason") == "sexual_or_grooming_content" && it.getString("sender") == "badguy" })
        assertTrue(outbox().none { it.optString("type") == "captured_message" || it.optString("type") == "alert_fragment" })
        // Device-status alerts are not message content and keep their fixed text.
        GuardEngine.sendAlertFromChild(ctx, "tamper_attempt", "IG Mute Settings", "Child tried to mute Instagram DM notifications.", bypassDedupe = true)
        assertTrue(outbox().any { it.optString("reason") == "tamper_attempt" && it.getString("text").startsWith("Child tried") })
    }
    @Test fun pausedAndWithdrawnPhonesStayTextFree() {
        activate(); assertTrue(RiskLocal.pause(ctx))
        assertEquals(RiskCapture.CONTENT_FREE, RiskLocal.captureMode(ctx))
        capture(obs("send nudes ${secret}1", conversation = "badguy"), obs(text(2)))
        assertEquals(0, count("risk_store")); assertNoPrivateText()
        assertTrue(RiskLocal.resume(ctx)); assertTrue(RiskLocal.withdraw(ctx))
        capture(obs("send nudes ${secret}3", conversation = "badguy"), obs(text(4)))
        GuardEngine.sendAlertFromChild(ctx, "stranger_message", "x", "late ${secret}", bypassDedupe = true)
        assertEquals(0, count("risk_store")); assertNoPrivateText()
        assertTrue(outbox().any { it.optString("reason") == "sexual_or_grooming_content" })
        // Only the parent's policy returns the phone to the earlier behaviour.
        enablePolicy(false); capture(obs(text(5)))
        assertTrue(outbox().any { it.toString().contains(secret) })
    }

    // ---------- scoring ----------
    @Test fun eachThreadIsScoredOnItsLatest30MessagesWithTheExactPrompt() {
        activate()
        capture(*(1..45).map { obs(text(it), if (it % 2 == 0) "outgoing" else "incoming") }.toTypedArray())
        capture(obs("other ${secret}", conversation = "second friend"))
        val scorer = FakeScorer()
        val result = runBatch(scorer)
        assertEquals(RiskBatch.Outcome.COMPLETED, result.outcome); assertEquals(2, result.scored)
        val instruction = ctx.resources.openRawResource(R.raw.risk_instruction_v1).readBytes().toString(Charsets.UTF_8)
        val expected = (16..45).map { RiskMessage(if (it % 2 == 0) "child" else "other", text(it)) }
        assertTrue(scorer.prompts.contains(RiskPrompt.render(instruction, expected)))
        assertEquals(2, scorer.prompts.size); assertTrue(scorer.closed)
        assertEquals(0, FamilyEvidence(ctx).use { it.riskDueThreads(scope).size })
        assertEquals(RiskBatch.Result(RiskBatch.Outcome.COMPLETED), runBatch())   // nothing new: nothing scored
        assertTrue(RiskLocal.lastRun(ctx) > 0)
    }
    @Test fun theReportedScoreIsTheHighestInFourteenDaysAndNeedsReviewKeepsItsReason() {
        activate(); capture(obs(text(1)))
        runBatch(FakeScorer(logits = { FakeScorer.HIGH }))
        capture(obs(text(2)))
        runBatch(FakeScorer(logits = { FakeScorer.LOW }))
        FamilyEvidence(ctx).use { db ->
            val row = db.riskReportRows(scope, System.currentTimeMillis()).single()
            assertEquals(100.0, row.score!!, 0.001); assertNull(row.reason); assertEquals("friend", row.label)
            // Once the high result is older than 14 days only the low one counts.
            db.writableDatabase.execSQL("UPDATE risk_results SET scored_at=scored_at-? WHERE score>=99", arrayOf(15 * day))
            assertEquals(0.0, db.riskReportRows(scope, System.currentTimeMillis()).single().score!!, 0.001)
        }
        capture(obs(text(3))); runBatch(FakeScorer(logits = { FakeScorer.UNSURE }))
        FamilyEvidence(ctx).use { assertEquals(0.0, it.riskReportRows(scope, System.currentTimeMillis()).single().score!!, 0.001) }
        capture(obs("lone", conversation = "second", kind = "direct")); runBatch(FakeScorer(logits = { FakeScorer.UNSURE }))
        FamilyEvidence(ctx).use { db ->
            val review = db.riskReportRows(scope, System.currentTimeMillis()).first { it.label == "second" }
            assertNull(review.score); assertEquals("insufficient_context", review.reason)
        }
    }
    @Test fun aPromptThatDoesNotFitIsReportedAsContextTooLong() {
        activate(); capture(obs(text(1)))
        val scorer = object : RiskScorer { override fun score(prompt: String): FloatArray = throw RiskContextTooLong(); override fun close() {} }
        assertEquals(1, RiskBatch.run(ctx, { scorer }, { false }).needsReview)
        FamilyEvidence(ctx).use { assertEquals("context_too_long", it.riskReportRows(scope, System.currentTimeMillis()).single().reason) }
        assertEquals("context_too_long", reports().last().getJSONArray("conversations").getJSONObject(0).getString("review_reason"))
    }
    @Test fun aWrongModelFileIsNotLoadedAndTheParentIsToldItMismatches() {
        activate(); capture(obs(text(1)))
        RiskModelStore.file(ctx).writeBytes(tinyBytes.copyOf().also { it[0] = 0 })
        var opened = false
        val result = RiskBatch.run(ctx, { opened = true; FakeScorer() }, { false })
        assertEquals(RiskBatch.Outcome.MODEL_MISMATCH, result.outcome); assertFalse(opened)
        assertEquals("mismatch", reports().last().getJSONObject("state").getString("model"))
        assertEquals(0, count("risk_results"))
    }
    @Test fun pauseStopsARunInProgressAndTheInterruptedThreadIsScoredAgainNextTime() {
        activate()
        capture(obs(text(1), conversation = "first"), obs(text(2), conversation = "second"))
        val scorer = FakeScorer(onScore = { if (it.contains(text(1)) || it.contains(text(2))) RiskLocal.pause(ctx) })
        val result = runBatch(scorer)
        assertEquals(RiskBatch.Outcome.INACTIVE, result.outcome)
        assertEquals(1, scorer.prompts.size); assertEquals(0, count("risk_results")); assertTrue(scorer.closed)
        assertTrue(reports().last().getJSONObject("state").getBoolean("paused"))
        assertEquals(2, FamilyEvidence(ctx).use { it.riskDueThreads(scope).size })
        RiskLocal.resume(ctx)
        assertEquals(2, runBatch().scored)
    }
    @Test fun stopFlagAndSessionChangeEndTheRunBetweenThreads() {
        activate(); capture(obs(text(1), conversation = "first"), obs(text(2), conversation = "second"))
        var stop = false
        val first = FakeScorer(onScore = { stop = true })
        assertEquals(RiskBatch.Outcome.STOPPED, runBatch(first) { stop }.outcome); assertEquals(1, first.prompts.size); assertTrue(first.closed)
        val second = FakeScorer(onScore = { GuardEngine.setFamilyToken(ctx, UUID.randomUUID().toString(), preserveFamilyHistory = true) })
        // A key rotation wipes the store, so the interrupted run keeps nothing.
        val outcome = runBatch(second).outcome
        assertTrue(outcome == RiskBatch.Outcome.SESSION_CHANGED || outcome == RiskBatch.Outcome.INACTIVE)
        assertEquals(0, count("risk_results")); assertEquals(0, count("risk_store"))
    }
    @Test fun anInstructionThatDoesNotMatchTheShippedHashIsNeverUsed() {
        // The shipped resource is exact; this guards the constant the batch compares against.
        val bytes = ctx.resources.openRawResource(R.raw.risk_instruction_v1).readBytes()
        assertEquals(RiskModelSpec.INSTRUCTION_SHA256, sha(bytes))
    }

    // ---------- reports ----------
    @Test fun reportHasExactlyTheAllowedFieldsAndNoMessageData() {
        activate(); capture(obs("send nudes ${secret}", conversation = "friend"), obs(text(2), "outgoing"))
        runBatch()
        val report = reports().last()
        assertEquals(setOf("type", "sent_at_ms", "state", "conversations", "scope", "command_id"), report.keys().asSequence().toSet())
        assertEquals(setOf("policy_id", "model", "consent", "paused", "paused_at_ms", "last_run_ms", "scored_count", "unknown_threads"), report.getJSONObject("state").keys().asSequence().toSet())
        val row = report.getJSONArray("conversations").getJSONObject(0)
        assertEquals(setOf("account_label", "score", "review_reason", "scored_at_ms"), row.keys().asSequence().toSet())
        assertEquals("friend", row.getString("account_label")); assertEquals(100.0, row.getDouble("score"), 0.001)
        val state = report.getJSONObject("state")
        assertEquals("valid", state.getString("model")); assertEquals("accepted", state.getString("consent")); assertFalse(state.getBoolean("paused"))
        assertEquals(1, state.getInt("scored_count")); assertEquals(RiskLocal.policyId(ctx), state.getString("policy_id"))
        for (w in rawOutbox()) assertFalse(w.contains(secret))
        assertNoPrivateText()
        for (j in reports()) { assertFalse(j.toString().contains("send nudes")); assertFalse(j.toString().contains("author")) }
    }
    @Test fun onlyChangedRowsAreResentAndRecordsHoldAtMost200Rows() {
        activate()
        val many = (1..210).map { obs("m$it ${secret}", conversation = "friend$it") }
        for (chunk in many.chunked(100)) capture(*chunk.toTypedArray())
        // 210 conversations are stored; score them all, which needs three records (200 + 10).
        assertEquals(210, runBatch().scored)
        val sizes = reports().map { it.getJSONArray("conversations").length() }.filter { it > 0 }.sorted()
        assertEquals(listOf(10, 200), sizes)
        clearOutbox()
        assertTrue(RiskReporter.send(ctx))
        assertEquals(listOf(0), reports().map { it.getJSONArray("conversations").length() })
        clearOutbox(); capture(obs("again ${secret}", conversation = "friend7")); runBatch()
        assertEquals(listOf(1), reports().map { it.getJSONArray("conversations").length() })
    }
    @Test fun everyStateChangeReachesTheParentIncludingWhileTheModeIsOff() {
        assertTrue(installModel())
        assertEquals("valid", reports().last().getJSONObject("state").getString("model")); assertEquals("none", reports().last().getJSONObject("state").getString("consent"))
        enablePolicy(); assertEquals(RiskLocal.policyId(ctx), reports().maxByOrNull { it.getLong("sent_at_ms") }!!.getJSONObject("state").getString("policy_id"))
        fun latest() = reports().sortedBy { it.getLong("sent_at_ms") }.last().getJSONObject("state")
        Thread.sleep(2); assertTrue(RiskLocal.accept(ctx)); assertEquals("accepted", latest().getString("consent"))
        Thread.sleep(2); assertTrue(RiskLocal.pause(ctx)); assertTrue(latest().getBoolean("paused")); assertTrue(latest().getLong("paused_at_ms") > 0)
        Thread.sleep(2); assertTrue(RiskLocal.resume(ctx)); assertFalse(latest().getBoolean("paused")); assertTrue(latest().isNull("paused_at_ms"))
        Thread.sleep(2); assertTrue(RiskLocal.withdraw(ctx)); assertEquals("withdrawn", latest().getString("consent"))
    }

    // ---------- withdraw, wipes ----------
    @Test fun withdrawDeletesTheStoreAndUnsentScoresAndNeedsFreshConsent() {
        activate(); capture(obs(text(1))); runBatch()
        capture(obs(text(2), conversation = "second")); runBatch()
        assertTrue(count("risk_store") > 0 && count("risk_results") > 0)
        clearOutbox()
        assertTrue(RiskLocal.withdraw(ctx))
        for (t in listOf("risk_store", "risk_results", "risk_threads", "risk_unknown")) assertEquals(t, 0, count(t))
        assertEquals("withdrawn", reports().last().getJSONObject("state").getString("consent"))
        assertEquals(0, reports().last().getJSONArray("conversations").length())
        assertFalse(RiskLocal.active(ctx)); assertFalse(RiskLocal.pendingPolicy(ctx))
        assertFalse(RiskLocal.accept(ctx))
        enablePolicy(); assertTrue(RiskLocal.pendingPolicy(ctx)); assertEquals("none", RiskLocal.consentState(ctx))
        assertTrue(RiskLocal.accept(ctx)); assertTrue(RiskLocal.active(ctx))
    }
    @Test fun everyWipeTriggerEmptiesTheStore() {
        fun fill() { activate(); capture(obs(text(1))); runBatch(); assertTrue(count("risk_store") > 0) }
        fun empty(name: String) { for (t in listOf("risk_store", "risk_results", "risk_threads", "risk_unknown")) assertEquals("$name $t", 0, count(t)) }
        fill(); enablePolicy(false); empty("policy off")
        reset(); fill(); RiskLocal.removeModel(ctx); empty("model removed")
        reset(); fill(); GuardEngine.withdrawCollection(ctx); empty("collection withdrawn")
        reset(); fill(); GuardEngine.setRole(ctx, "unconfigured"); empty("role change")
        assertEquals(RiskLocal.policyId(ctx), ""); assertEquals("none", RiskLocal.consentState(ctx))
        reset(); fill(); GuardEngine.setFamilyToken(ctx, UUID.randomUUID().toString()); empty("new family")
        assertFalse(RiskLocal.policyEnabled(ctx))
        reset(); fill(); GuardEngine.setFamilyToken(ctx, UUID.randomUUID().toString(), preserveFamilyHistory = true); empty("key rotation")
        assertTrue("rotation keeps the family's mode and consent", RiskLocal.policyEnabled(ctx)); assertEquals("accepted", RiskLocal.consentState(ctx))
        reset(); fill(); FamilyEvidence(ctx).use { it.rotate(scope, "other-scope") { w -> w } }; empty("evidence rotate")
    }
    @Test fun dailyMaintenanceWipesAStoreThatNoLongerHasAMode() {
        activate(); capture(obs(text(1)))
        ArchiveMaintenance.pruneAll(ctx); assertEquals(1, count("risk_store"))
        prefs.edit().putBoolean("scores_policy_enabled", false).commit()
        ArchiveMaintenance.pruneAll(ctx); assertEquals(0, count("risk_store"))
    }

    // ---------- schedule ----------
    @Test fun jobsAreScheduledOnlyWhileActiveWithIdleAndChargingThenChargingOnly() {
        val scheduler = ctx.getSystemService(JobScheduler::class.java)
        assertTrue(installModel()); enablePolicy()
        GuardEngine.syncChildComponents(ctx)
        assertTrue(scheduler.allPendingJobs.none { it.service.className.endsWith("RiskScoringJob") })
        assertTrue(RiskLocal.accept(ctx))
        val jobs = scheduler.allPendingJobs.filter { it.service.className == "app.family.igguard.RiskScoringJob" }
        val primary = jobs.single { it.id == RiskScoringSchedule.PRIMARY_ID }
        assertTrue(primary.isRequireCharging); assertTrue(primary.isRequireDeviceIdle); assertTrue(primary.isPeriodic); assertTrue(primary.isPersisted)
        assertEquals(6 * 3_600_000L, primary.intervalMillis)
        val fallback = jobs.single { it.id == RiskScoringSchedule.FALLBACK_ID }
        assertTrue(fallback.isRequireCharging); assertFalse(fallback.isRequireDeviceIdle); assertFalse(fallback.isPeriodic); assertTrue(fallback.isPersisted)
        assertTrue(fallback.minLatencyMillis in (47 * 3_600_000L)..(48 * 3_600_000L))
        // No run for 48 hours: the next schedule asks for charging only and is due at once.
        prefs.edit().putLong("scores_last_run_ms", System.currentTimeMillis() - 49 * 3_600_000L).commit()
        RiskScoringSchedule.rearm(ctx)
        val late = scheduler.allPendingJobs.single { it.id == RiskScoringSchedule.FALLBACK_ID }
        assertEquals(0L, late.minLatencyMillis); assertTrue(late.isRequireCharging); assertFalse(late.isRequireDeviceIdle)
        // A completed run restarts the clock.
        capture(obs(text(1))); runBatch(); RiskScoringSchedule.rearm(ctx)
        assertTrue(scheduler.allPendingJobs.single { it.id == RiskScoringSchedule.FALLBACK_ID }.minLatencyMillis > 47 * 3_600_000L)
        assertTrue(RiskLocal.pause(ctx))
        assertTrue(scheduler.allPendingJobs.none { it.service.className.endsWith("RiskScoringJob") })
        assertTrue(RiskLocal.resume(ctx)); assertEquals(2, scheduler.allPendingJobs.count { it.service.className.endsWith("RiskScoringJob") })
        RiskLocal.withdraw(ctx); assertTrue(scheduler.allPendingJobs.none { it.service.className.endsWith("RiskScoringJob") })
    }
}
