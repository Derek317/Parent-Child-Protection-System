package app.family.igguard

import android.app.Application
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Every child send path after a QR pairing produces a signed wrapper that ChildRecords.gate verifies, and each record still does what
 * it did in 5.2 once it is through the gate. One app context: the phone is the child first (real send paths, frames read from its
 * outbox), then becomes the parent with the same parent key and K pinned, and the very same ciphertexts are delivered to it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ChildRecordWiringTest {
    private val ctx: Application get() = RuntimeEnvironment.getApplication()
    private val prefs get() = LocalVault.preferences(ctx)
    private val parent = FamilySecurity.newSigningIdentity()

    @Before fun reset() {
        prefs.edit().clear().commit(); ctx.deleteDatabase("family_evidence.db")
        TestKeyStore.privateKeys.clear(); TestKeyStore.certificates.clear()
    }

    /** The child's queued records, reassembled from their frames: event id -> wrapper as the parent sees it after reassembly. */
    private fun queued(token: String): Map<String, JSONObject> {
        val frames = FamilyEvidence(ctx).use { it.due(GuardEngine.getChannelTopicForToken(token), Long.MAX_VALUE) }
        return frames.groupBy { it.id.substringBeforeLast(':') }.mapValues { (_, parts) ->
            val decoded = parts.map { GuardEngine.decryptPayload(token, it.wire)!! }
            if (decoded.size == 1 && decoded[0].optString("type") != "alert_fragment") decoded[0]
            else JSONObject(String(decoded.sortedBy { it.getInt("part") }.flatMap { Base64.decode(it.getString("data"), Base64.NO_WRAP).toList() }.toByteArray(), Charsets.UTF_8))
                .also { if (decoded[0].optBoolean("receipt_requested")) it.put("receipt_requested", true) }
        }
    }
    private fun wires(token: String) = FamilyEvidence(ctx).use { it.due(GuardEngine.getChannelTopicForToken(token), Long.MAX_VALUE) }.map { it.wire }

    @Test fun everyChildSendPathIsSignedAndStillWorksAfterTheGate() {
        val r = QrPairing.pairChild(ctx, parent)
        val token = r.token
        // Full capture confirmed (capture_ack) and a captured message.
        val policy = UUID.randomUUID().toString()
        prefs.edit().putString("capture_policy_id", policy).commit()
        assertTrue(GuardEngine.confirmFullCapture(ctx))
        assertEquals(1, GuardEngine.captureMessages(ctx, listOf(CapturedText("stranger", "send nudes", "incoming", author = "stranger", sourceKey = "k1"))).size)
        GuardEngine.updateAutoDetectedChildHandle(ctx, "kid_handle")                                          // sync_child_handle
        GuardEngine.sendAlertFromChild(ctx, "test_alert", "測試", "test", bypassDedupe = true)                 // test alert
        GuardEngine.sendAlertFromChild(ctx, "unlock_used", "@kid", "unlocked", bypassDedupe = true)            // unlock notice
        GuardEngine.checkAndSendChildHeartbeatIfDue(ctx, force = true)                                        // heartbeat (tamper_attempt: no listener here)
        val state = JSONObject().put("policy_id", UUID.randomUUID().toString()).put("model", "valid").put("consent", "accepted").put("active", true)
            .put("paused", false).put("paused_at_ms", JSONObject.NULL).put("last_run_ms", System.currentTimeMillis()).put("scored_count", 1).put("unknown_threads", 0)
        val row = JSONObject().put("account_label", "alice").put("score", 42.0).put("review_reason", JSONObject.NULL).put("scored_at_ms", System.currentTimeMillis() - 1000)
        assertNotNull(GuardEngine.queueScoresReport(ctx, JSONObject().put("type", "scores_mode_report").put("sent_at_ms", System.currentTimeMillis())
            .put("state", state).put("conversations", JSONArray().put(row))))                               // scores_mode_report (fragmented)
        val beforeScoresMode = queued(token)
        // Scores-only mode: a message alert leaves without its text.
        RiskLocal.receivePolicy(ctx, UUID.randomUUID().toString(), true); assertTrue(RiskLocal.accept(ctx))
        GuardEngine.sendAlertFromChild(ctx, "stranger_message", "peer", "secret words", bypassDedupe = true)

        val records = beforeScoresMode + queued(token)
        val types = mutableSetOf<String>()
        for ((id, wrapper) in records) {
            assertFalse("no 5.2 envelope after a QR pairing: $id", wrapper.has("relay_control"))
            val g = ChildRecords.gate(wrapper, r.childKey, parent.publicKey, System.currentTimeMillis(), null)
            assertTrue("$id: $g", g is ChildRecords.GateResult.Verified)
            val v = g as ChildRecords.GateResult.Verified
            types += if (v.type == "alert") "alert:" + v.record.getString("reason") else v.type
            if (v.type == "alert" && v.record.getString("reason") == "stranger_message") {
                assertEquals("", v.record.getString("text")); assertTrue(v.record.getBoolean("content_free"))
                assertFalse("the signed text is the scrubbed one", wrapper.getString("child_signed").contains("secret words"))
            }
        }
        assertTrue(types.toString(), types.containsAll(listOf("alert:pairing_verified", "alert:test_alert", "alert:unlock_used", "alert:tamper_attempt",
            "alert:stranger_message", "captured_message", "capture_ack", "sync_child_handle", "scores_mode_report")))

        // The same ciphertexts, delivered to this phone as the parent with K pinned.
        val all = wires(token)
        GuardEngine.setRole(ctx, "parent")
        prefs.edit().putString("parent_signing_private", parent.privateKey).putString("parent_signing_public", parent.publicKey)
            .putString("pinned_child_key", FamilySecurity.canonicalKeyText(r.childKey)).putLong("child_pairing_pending_ms", System.currentTimeMillis())
            .putString("parent_master_password", QrPairing.PASSWORD).remove("parent_password_verifier").remove("child_self_handle").commit()
        GuardEngine.forgetParentIdentityCache()
        assertEquals(parent.publicKey, GuardEngine.relayPublicKey(ctx))
        val notified = mutableListOf<JSONObject>()
        for (wire in all) {
            val line = JSONObject().put("event", "message").put("id", UUID.randomUUID().toString()).put("message", wire)
            assertEquals(RelayConsumption.CONSUMED, GuardEngine.processIncomingRelayLine(ctx, line.toString(), "parent", { notified += it }))
        }
        assertTrue("the heartbeat counts", GuardEngine.hasChildHeartbeat(ctx))
        assertFalse("the first verified record completes the pairing", GuardEngine.childPairingPending(ctx))
        assertEquals(GuardEngine.ChildTrust.VERIFIED, GuardEngine.childTrust(ctx))
        assertEquals(policy, prefs.getString("capture_child_ack_id", null))
        assertEquals("kid_handle", GuardEngine.getChildSelfHandle(ctx))
        assertEquals(listOf("alice"), FamilyEvidence(ctx).use { it.riskScores(GuardEngine.getChannelTopic(ctx)) }.map { it.accountLabel })
        val reasons = GuardEngine.getParentAlerts(ctx).map { it.optString("reason") }
        assertTrue(reasons.toString(), reasons.containsAll(listOf("pairing_verified", "test_alert", "unlock_used", "tamper_attempt", "stranger_message")))
        assertTrue(notified.any { it.optString("reason") == "test_alert" })
        assertTrue("no tamper notice for genuine records", GuardEngine.getParentAlerts(ctx).none { it.optString("sender") == ChildRecords.TAMPER_SENDER })
        // capture_ack / sync_child_handle replay state persisted for this child.
        assertTrue(JSONObject(prefs.getString("child_record_state_json", "{}")!!).has("capture_ack"))
        // Delivered twice: consumed without effect.
        for (wire in all) assertEquals(RelayConsumption.CONSUMED, GuardEngine.processIncomingRelayLine(ctx,
            JSONObject().put("event", "message").put("id", UUID.randomUUID().toString()).put("message", wire).toString(), "parent", null))
    }

    @Test fun aRepostOfAlreadyQueuedMessagesSignsNothing() {
        QrPairing.pairChild(ctx, parent)
        prefs.edit().putString("capture_policy_id", UUID.randomUUID().toString()).commit()
        assertTrue(GuardEngine.confirmFullCapture(ctx))
        val batch = (1..10).map { CapturedText("stranger", "message $it", "incoming", author = "stranger", sourceKey = "k$it") }
        val calls = mutableMapOf<String, Int>()
        TestKeyStore.onLoad = {
            Thread.currentThread().stackTrace.map { it.methodName }.firstOrNull { it == "loadChildSigningKey" || it == "signChildBytes" }
                ?.let { calls[it] = (calls[it] ?: 0) + 1 }
        }
        try {
            assertEquals(10, GuardEngine.captureMessages(ctx, batch).size)
            assertEquals("one key load for the batch, one signature per row", mapOf("loadChildSigningKey" to 1, "signChildBytes" to 10), calls)
            calls.clear()
            assertEquals(0, GuardEngine.captureMessages(ctx, batch).size)
            assertEquals("a repost of queued messages must not touch the signing key", emptyMap<String, Int>(), calls)
        } finally { TestKeyStore.onLoad = {} }
    }
    @Test fun anAlertThatCouldNotBeQueuedIsNotSuppressedAsADuplicate() {
        val r = QrPairing.pairChild(ctx)
        val before = wires(r.token).size
        TestKeyStore.failEcSigning = true
        try { GuardEngine.sendAlertFromChild(ctx, "stranger_message", "stranger", "hi there", fromNotification = true) } finally { TestKeyStore.failEcSigning = false }
        assertEquals("the signing failure queued nothing", before, wires(r.token).size)
        var suppressed = false
        GuardEngine.sendAlertFromChild(ctx, "stranger_message", "stranger", "hi there", fromNotification = true) { ok, message -> if (!ok && message == "Duplicate suppressed") suppressed = true }
        assertFalse("the retry is not a duplicate of an alert that was never queued", suppressed)
        assertTrue(wires(r.token).size > before)
    }
    @Test fun aChildWithoutAQrPairingSendsThe52FormatUnchanged() {
        GuardEngine.setRole(ctx, "parent"); GuardEngine.setPin(ctx, QrPairing.PASSWORD)
        val invite = QrPairing.legacyInvite(ctx)
        GuardEngine.setRole(ctx, "unconfigured")
        prefs.edit().remove("parent_master_password").remove("parent_share_verifier").remove("parent_signing_public").commit()
        assertTrue(GuardEngine.applyPairing(ctx, invite))
        GuardEngine.sendAlertFromChild(ctx, "test_alert", "測試", "test", bypassDedupe = true)
        GuardEngine.updateAutoDetectedChildHandle(ctx, "kid_handle")
        val records = queued(invite.token).values
        assertTrue(records.isNotEmpty())
        for (r in records) assertFalse(r.has("child_signed"))
        assertTrue(records.any { it.optBoolean("relay_control") })
        assertTrue(records.any { it.optString("reason") == "test_alert" && it.has("receipt_requested") })
    }
}
