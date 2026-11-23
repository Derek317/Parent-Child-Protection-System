package app.family.igguard

import app.family.igguard.ChildRecords.DropReason
import app.family.igguard.ChildRecords.GateResult
import app.family.igguard.ChildRecords.TamperKind
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Base64
import java.util.UUID

/** The parent's signature gate and the child's wrapper for signed child records (software keys; the Keystore path is in ChildSigningKeyTest). */
class ChildRecordsTest {
    private val now = 1_800_000_000_000L
    private val day = 86_400_000L
    private val scope = "ig_hk_test"
    private val encoder = Base64.getUrlEncoder()
    private val parent = FamilySecurity.newSigningIdentity()
    private val child = FamilySecurity.newSigningIdentity()
    private val policy = UUID.randomUUID().toString()

    private fun signer(k: FamilySecurity.SigningIdentity = child): (ByteArray) -> ByteArray = { FamilySecurity.signBytes(it, k.privateKey) }
    private fun alert(reason: String = "heartbeat_ok", ts: Long = now) = JSONObject().put("type", "alert").put("event_id", UUID.randomUUID().toString())
        .put("capture_source", "app_or_visible_screen").put("reason", reason).put("sender", "Pixel").put("text", "status").put("ts_ms", ts).put("time", "")
        .put("trusted_csv", "").put("child_self_handle", "kid")
    /** A real captured record; [reason] picks the ConversationCapture.record branch that produces it. */
    private fun captured(reason: String, at: Long = now): JSONObject {
        val text = if (reason == "sexual_or_grooming_content") "send nudes" else "hello"
        return ConversationCapture.record(CapturedText("stranger", text, "incoming", author = "stranger"), at,
            trusted = reason == "conversation_context", flagged = reason == "flagged_contact_follow_up")
            .put("policy_id", policy).put("child_self_handle", "kid").put("time", "")
            .also { assertEquals(reason, it.getString("reason")) }
    }
    private fun control(type: String, ts: Long = now) = when (type) {
        "capture_ack" -> JSONObject().put("type", type).put("policy_id", policy).put("schema_version", 2).put("ts_ms", ts)
        else -> JSONObject().put("type", type).put("child_self_handle", "kid").put("ts_ms", ts).put("time", "")
    }.put("scope", scope).put("command_id", UUID.randomUUID().toString())
    private fun scores(sent: Long = now) = JSONObject().put("type", "scores_mode_report").put("sent_at_ms", sent)
        .put("state", JSONObject().put("policy_id", policy).put("model", "valid").put("consent", "accepted").put("active", true).put("paused", false)
            .put("paused_at_ms", JSONObject.NULL).put("last_run_ms", sent).put("scored_count", 1).put("unknown_threads", 0))
        .put("conversations", JSONArray().put(JSONObject().put("account_label", "alice").put("score", 42.0).put("review_reason", JSONObject.NULL).put("scored_at_ms", sent - 1000)))
        .put("scope", scope).put("command_id", UUID.randomUUID().toString())
    private fun idOf(r: JSONObject) = if (r.optString("type") in setOf("alert", "captured_message")) r.getString("event_id") else "control:" + r.getString("command_id")
    private fun wrap(r: JSONObject, receipt: Boolean? = null, p: String = parent.publicKey, k: String = child.publicKey, by: FamilySecurity.SigningIdentity = child) =
        ChildRecords.wrapForParent(r.toString(), idOf(r), p, k, signer(by), receipt)
    /** A wrapper whose signature covers [signedBytes] instead of the canonical bytes. */
    private fun rawWrap(r: JSONObject, signedBytes: ByteArray) = JSONObject().put("type", "alert").put("event_id", idOf(r)).put("child_signed", r.toString())
        .put("child_sig", encoder.encodeToString(FamilySecurity.signBytes(signedBytes, child.privateKey)))
    private fun gate(frame: JSONObject, pinned: String? = child.publicKey, state: String? = null, at: Long = now, p: String = parent.publicKey) =
        ChildRecords.gate(frame, pinned, p, at, state)
    private fun verified(frame: JSONObject, pinned: String? = child.publicKey, state: String? = null, at: Long = now): GateResult.Verified {
        val got = gate(frame, pinned, state, at)
        assertTrue(got.toString().take(200), got is GateResult.Verified)
        return got as GateResult.Verified
    }
    private fun build(type: String, reason: String?) = when (type) {
        "alert" -> alert(reason!!)
        "captured_message" -> captured(reason!!)
        "scores_mode_report" -> scores()
        else -> control(type)
    }

