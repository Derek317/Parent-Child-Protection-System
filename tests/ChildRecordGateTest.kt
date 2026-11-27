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
        TestKeyStore.privateKeys.keys.removeAll { it.startsWith("ig_guard.child_signing") } // tests that pair again leave a K behind
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

    /** A refused multi-part record leaves none of its parts stored; a forged set cannot cost a genuine record with the same id. */
    @Test fun aRefusedMultiPartRecordLeavesNoPartsAndCannotDropAGenuineOne() {
        val id = UUID.randomUUID().toString()
        fun big(text: String) = heartbeat().put("event_id", id).put("reason", "test_alert").put("text", text)
        fun parts(frame: JSONObject) = AlertFrames.split(frame)
        val forged = parts(big("x".repeat(3000)))                   // unsigned: Tamper once complete
        assertTrue(forged.size > 1)
        for (p in forged.dropLast(1)) assertEquals(RelayConsumption.CONSUMED, deliver(p))
        assertTrue(FamilyEvidence(ctx).use { it.incompleteCount(scope) } > 0)
        assertEquals(RelayConsumption.REJECTED, deliver(forged.last()))
        assertEquals("no part of the refused record stays", 0, FamilyEvidence(ctx).use { it.incompleteCount(scope) })
        assertEquals(1, notices()); assertEquals(0, stored())
        // The genuine record with the same id is still taken afterwards.
        val genuine = parts(signed(big("y".repeat(3000))))
        for (p in genuine) deliver(p)
        assertEquals(1, stored()); assertEquals(0, FamilyEvidence(ctx).use { it.incompleteCount(scope) })
        // And a forged set arriving while a genuine one is in progress is refused part by part: it never completes, deletes nothing.
        val id2 = UUID.randomUUID().toString()
        fun big2(text: String) = heartbeat().put("event_id", id2).put("reason", "test_alert").put("text", text)
        val real = parts(signed(big2("z".repeat(3000)))); val fake = parts(big2("w".repeat(3000)))
        deliver(real.first())
        for (p in fake) deliver(p)
        for (p in real.drop(1)) deliver(p)
        assertEquals(2, stored())
    }

    /** A complete genuine multi-part record refused after reassembly (quietly dropped, or a captured message without its policy)
     *  keeps none of its parts: they would count as unfinished for days and fill the 100-record bound. */
    @Test fun aCompleteMultiPartRecordThatIsRefusedLeavesNoParts() {
        fun incomplete() = FamilyEvidence(ctx).use { it.incompleteCount(scope) }
        val old = heartbeat(ts = System.currentTimeMillis() - 11 * 86_400_000L).put("reason", "test_alert").put("text", "x".repeat(3000))
        val dropped = AlertFrames.split(signed(old)); assertTrue(dropped.size > 1)
        for (p in dropped) deliver(p)
        assertEquals(1, GuardEngine.childQuietDrops(ctx))
        assertEquals("quietly dropped: no part stays", 0, incomplete())
        val captured = ConversationCapture.record(CapturedText("stranger", "hello " + "y".repeat(3000), "incoming", author = "stranger"), System.currentTimeMillis(), false, false)
            .put("policy_id", UUID.randomUUID().toString()).put("child_self_handle", "kid")
        val refused = AlertFrames.split(signed(captured)); assertTrue(refused.size > 1)
        for (p in refused) deliver(p)
        assertEquals("a captured message without its policy: no part stays", 0, incomplete())
        assertEquals(0, stored()); assertEquals(0, notices())
    }

    /** The child resends a record until it gets a receipt: an older alert delivered again must not set the child's handle back. */
    @Test fun aRedeliveredOlderAlertDoesNotSetTheChildHandleBack() {
        val now = System.currentTimeMillis()
        val first = signed(heartbeat(ts = now - 2_000, reason = "test_alert").put("child_self_handle", "old"))
        assertEquals(RelayConsumption.CONSUMED, deliver(first)); assertEquals("old", GuardEngine.getChildSelfHandle(ctx))
        assertEquals(RelayConsumption.CONSUMED, deliver(signed(heartbeat(ts = now - 1_000, reason = "test_alert").put("child_self_handle", "new"))))
        assertEquals("new", GuardEngine.getChildSelfHandle(ctx))
        assertEquals(RelayConsumption.CONSUMED, deliver(JSONObject(first.toString())))      // record 1 again
        assertEquals("a repeated record changes nothing", "new", GuardEngine.getChildSelfHandle(ctx))
        assertEquals(2, stored())
    }

    private fun handleSync(name: String, ts: Long) = JSONObject().put("type", "sync_child_handle").put("child_self_handle", name).put("ts_ms", ts).put("time", "x")
        .put("scope", scope).put("command_id", UUID.randomUUID().toString())

    /** An older record arriving for the first time after a newer one (alert or signed handle sync) does not set the handle back. */
    @Test fun anOlderRecordArrivingAfterANewerOneDoesNotSetTheChildHandleBack() {
        val now = System.currentTimeMillis()
        assertEquals(RelayConsumption.CONSUMED, deliver(signed(heartbeat(ts = now - 2_000, reason = "test_alert").put("child_self_handle", "first"))))
        assertEquals(RelayConsumption.CONSUMED, deliver(signed(heartbeat(ts = now - 1_000, reason = "test_alert").put("child_self_handle", "newer"))))
        assertEquals("a newer record still updates", "newer", GuardEngine.getChildSelfHandle(ctx))
        assertEquals(RelayConsumption.CONSUMED, deliver(signed(heartbeat(ts = now - 5_000, reason = "test_alert").put("child_self_handle", "older"))))
        assertEquals("newer", GuardEngine.getChildSelfHandle(ctx)); assertEquals(3, stored())
        // A signed handle sync is ordered only against other syncs by the gate; against the alert's time it is older.
        assertEquals(RelayConsumption.CONSUMED, deliver(signed(handleSync("older_sync", now - 3_000))))
        assertEquals("newer", GuardEngine.getChildSelfHandle(ctx))
        assertEquals(RelayConsumption.CONSUMED, deliver(signed(handleSync("newest_sync", now))))
        assertEquals("newest_sync", GuardEngine.getChildSelfHandle(ctx))
        // A captured message of an old chat message: its handle was read at capture time (captured_at_ms), not at ts_ms.
        val policy = UUID.randomUUID().toString(); prefs.edit().putString("capture_policy_id", policy).commit()
        val captured = ConversationCapture.record(CapturedText("stranger", "hello", "incoming", author = "stranger", messageTime = now - 86_400_000L), now + 1, false, false)
            .put("policy_id", policy).put("child_self_handle", "captured_now")
        assertEquals(RelayConsumption.CONSUMED, deliver(signed(captured)))
        assertEquals("captured_now", GuardEngine.getChildSelfHandle(ctx))
    }

    /** After an upgrade the handle has no stored time: the first record with a handle is taken, whatever its time. */
    @Test fun aStoredHandleWithoutItsTimeIsReplacedByTheFirstRecordWithAHandle() {
        GuardEngine.setChildSelfHandle(ctx, "from_before"); assertFalse(prefs.contains("child_self_handle_ts_ms"))
        assertEquals(RelayConsumption.CONSUMED, deliver(signed(heartbeat(ts = System.currentTimeMillis() - 86_400_000L, reason = "test_alert").put("child_self_handle", "first_record"))))
        assertEquals("first_record", GuardEngine.getChildSelfHandle(ctx)); assertTrue(prefs.contains("child_self_handle_ts_ms"))
    }

    /** A new family starts without the old family's handle time, so its first record with a handle is not refused as older. */
    @Test fun aNewFamilyClearsTheTimeStoredWithTheHandle() {
        assertEquals(RelayConsumption.CONSUMED, deliver(signed(heartbeat(ts = System.currentTimeMillis(), reason = "test_alert").put("child_self_handle", "old_family"))))
        assertTrue(prefs.contains("child_self_handle_ts_ms"))
        GuardEngine.setFamilyToken(ctx, UUID.randomUUID().toString())
        assertFalse(prefs.contains("child_self_handle")); assertFalse(prefs.contains("child_self_handle_ts_ms"))
    }

    @Test fun aPre60PairingIsRefusedFromAprilFirst2027() {
        prefs.edit().remove("pinned_child_key").commit()
        val sync = { JSONObject().put("type", "sync_child_handle").put("child_self_handle", "h").put("ts_ms", System.currentTimeMillis()).put("time", "x") }
        try {
            GuardEngine.wallClock = { GuardEngine.LEGACY_PAIRING_END_MS - 1 }
            assertEquals(RelayConsumption.CONSUMED, deliver(legacyControl(sync())))
            GuardEngine.wallClock = { GuardEngine.LEGACY_PAIRING_END_MS }
            assertEquals(RelayConsumption.REJECTED, deliver(legacyControl(sync())))
        } finally { GuardEngine.wallClock = { System.currentTimeMillis() } }
    }

    /** A 5.2 pairing (no pinned key) sends an unsigned handle sync with ts_ms: the same rule applies to it. */
    @Test fun aLegacyHandleSyncFollowsTheSameOrder() {
        prefs.edit().remove("pinned_child_key").commit()
        val now = System.currentTimeMillis()
        assertEquals(RelayConsumption.CONSUMED, deliver(legacyControl(JSONObject().put("type", "sync_child_handle").put("child_self_handle", "newer").put("ts_ms", now).put("time", "x"))))
        assertEquals("newer", GuardEngine.getChildSelfHandle(ctx))
        assertEquals(RelayConsumption.CONSUMED, deliver(legacyControl(JSONObject().put("type", "sync_child_handle").put("child_self_handle", "older").put("ts_ms", now - 60_000).put("time", "x"))))
        assertEquals("newer", GuardEngine.getChildSelfHandle(ctx))
    }

    private fun relayTakesEverythingQueued() { for (frame in FamilyEvidence(ctx).use { it.due(scope, Long.MAX_VALUE, "parent") }) GuardEngine.recordRelayAcceptance(ctx, scope, frame) }

    /** Remote reset of a pinned child: once the relay holds it, K is unpinned and nothing later "from" that child is taken. */
    @Test fun aRemoteResetOfAVerifiedChildUnpinsItAndRefusesEveryLaterRecordUntilANewQrPairing() {
        prefs.edit().putBoolean("family_qr_only", true).commit() // written with T1 by completeParentPairing; pin() alone skips that
        assertEquals(RelayConsumption.CONSUMED, deliver(signed(heartbeat())))
        assertEquals(GuardEngine.ChildTrust.VERIFIED, GuardEngine.childTrust(ctx)); assertTrue(GuardEngine.hasChildHeartbeat(ctx))
        val familyToken = token
        GuardEngine.rotateKeyAndNotifyChild(ctx, true)
        assertEquals("queued, not yet with the relay", GuardEngine.ChildTrust.VERIFIED, GuardEngine.childTrust(ctx))
        relayTakesEverythingQueued()
        assertEquals(GuardEngine.ChildTrust.REMOTE_RESET, GuardEngine.childTrust(ctx)); assertTrue(GuardEngine.childRemotelyReset(ctx))
        assertNull("the pin is dropped", GuardEngine.pinnedChildKey(ctx))
        assertFalse("not a silent offline child", GuardEngine.hasChildHeartbeat(ctx) || GuardEngine.isChildHeartbeatOverdue(ctx))
        assertEquals("the token stays until a new QR pairing", familyToken, token)
        val kept = stored()
        for (frame in listOf(signed(heartbeat()), heartbeat(), signed(heartbeat(), by = stranger), signed(scoresReport().put("command_id", UUID.randomUUID().toString()))))
            assertEquals(RelayConsumption.REJECTED, deliver(frame))
        assertEquals(kept, stored()); assertEquals(0L, lastSeen()); assertEquals(GuardEngine.ChildTrust.REMOTE_RESET, GuardEngine.childTrust(ctx))
        // Pairing again with the QR code clears the mark and replaces the token.
        val rooms = FakeRooms(); val link = ParentPairingLink(ctx, {}, rooms.opener)
        link.begin(); rooms.rooms[0].ready()
        val child = ChildPairing({ FamilySecurity.createChildSigningKey(ctx) }, { FamilySecurity.deleteChildSigningKey(ctx, it) }, QrPairing.palette)
        rooms.rooms[0].deliver(child.start(link.qrText!!, "Pixel 7", System.currentTimeMillis())!!.hello)
        assertTrue(child.onReply(rooms.rooms[0].records().single(), System.currentTimeMillis()))
        assertTrue(link.enterDigits((child.state as ChildPairingState.ShowingDigits).digits))
        child.confirm(System.currentTimeMillis()); assertTrue(link.accept())
        assertFalse(GuardEngine.childRemotelyReset(ctx)); assertEquals(GuardEngine.ChildTrust.PAIRING, GuardEngine.childTrust(ctx))
        assertNotEquals(familyToken, token)
    }

    @Test fun aRemoteResetOfA52PairingChangesNothingOnTheParent() {
        prefs.edit().remove("pinned_child_key").commit()
        assertEquals(RelayConsumption.CONSUMED, deliver(heartbeat()))
        GuardEngine.rotateKeyAndNotifyChild(ctx, true); relayTakesEverythingQueued()
        assertFalse(GuardEngine.childRemotelyReset(ctx)); assertEquals(GuardEngine.ChildTrust.NOT_VERIFIED, GuardEngine.childTrust(ctx))
    }

    // ---------- replay ids (seen) pruned by the nightly cleanup ----------
    private val day = 86_400_000L
    private fun putSeen(id: String, storedAt: Long?) = FamilyEvidence(ctx).use {
        it.writableDatabase.execSQL("INSERT INTO seen(scope,id,stored_at) VALUES(?,?,?)", arrayOf(scope, id, storedAt))
    }
    private fun seen(id: String) = FamilyEvidence(ctx).use { it.hasSeen(scope, id) }

    @Test fun aReplayOfAnIdPrunedFromSeenIsStillRefusedByTheRecordWindow() {
        prefs.edit().putBoolean("family_qr_only", true).commit()
        val now = System.currentTimeMillis()
        // Received 17 days ago, then deleted: its record time was at most 24 h ahead of the parent's clock then.
        val record = heartbeat(now - 17 * day + RiskLimits.REPORT_FUTURE_MS, "stranger_message")
        putSeen(record.getString("event_id"), now - 17 * day)
        ArchiveMaintenance.pruneAll(ctx, now)
        assertFalse("pruned after twice the record window", seen(record.getString("event_id")))
        assertEquals(RelayConsumption.REJECTED, deliver(signed(record)))
        assertEquals("the replay is not stored", 0, stored())
    }
    @Test fun theNightlyPruneDropsOnlyOldIdsWithoutARecordAndOnlyOnAQrOnlyParent() {
        val now = System.currentTimeMillis()
        // A 5.2 pairing (no key, no QR-only fact): seen is its only replay guard, so nothing is pruned.
        prefs.edit().remove("pinned_child_key").commit()
        putSeen("legacy", now - 40 * day)
        ArchiveMaintenance.pruneAll(ctx, now)
        assertTrue(seen("legacy"))
        // A child phone keeps its discard tombstones.
        prefs.edit().putBoolean("family_qr_only", true).commit(); pin(); GuardEngine.setRole(ctx, "child")
        ArchiveMaintenance.pruneAll(ctx, now)
        assertTrue(seen("legacy"))
        GuardEngine.setRole(ctx, "parent")
        val kept = heartbeat(reason = "stranger_message")
        assertEquals(RelayConsumption.CONSUMED, deliver(signed(kept)))
        FamilyEvidence(ctx).use { it.writableDatabase.execSQL("UPDATE seen SET stored_at=? WHERE id=?", arrayOf(now - 40 * day, kept.getString("event_id"))) }
        putSeen("old", now - 17 * day); putSeen("recent", now - 15 * day); putSeen("unstamped", null)
        ArchiveMaintenance.pruneAll(ctx, now)
        assertFalse(seen("legacy")); assertFalse(seen("old"))
        assertTrue(seen("recent")); assertTrue("an id with a stored record stays", seen(kept.getString("event_id")))
        // An id without a time gets the time of the first cleanup that sees it, never an earlier one.
        ArchiveMaintenance.pruneAll(ctx, now + 15 * day); assertTrue(seen("unstamped"))
        ArchiveMaintenance.pruneAll(ctx, now + 17 * day); assertFalse(seen("unstamped"))
    }
    @Test fun anUpgradeFromSchemaTenKeepsEveryReplayIdAndPrunesNoneEarly() {
        prefs.edit().putBoolean("family_qr_only", true).commit()
        val now = System.currentTimeMillis()
        FamilyEvidence(ctx).use { db ->
            val sql = db.writableDatabase
            sql.execSQL("DROP TABLE seen"); sql.execSQL("CREATE TABLE seen(scope TEXT NOT NULL,id TEXT NOT NULL,PRIMARY KEY(scope,id))")
            sql.execSQL("INSERT INTO seen VALUES(?,'a'),(?,'b')", arrayOf(scope, scope))
            sql.version = 10
        }
        FamilyEvidence(ctx).use { db -> assertEquals(11, db.readableDatabase.version) }
        ArchiveMaintenance.pruneAll(ctx, now); ArchiveMaintenance.pruneAll(ctx, now + 15 * day)
        assertTrue(seen("a")); assertTrue(seen("b"))
        ArchiveMaintenance.pruneAll(ctx, now + 17 * day)
        assertFalse(seen("a")); assertFalse(seen("b"))
    }

    private val room get() = FamilyRelay.room(parentKey, scope)
    private fun deliverWire(wire: String) = GuardEngine.processIncomingRelayLine(ctx, JSONObject().put("event", "message")
        .put("id", UUID.randomUUID().toString()).put("message", wire).toString(), "parent", null)

    @Test fun theParentsOwnCommandReflectedByTheRelayIsRefusedQuietly() {
        val own = FamilySecurity.loadOrCreateParentIdentity(ctx, null, parentKey)
        val command = JSONObject().put("type", "unlock_code").put("scope", scope).put("code", "123456").put("exp", System.currentTimeMillis() + 60_000L)
            .put("command_id", UUID.randomUUID().toString()).put("ts_ms", System.currentTimeMillis())
        assertEquals(RelayConsumption.REJECTED, deliver(Pairing.commandRecord(FamilySecurity.signCommand(command, own.privateKey), command.getString("command_id"))))
        assertEquals("a reflection is not the child tampering", 0, notices())
        assertEquals("another key's command still counts as tampering", RelayConsumption.REJECTED,
            deliver(Pairing.commandRecord(FamilySecurity.signCommand(command, stranger.privateKey), command.getString("command_id"))))
        assertEquals(1, notices())
    }

    @Test fun aV2EnvelopeIsReadOnlyFromTheChildForThisRoomAndRecord() {
        val frame = signed(heartbeat()); val id = frame.getString("event_id") + ":0"
        assertEquals(RelayConsumption.REJECTED, deliverWire(GuardEngine.encryptPayloadV2(token, frame.toString(), room, "parent", id)))
        assertEquals(RelayConsumption.REJECTED, deliverWire(GuardEngine.encryptPayloadV2(token, frame.toString(), "a".repeat(64), "child", id)))
        assertEquals(RelayConsumption.REJECTED, deliverWire(GuardEngine.encryptPayloadV2(token, frame.toString(), room, "child", UUID.randomUUID().toString() + ":0")))
        assertEquals(0, notices()); assertEquals(0L, lastSeen())
        assertEquals(RelayConsumption.CONSUMED, deliverWire(GuardEngine.encryptPayloadV2(token, frame.toString(), room, "child", id)))
        assertTrue("the child's heartbeat counts", lastSeen() > 0L)
        assertNull("v1 readers without the room never take v2", GuardEngine.decryptPayload(token, GuardEngine.encryptPayloadV2(token, frame.toString(), room, "child", id)))
    }
}
