package app.family.igguard

import android.app.Application
import android.content.Context
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

/** Scores-only family mode, parent side: strict receiver, score tables, policy sender, notification. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ScoresModeParentTest {
    private val ctx: Application get() = RuntimeEnvironment.getApplication()
    private val prefs get() = LocalVault.preferences(ctx)
    private val scope get() = GuardEngine.getChannelTopic(ctx)
    private val token get() = GuardEngine.getOrCreateFamilyToken(ctx)
    private val day = 86_400_000L

    @Before fun reset() {
        ctx.deleteDatabase("family_evidence.db"); prefs.edit().clear().commit()
        GuardEngine.setFamilyToken(ctx, UUID.randomUUID().toString()); GuardEngine.setRole(ctx, "parent")
    }

    private fun row(label: String, score: Double?, at: Long, reason: String? = if (score == null) "insufficient_context" else null) =
        JSONObject().put("account_label", label).put("score", score ?: JSONObject.NULL).put("review_reason", reason ?: JSONObject.NULL).put("scored_at_ms", at)
    private fun state(model: String = "valid") = JSONObject().put("policy_id", UUID.randomUUID().toString()).put("model", model).put("consent", "accepted")
        .put("active", true).put("paused", false).put("paused_at_ms", JSONObject.NULL).put("last_run_ms", System.currentTimeMillis()).put("scored_count", 2).put("unknown_threads", 1)
    private fun report(rows: List<JSONObject> = listOf(row("alice", 42.0, System.currentTimeMillis() - 1000)), sentAt: Long = System.currentTimeMillis(), state: JSONObject = state()) =
        JSONObject().put("type", "scores_mode_report").put("sent_at_ms", sentAt).put("state", state).put("conversations", JSONArray(rows))
            .put("scope", scope).put("command_id", UUID.randomUUID().toString())
    private fun deliver(payload: JSONObject, callback: ((JSONObject) -> Unit)? = null): RelayConsumption {
        val id = payload.optString("command_id")
        val wrapped = JSONObject().put("type", "alert").put("event_id", "control:$id").put("relay_control", true).put("envelope", payload)
        val line = JSONObject().put("event", "message").put("id", UUID.randomUUID().toString()).put("message", GuardEngine.encryptPayload(token, wrapped.toString()))
        return GuardEngine.processIncomingRelayLine(ctx, line.toString(), "parent", callback)
    }
    private fun count(table: String) = FamilyEvidence(ctx).use { db -> db.readableDatabase.rawQuery("SELECT COUNT(*) FROM $table", null).use { it.moveToFirst(); it.getInt(0) } }
    private fun scores() = FamilyEvidence(ctx).use { it.riskScores(scope) }
    private fun childState() = FamilyEvidence(ctx).use { it.scoresModeState(scope) }

    @Test fun aValidReportIsStoredAndReadable() {
        val at = System.currentTimeMillis() - 5000
        assertEquals(RelayConsumption.CONSUMED, deliver(report(listOf(row("alice", 42.5, at), row("bob", null, at + 1)))))
        val rows = scores()
        assertEquals(listOf("bob", "alice"), rows.map { it.accountLabel })
        assertEquals(42.5, rows.last().score!!, 0.0001); assertNull(rows.first().score); assertEquals("insufficient_context", rows.first().reviewReason)
        assertEquals(at, rows.last().scoredAt)
        val s = childState()!!
        assertEquals("valid", s.model); assertEquals("accepted", s.consent); assertFalse(s.paused); assertNull(s.pausedAt)
        assertTrue(s.active); assertEquals(2, s.scoredCount); assertEquals(1, s.unknownThreads); assertFalse(s.stale); assertTrue(s.receivedAt > 0)
    }
    @Test fun aReportCreatesNoEvidenceNotificationOrStatistics() {
        var notified = 0
        val before = GuardEngine.getParentStatistics(ctx).dailyTotals().sum()
        deliver(report(), { notified++ })
        assertEquals(0, count("evidence")); assertEquals(0, notified); assertTrue(GuardEngine.getParentAlerts(ctx).isEmpty())
        assertEquals(before, GuardEngine.getParentStatistics(ctx).dailyTotals().sum())
    }
    @Test fun everyInvalidVariantIsDroppedWhole() {
        val now = System.currentTimeMillis()
        val variants = mutableMapOf<String, JSONObject.() -> Unit>(
            "extra top field" to { put("text", "hello") },
            "extra state field" to { getJSONObject("state").put("note", "x") },
            "extra row field" to { getJSONArray("conversations").getJSONObject(0).put("text", "x") },
            "missing state field" to { getJSONObject("state").remove("unknown_threads") },
            "missing row field" to { getJSONArray("conversations").getJSONObject(0).remove("scored_at_ms") },
            "wrong type" to { put("type", "risk_report") },
            "wrong scope" to { put("scope", "other") },
            "bad command id" to { put("command_id", "nope") },
            "score too high" to { getJSONArray("conversations").getJSONObject(0).put("score", 100.5) },
            "score negative" to { getJSONArray("conversations").getJSONObject(0).put("score", -1) },
            "score is text" to { getJSONArray("conversations").getJSONObject(0).put("score", "42") },
            "null score without reason" to { getJSONArray("conversations").getJSONObject(0).put("score", JSONObject.NULL) },
            "score and reason" to { getJSONArray("conversations").getJSONObject(0).put("review_reason", "insufficient_context") },
            "unknown reason" to { getJSONArray("conversations").getJSONObject(0).put("score", JSONObject.NULL).put("review_reason", "because") },
            "empty label" to { getJSONArray("conversations").getJSONObject(0).put("account_label", " ") },
            "long label" to { getJSONArray("conversations").getJSONObject(0).put("account_label", "x".repeat(65)) },
            "sent before the replay horizon" to { put("sent_at_ms", now - RiskLimits.REPORT_HORIZON_MS - 60_000) },
            "active missing" to { getJSONObject("state").remove("active") },
            "active not boolean" to { getJSONObject("state").put("active", "yes") },
            "bad model" to { getJSONObject("state").put("model", "other") },
            "bad consent" to { getJSONObject("state").put("consent", "yes") },
            "paused without time" to { getJSONObject("state").put("paused", true) },
            "bad policy id" to { getJSONObject("state").put("policy_id", "1") },
            "negative count" to { getJSONObject("state").put("scored_count", -1) },
            "sent far in future" to { put("sent_at_ms", now + 3 * day) },
            "rows as object" to { put("conversations", JSONObject()) },
            "too many rows" to { put("conversations", JSONArray((0..200).map { row("c$it", 1.0, now - 1000) })) },
            "duplicate labels" to { put("conversations", JSONArray(listOf(row("a", 1.0, now - 1000), row("A", 2.0, now - 1000)))) }
        )
        for ((name, change) in variants) {
            val payload = report(); payload.change()
            deliver(payload)
            assertTrue("$name stored rows", scores().isEmpty()); assertNull("$name stored state", childState())
        }
        assertEquals(0, count("evidence"))
        // The same shape, valid, is accepted (a negative control for the table above).
        deliver(report()); assertEquals(1, scores().size)
        // Exactly 200 rows are allowed.
        deliver(report((0 until 200).map { row("c$it", 1.0, now - 1000) })); assertEquals(201, scores().size)
    }
    @Test fun aChildCannotSendRecordsAsTheParentAndTheParentIgnoresThemAsChild() {
        // The receiver is parent-only.
        GuardEngine.setRole(ctx, "child")
        val payload = report()
        val wrapped = JSONObject().put("type", "alert").put("event_id", "control:" + payload.getString("command_id")).put("relay_control", true).put("envelope", payload)
        val line = JSONObject().put("event", "message").put("id", UUID.randomUUID().toString()).put("message", GuardEngine.encryptPayload(token, wrapped.toString()))
        assertEquals(RelayConsumption.REJECTED, GuardEngine.processIncomingRelayLine(ctx, line.toString(), "child", null))
        assertEquals(0, count("risk_scores"))
    }
    @Test fun upsertKeepsTheNewestAndStateKeepsTheNewestReport() {
        val now = System.currentTimeMillis()
        deliver(report(listOf(row("alice", 10.0, now - 5000)), sentAt = now - 4000))
        deliver(report(listOf(row("Alice", 30.0, now - 3000)), sentAt = now - 2000, state = state("mismatch")))
        assertEquals(30.0, scores().single().score!!, 0.0001); assertEquals("mismatch", childState()!!.model)
        deliver(report(listOf(row("alice", 99.0, now - 11 * 60_000)), sentAt = now - 10 * 60_000, state = state("missing")))   // an older report arriving late
        assertEquals(30.0, scores().single().score!!, 0.0001)   // scores keep the newest scored_at
        assertEquals("mismatch", childState()!!.model)          // state follows the send time, not arrival order
    }
    @Test fun firstReachingFiftyNotifiesOncePerFourteenDaysWithoutNamesOrScores() {
        val now = System.currentTimeMillis()
        val seen = mutableListOf<JSONObject>()
        deliver(report(listOf(row("alice", 49.0, now - 9000))), { seen.add(it) }); assertTrue(seen.isEmpty())
        deliver(report(listOf(row("alice", 50.0, now - 8000))), { seen.add(it) }); assertEquals(1, seen.size)
        deliver(report(listOf(row("alice", 80.0, now - 7000))), { seen.add(it) }); assertEquals(1, seen.size)
        deliver(report(listOf(row("alice", 20.0, now - 6000))), { seen.add(it) })
        deliver(report(listOf(row("alice", 60.0, now - 5000))), { seen.add(it) }); assertEquals(1, seen.size)
        deliver(report(listOf(row("bob", 75.0, now - 4000))), { seen.add(it) }); assertEquals(2, seen.size)
        for (n in seen) {
            val flat = n.toString()
            assertFalse(flat.contains("alice")); assertFalse(flat.contains("bob")); assertEquals("", n.getString("text")); assertEquals("", n.getString("sender"))
            assertEquals("scores_threshold", n.getString("reason"))
        }
        assertEquals(0, count("evidence"))
        // 14 days later the same conversation may notify again.
        deliver(report(listOf(row("alice", 70.0, now + 15 * day - 1000)), sentAt = now + 15 * day), { seen.add(it) }, ); assertEquals(2, seen.size)
    }
    @Test fun theParentCannotEnableWithoutAValidModelReportButCanAlwaysDisable() {
        GuardEngine.relayPublicKey(ctx)
        assertFalse(GuardEngine.sendScoresModePolicy(ctx, true)); assertNull(GuardEngine.scoresModePolicy(ctx))
        deliver(report(state = state("missing"))); assertFalse(GuardEngine.sendScoresModePolicy(ctx, true))
        deliver(report(sentAt = System.currentTimeMillis() + 1000, state = state("valid")))
        assertTrue(GuardEngine.sendScoresModePolicy(ctx, true))
        val first = GuardEngine.scoresModePolicy(ctx)!!
        assertTrue(first.enabled)
        val queued = FamilyEvidence(ctx).use { it.due(scope, Long.MAX_VALUE, "parent") }
        val commands = queued.mapNotNull { GuardEngine.decryptPayload(token, it.wire)?.optJSONObject("envelope") }.map { JSONObject(it.getString("payload")) }
        val policy = commands.single { it.optString("type") == "scores_mode_policy" }
        assertEquals(first.policyId, policy.getString("policy_id")); assertTrue(policy.getBoolean("enabled")); assertEquals(scope, policy.getString("scope"))
        assertTrue(GuardEngine.sendScoresModePolicy(ctx, false))
        assertFalse(GuardEngine.scoresModePolicy(ctx)!!.enabled); assertNotEquals(first.policyId, GuardEngine.scoresModePolicy(ctx)!!.policyId)
    }
    @Test fun theMethodIsSignedStoredAndAMethodChangeIsANewPolicy() {
        GuardEngine.relayPublicKey(ctx)
        deliver(report(state = state("valid")))
        assertTrue(GuardEngine.sendScoresModePolicy(ctx, true)); val capture = GuardEngine.scoresModePolicy(ctx)!!
        assertEquals("capture", capture.method); assertFalse("an absent method means capture", queuedPolicies().single().has("method"))
        assertTrue(GuardEngine.sendScoresModePolicy(ctx, true, "upload")); val upload = GuardEngine.scoresModePolicy(ctx)!!
        assertEquals("upload", upload.method); assertNotEquals(capture.policyId, upload.policyId)
        assertEquals("upload", queuedPolicies().single { it.getString("policy_id") == upload.policyId }.getString("method"))
        assertTrue(GuardEngine.sendScoresModePolicy(ctx, true, "upload")); assertEquals("the same method resends the same policy", upload.policyId, GuardEngine.scoresModePolicy(ctx)!!.policyId)
        assertTrue(GuardEngine.resendScoresModePolicy(ctx)); assertEquals("upload", queuedPolicies().last { it.getString("policy_id") == upload.policyId }.getString("method"))
        assertTrue(GuardEngine.sendScoresModePolicy(ctx, true, "capture")); assertNotEquals(upload.policyId, GuardEngine.scoresModePolicy(ctx)!!.policyId)
        assertEquals("capture", GuardEngine.scoresModePolicy(ctx)!!.method)
        val before = GuardEngine.scoresModePolicy(ctx)!!.policyId
        assertFalse("an unknown method is refused", GuardEngine.sendScoresModePolicy(ctx, true, "scrape")); assertEquals(before, GuardEngine.scoresModePolicy(ctx)!!.policyId)
    }
    @Test fun aReportIsStaleAfterSeventyTwoHours() {
        deliver(report())
        assertFalse(childState()!!.stale)
        FamilyEvidence(ctx).use { it.writableDatabase.execSQL("UPDATE risk_state SET received_at=?", arrayOf(System.currentTimeMillis() - 73 * 3_600_000L)) }
        assertTrue(childState()!!.stale)
    }
    @Test fun labelsAndScoresAreSealedInTheDatabase() {
        deliver(report(listOf(row("secretlabel", 61.375, System.currentTimeMillis() - 1000))))
        FamilyEvidence(ctx).use { db -> db.readableDatabase.rawQuery("SELECT label,result,label_index FROM risk_scores", null).use { c ->
            assertTrue(c.moveToFirst()); for (i in 0..2) assertFalse(c.getString(i).contains("secretlabel"))
            // Sealed text is "igv1:" plus random base64, which can contain "61" by chance. A clear result (a JSON object or a
            // number) is ruled out by the prefix and by characters base64 never has.
            val sealed = c.getString(1)
            assertTrue(sealed.startsWith("igv1:")); assertTrue(sealed.none { it in "{\".}" })
        } }
        val file = ctx.getDatabasePath("family_evidence.db").readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(file.contains("secretlabel")); assertFalse(file.contains("61.375"))
    }

    // ---------- deletion rules ----------
    @Test fun scoresExpireOnTheRetentionClockCountedFromScoredAt() {
        val now = System.currentTimeMillis()
        FamilyEvidence(ctx).use { it.setRetentionDays(scope, 7) }
        deliver(report(listOf(row("old", 10.0, now - 6 * day), row("new", 20.0, now - 1000))))
        assertEquals(2, scores().size)
        FamilyEvidence(ctx).use { db ->
            assertEquals(listOf("new"), db.riskScores(scope, now + 2 * day).map { it.accountLabel })   // readers enforce expiry on their own
            db.pruneExpired(scope, now + 2 * day)
            assertEquals(1, count("risk_scores")); assertEquals("new", db.riskScores(scope, now).single().accountLabel)
            db.pruneExpired(scope, now + 8 * day)
            assertEquals(0, count("risk_scores")); assertEquals(0, count("risk_state"))
        }
    }
    @Test fun maintenanceFindsScopesThatHoldOnlyScores() {
        val now = System.currentTimeMillis()
        FamilyEvidence(ctx).use { it.setRetentionDays(scope, 1) }
        deliver(report(listOf(row("old", 10.0, now - 2 * day))))
        FamilyEvidence(ctx).use { it.writableDatabase.execSQL("UPDATE risk_scores SET scored_at=?", arrayOf(now - 2 * day)) }
        ArchiveMaintenance.pruneAll(ctx, now); assertEquals(0, count("risk_scores"))
    }
    @Test fun deleteArchiveRotateAndOtherArchivesFollowTheArchive() {
        val now = System.currentTimeMillis()
        deliver(report(listOf(row("alice", 33.0, now - 1000))))
        FamilyEvidence(ctx).use { db ->
            db.rotate(scope, "moved") { it }
            assertTrue(db.riskScores(scope).isEmpty()); assertNull(db.scoresModeState(scope))
            assertEquals("alice", db.riskScores("moved").single().accountLabel); assertEquals(33.0, db.riskScores("moved").single().score!!, 0.0001)
            assertEquals("valid", db.scoresModeState("moved")!!.model)
            assertTrue(db.otherArchiveCount(scope) > 0)
            db.deleteOtherArchives(scope)
            assertTrue(db.riskScores("moved").isEmpty()); assertNull(db.scoresModeState("moved")); assertEquals(0, db.otherArchiveCount(scope))
        }
        deliver(report(listOf(row("alice", 33.0, now - 500))))
        FamilyEvidence(ctx).use { db -> db.deleteArchive(scope); assertTrue(db.riskScores(scope).isEmpty()); assertNull(db.scoresModeState(scope)) }
        assertEquals(0, count("risk_scores")); assertEquals(0, count("risk_state"))
    }
    @Test fun scoresAlreadyReceivedStayAfterTheChildWithdraws() {
        deliver(report(listOf(row("alice", 33.0, System.currentTimeMillis() - 1000))))
        val withdrawn = state().put("consent", "withdrawn")
        deliver(report(emptyList(), sentAt = System.currentTimeMillis() + 10, state = withdrawn))
        assertEquals(1, scores().size); assertEquals("withdrawn", childState()!!.consent)
    }
    @Test fun aReplayedReportChangesNothing() {
        val now = System.currentTimeMillis(); var notified = 0
        val first = report(listOf(row("alice", 80.0, now - 2000)), sentAt = now - 1000)
        deliver(first, { notified++ }); assertEquals(1, notified)
        FamilyEvidence(ctx).use { it.writableDatabase.execSQL("UPDATE risk_state SET received_at=?", arrayOf(now - 73 * 3_600_000L)) }
        assertTrue(childState()!!.stale)
        deliver(JSONObject(first.toString()), { notified++ })      // the same report again
        assertTrue("a replay must not clear staleness", childState()!!.stale); assertEquals(1, notified)
        // ...nor bring back rows the parent deleted.
        FamilyEvidence(ctx).use { it.deleteArchive(scope) }
        deliver(JSONObject(first.toString()), { notified++ })
        assertTrue(scores().isEmpty()); assertNull(childState()); assertEquals(1, notified)
        val later = System.currentTimeMillis() + 60_000
        deliver(report(listOf(row("alice", 80.0, later)), sentAt = later), { notified++ }); assertEquals(1, scores().size)
        assertEquals("a notified label does not notify again after deletion", 1, notified)
    }
    @Test fun anOldReportReplayedAfterManyNewerOnesChangesNothing() {
        val first = report(listOf(row("alice", 20.0, System.currentTimeMillis() - 5000)), sentAt = System.currentTimeMillis() - 4000); deliver(first)
        repeat(100) { deliver(report(listOf(row("alice", 20.0 + it % 5, System.currentTimeMillis() - 3000 + it)), sentAt = System.currentTimeMillis())) }
        val before = scores().single(); val received = childState()!!.receivedAt
        deliver(JSONObject(first.toString()))
        assertEquals(before, scores().single()); assertEquals(received, childState()!!.receivedAt)
        assertEquals(101, count("risk_replay"))
        // Ids are pruned after 30 days.
        FamilyEvidence(ctx).use { it.pruneExpired(scope, System.currentTimeMillis() + 31 * day) }
        assertEquals(0, count("risk_replay"))
    }
    @Test fun aChildClockAheadDoesNotBlockLaterStates() {
        val now = System.currentTimeMillis()
        deliver(report(sentAt = now + 20 * 3_600_000L, state = state("valid")))
        val paused = state("valid").put("paused", true).put("paused_at_ms", now)
        deliver(report(sentAt = now, state = paused))
        assertTrue(childState()!!.paused)
        deliver(report(sentAt = now + 1, state = state("valid").put("consent", "withdrawn")))
        assertEquals("withdrawn", childState()!!.consent)
        assertTrue(childState()!!.sentAt <= System.currentTimeMillis())   // the far-future clock is clamped for display
    }
    @Test fun aChildClockThatMovedBackKeepsRowsAndOrderingAndTheReplayHorizonHolds() {
        val now = System.currentTimeMillis()
        // B6: scored before the clock moved back, sent after: the row is kept, its time clamped to the send time.
        assertEquals(RelayConsumption.CONSUMED, deliver(report(listOf(row("alice", 42.0, now + 60_000)), sentAt = now)))
        assertEquals(now, scores().single().scoredAt); assertEquals("valid", childState()!!.model)
        // A small step back (inside the slack) still applies the state.
        deliver(report(listOf(row("bob", 10.0, now - 3 * 60_000)), sentAt = now - 2 * 60_000, state = state("mismatch")))
        assertEquals("mismatch", childState()!!.model); assertEquals(2, scores().size)
        // Further back: rows are still stored, the state is not applied, and the slack cannot be chained (stored time never drops).
        deliver(report(listOf(row("carol", 11.0, now - 7 * 60_000)), sentAt = now - 6 * 60_000, state = state("missing")))
        assertEquals("mismatch", childState()!!.model); assertEquals(3, scores().size)
        assertEquals(now, childState()!!.sentAt)
        // Replay horizon: just inside is accepted, just outside is refused whole.
        val received = childState()!!.receivedAt
        deliver(report(listOf(row("dave", 12.0, now - RiskLimits.REPORT_HORIZON_MS - 120_000)), sentAt = now - RiskLimits.REPORT_HORIZON_MS - 60_000))
        assertEquals(3, scores().size); assertEquals(received, childState()!!.receivedAt)
        deliver(report(listOf(row("dave", 12.0, now - RiskLimits.REPORT_HORIZON_MS + 60_000)), sentAt = now - RiskLimits.REPORT_HORIZON_MS + 120_000))
        assertEquals(4, scores().size)
        // A horizon-old report's id outlives the horizon: ids are kept REPLAY_KEEP_MS by arrival.
        assertTrue(RiskLimits.REPORT_HORIZON_MS + RiskLimits.REPORT_FUTURE_MS <= RiskLimits.REPLAY_KEEP_MS)
    }
    @Test fun enablingTwiceResendsTheSamePolicyIdAndDisablingThenEnablingMakesANewOne() {
        GuardEngine.relayPublicKey(ctx)
        deliver(report(state = state("valid")))
        assertTrue(GuardEngine.sendScoresModePolicy(ctx, true)); val id = GuardEngine.scoresModePolicy(ctx)!!.policyId
        assertTrue(GuardEngine.sendScoresModePolicy(ctx, true)); assertEquals(id, GuardEngine.scoresModePolicy(ctx)!!.policyId)
        assertTrue(GuardEngine.sendScoresModePolicy(ctx, false)); assertNotEquals(id, GuardEngine.scoresModePolicy(ctx)!!.policyId)
        assertTrue(GuardEngine.sendScoresModePolicy(ctx, true)); assertNotEquals(id, GuardEngine.scoresModePolicy(ctx)!!.policyId)
    }
    @Test fun schemaIsVersionEleven() {
        FamilyEvidence(ctx).use { assertEquals(11, it.readableDatabase.version) }
    }

    // ---------- second review ----------
    private fun queuedPolicies(): List<JSONObject> = FamilyEvidence(ctx).use { it.due(scope, Long.MAX_VALUE, "parent") }
        .mapNotNull { GuardEngine.decryptPayload(token, it.wire)?.optJSONObject("envelope") }.map { JSONObject(it.getString("payload")) }.filter { it.optString("type") == "scores_mode_policy" }
    @Test fun aReportThatShowsAnUnappliedPolicyTriggersOneRepostPerHourWithTheSameId() {
        GuardEngine.relayPublicKey(ctx)
        deliver(report(state = state("valid")))
        assertTrue(GuardEngine.sendScoresModePolicy(ctx, true)); val id = GuardEngine.scoresModePolicy(ctx)!!.policyId
        val sent = queuedPolicies().size
        // The child reports another (or no) policy: re-post the current one with a new signature and time.
        deliver(report(state = state("valid").put("policy_id", JSONObject.NULL).put("consent", "none")))
        val after = queuedPolicies()
        assertEquals(sent + 1, after.size)
        assertTrue(after.all { it.getString("policy_id") == id && it.getBoolean("enabled") })
        assertTrue(after.map { it.getString("command_id") }.toSet().size == after.size)
        deliver(report(state = state("valid").put("policy_id", UUID.randomUUID().toString())))
        assertEquals("rate limited to once an hour", sent + 1, queuedPolicies().size)
        // A child that holds the current policy triggers nothing.
        FamilyEvidence(ctx).use { it.writableDatabase.execSQL("DELETE FROM outbox") }
        prefs.edit().putLong("scores_policy_repost_ms", 0).commit()
        deliver(report(state = state("valid").put("policy_id", id)))
        assertEquals(0, queuedPolicies().size)
        // Manual send again keeps the id.
        assertTrue(GuardEngine.resendScoresModePolicy(ctx)); assertEquals(id, GuardEngine.scoresModePolicy(ctx)!!.policyId); assertEquals(id, queuedPolicies().single().getString("policy_id"))
    }
    @Test fun aSwitchOffThatTheChildMissedIsRepostedAsOffWithTheSameId() {
        GuardEngine.relayPublicKey(ctx)
        deliver(report(state = state("valid")))
        assertTrue(GuardEngine.sendScoresModePolicy(ctx, true)); assertTrue(GuardEngine.sendScoresModePolicy(ctx, false))
        val off = GuardEngine.scoresModePolicy(ctx)!!; assertFalse(off.enabled)
        FamilyEvidence(ctx).use { it.writableDatabase.execSQL("DELETE FROM outbox") }
        prefs.edit().putLong("scores_policy_repost_ms", 0).commit()
        deliver(report(state = state("valid").put("policy_id", UUID.randomUUID().toString()).put("consent", "accepted")))
        val re = queuedPolicies().single(); assertEquals(off.policyId, re.getString("policy_id")); assertFalse(re.getBoolean("enabled"))
        // Once the child shows the switched-off policy and no consent nothing more is sent.
        FamilyEvidence(ctx).use { it.writableDatabase.execSQL("DELETE FROM outbox") }
        prefs.edit().putLong("scores_policy_repost_ms", 0).commit()
        deliver(report(state = state("valid").put("policy_id", off.policyId).put("consent", "none")))
        assertEquals(0, queuedPolicies().size)
    }
    @Test fun aPolicyThatCannotBeQueuedIsNotReportedAsSent() {
        GuardEngine.relayPublicKey(ctx)
        deliver(report(state = state("valid")))
        TestKeyStore.failEcSigning = true
        try {
            assertFalse(GuardEngine.sendScoresModePolicy(ctx, true)); assertNull(GuardEngine.scoresModePolicy(ctx))
        } finally { TestKeyStore.failEcSigning = false }
        assertTrue(GuardEngine.sendScoresModePolicy(ctx, true))
    }
    @Test fun aDeletedScoreIsNotResurrectedByAResendButNewerScoresAreAccepted() {
        val now = System.currentTimeMillis()
        deliver(report(listOf(row("alice", 70.0, now - 5000)), sentAt = now - 4000)); assertEquals(1, scores().size)
        FamilyEvidence(ctx).use { it.deleteArchive(scope) }
        deliver(report(listOf(row("alice", 70.0, now - 5000)), sentAt = now))   // the child's resend of the same score
        assertTrue(scores().isEmpty())
        val later = System.currentTimeMillis() + 60_000
        deliver(report(listOf(row("alice", 30.0, later)), sentAt = later)); assertEquals(30.0, scores().single().score!!, 0.001)
    }
    @Test fun retentionPrunedRowsAreTombstonedAndTombstonesExpireAfterThirtyDaysAndRotate() {
        val now = System.currentTimeMillis()
        FamilyEvidence(ctx).use { it.setRetentionDays(scope, 7) }
        deliver(report(listOf(row("alice", 60.0, now - 1000)), sentAt = now))
        FamilyEvidence(ctx).use { db ->
            db.pruneExpired(scope, now + 8 * day); assertEquals(0, count("risk_scores")); assertEquals(1, count("risk_tombstones"))
            db.rotate(scope, "moved") { it }
            assertEquals(1, count("risk_tombstones"))
            db.readableDatabase.rawQuery("SELECT scope,label FROM risk_tombstones", null).use { c -> assertTrue(c.moveToFirst()); assertEquals("moved", c.getString(0)); assertFalse(c.getString(1).contains("alice")) }
            db.pruneExpired("moved", now + 39 * day); assertEquals(0, count("risk_tombstones"))
        }
    }
    @Test fun aRowNotifiedWithinFourteenDaysDoesNotNotifyAgainEvenAfterDeletion() {
        val now = System.currentTimeMillis(); var notified = 0
        deliver(report(listOf(row("alice", 80.0, now - 3000)), sentAt = now), { notified++ }); assertEquals(1, notified)
        FamilyEvidence(ctx).use { it.deleteArchive(scope) }
        val later = now + 60_000
        deliver(report(listOf(row("alice", 90.0, later)), sentAt = later), { notified++ }); assertEquals(1, notified)
        // After the 14-day window it may notify again.
        FamilyEvidence(ctx).use { it.writableDatabase.execSQL("UPDATE risk_scores SET first_reached_50=first_reached_50-?", arrayOf(15 * day)) }
        val again = later + 60_000
        deliver(report(listOf(row("alice", 90.0, again)), sentAt = again), { notified++ }); assertEquals(2, notified)
    }
}