    @Test fun everyRecordAChildSendsRoundTripsUnchanged() {
        assertEquals(FamilySecurity.childAlertReasons, ChildRecords.SENT_BY_CHILD.filter { it.first == "alert" }.map { it.second }.toSet())
        assertEquals(FamilySecurity.capturedReasons, ChildRecords.SENT_BY_CHILD.filter { it.first == "captured_message" }.map { it.second }.toSet())
        for ((type, reason) in ChildRecords.SENT_BY_CHILD) {
            val r = build(type, reason)
            val got = verified(wrap(r))
            assertTrue("$type/$reason", got.record.similar(r))
            assertEquals(type, got.type)
            assertEquals(idOf(r).removePrefix("control:"), got.id)
            assertEquals(now, got.timeMs)
            assertEquals(type in setOf("capture_ack", "sync_child_handle"), got.newControlState != null)
            if (type == "captured_message") assertTrue(ConversationCapture.valid(got.record))
            // The strict codec still parses the verified record: no field was added to a control record.
            if (type == "scores_mode_report") assertNotNull(ScoresReportCodec.parse(got.record, scope, now))
        }
        // pairing_verified is an alert reason; its records verify like any other (also covered by the loop above).
        assertEquals("alert", verified(wrap(alert("pairing_verified"))).type)
    }

    @Test fun wrapperIsExactlyWhatTheTransportSends() {
        val r = alert()
        val w = wrap(r)
        assertEquals(setOf("type", "event_id", "child_signed", "child_sig"), w.keys().asSequence().toSet())
        assertEquals("alert", w.getString("type")); assertEquals(r.getString("event_id"), w.getString("event_id"))
        assertEquals(r.toString(), w.getString("child_signed"))
        val c = control("capture_ack"); assertEquals("control:" + c.getString("command_id"), wrap(c).getString("event_id"))
        // receipt_requested stays an unsigned outer field; the gate hands it to the alert branch, never to control records.
        val withReceipt = wrap(r, receipt = true)
        assertEquals(true, withReceipt.get("receipt_requested"))
        assertEquals(w.getString("child_signed"), withReceipt.getString("child_signed"))
        assertTrue(verified(withReceipt).record.getBoolean("receipt_requested"))
        assertFalse(verified(wrap(alert(), receipt = false)).record.has("receipt_requested"))
        val s = scores()
        assertNotNull(ScoresReportCodec.parse(verified(wrap(s).put("receipt_requested", true)).record, scope, now))
        for (bad in listOf<() -> Unit>(
                { ChildRecords.wrapForParent(r.toString(), UUID.randomUUID().toString(), parent.publicKey, child.publicKey, signer()) },
                { ChildRecords.wrapForParent(c.toString(), c.getString("command_id"), parent.publicKey, child.publicKey, signer()) },
                { ChildRecords.wrapForParent(r.toString(), idOf(r), "garbage", child.publicKey, signer()) },
                { ChildRecords.wrapForParent(r.toString(), idOf(r), parent.publicKey, "garbage", signer()) }))
            try { bad(); fail() } catch (_: IllegalArgumentException) { }
    }

