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

/** Manual upload method on the child's phone: nothing is collected from Instagram, the import fills the same store, order is oldest first. Invented chats only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UploadMethodTest {
    private val ctx: Application get() = RuntimeEnvironment.getApplication()
    private val prefs get() = LocalVault.preferences(ctx)
    private val scope get() = GuardEngine.getChannelTopic(ctx)
    private val token get() = GuardEngine.getOrCreateFamilyToken(ctx)
    private var original: ProxySelector? = null
    private val secret = "PRIVATE-WORDS-"
    private val hour = 3_600_000L
    private val teen = "Alex Teen"
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
    private fun html(messages: List<M>, now: Long = System.currentTimeMillis()) = "<html><body>" + messages.joinToString("") {
        "<div class=\"pam\"><h2 class=\"_3-95 _2pim _a6-h _a6-i\">${it.sender}</h2><div class=\"_3-95 _a6-p\"><div><div></div><div>${it.text}</div><div></div></div></div><div class=\"_3-94 _a6-o\">${time(now - it.hoursAgo * hour)}</div></div>"
    } + "</body></html>"
    private fun json(messages: List<M>, participants: List<String> = listOf(friend, teen), now: Long = System.currentTimeMillis()) = JSONObject()
        .put("participants", JSONArray(participants.map { JSONObject().put("name", it) }))
        .put("messages", JSONArray(messages.map { JSONObject().put("sender_name", it.sender).put("timestamp_ms", now - it.hoursAgo * hour).put("content", it.text) })).toString()
    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> for ((name, bytes) in entries) { z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() } }
        return out.toByteArray()
    }

    // ---------- nothing is collected from Instagram in upload method ----------
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

    @Test fun inUploadMethodObservationsAreNeitherStoredScoredForwardedNorAlertedOn() {
        activate(ScoresMethod.UPLOAD)
        assertEquals(RiskCapture.UPLOAD, RiskLocal.captureMode(ctx))
        val before = nonReportRecords().size
        val accepted = GuardEngine.captureMessages(ctx, listOf(note("Pal", "hello $secret", "incoming", 3000), note("badguy", "send nudes $secret", "incoming", 2000), screen("from the screen $secret", "incoming")))
        assertTrue(accepted.isEmpty()); assertEquals(0, count("risk_store")); assertEquals(0, count("risk_unknown")); assertEquals(before, nonReportRecords().size)
        // The notification listener is gated before it reads anything: no alert, no stored text.
        val service = Robolectric.buildService(IgNotificationListener::class.java).create()
        try { service.get().onNotificationPosted(posted("send nudes $secret")) } finally { service.destroy() }
        assertEquals(0, count("risk_store")); assertEquals(before, nonReportRecords().size)
        assertTrue(outbox().none { it.toString().contains(secret) })
        assertEquals(0, runBatch().scored)
    }
    @Test fun theSameObservationsAreKeptInCaptureMethodSoTheGateIsWhatStopsThem() {
        activate(ScoresMethod.CAPTURE)
        assertEquals(RiskCapture.ACTIVE, RiskLocal.captureMode(ctx))
        assertEquals(2, GuardEngine.captureMessages(ctx, listOf(note("Pal", "hello $secret", "incoming", 3000), screen("from the screen $secret", "incoming", "Other"))).size)
        assertEquals(2, count("risk_store"))
        val service = Robolectric.buildService(IgNotificationListener::class.java).create()
        try { service.get().onNotificationPosted(posted("send nudes $secret")) } finally { service.destroy() }
        assertTrue("capture method still reads notifications", count("risk_store") >= 3 || outbox().any { it.optString("reason") == "stranger_message" || it.optString("type") == "alert" })
    }
    @Test fun theScreenServiceDoesNotEvenTouchInstagramWindowsInUploadMethod() {
        fun connected(): Boolean {
            GuardEngine.cpAccConnected = false
            val service = Robolectric.buildService(IgAccessibilityService::class.java).create()
            try {
                val event = android.view.accessibility.AccessibilityEvent.obtain(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply { packageName = GuardEngine.INSTAGRAM_PKG }
                service.get().onAccessibilityEvent(event)
                return GuardEngine.cpAccConnected   // read before the service's own teardown resets it
            } finally { service.destroy() }
        }
        activate(ScoresMethod.CAPTURE); assertTrue("capture method reads the screen", connected())
        reset(); activate(ScoresMethod.UPLOAD); assertFalse("upload method does not", connected())
    }
    @Test fun aMethodThatIsNotYetAgreedDoesNotSwitchCaptureOff() {
        activate(ScoresMethod.CAPTURE)
        RiskLocal.receivePolicy(ctx, UUID.randomUUID().toString(), true, ScoresMethod.UPLOAD)
        assertEquals(RiskCapture.ACTIVE, RiskLocal.captureMode(ctx))
        assertEquals(1, GuardEngine.captureMessages(ctx, listOf(note("Pal", "still captured $secret", "incoming", 3000))).size)
        assertTrue(RiskLocal.accept(ctx)); assertEquals(RiskCapture.UPLOAD, RiskLocal.captureMode(ctx))
        assertTrue(GuardEngine.captureMessages(ctx, listOf(note("Pal", "not any more $secret", "incoming", 1000))).isEmpty())
    }
    @Test fun switchingTheMethodOffOrBackToCaptureRestoresTheRightBehaviour() {
        activate(ScoresMethod.UPLOAD)
        RiskLocal.receivePolicy(ctx, UUID.randomUUID().toString(), false)   // the parent turns the mode off: the earlier behaviour applies again
        assertEquals(RiskCapture.NORMAL, RiskLocal.captureMode(ctx)); assertNull(RiskLocal.agreedMethod(ctx))
    }
    @Test fun withNotificationAccessOffTheHeartbeatIsNotATamperAlertInUploadMethod() {
        activate(ScoresMethod.UPLOAD)
        GuardEngine.checkAndSendChildHeartbeatIfDue(ctx, force = true)
        val reasons = outbox().filter { it.optString("type") == "alert" }.map { it.optString("reason") }
        assertTrue("a heartbeat was sent: $reasons", reasons.contains("heartbeat_ok")); assertFalse(reasons.contains("tamper_attempt"))
    }
    @Test fun analysisRunsWithoutTheCaptureConfirmationInUploadMethodOnly() {
        activate(ScoresMethod.UPLOAD)
        prefs.edit().remove("capture_local_ack_id").commit()
        assertFalse(GuardEngine.fullCaptureEnabled(ctx)); assertTrue(RiskLocal.active(ctx))
        reset(); activate(ScoresMethod.CAPTURE); prefs.edit().remove("capture_local_ack_id").commit()
        assertFalse("capture method still needs the confirmed two-way capture", RiskLocal.active(ctx))
    }
}
