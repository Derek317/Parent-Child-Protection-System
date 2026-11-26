package app.family.igguard

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

/** The "records not delivered" warning: the child's three signed heartbeat fields and the parent's rule (DeliveryHealth). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DeliveryHealthTest {
    private val ctx: Application get() = RuntimeEnvironment.getApplication()
    private val prefs get() = LocalVault.preferences(ctx)
    private val token get() = GuardEngine.getOrCreateFamilyToken(ctx)
    private val scope get() = GuardEngine.getChannelTopic(ctx)

    @Before fun reset() {
        prefs.edit().clear().commit(); ctx.deleteDatabase("family_evidence.db")
        TestKeyStore.privateKeys.clear(); TestKeyStore.certificates.clear()
        GuardEngine.parentRelayReadySince = 0L; GuardEngine.deliveryClock = System::currentTimeMillis
    }

    // ---------------- child ----------------
    /** The queued records of this child, decrypted (single-frame records only: heartbeats and short alerts). */
    private fun queued(): List<JSONObject> = FamilyEvidence(ctx).use { it.due(scope, Long.MAX_VALUE) }.groupBy { it.id.substringBeforeLast(':') }.values.map { parts ->
        val decoded = parts.map { GuardEngine.decryptPayload(token, it.wire)!! }
        if (decoded.size == 1 && decoded[0].optString("type") != "alert_fragment") decoded[0]
        else JSONObject(String(decoded.sortedBy { it.getInt("part") }.flatMap { android.util.Base64.decode(it.getString("data"), android.util.Base64.NO_WRAP).toList() }.toByteArray(), Charsets.UTF_8))
    }
    private fun heartbeats() = queued().map { if (it.has("child_signed")) JSONObject(it.getString("child_signed")) else it }.filter { it.optString("sender") == "子女手機定時回報" }
    private val seenHeartbeats = mutableSetOf<String>()
    /** The heartbeat queued last (each call notes the ids seen so far). */
    private fun lastHeartbeat(): JSONObject {
        val all = heartbeats(); val fresh = all.filter { it.getString("event_id") !in seenHeartbeats }
        seenHeartbeats += all.map { it.getString("event_id") }
        return fresh.singleOrNull() ?: all.maxByOrNull { it.getLong("ts_ms") }!!
    }
    private fun fields(r: JSONObject) = Triple(r.getString("waiting"), r.getString("oldest_min"), r.getString("expired"))
    private fun alert() = GuardEngine.sendAlertFromChild(ctx, "test_alert", "測試", "test", bypassDedupe = true)
    private fun expireEverythingQueued() {
        FamilyEvidence(ctx).use { it.writableDatabase.execSQL("UPDATE outbox_lifetimes SET expires=? WHERE scope=?", arrayOf(System.currentTimeMillis() - 1, scope)) }
        FamilyEvidence(ctx).use { it.pruneExpired(scope) }
    }
    private fun receipt(identity: FamilySecurity.SigningIdentity, eventId: String) {
        val id = UUID.randomUUID().toString()
        val payload = JSONObject().put("type", "alert_receipt").put("scope", scope).put("command_id", id).put("ts_ms", System.currentTimeMillis())
            .put("event_id", eventId).put("outcome", "stored")
        val record = Pairing.commandRecord(FamilySecurity.signCommand(payload, identity.privateKey), id)
        assertEquals(RelayConsumption.CONSUMED, GuardEngine.processIncomingRelayLine(ctx, JSONObject().put("event", "message").put("id", UUID.randomUUID().toString())
            .put("message", GuardEngine.encryptPayload(token, record.toString())).toString(), "child", null))
    }
    private fun alertIds() = queued().filter { it.has("child_signed") }.map { JSONObject(it.getString("child_signed")) }.filter { it.optString("reason") == "test_alert" }.map { it.getString("event_id") }

    @Test fun countsReceiptWaitingRecordsButNotHeartbeatsAndCaps() {
        QrPairing.pairChild(ctx)                                     // pairing_verified waits for a receipt: 1
        repeat(3) { alert() }
        GuardEngine.checkAndSendChildHeartbeatIfDue(ctx, force = true)
        assertEquals(Triple("04", "0000", "0"), fields(lastHeartbeat()))
        GuardEngine.checkAndSendChildHeartbeatIfDue(ctx, force = true)
        assertEquals("the first heartbeat is not counted", Triple("04", "0000", "0"), fields(lastHeartbeat()))
        val later = GuardEngine.deliveryHealthFields(ctx, UUID.randomUUID().toString(), System.currentTimeMillis() + 61 * 60_000L)
        assertEquals("04", later.getString("waiting")); assertEquals("0061", later.getString("oldest_min"))
        // Caps and widths.
        assertEquals("99", DeliveryHealth.fields(150, 0L, false, 1L).getString("waiting"))
        assertEquals("9999", DeliveryHealth.fields(1, 0L, false, 20_000L * 60_000L).getString("oldest_min"))
        assertEquals("00", DeliveryHealth.fields(0, null, false, 1L).getString("waiting"))
    }

    @Test fun theHeartbeatHasTheSameLengthInEveryState() {
        val identity = FamilySecurity.newSigningIdentity(); QrPairing.pairChild(ctx, identity)
        GuardEngine.checkAndSendChildHeartbeatIfDue(ctx, force = true); val a = lastHeartbeat()
        repeat(12) { alert() }
        GuardEngine.checkAndSendChildHeartbeatIfDue(ctx, force = true); val b = lastHeartbeat()
        expireEverythingQueued()
        GuardEngine.checkAndSendChildHeartbeatIfDue(ctx, force = true); val c = lastHeartbeat()
        assertNotEquals(fields(a), fields(b)); assertEquals("1", fields(c).third)
        fun lengthWithoutRandomText(r: JSONObject) = r.toString().length - r.getString("text").length
        assertEquals(lengthWithoutRandomText(a), lengthWithoutRandomText(b)); assertEquals(lengthWithoutRandomText(a), lengthWithoutRandomText(c))
    }

    @Test fun theExpiredBitIsSetByTtlAndClearedOnlyAsSpecified() {
        val identity = FamilySecurity.newSigningIdentity(); QrPairing.pairChild(ctx, identity)
        // Only a heartbeat expires: no bit.
        FamilyEvidence(ctx).use { db -> db.due(scope, Long.MAX_VALUE).map { it.id.substringBeforeLast(':') }.distinct().forEach { db.acknowledgeEvent(scope, it) } }
        GuardEngine.checkAndSendChildHeartbeatIfDue(ctx, force = true)
        expireEverythingQueued()
        GuardEngine.checkAndSendChildHeartbeatIfDue(ctx, force = true)
        assertEquals("0", fields(lastHeartbeat()).third)
        // A counted record expires unreceipted: sticky bit.
        alert(); val old = alertIds().single()
        expireEverythingQueued()
        assertTrue(prefs.getLong(DeliveryHealth.EXPIRED_AT_MS, 0L) > 0L)
        Thread.sleep(5)
        alert(); val fresh = alertIds().single()
        GuardEngine.checkAndSendChildHeartbeatIfDue(ctx, force = true); assertEquals("1", fields(lastHeartbeat()).third)
        // Not cleared by a heartbeat's receipt, nor by a receipt for a record queued before the expiry.
        receipt(identity, lastHeartbeat().getString("event_id"))
        receipt(identity, old)
        GuardEngine.checkAndSendChildHeartbeatIfDue(ctx, force = true); assertEquals("1", fields(lastHeartbeat()).third)
        // Cleared by the receipt of a counted record queued after it.
        receipt(identity, fresh)
        GuardEngine.checkAndSendChildHeartbeatIfDue(ctx, force = true); assertEquals("0", fields(lastHeartbeat()).third)
        // Also cleared by a new pairing and by leaving the role.
        alert(); expireEverythingQueued(); assertTrue(prefs.getLong(DeliveryHealth.EXPIRED_AT_MS, 0L) > 0L)
        QrPairing.pairChild(ctx, password = QrPairing.PASSWORD); assertFalse(prefs.contains(DeliveryHealth.EXPIRED_AT_MS))
        alert(); expireEverythingQueued(); assertTrue(prefs.getLong(DeliveryHealth.EXPIRED_AT_MS, 0L) > 0L)
        GuardEngine.setRole(ctx, "unconfigured"); assertFalse(prefs.contains(DeliveryHealth.EXPIRED_AT_MS))
    }

    @Test fun a52PairedChildSendsThe52HeartbeatUnchanged() {
        GuardEngine.setRole(ctx, "parent"); GuardEngine.setPin(ctx, QrPairing.PASSWORD); val invite = QrPairing.legacyInvite(ctx)
        GuardEngine.setRole(ctx, "unconfigured")
        prefs.edit().remove("parent_master_password").remove("parent_share_verifier").remove("parent_signing_public").commit()
        assertTrue(GuardEngine.applyPairing(ctx, invite))
        GuardEngine.checkAndSendChildHeartbeatIfDue(ctx, force = true)
        val hb = queued().single { it.optString("sender") == "子女手機定時回報" }
        assertFalse(hb.has("child_signed"))
        for (f in listOf("waiting", "oldest_min", "expired")) assertFalse(f, hb.has(f))
    }

    // ---------------- parent ----------------
    private val kid = FamilySecurity.newSigningIdentity()
    private fun parentWithPinnedChild() {
        GuardEngine.setRole(ctx, "parent"); GuardEngine.setPin(ctx, QrPairing.PASSWORD); token
        prefs.edit().putString("pinned_child_key", FamilySecurity.canonicalKeyText(kid.publicKey)).putBoolean("family_qr_only", true).commit()
    }
    private var sendClock = System.currentTimeMillis() - 60_000L
    /** Each heartbeat a little later than the last, as a child sends them (the parent acts only on newer send times). */
    private fun hb(waiting: Any, oldest: Any, expired: Any, ts: Long = ++sendClock) = JSONObject().put("type", "alert").put("event_id", UUID.randomUUID().toString())
        .put("capture_source", "app_or_visible_screen").put("reason", "heartbeat_ok").put("sender", "子女手機定時回報").put("text", "裝置 運作中")
        .put("ts_ms", ts).put("time", "x").put("trusted_csv", "").put("child_self_handle", "")
        .put("waiting", waiting).put("oldest_min", oldest).put("expired", expired)
    private fun signed(r: JSONObject) = ChildRecords.wrapForParent(r.toString(), r.getString("event_id"), GuardEngine.relayPublicKey(ctx), kid.publicKey, { FamilySecurity.signBytes(it, kid.privateKey) }, true)
    private fun deliver(frame: JSONObject) = GuardEngine.processIncomingRelayLine(ctx, JSONObject().put("event", "message").put("id", UUID.randomUUID().toString())
        .put("message", GuardEngine.encryptPayload(token, frame.toString())).toString(), "parent", null)
    private fun notices() = GuardEngine.getParentAlerts(ctx).count { it.optString("sender") == DeliveryHealth.NOTICE_SENDER }
    private fun connectedFor(minutes: Long) { GuardEngine.parentRelayReadySince = System.currentTimeMillis() - minutes * 60_000L }

    @Test fun aVerifiedHeartbeatRaisesOneContentFreeNoticeUnderTheRule() {
        parentWithPinnedChild(); connectedFor(20)
        assertEquals(RelayConsumption.CONSUMED, deliver(signed(hb("03", "0059", "0")))); assertEquals("59 minutes: quiet", 0, notices())
        assertEquals(RelayConsumption.CONSUMED, deliver(signed(hb("03", "0060", "0")))); assertEquals(1, notices())
        val notice = GuardEngine.getParentAlerts(ctx).single { it.optString("sender") == DeliveryHealth.NOTICE_SENDER }
        assertEquals("tamper_attempt", notice.optString("reason")); assertEquals(DeliveryHealth.NOTICE_TEXT, notice.optString("text"))
        assertFalse("no count in the notice", notice.optString("text").contains("03"))
        // Once an hour.
        deliver(signed(hb("05", "0120", "1"))); assertEquals(1, notices())
        prefs.edit().putLong(DeliveryHealth.NOTICE_MS, System.currentTimeMillis() - DeliveryHealth.NOTICE_INTERVAL_MS).commit()
        deliver(signed(hb("05", "0120", "0"))); assertEquals(2, notices())
        // "00"/"0" clears the condition: the next bad heartbeat raises at once.
        deliver(signed(hb("00", "0000", "0"))); assertFalse(prefs.contains(DeliveryHealth.NOTICE_MS))
        deliver(signed(hb("00", "0000", "1"))); assertEquals("expired alone raises", 3, notices())
    }

    @Test fun strictParsingIgnoresMalformedFieldsButTheHeartbeatStillCounts() {
        parentWithPinnedChild(); connectedFor(20)
        for ((w, o, e) in listOf(listOf<Any>("3", "0060", "0"), listOf<Any>("03", "60", "0"), listOf<Any>("03", "0060", "2"),
                listOf<Any>(3, 60, 0), listOf<Any>("0a", "0060", "1"))) {
            prefs.edit().remove("parent_last_child_hb_ms").commit()
            assertEquals(RelayConsumption.CONSUMED, deliver(signed(hb(w, o, e))))
            assertTrue("the heartbeat still counts", GuardEngine.hasChildHeartbeat(ctx))
        }
        assertEquals(0, notices())
    }

    @Test fun theParentMustHaveBeenConnectedFor15Minutes() {
        parentWithPinnedChild()
        deliver(signed(hb("09", "0300", "1"))); assertEquals("not connected", 0, notices())
        connectedFor(14); deliver(signed(hb("09", "0300", "1"))); assertEquals("14 minutes", 0, notices())
        connectedFor(16); deliver(signed(hb("09", "0300", "1"))); assertEquals(1, notices())
        GuardEngine.parentRelayReady(false); assertEquals(0L, GuardEngine.parentRelayReadySince)
        GuardEngine.parentRelayReady(true, 1_000L); GuardEngine.parentRelayReady(true, 2_000L); assertEquals("continuous: the first ready counts", 1_000L, GuardEngine.parentRelayReadySince)
    }

    /** A relay that drops this phone's socket every 14 minutes cannot hold the notice back: it comes 3 hours into the bad run. */
    @Test fun aConnectionResetEvery14MinutesDelaysTheNoticeToTheThreeHourBoundOnly() {
        parentWithPinnedChild()
        val start = System.currentTimeMillis(); var t = start
        GuardEngine.deliveryClock = { t }
        val raisedAt = mutableListOf<Long>()
        while (t <= start + 4 * 60 * 60_000L) {
            GuardEngine.parentRelayReadySince = t - (t - start) % (14 * 60_000L)          // reset every 14 minutes
            val before = notices()
            assertEquals(RelayConsumption.CONSUMED, deliver(signed(hb("04", "0200", "0"))))
            if (notices() > before) raisedAt += t
            t += 45 * 60_000L
        }
        assertEquals("exactly one notice in 4 hours", 1, raisedAt.size)
        assertTrue("only after the 3-hour bound", raisedAt.single() - start >= DeliveryHealth.LONG_BAD_MS)
        // A clear report ends the run: the next bad run starts its own 3 hours.
        deliver(signed(hb("00", "0000", "0"))); assertFalse(prefs.contains(DeliveryHealth.FIRST_BAD_MS))
    }

    @Test fun theNoticeStateGoesWithTheRoleOrTheFamily() {
        parentWithPinnedChild()
        fun dirty() = prefs.edit().apply { DeliveryHealth.PARENT_KEYS.forEach { k -> putLong(k, 1L) } }.commit()
        dirty(); GuardEngine.setFamilyToken(ctx, UUID.randomUUID().toString())                       // a new family
        assertTrue(DeliveryHealth.PARENT_KEYS.none { prefs.contains(it) })
        dirty(); assertTrue(GuardEngine.leaveParentRole(ctx))                                           // leaving the role
        assertTrue(DeliveryHealth.PARENT_KEYS.none { prefs.contains(it) })
    }

    /** An older (or equal) send time than the last one acted on neither clears nor raises. */
    @Test fun anOlderHeartbeatNeitherClearsNorRaises() {
        parentWithPinnedChild(); connectedFor(20)
        val t2 = System.currentTimeMillis() - 1_000L
        deliver(signed(hb("03", "0090", "0", ts = t2))); assertEquals(1, notices())
        val timer = prefs.getLong(DeliveryHealth.NOTICE_MS, 0L); val first = prefs.getLong(DeliveryHealth.FIRST_BAD_MS, 0L)
        assertTrue(timer > 0L && first > 0L)
        deliver(signed(hb("00", "0000", "0", ts = t2 - 5_000L)))          // older clear report
        deliver(signed(hb("00", "0000", "0", ts = t2)))                    // same send time
        assertEquals(timer, prefs.getLong(DeliveryHealth.NOTICE_MS, 0L)); assertEquals(first, prefs.getLong(DeliveryHealth.FIRST_BAD_MS, 0L))
        prefs.edit().putLong(DeliveryHealth.NOTICE_MS, 1L).commit()
        deliver(signed(hb("09", "0300", "1", ts = t2 - 1L)))               // older bad report
        assertEquals("an older bad report raises nothing", 1, notices())
        deliver(signed(hb("00", "0000", "0", ts = t2 + 1L)))               // a newer clear report does clear
        assertFalse(prefs.contains(DeliveryHealth.FIRST_BAD_MS))
    }

    /** A child clock set ahead and then back: the heartbeat dated ahead must not switch the check off until the real time catches up. */
    @Test fun aHeartbeatDatedAheadDoesNotBlockTheCheckOnceTheChildClockIsBack() {
        parentWithPinnedChild(); connectedFor(20)
        val now = System.currentTimeMillis()
        assertEquals(RelayConsumption.CONSUMED, deliver(signed(hb("00", "0000", "0", ts = now + 10 * 60 * 60_000L))))   // 10 hours ahead
        assertEquals(RelayConsumption.CONSUMED, deliver(signed(hb("03", "0090", "0", ts = now + 60_000L))))             // the real time again
        assertEquals("the bad report after the clock was set back raises the notice", 1, notices())
    }

    /** A child clock 3 minutes fast: its verified heartbeat still counts as a sign of life, stored at the parent's now. */
    @Test fun aHeartbeatDatedMinutesAheadIsRecordedAtNow() {
        parentWithPinnedChild()
        val before = System.currentTimeMillis()
        assertEquals(RelayConsumption.CONSUMED, deliver(signed(hb("00", "0000", "0", ts = before + 3 * 60_000L))))
        val after = System.currentTimeMillis()
        val stored = prefs.getLong("parent_last_child_hb_ms", 0L)
        assertTrue("stored time is the parent's now, not the child's: $stored", stored in before..after)
    }

    /** A send time more than 24 hours ahead is still not a sign of life. */
    @Test fun aHeartbeatDated25HoursAheadIsRefused() {
        GuardEngine.recordChildHeartbeatOnParent(ctx, "summary", System.currentTimeMillis() + 25 * 3_600_000L)
        assertFalse(GuardEngine.hasChildHeartbeat(ctx))
    }

    /** A child clock wrong by more than the 10-day command window: its heartbeats fall outside the parent's window too, so the parent
     *  stops seeing heartbeats and its existing missing-heartbeat alert fires; each refused record is counted as a quiet drop. */
    @Test fun aChildClockFarOffShowsOnTheParentAsMissingHeartbeats() {
        parentWithPinnedChild()
        val now = System.currentTimeMillis()
        assertEquals(RelayConsumption.CONSUMED, deliver(signed(hb("00", "0000", "0", ts = now - 101 * 60_000L))))   // the last good heartbeat
        val drops = GuardEngine.childQuietDrops(ctx)
        assertEquals("11 days ahead", RelayConsumption.REJECTED, deliver(signed(hb("00", "0000", "0", ts = now + 11 * 86_400_000L))))
        assertEquals("11 days behind", RelayConsumption.REJECTED, deliver(signed(hb("00", "0000", "0", ts = now - 11 * 86_400_000L))))
        assertEquals(drops + 2, GuardEngine.childQuietDrops(ctx))
        assertTrue("no heartbeat counted: overdue", GuardEngine.isChildHeartbeatOverdue(ctx))
        assertTrue("the parent's missing-heartbeat alert fires", GuardEngine.shouldFireHeartbeatMissingAlert(ctx))
    }

    /** A signed scores report as a QR-paired child queues it (control record, receipt requested); [model] "other" is refused by the parser. */
    private fun scoresReport(sentAt: Long, model: String = "valid", rows: Int = 0): Pair<String, JSONObject> {
        val id = UUID.randomUUID().toString()
        val state = JSONObject().put("policy_id", JSONObject.NULL).put("model", model).put("consent", "none").put("active", false)
            .put("paused", false).put("paused_at_ms", JSONObject.NULL).put("last_run_ms", JSONObject.NULL).put("scored_count", 0).put("unknown_threads", 0)
        val conversations = org.json.JSONArray().apply { repeat(rows) { put(JSONObject().put("account_label", "account$it").put("score", 10.0).put("review_reason", JSONObject.NULL).put("scored_at_ms", sentAt)) } }
        val record = JSONObject().put("type", "scores_mode_report").put("sent_at_ms", sentAt).put("state", state).put("conversations", conversations)
            .put("scope", scope).put("command_id", id)
        return "control:$id" to ChildRecords.wrapForParent(record.toString(), "control:$id", GuardEngine.relayPublicKey(ctx), kid.publicKey, { FamilySecurity.signBytes(it, kid.privateKey) }, true)
    }
    /** The receipts this parent has queued: event id -> outcome. */
    private fun parentReceipts(): Map<String, String> = FamilyEvidence(ctx).use { it.due(scope, Long.MAX_VALUE, "parent") }.filter { it.kind == "receipt" }
        .map { JSONObject(GuardEngine.decryptPayload(token, it.wire)!!.getJSONObject("envelope").getString("payload")) }
        .associate { it.getString("event_id") to it.getString("outcome") }

    /** Scores reports: the parent receipts a verified report it stored, and never one its parser refused. */
    @Test fun theParentReceiptsAStoredScoresReportAndNotARefusedOne() {
        parentWithPinnedChild()
        val (stored, good) = scoresReport(System.currentTimeMillis() - 1_000L)
        assertEquals(RelayConsumption.CONSUMED, deliver(good))
        assertEquals(mapOf(stored to "stored"), parentReceipts())
        val (refused, bad) = scoresReport(System.currentTimeMillis() - 1_000L, model = "other")
        assertEquals(RelayConsumption.CONSUMED, deliver(bad))
        assertFalse("a refused report gets no receipt", parentReceipts().containsKey(refused))
    }

    /** The 30-second receipt throttle, moved back for [event] only: a resend comes after the child's retry backoff (30 s or more). */
    @Suppress("UNCHECKED_CAST")
    private fun receiptThrottlePassed(event: String) {
        val recent = GuardEngine::class.java.getDeclaredField("recentAlerts").apply { isAccessible = true }.get(GuardEngine) as LinkedHashMap<String, Long>
        synchronized(GuardEngine) { recent.computeIfPresent("receipt:$scope:$event") { _, at -> at - 31_000L } }
    }
    private fun dropParentReceipts() = FamilyEvidence(ctx).use { db -> db.due(scope, Long.MAX_VALUE, "parent").filter { it.kind == "receipt" }.forEach { db.acknowledge(scope, it.id) } }

    /** A lost receipt: the child sends an already stored report again, whole or in parts, and the parent receipts it again. */
    @Test fun theParentReceiptsAStoredScoresReportAgainWhenItIsResent() {
        parentWithPinnedChild()
        val (single, frame) = scoresReport(System.currentTimeMillis() - 1_000L)
        assertEquals(RelayConsumption.CONSUMED, deliver(frame)); assertEquals(mapOf(single to "stored"), parentReceipts())
        dropParentReceipts(); receiptThrottlePassed(single)
        assertEquals(RelayConsumption.CONSUMED, deliver(frame))
        assertEquals("single frame, already stored: receipted again", mapOf(single to "stored"), parentReceipts())

        val (parted, whole) = scoresReport(System.currentTimeMillis() - 1_000L, rows = 60)
        val parts = AlertFrames.split(whole); assertTrue(parts.size > 1)
        for (part in parts) assertEquals(RelayConsumption.CONSUMED, deliver(part))
        assertEquals("stored", parentReceipts()[parted])
        dropParentReceipts(); receiptThrottlePassed(parted)
        assertEquals(RelayConsumption.CONSUMED, deliver(parts.first()))
        assertEquals("a part of an already stored report: receipted again", mapOf(parted to "stored"), parentReceipts())
    }

    @Test fun legacyHeartbeatsCarryingTheFieldsRaiseNothing() {
        // No pinned key, no QR fact: a 5.2 pairing takes the heartbeat as Legacy, and the fields are ignored.
        GuardEngine.setRole(ctx, "parent"); GuardEngine.setPin(ctx, QrPairing.PASSWORD); token; connectedFor(20)
        assertEquals(RelayConsumption.CONSUMED, deliver(hb("09", "0300", "1")))
        assertTrue(GuardEngine.hasChildHeartbeat(ctx)); assertEquals(0, notices())
        // With a pinned key the unsigned heartbeat is refused outright.
        parentWithPinnedChild(); connectedFor(20)
        assertEquals(RelayConsumption.REJECTED, deliver(hb("09", "0300", "1"))); assertEquals(0, notices())
    }
}