    @Test fun missingOrBadSignaturesAreTamper() {
        val attacker = FamilySecurity.newSigningIdentity()
        val otherParent = FamilySecurity.newSigningIdentity()
        val good = wrap(alert())
        val text = good.getString("child_signed")
        val i = text.indexOf("status")
        val cases = mapOf(
            JSONObject(good.toString()).apply { remove("child_signed") } to TamperKind.UNSIGNED,
            JSONObject(good.toString()).apply { remove("child_sig") } to TamperKind.UNSIGNED,
            JSONObject(good.toString()).put("child_signed", JSONObject(text)) to TamperKind.UNSIGNED,
            JSONObject(good.toString()).put("child_sig", 12345) to TamperKind.UNSIGNED,
            JSONObject(good.toString()).put("child_signed", JSONObject.NULL) to TamperKind.UNSIGNED,
            wrap(alert(), by = attacker) to TamperKind.BAD_SIGNATURE,                                        // another key
            JSONObject(good.toString()).put("child_signed", text.substring(0, i) + "S" + text.substring(i + 1)) to TamperKind.BAD_SIGNATURE, // one byte changed
            wrap(alert(), p = otherParent.publicKey) to TamperKind.BAD_SIGNATURE,                           // signed for another family
            JSONObject(good.toString()).put("child_sig", good.getString("child_sig").dropLast(4)) to TamperKind.BAD_SIGNATURE, // truncated
            JSONObject(good.toString()).put("child_sig", good.getString("child_sig") + "A".repeat(129)) to TamperKind.BAD_SIGNATURE, // oversized
            JSONObject(good.toString()).put("child_sig", "!!notbase64") to TamperKind.BAD_SIGNATURE,
            JSONObject(good.toString()).put("child_sig", wrap(alert()).getString("child_sig")) to TamperKind.BAD_SIGNATURE, // another record's signature
            wrap(alert().put("text", "x".repeat(1_048_576))) to TamperKind.BAD_SIGNATURE,                  // over the 1 MiB bound: never hashed
            // Downgrade: the unsigned 5.2 formats, alert and control, from a child whose key is pinned.
            alert() to TamperKind.UNSIGNED,
            control("capture_ack").let { JSONObject().put("type", "alert").put("event_id", idOf(it)).put("relay_control", true).put("envelope", it) } to TamperKind.UNSIGNED)
        for ((frame, kind) in cases) assertEquals(frame.toString().take(200), GateResult.Tamper(kind), gate(frame))
        assertEquals(GateResult.Tamper(TamperKind.BAD_SIGNATURE), gate(good, pinned = attacker.publicKey))   // claimed for another child
        assertEquals(GateResult.Tamper(TamperKind.BAD_SIGNATURE), gate(good, p = otherParent.publicKey))     // replayed into another family
    }

