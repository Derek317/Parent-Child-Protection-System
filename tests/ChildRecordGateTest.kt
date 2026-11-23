package app.family.igguard

import android.app.Application
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

/** Attacks on a parent with a pinned child key, through GuardEngine.processIncomingRelayLine (design 3.3-3.5). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ChildRecordGateTest {
    private val ctx: Application get() = RuntimeEnvironment.getApplication()
    private val prefs get() = LocalVault.preferences(ctx)
    private val kid = FamilySecurity.newSigningIdentity()
    private val stranger = FamilySecurity.newSigningIdentity()
    private val token get() = GuardEngine.getOrCreateFamilyToken(ctx)
    private val scope get() = GuardEngine.getChannelTopic(ctx)

    @Before fun reset() {
        prefs.edit().clear().commit(); ctx.deleteDatabase("family_evidence.db")
        GuardEngine.setRole(ctx, "parent"); GuardEngine.setPin(ctx, QrPairing.PASSWORD)
        GuardEngine.getOrCreateFamilyToken(ctx)
        pin()
    }
    private fun pin() = prefs.edit().putString("pinned_child_key", FamilySecurity.canonicalKeyText(kid.publicKey)).commit()
    private val parentKey get() = GuardEngine.relayPublicKey(ctx)

    private fun heartbeat(ts: Long = System.currentTimeMillis(), reason: String = "heartbeat_ok") = JSONObject().put("type", "alert").put("event_id", UUID.randomUUID().toString())
        .put("capture_source", "app_or_visible_screen").put("reason", reason).put("sender", "子女手機定時回報").put("text", "裝置 運作中").put("ts_ms", ts).put("time", "x")
        .put("trusted_csv", "").put("child_self_handle", "")
    private fun signed(record: JSONObject, by: FamilySecurity.SigningIdentity = kid, id: String = record.optString("event_id").ifEmpty { "control:" + record.getString("command_id") }) =
        ChildRecords.wrapForParent(record.toString(), id, parentKey, by.publicKey, { FamilySecurity.signBytes(it, by.privateKey) }, if (record.optString("type") == "alert") true else null)
    private fun deliver(frame: JSONObject, callback: ((JSONObject) -> Unit)? = null): RelayConsumption =
        GuardEngine.processIncomingRelayLine(ctx, JSONObject().put("event", "message").put("id", UUID.randomUUID().toString())
            .put("message", GuardEngine.encryptPayload(token, frame.toString())).toString(), "parent", callback)
    private fun legacyControl(payload: JSONObject): JSONObject {
        val id = UUID.randomUUID().toString(); payload.put("scope", scope).put("command_id", id)
        return JSONObject().put("type", "alert").put("event_id", "control:$id").put("relay_control", true).put("envelope", payload)
    }
    private fun lastSeen() = prefs.getLong("parent_last_child_hb_ms", 0L)
    private fun notices() = GuardEngine.getParentAlerts(ctx).count { it.optString("sender") == ChildRecords.TAMPER_SENDER }
    private fun stored() = GuardEngine.getParentAlerts(ctx).count { it.optString("sender") != ChildRecords.TAMPER_SENDER }
    private fun scoresReport(sentAt: Long = System.currentTimeMillis()): JSONObject {
        val state = JSONObject().put("policy_id", UUID.randomUUID().toString()).put("model", "valid").put("consent", "accepted").put("active", true)
            .put("paused", false).put("paused_at_ms", JSONObject.NULL).put("last_run_ms", sentAt).put("scored_count", 1).put("unknown_threads", 0)
        return JSONObject().put("type", "scores_mode_report").put("sent_at_ms", sentAt).put("state", state).put("conversations", JSONArray().put(
            JSONObject().put("account_label", "alice").put("score", 42.0).put("review_reason", JSONObject.NULL).put("scored_at_ms", sentAt - 1000)))
    }

    @Test fun aForged52HeartbeatIsRefusedNeverCountsAndRaisesOneNoticePerHour() {
        prefs.edit().putLong("parent_last_child_hb_ms", System.currentTimeMillis() - 101 * 60_000L).putBoolean("parent_hb_missing_alerted", false).commit()
        val before = lastSeen()
        var notified = 0
        assertEquals(RelayConsumption.REJECTED, deliver(heartbeat()) { notified++ })
        assertEquals(RelayConsumption.REJECTED, deliver(heartbeat()) { notified++ })
        assertEquals("a forged heartbeat must not update last-seen", before, lastSeen())
        assertTrue("the 100-minute warning still fires", GuardEngine.shouldFireHeartbeatMissingAlert(ctx))
        assertEquals("one notice per hour", 1, notices()); assertEquals(1, notified)
        assertEquals("nothing from the frame is stored", 0, stored())
        val notice = GuardEngine.getParentAlerts(ctx).single()
        assertEquals("tamper_attempt", notice.optString("reason")); assertEquals(ChildRecords.TAMPER_TEXT, notice.optString("text"))
        // An hour later: one more.
        prefs.edit().putLong("child_tamper_notice_ms", System.currentTimeMillis() - ChildRecords.TAMPER_INTERVAL_MS).commit()
        assertEquals(RelayConsumption.REJECTED, deliver(heartbeat()))
        assertEquals(2, notices()); assertEquals(before, lastSeen())
    }

    @Test fun aTamperFrameNeverCountsAsASignOfLife() {
        for (frame in listOf(heartbeat(), signed(heartbeat(), by = stranger), signed(heartbeat()).put("event_id", UUID.randomUUID().toString()))) {
            prefs.edit().remove("child_tamper_notice_ms").commit()
            assertEquals(RelayConsumption.REJECTED, deliver(frame))
            assertEquals(0L, lastSeen()); assertFalse(GuardEngine.hasChildHeartbeat(ctx))
        }
        assertEquals(3, notices()); assertEquals(0, stored())
    }

    @Test fun aQuietDropNeverCountsAsASignOfLifeAndRaisesNoNotice() {
        // Genuine signature, outside the rules: a heartbeat dated 9 days back, and a signed alert with an unknown reason.
        assertEquals(RelayConsumption.REJECTED, deliver(signed(heartbeat(ts = System.currentTimeMillis() - 9 * 86_400_000L))))
        assertEquals(RelayConsumption.REJECTED, deliver(signed(heartbeat(reason = "all_is_well"))))
        assertEquals(0L, lastSeen()); assertEquals(0, notices()); assertEquals(0, stored())
        assertEquals(2, GuardEngine.childQuietDrops(ctx))
    }

    @Test fun forgedScoresReportAndCaptureAckAreRefused() {
        val policy = UUID.randomUUID().toString(); prefs.edit().putString("capture_policy_id", policy).commit()
        assertEquals(RelayConsumption.REJECTED, deliver(legacyControl(scoresReport())))
        assertEquals(RelayConsumption.REJECTED, deliver(legacyControl(JSONObject().put("type", "capture_ack").put("policy_id", policy).put("schema_version", 2).put("ts_ms", System.currentTimeMillis()))))
        assertTrue(FamilyEvidence(ctx).use { it.riskScores(scope) }.isEmpty())
        assertNull(prefs.getString("capture_child_ack_id", null))
        assertEquals(1, notices())
        // The same records signed by K go through.
        val ack = JSONObject().put("type", "capture_ack").put("policy_id", policy).put("schema_version", 2).put("ts_ms", System.currentTimeMillis())
            .put("scope", scope).put("command_id", UUID.randomUUID().toString())
        assertEquals(RelayConsumption.CONSUMED, deliver(signed(ack)))
        assertEquals(policy, prefs.getString("capture_child_ack_id", null))
        assertEquals(RelayConsumption.CONSUMED, deliver(signed(scoresReport().put("scope", scope).put("command_id", UUID.randomUUID().toString()))))
        assertEquals(1, FamilyEvidence(ctx).use { it.riskScores(scope) }.size)
        // A replayed ack (same id) is consumed without effect; an older handle sync after a newer one too.
        prefs.edit().remove("capture_child_ack_id").commit()
        assertEquals(RelayConsumption.CONSUMED, deliver(signed(ack)))
        assertNull(prefs.getString("capture_child_ack_id", null))
        val now = System.currentTimeMillis()
        fun handle(name: String, ts: Long) = JSONObject().put("type", "sync_child_handle").put("child_self_handle", name).put("ts_ms", ts).put("time", "x")
            .put("scope", scope).put("command_id", UUID.randomUUID().toString())
        assertEquals(RelayConsumption.CONSUMED, deliver(signed(handle("new_handle", now))))
        assertEquals(RelayConsumption.CONSUMED, deliver(signed(handle("old_handle", now - 60_000))))
        assertEquals("new_handle", GuardEngine.getChildSelfHandle(ctx))
    }

    @Test fun replayedOrDelayedVerifiedHeartbeatsDoNotRefreshLastSeen() {
        val now = System.currentTimeMillis()
        val first = signed(heartbeat(ts = now - 1_000))
        assertEquals(RelayConsumption.CONSUMED, deliver(first))
        val seen = lastSeen(); assertEquals(now - 1_000, seen)
        assertEquals(RelayConsumption.CONSUMED, deliver(JSONObject(first.toString())))     // the same record again
        assertEquals(seen, lastSeen())
        assertEquals(RelayConsumption.CONSUMED, deliver(signed(heartbeat(ts = now - 1_000))))  // equal send time, new id
        assertEquals(seen, lastSeen())
        assertEquals(RelayConsumption.CONSUMED, deliver(signed(heartbeat(ts = now - 3 * 3_600_000L))))  // delayed by hours
        assertEquals(seen, lastSeen())
        assertEquals(0, notices())
    }

    @Test fun aRewrappedOrDowngradedRecordIsTamper() {
        val genuine = signed(heartbeat())
        assertEquals(RelayConsumption.REJECTED, deliver(JSONObject(genuine.toString()).put("event_id", UUID.randomUUID().toString())))
        prefs.edit().remove("child_tamper_notice_ms").commit()
        assertEquals(RelayConsumption.REJECTED, deliver(JSONObject(genuine.getString("child_signed"))))   // the inner record, unsigned
        assertEquals(2, notices()); assertEquals(0L, lastSeen())
    }

    @Test fun a52PairingKeepsWorkingNotVerifiedUntilAQrPairingThenUnsignedIsRefused() {
        prefs.edit().remove("pinned_child_key").commit()
        assertEquals(GuardEngine.ChildTrust.NO_CHILD, GuardEngine.childTrust(ctx))
        assertEquals(RelayConsumption.CONSUMED, deliver(heartbeat()))
        assertTrue("an unverified heartbeat still counts for the warning", lastSeen() > 0)
        assertEquals(GuardEngine.ChildTrust.NOT_VERIFIED, GuardEngine.childTrust(ctx))
        // A signed record without a pinned key is dropped quietly.
        assertEquals(RelayConsumption.REJECTED, deliver(signed(heartbeat())))
        // Pair again through the QR flow (parent engine, bare child machine).
        val rooms = FakeRooms(); val link = ParentPairingLink(ctx, {}, rooms.opener)
        link.begin(); rooms.rooms[0].ready()
        val child = ChildPairing({ FamilySecurity.createChildSigningKey(ctx) }, { FamilySecurity.deleteChildSigningKey(ctx, it) }, QrPairing.palette)
        val start = child.start(link.qrText!!, "Pixel 7", System.currentTimeMillis())!!
        rooms.rooms[0].deliver(start.hello)
        assertTrue(child.onReply(rooms.rooms[0].records().single(), System.currentTimeMillis()))
        assertTrue(link.enterDigits((child.state as ChildPairingState.ShowingDigits).digits))
        child.confirm(System.currentTimeMillis()); assertTrue(link.accept())
        assertEquals(GuardEngine.ChildTrust.PAIRING, GuardEngine.childTrust(ctx))
        assertEquals(RelayConsumption.REJECTED, deliver(heartbeat()))
        assertEquals(GuardEngine.ChildTrust.PAIRING, GuardEngine.childTrust(ctx))
        val alias = TestKeyStore.privateKeys.keys.single { it.startsWith("ig_guard.child_signing") }
        val k = FamilySecurity.loadChildSigningKey(ctx, alias)
        val record = heartbeat()
        val frame = ChildRecords.wrapForParent(record.toString(), record.getString("event_id"), parentKey, k.publicKey, { FamilySecurity.signChildBytes(it, alias) }, true)
        assertEquals(RelayConsumption.CONSUMED, deliver(frame))
        assertEquals("banner gone", GuardEngine.ChildTrust.VERIFIED, GuardEngine.childTrust(ctx))
    }
}