    @Test fun genuineRecordsOutsideTheRulesAreDroppedQuietly() {
        fun drop(r: JSONObject) = gate(wrap(r))
        val q = { reason: DropReason -> GateResult.QuietDrop(reason) }
        // Past limits per class, and the edge itself accepted.
        for ((older, edge) in listOf(alert(ts = now - 8 * day - 1) to alert(ts = now - 8 * day),
                captured("stranger_message", now - 8 * day - 1) to captured("stranger_message", now - 8 * day),
                scores(now - RiskLimits.REPORT_HORIZON_MS - 1) to scores(now - RiskLimits.REPORT_HORIZON_MS),
                control("capture_ack", now - day - 1) to control("capture_ack", now - day),
                control("sync_child_handle", now - day - 1) to control("sync_child_handle", now - day))) {
            assertEquals(older.getString("type"), q(DropReason.OUT_OF_WINDOW), drop(older))
            verified(wrap(edge))
        }
        // Future: up to 24 hours ahead of the parent's clock.
        assertEquals(day, RiskLimits.REPORT_FUTURE_MS)
        for (ahead in listOf(120_000L, 120_001L, 23 * 3_600_000L, day)) {
            verified(wrap(alert(ts = now + ahead))); verified(wrap(control("capture_ack", now + ahead))); verified(wrap(scores(now + ahead)))
            verified(wrap(captured("conversation_context", now + ahead)))
        }
        for (r in listOf(alert(ts = now + day + 1), control("sync_child_handle", now + day + 1), scores(now + day + 1), captured("stranger_message", now + day + 1), alert(ts = 0)))
            assertEquals(q(DropReason.OUT_OF_WINDOW), drop(r))
        for (r in listOf(control("sync_trusted"), control("key_rotated"), control("alert_receipt"), control("alert_fragment"), control("sync_child_handle").apply { remove("type") },
                control("pair_hello").put("k", child.publicKey).put("ec", child.publicKey)))   // pair_hello belongs to the pairing room
            assertEquals(r.optString("type"), q(DropReason.UNKNOWN_TYPE), drop(r))
        for (r in listOf(alert("remote_reset"), alert("scores_threshold"), JSONObject(alert().toString()).apply { remove("reason") }, captured("stranger_message").put("reason", "heartbeat_ok")))
            assertEquals(q(DropReason.UNKNOWN_REASON), drop(r))
        for (id in listOf("has space", "a".repeat(161), "", "bad/id")) assertEquals(id, q(DropReason.BAD_ID), drop(alert().put("event_id", id)))
        val numericId = control("capture_ack").put("command_id", 7).toString()
        assertEquals(q(DropReason.BAD_ID), gate(JSONObject().put("type", "alert").put("event_id", "control:7").put("child_signed", numericId)
            .put("child_sig", encoder.encodeToString(FamilySecurity.signBytes(FamilySecurity.childRecordSignedBytes(numericId, parent.publicKey, child.publicKey), child.privateKey)))))
        assertEquals(q(DropReason.TOO_LARGE), drop(control("capture_ack").put("pad", "x".repeat(16_384))))
        verified(wrap(alert().put("text", "x".repeat(20_000))))                                       // large alerts still pass
        assertEquals(q(DropReason.MALFORMED_RECORD), drop(alert().put("ts_ms", now.toString())))
        assertEquals(q(DropReason.MALFORMED_RECORD), drop(JSONObject(alert().toString()).apply { remove("ts_ms") }))
        val notJson = "not json"
        assertEquals(q(DropReason.MALFORMED_RECORD), gate(JSONObject().put("type", "alert").put("event_id", "x").put("child_signed", notJson)
            .put("child_sig", encoder.encodeToString(FamilySecurity.signBytes(FamilySecurity.childRecordSignedBytes(notJson, parent.publicKey, child.publicKey), child.privateKey)))))
    }

    @Test fun aGenuineRecordUnderAnotherWrapperIdIsTamperAndNeverProcessed() {
        // The relay cannot re-wrap (it has no family secret); only a holder of it can, so this is never an honest child's doing.
        for (r in listOf(alert(), captured("stranger_message"), control("capture_ack"), scores())) {
            val w = wrap(r)
            val ownId = idOf(r)
            for (id in listOf(UUID.randomUUID().toString(), "control:" + UUID.randomUUID(), if (ownId.startsWith("control:")) ownId.removePrefix("control:") else "control:$ownId"))
                assertEquals(GateResult.Tamper(TamperKind.REWRAPPED), gate(JSONObject(w.toString()).put("event_id", id)))
            assertEquals(GateResult.Tamper(TamperKind.REWRAPPED), gate(JSONObject(w.toString()).apply { remove("event_id") }))
        }
        // Re-wrapped and also out of its window: still reported as re-wrapped.
        val old = wrap(alert(ts = now - 9 * day))
        assertEquals(GateResult.Tamper(TamperKind.REWRAPPED), gate(old.put("event_id", UUID.randomUUID().toString())))
    }

    @Test fun legacyOnlyWithoutPinnedKeyAndWithoutChildSigned() {
        val plain = alert()
        val legacy = gate(plain, pinned = null)
        assertTrue(legacy is GateResult.Legacy && legacy.frame === plain)
        assertEquals(GateResult.QuietDrop(DropReason.NO_PINNED_KEY), gate(wrap(alert()), pinned = null))
        assertEquals(GateResult.QuietDrop(DropReason.NO_PINNED_KEY), gate(JSONObject(plain.toString()).put("child_signed", 5), pinned = null))
        assertTrue(gate(JSONObject(plain.toString()).put("child_sig", "x"), pinned = null) is GateResult.Legacy)
        // A parent with a pinned key never returns Legacy, whatever arrives.
        val frames = listOf(plain, JSONObject(), JSONObject(plain.toString()).put("child_sig", "x"), JSONObject(plain.toString()).put("child_signed", "{}"),
            wrap(alert()), wrap(alert(ts = 0)), wrap(alert(), by = FamilySecurity.newSigningIdentity()), wrap(control("capture_ack")), wrap(scores()))
        for (f in frames) assertFalse(f.toString().take(100), gate(f) is GateResult.Legacy)
    }

    @Test fun controlRecordsReplayAndOrderingPerChild() {
        val first = control("capture_ack", now - 60_000)
        val s1 = verified(wrap(first)).newControlState!!
        assertEquals(GateResult.Replay, gate(wrap(first), state = s1))                                   // same id again (fresh signature)
        assertEquals(GateResult.Replay, gate(wrap(control("capture_ack", now - 120_000)), state = s1))   // older than the last capture_ack
        // Another type has its own clock; a later record of the same type is accepted and the state grows.
        val s2 = verified(wrap(control("sync_child_handle", now - 3_600_000)), state = s1).newControlState!!
        val second = control("capture_ack", now - 60_000)                                               // same time is not "backwards"
        val s3 = verified(wrap(second), state = s2).newControlState!!
        assertEquals(1, JSONObject(s1).getJSONObject("ids").length()); assertEquals(2, JSONObject(s2).getJSONObject("ids").length())
        val ids3 = JSONObject(s3).getJSONObject("ids")
        assertEquals(3, ids3.length()); assertTrue(ids3.has(first.getString("command_id")) && ids3.has(second.getString("command_id")))
        // A day later the ids whose record time is more than 24 h past are pruned; the per-type times stay.
        val later = now + day + 1
        val s4 = verified(wrap(control("capture_ack", later)), state = s3, at = later).newControlState!!
        val ids = JSONObject(s4).getJSONObject("ids")
        assertEquals(1, ids.length())
        assertEquals(later, JSONObject(s4).getLong("capture_ack")); assertEquals(now - 3_600_000, JSONObject(s4).getLong("sync_child_handle"))
        // Unreadable state starts empty; content records keep no state here and are never Replay (the evidence store dedupes them).
        assertNotNull(verified(wrap(control("capture_ack")), state = "garbage").newControlState)
        val a = wrap(alert())
        assertNull(verified(a, state = s1).newControlState); assertNull(verified(a, state = s1).newControlState)
        assertNull(verified(wrap(scores()), state = s1).newControlState)
        // Per child: the caller passes each child's own state, so one child's ids never block another's.
        assertTrue(gate(wrap(first), state = null) is GateResult.Verified)
    }

    @Test fun tamperNoticeAtMostOncePerHour() {
        assertTrue(ChildRecords.tamperNoticeDue(0, now))
        assertFalse(ChildRecords.tamperNoticeDue(now, now))
        assertFalse(ChildRecords.tamperNoticeDue(now - 3_600_000 + 1, now))
        assertTrue(ChildRecords.tamperNoticeDue(now - 3_600_000, now))
        assertTrue(ChildRecords.tamperNoticeDue(now + 60_000, now))   // the clock went back: one notice rather than silence
        assertEquals("訊息核對", ChildRecords.TAMPER_SENDER)
        assertEquals("收到一則未能核實來自此子女手機的訊息，已拒絕。內容未被保存。", ChildRecords.TAMPER_TEXT)
    }

    @Test fun refusalsCarryNoRecordContent() {
        val marker = "MARKER-must-not-leak"
        val r = alert().put("text", marker).put("sender", marker)
        val refused = listOf(gate(wrap(r), pinned = FamilySecurity.newSigningIdentity().publicKey), gate(JSONObject(r.toString())),
            gate(wrap(JSONObject(r.toString()).put("reason", marker))), gate(wrap(JSONObject(r.toString()).put("ts_ms", 1L))),
            gate(wrap(r).put("event_id", marker)), gate(wrap(r), pinned = null))
        for (x in refused) {
            assertTrue(x is GateResult.Tamper || x is GateResult.QuietDrop)
            assertFalse(x.toString(), x.toString().contains(marker) || x.toString().contains("status"))
        }
        for (x in listOf(FamilySecurity.checkChildRecord(wrap(JSONObject(r.toString()).put("ts_ms", 1L)), child.publicKey, parent.publicKey, now),
                FamilySecurity.checkChildRecord(wrap(r, by = FamilySecurity.newSigningIdentity()), child.publicKey, parent.publicKey, now)))
            assertFalse(x.toString().contains(marker))
    }

    @Test fun keyTextsNormaliseToThePaddedForm() {
        val padded = child.publicKey
        val unpadded = padded.trimEnd('=')
        assertNotEquals(padded, unpadded)
        assertEquals(padded, FamilySecurity.canonicalKeyText(unpadded)); assertEquals(padded, FamilySecurity.canonicalKeyText(padded))
        assertEquals(parent.publicKey, FamilySecurity.canonicalKeyText(parent.publicKey.trimEnd('=')))
        // Signed with either text of K (and of P), verified with either: one canonical form is hashed on both phones.
        for (signK in listOf(padded, unpadded)) for (signP in listOf(parent.publicKey, parent.publicKey.trimEnd('=')))
            for (pinK in listOf(padded, unpadded)) for (pinP in listOf(parent.publicKey, parent.publicKey.trimEnd('=')))
                assertTrue(gate(wrap(alert(), p = signP, k = signK), pinned = pinK, p = pinP) is GateResult.Verified)
        // A signature over a non-canonical text form is accepted under no form: two forms can never give two accepted signatures.
        val r = alert()
        for (other in listOf(FamilySecurity.childRecordSignedBytes(r.toString(), parent.publicKey, unpadded),
                FamilySecurity.childRecordSignedBytes(r.toString(), parent.publicKey.trimEnd('='), padded)))
            for (pin in listOf(padded, unpadded)) assertEquals(GateResult.Tamper(TamperKind.BAD_SIGNATURE), gate(rawWrap(r, other), pinned = pin))
        assertTrue(gate(rawWrap(r, FamilySecurity.childRecordSignedBytes(r.toString(), parent.publicKey, padded)), pinned = unpadded) is GateResult.Verified)
        // Texts that are not a P-256 key are refused everywhere.
        val standard = Base64.getEncoder().encodeToString(Base64.getUrlDecoder().decode(padded))
        for (bad in listOf("garbage", "", padded.dropLast(1), "$padded=", " $padded", padded.replace('-', '+').replace('_', '/').takeIf { it != padded } ?: standard + "!")) {
            assertNull(bad, FamilySecurity.canonicalKeyText(bad))
            assertEquals(FamilySecurity.ChildRecordResult.InvalidKey, FamilySecurity.checkChildRecord(wrap(r), bad, parent.publicKey, now))
            assertEquals(FamilySecurity.ChildRecordResult.InvalidKey, FamilySecurity.checkChildRecord(wrap(r), padded, bad, now))
            assertNull(FamilySecurity.readChildRecord(wrap(r), bad, parent.publicKey, now))
            for (call in listOf<() -> Unit>({ gate(wrap(r), pinned = bad) }, { gate(wrap(r), p = bad) }))
                try { call(); fail(bad) } catch (_: IllegalStateException) { }
        }
    }

    // ---------------- the reasons the child's sources really send ----------------
    private val root: File = run {
        val vector = File(requireNotNull(System.getProperty("igguard.protocol.vector")) { "igguard.protocol.vector is not set" })
        File(vector.parentFile, "../..").canonicalFile
    }
    private fun source(name: String) = File(root, name).readText()
    /** The reason argument of every `sendAlertFromChild(` call: the named `reason =` argument, else the second positional one. */
    private fun reasonArguments(text: String): List<String> = Regex("sendAlertFromChild\\(").findAll(text)
        .filterNot { text.substring(maxOf(0, it.range.first - 4), it.range.first) == "fun " }.map { m ->
            val args = mutableListOf<String>(); val current = StringBuilder(); var depth = 0; var i = m.range.last + 1; var inString = false
            while (i < text.length) {
                val c = text[i]
                if (inString) { current.append(c); if (c == '\\') { current.append(text[i + 1]); i++ } else if (c == '"') inString = false }
                else when (c) {
                    '"' -> { inString = true; current.append(c) }
                    '(', '[', '{' -> { depth++; current.append(c) }
                    ')', ']', '}' -> if (depth == 0) { args.add(current.toString().trim()); break } else { depth--; current.append(c) }
                    ',' -> if (depth == 0) { args.add(current.toString().trim()); current.clear() } else current.append(c)
                    else -> current.append(c)
                }
                i++
            }
            args.firstOrNull { Regex("^reason\\s*=").containsMatchIn(it) }?.substringAfter('=')?.trim() ?: args[1]
        }.toList()

    @Test fun everyReasonTheChildSourcesSendIsAllowed() {
        val allowed = FamilySecurity.childAlertReasons
        val main = reasonArguments(source("MainActivity.kt")); val risk = reasonArguments(source("RiskPipeline.kt"))
        assertTrue(main.size >= 12)
        val literal = Regex("^\"([a-z_]+)\"$")
        for (arg in main + risk) literal.find(arg)?.let { assertTrue(arg, it.groupValues[1] in allowed) }
        // Variable reasons: exactly the traced call sites. A new variable site fails here until it is traced and listed.
        assertEquals(listOf("reason", "reason", "reason"), main.filter { !literal.matches(it) })
        assertEquals(listOf("r.getString(\"reason\")"), risk.filter { !literal.matches(it) })
        val mainText = source("MainActivity.kt")
        // MainActivity ~411: the heartbeat.
        val heartbeat = Regex("val reason = if \\(notifOn \\|\\| RiskLocal\\.uploadMethodOn\\(ctx\\)\\) \"(\\w+)\" else \"(\\w+)\"").find(mainText)!!
        assertEquals(listOf("heartbeat_ok", "tamper_attempt"), heartbeat.groupValues.drop(1))
        // MainActivity ~2179 and ~2509: evaluateMessage, which returns a literal or null.
        val evaluate = mainText.substringAfter("fun evaluateMessage(").substringBefore("\n    fun ")
        val returned = Regex("return \"(\\w+)\"").findAll(evaluate).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("sexual_or_grooming_content", "stranger_message"), returned)
        assertTrue(Regex("return (?!\"|null)").findAll(evaluate).none())
        assertEquals(2, Regex("val reason = GuardEngine\\.evaluateMessage\\(").findAll(mainText).count())
        // RiskPipeline sendContentFreeAlerts: the reasons of actionable captured records (every ConversationCapture.record branch).
        val capturedReasons = listOf("conversation_context", "stranger_message", "sexual_or_grooming_content", "flagged_contact_follow_up").map { captured(it) }
        assertTrue(capturedReasons.all { it.getString("reason") in FamilySecurity.capturedReasons })
        assertTrue(capturedReasons.filter { ConversationCapture.actionable(it) }.all { it.getString("reason") in allowed })
        val commentDraft = ConversationCapture.record(CapturedText("x", "hello", "incoming", source = "comment_story_draft"), now, false, false)
        assertEquals("conversation_context", commentDraft.getString("reason"))
        // The gate never logs or stores anything.
        val gateSource = source("ChildRecords.kt")
        assertFalse(gateSource.contains("Log.") || gateSource.contains("println") || gateSource.contains("prefs("))
    }
}
